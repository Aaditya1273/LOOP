"""The whole service over HTTP against the real anzen_core program on a local Solana validator: passkey-signed
actions verified by the secp256r1 precompile, venue codes and agents by ed25519, waitlist refill, give-back, staff
check-in, settlement, show-up records, Agent Pass rules and x402. Nothing is mocked.

Needs `cargo-build-sbf` output (programs/anzen_core/target/deploy) and solana-test-validator on PATH."""

import base64
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
from eth_abi import encode
from fastapi.testclient import TestClient
from solders.keypair import Keypair

from anzen import messages, store
from anzen.app import create_app
from anzen.p256 import N, WEBAUTHN_AUTH
from anzen.service import Anzen
from anzen.solana_chain import Rpc, SolanaChain
from anzen.solana_setup import setup

from .conftest import Key

ROOT = Path(__file__).resolve().parents[2]
SO = ROOT / "programs/anzen_core/target/deploy/anzen_core.so"
PROGRAM_KEY = ROOT / "programs/anzen_core/keys/anzen_core-keypair.json"


def _port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


@pytest.fixture(scope="module")
def sol(tmp_path_factory):
    if not (shutil.which("solana-test-validator") and SO.exists() and PROGRAM_KEY.exists()):
        pytest.skip("needs solana-test-validator and `cargo-build-sbf` in programs/anzen_core")
    program_id = str(Keypair.from_bytes(bytes(json.loads(PROGRAM_KEY.read_text()))).pubkey())
    port, ledger = _port(), tmp_path_factory.mktemp("ledger")
    proc = subprocess.Popen(["solana-test-validator", "--reset", "--quiet", "--ledger", str(ledger), "--rpc-port", str(port),
                             "--faucet-port", str(_port()), "--bpf-program", program_id, str(SO)],
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    url = f"http://127.0.0.1:{port}"
    rpc = Rpc(url)
    for _ in range(120):
        try:
            rpc.call("getSlot")
            break
        except Exception:
            time.sleep(0.5)
    relayer, issuer = Keypair(), Keypair()
    sig = rpc.call("requestAirdrop", str(relayer.pubkey()), 100 * 10**9)
    for _ in range(60):
        st = rpc.status(sig)
        if st and st.get("confirmationStatus") in ("confirmed", "finalized"):
            break
        time.sleep(0.5)
    try:
        d = setup(url, program_id, relayer, issuer.pubkey(), 104)
    except BaseException:
        proc.terminate()
        raise
    chain = SolanaChain(url, program_id, d["mint"], 104, "0x" + bytes(relayer)[:32].hex(), "0x" + bytes(issuer)[:32].hex())
    svc = Anzen(store.connect(tmp_path_factory.mktemp("db") / "anzen.db"), chain, faucet_amount=100_000_000)
    yield svc, TestClient(create_app(svc))
    proc.terminate()


def ok(svc, r):
    assert r.status_code == 200, r.text
    tx = r.json()["tx"]
    assert svc.chain.wait(tx)["status"] == 1, tx
    return tx


def webauthn(key: Key, message: bytes) -> dict:
    challenge = base64.urlsafe_b64encode(hashlib.sha256(message).digest()).rstrip(b"=").decode()
    client = ('{"type":"webauthn.get","challenge":"' + challenge + '","origin":"https://anzen-shonko.vercel.app",'
              '"crossOrigin":false}')
    auth_data = hashlib.sha256(b"anzen-shonko.vercel.app").digest() + bytes([0x05]) + (1).to_bytes(4, "big")
    r, s = decode_dss_signature(key.sk.sign(auth_data + hashlib.sha256(client.encode()).digest(), ec.ECDSA(hashes.SHA256())))
    auth = encode([WEBAUTHN_AUTH], [(r.to_bytes(32, "big"), s.to_bytes(32, "big"), 23, 1, auth_data, client)])  # s may be high
    return {"qx": "0x" + key.qx.hex(), "qy": "0x" + key.qy.hex(), "auth": "0x" + auth.hex()}


def test_solana_lifecycle(sol):
    svc, client = sol
    c = svc.chain
    cid, t = c.chain_id, c.treasury.address
    h = client.get("/health").json()
    assert h["vm"] == "svm" and h["programId"] == str(c.program)

    org, alice, bob, carol, dave = (Key() for _ in range(5))
    for k in (alice, bob, carol, dave, org):
        ok(svc, client.post("/faucet", json={"keyId": k.id}))
    assert client.get(f"/accounts/{alice.id}").json()["balance"] == 100_000_000

    def nonce(k: Key) -> int:
        return c.nonce(bytes.fromhex(k.id[2:]))

    def create(capacity: int, category: int, fee: int, title: str) -> int:
        now = c.now()
        terms = {"amount": 10_000_000, "capacity": capacity, "commitDeadline": now + 40, "checkInStart": now + 42,
                 "checkInEnd": now + 55, "noShow": "split", "issuer": h["issuer"]}
        policy = json.dumps({"version": 1, "title": title, "description": "", "creator": "Studio North",
                             "venue": "Studio North", "startsAt": terms["checkInStart"], "timezone": "UTC", "artwork": 1,
                             "disputeContact": "desk@example.com", **terms, "category": category, "lateFeeBps": fee},
                            separators=(",", ":"))
        cfg = messages.EventConfig(bytes.fromhex(org.id[2:]), hashlib.sha256(policy.encode()).digest(), h["issuer"],
                                   10_000_000, capacity, terms["commitDeadline"], terms["checkInStart"], terms["checkInEnd"], 1)
        deadline = now + 600
        sig = org.sign(messages.create_v2(cid, t, nonce(org), deadline, cfg, category, fee))
        config = {"organizer": org.id, "policyHash": "0x" + cfg.policy_hash.hex(), "issuer": h["issuer"],
                  "amount": 10_000_000, "capacity": capacity, "commitDeadline": cfg.commit_deadline,
                  "checkInStart": cfg.check_in_start, "checkInEnd": cfg.check_in_end, "noShow": 1}
        tx = ok(svc, client.post("/events", json={"policy": policy, "config": config, "deadline": deadline, **sig,
                                                  "terms": {"category": category, "lateFeeBps": fee}}))
        return client.get(f"/tx/{tx}").json()["eventId"]

    def commit(k: Key, eid: int, passkey: bool = False):
        deadline = c.now() + 600
        msg = messages.commit(cid, t, eid, 10_000_000, nonce(k), deadline)
        return client.post(f"/events/{eid}/commit", json={"deadline": deadline, **(webauthn(k, msg) if passkey else k.sign(msg))})

    spin = create(1, 1, 2000, "Spin 7am")
    yoga = create(2, 1, 0, "Yoga")
    meetup = create(3, 3, 0, "Builders' night")
    e = client.get(f"/events/{spin}").json()
    assert e["policyVerified"] and e["categoryName"] == "Fitness" and e["lateFeeBps"] == 2000

    # waitlist + refill on give-back (alice's commit is a browser passkey: WebAuthn through secp256r1)
    ok(svc, commit(alice, spin, passkey=True))
    ok(svc, commit(bob, spin))
    me = lambda k, eid: client.get(f"/events/{eid}", params={"key": k.id}).json()["me"]  # noqa: E731
    assert me(bob, spin)["status"] == "waitlisted"
    assert commit(org, spin).json()["error"] == "OwnEvent"
    deadline = c.now() + 600
    ok(svc, client.post(f"/events/{spin}/release", json={"deadline": deadline, **alice.sign(
        messages.release(cid, t, spin, nonce(alice), deadline))}))
    assert me(alice, spin)["status"] == "released" and me(bob, spin)["status"] == "committed"
    a = client.get(f"/accounts/{alice.id}").json()
    assert a["balance"] == 100_000_000 and a["record"]["shields"] == 1
    assert client.get(f"/events/{spin}").json()["refilled"] == 1

    ok(svc, commit(alice, yoga))
    ok(svc, commit(carol, yoga))

    # Agent Pass + x402: fitness only, so the meetup is refused
    agent = Keypair()
    now = c.now()
    agent_hex = "0x" + bytes(agent.pubkey()).hex()
    rules = {"categories": 1 << 1, "maxLateFeeBps": 10_000, "maxActive": 2, "maxLeadDays": 7}
    sig = dave.sign(messages.grant_pass(cid, t, "0x" + bytes(agent.pubkey())[:20].hex(), messages.NO_AGENT_ID,
                                        50_000_000, now + 86_400, *rules.values(), nonce(dave), now + 600))
    ok(svc, client.post("/pass", json={"agent": agent_hex, "agentId": messages.NO_AGENT_ID, "cap": 50_000_000,
                                       "expiry": now + 86_400, "deadline": now + 600, "rules": rules, **sig}))
    assert client.get(f"/accounts/{dave.id}").json()["delegation"]["rules"] == rules

    def x402(eid: int):
        req = client.get(f"/x402/events/{eid}/reserve")
        assert req.status_code == 402
        x = req.json()["accepts"][0]["extra"]
        n = client.get(f"/agents/{agent_hex}").json()["nonce"]
        pre = messages.agent_commit(x["chainId"], x["treasury"], eid, bytes.fromhex(dave.id[2:]), x["amount"], n,
                                    x["now"] + 600)
        pay = {"x402Version": 1, "scheme": "anzen-pass", "network": req.json()["accepts"][0]["network"],
               "payload": {"keyId": dave.id, "deadline": x["now"] + 600, "signature": "0x" + bytes(agent.sign_message(pre)).hex()}}
        return client.get(f"/x402/events/{eid}/reserve", headers={"X-PAYMENT": base64.b64encode(json.dumps(pay).encode()).decode()})

    assert x402(meetup).json()["error"] == "CategoryNotAllowed"
    r = x402(spin)  # full: the agent's booking waits on the waitlist
    assert r.status_code == 200 and r.json()["reservation"] == "waitlisted", r.text
    assert me(dave, spin)["byAgent"]

    # check-in: bob with a venue code (issuer ed25519 + his P-256), carol by staff scan (both P-256)
    while c.now() < client.get(f"/events/{yoga}").json()["checkInStart"] + 1:
        time.sleep(0.5)
    deadline = c.now() + 300
    desk = client.post(f"/events/{spin}/desk", json={"deadline": deadline, **org.sign(messages.desk(cid, t, spin, deadline))})
    code = client.get(f"/events/{spin}/challenge", params={"token": desk.json()["token"]}).json()
    assert len(code["issuerSig"]) == 130  # 64-byte ed25519
    ch = bytes.fromhex(code["challenge"][2:])
    ok(svc, client.post(f"/events/{spin}/checkin", json={
        "challenge": code["challenge"], "expiresAt": code["expiresAt"], "issuerSig": code["issuerSig"],
        **bob.sign(messages.check_in(cid, t, spin, ch, code["expiresAt"]))}))
    issued = c.now()
    guest = carol.sign(messages.present(cid, t, yoga, issued))
    staff = webauthn(org, messages.staff(cid, t, yoga, bytes.fromhex(carol.id[2:]), issued))
    ok(svc, client.post(f"/events/{yoga}/staff-checkin", json={"guest": guest, "issuedAt": issued, "staff": staff}))
    assert me(bob, spin)["tier"] == 1 and me(carol, yoga)["tier"] == 2

    # settle: bob attended, dave (agent, never got a seat) refunded, alice missed yoga, carol split her share
    while c.now() <= client.get(f"/events/{yoga}").json()["checkInEnd"]:
        time.sleep(0.5)
    for eid, k in ((spin, bob), (spin, dave), (yoga, alice), (yoga, carol)):
        ok(svc, client.post(f"/events/{eid}/settle", json={"keyId": k.id}))
    b, d, a, cr = (client.get(f"/accounts/{k.id}").json() for k in (bob, dave, alice, carol))
    assert b["balance"] == 100_000_000 and b["record"]["attended"] == 1 and b["score"] == 80
    assert d["balance"] == 100_000_000 and d["record"]["attended"] + d["record"]["missed"] == 0
    assert cr["balance"] == 110_000_000  # her 10 back + alice's forfeited 10
    assert a["balance"] == 90_000_000 and a["record"]["streak"] == 0 and a["record"]["shields"] == 0  # shield spent
    assert client.get(f"/events/{spin}", params={"key": bob.id}).json()["me"]["passportId"] >= 1

    dash = client.get(f"/organizers/{org.id}/dashboard").json()
    assert dash["totals"]["events"] == 3 and dash["totals"]["refilled"] == 1
    proof = client.get(f"/proof/{spin}/{bob.id}").json()
    assert all(x["status"] == "confirmed" for x in proof["transactions"])
