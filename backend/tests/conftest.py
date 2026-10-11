"""Integration fixtures: a real anvil chain, the real contracts, real P-256 keys. Nothing here mocks the chain."""

import hashlib
import json
import shutil
import socket
import subprocess
import time
from pathlib import Path

import pytest
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.asymmetric.utils import decode_dss_signature
from fastapi.testclient import TestClient
from web3 import Web3

from anzen import messages, store
from anzen.app import create_app
from anzen.chain import ARTIFACTS, Chain
from anzen.p256 import N
from anzen.service import Anzen

# anvil's well-known dev accounts 0 and 1
RELAYER = "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80"
ISSUER = "0x59c6995e998f97a5a0044966f0945389dc9e86dae88c7a8412f4603b6b78690d"


class Key:
    """A P-256 key standing in for the phone's Keystore key; signs exactly like the app (SHA256withECDSA, low-s)."""

    def __init__(self):
        self.sk = ec.generate_private_key(ec.SECP256R1())
        n = self.sk.public_key().public_numbers()
        self.qx, self.qy = n.x.to_bytes(32, "big"), n.y.to_bytes(32, "big")
        self.id = "0x" + messages.key_id(self.qx, self.qy).hex()

    def sign(self, message: bytes) -> dict:
        r, s = decode_dss_signature(self.sk.sign(message, ec.ECDSA(hashes.SHA256())))
        s = min(s, N - s)
        return {"qx": "0x" + self.qx.hex(), "qy": "0x" + self.qy.hex(),
                "auth": "0x" + r.to_bytes(32, "big").hex() + s.to_bytes(32, "big").hex()}


def _free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


@pytest.fixture(scope="session")
def anvil():
    if not shutil.which("anvil") or not (ARTIFACTS / "AnzenTreasury.sol").exists():
        pytest.skip("needs foundry's anvil and `forge build` in contracts/")
    port = _free_port()
    # osaka includes the P-256 precompile at 0x100 (EIP-7951), the address Monad uses (RIP-7212)
    proc = subprocess.Popen(["anvil", "--port", str(port), "--hardfork", "osaka", "--silent"])
    url = f"http://127.0.0.1:{port}"
    w3 = Web3(Web3.HTTPProvider(url))
    for _ in range(100):
        if w3.is_connected():
            break
        time.sleep(0.05)
    yield url, w3
    proc.terminate()


def _deploy(w3: Web3, name: str, *args) -> str:
    art = json.loads((ARTIFACTS / f"{name}.sol" / f"{name}.json").read_text())
    acct = w3.eth.account.from_key(RELAYER)
    c = w3.eth.contract(abi=art["abi"], bytecode=art["bytecode"]["object"])
    tx = c.constructor(*args).build_transaction({"from": acct.address,
                                                 "nonce": w3.eth.get_transaction_count(acct.address)})
    h = w3.eth.send_raw_transaction(acct.sign_transaction(tx).raw_transaction)
    return w3.eth.wait_for_transaction_receipt(h)["contractAddress"]


@pytest.fixture
def env(anvil, tmp_path: Path):
    url, w3 = anvil
    snap = w3.provider.make_request("evm_snapshot", [])["result"]
    relayer = w3.eth.account.from_key(RELAYER).address
    token = _deploy(w3, "TestDollar", relayer)
    zero = "0x0000000000000000000000000000000000000000"
    treasury = _deploy(w3, "AnzenTreasury", token, zero, zero)  # no ERC-8004 registries on a bare local chain
    svc = Anzen(store.connect(tmp_path / "anzen.db"), Chain(url, treasury, token, RELAYER, ISSUER),
                faucet_amount=100_000_000)
    yield Env(w3, svc, TestClient(create_app(svc)))
    w3.provider.make_request("evm_revert", [snap])


class Env:
    def __init__(self, w3: Web3, svc: Anzen, client: TestClient):
        self.w3, self.svc, self.client = w3, svc, client
        self.chain_id, self.treasury = svc.chain.chain_id, svc.chain.treasury.address

    def now(self) -> int:
        return self.svc.chain.now()

    def warp(self, seconds: int) -> None:
        self.w3.provider.make_request("evm_increaseTime", [seconds])
        self.w3.provider.make_request("evm_mine", [])

    def confirm(self, tx: str) -> dict:
        self.svc.chain.wait(tx)
        r = self.client.get(f"/tx/{tx}").json()
        assert r["status"] == "confirmed", r
        return r

    def fund(self, key: Key) -> None:
        r = self.client.post("/faucet", json={"keyId": key.id})
        assert r.status_code == 200, r.text
        self.confirm(r.json()["tx"])

    def create_event(self, organizer: Key, *, amount=10_000_000, capacity=2, no_show=1, title="Rooftop listening session"):
        now = self.now()
        terms = {"amount": amount, "capacity": capacity, "commitDeadline": now + 86_400,
                 "checkInStart": now + 2 * 86_400, "checkInEnd": now + 2 * 86_400 + 3 * 3600,
                 "noShow": ["organizer", "split"][no_show], "issuer": self.svc.chain.issuer.address}
        policy = json.dumps({"version": 1, "title": title, "description": "Twenty seats.", "creator": "Mira K",
                             "venue": "Studio 4, Bandra", "startsAt": terms["checkInStart"] + 1800,
                             "timezone": "Asia/Kolkata", "artwork": 2, "disputeContact": "hello@example.com",
                             **terms}, separators=(",", ":"))
        cfg = messages.EventConfig(bytes.fromhex(organizer.id[2:]), hashlib.sha256(policy.encode()).digest(),
                                   terms["issuer"], amount, capacity, terms["commitDeadline"],
                                   terms["checkInStart"], terms["checkInEnd"], no_show)
        deadline = now + 600
        nonce = self.svc.chain.nonce(cfg.organizer)
        sig = organizer.sign(messages.create(self.chain_id, self.treasury, nonce, deadline, cfg))
        config = {"organizer": organizer.id, "policyHash": "0x" + cfg.policy_hash.hex(), "issuer": cfg.issuer,
                  "amount": amount, "capacity": capacity, "commitDeadline": cfg.commit_deadline,
                  "checkInStart": cfg.check_in_start, "checkInEnd": cfg.check_in_end, "noShow": no_show}
        r = self.client.post("/events", json={"policy": policy, "config": config, "deadline": deadline, **sig})
        assert r.status_code == 200, r.text
        return self.confirm(r.json()["tx"])["eventId"]

    def commit(self, key: Key, event_id: int, *, signer: Key | None = None):
        e = self.client.get(f"/events/{event_id}").json()
        deadline = self.now() + 600
        nonce = self.svc.chain.nonce(bytes.fromhex(key.id[2:]))
        sig = (signer or key).sign(messages.commit(self.chain_id, self.treasury, event_id, e["amount"], nonce, deadline))
        if signer:  # claim to be `key` while signing with someone else's key
            sig.update(qx="0x" + key.qx.hex(), qy="0x" + key.qy.hex())
        return self.client.post(f"/events/{event_id}/commit", json={"deadline": deadline, **sig})

    def open_desk(self, organizer: Key, event_id: int):
        deadline = self.now() + 300
        sig = organizer.sign(messages.desk(self.chain_id, self.treasury, event_id, deadline))
        return self.client.post(f"/events/{event_id}/desk", json={"deadline": deadline, **sig})

    def check_in(self, key: Key, event_id: int, code: dict):
        challenge = bytes.fromhex(code["challenge"][2:])
        sig = key.sign(messages.check_in(self.chain_id, self.treasury, event_id, challenge, code["expiresAt"]))
        return self.client.post(f"/events/{event_id}/checkin", json={
            "challenge": code["challenge"], "expiresAt": code["expiresAt"], "issuerSig": code["issuerSig"], **sig})
