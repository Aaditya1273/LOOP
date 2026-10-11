"""The treasury contract as seen from the service: reads, and relayed writes paid by the relayer key."""

import json
import threading
import time
from pathlib import Path

from eth_account import Account
from eth_account.messages import encode_defunct
from eth_utils import keccak
from web3 import Web3
from web3.logs import DISCARD
from web3.exceptions import ContractCustomError, ContractLogicError, TransactionNotFound

from .errors import AnzenError, Reason

ARTIFACTS = Path(__file__).resolve().parents[2] / "contracts" / "out"  # forge build output (tests deploy from it)
ABIS = Path(__file__).resolve().parent / "abi"  # committed copies, so a deployed service needs no Foundry


def _abi(name: str) -> list:
    built = ARTIFACTS / f"{name}.sol" / f"{name}.json"
    return json.loads(built.read_text())["abi"] if built.exists() else json.loads((ABIS / f"{name}.json").read_text())


class Chain:
    def __init__(self, rpc_url: str, treasury: str, token: str, relayer_key: str, issuer_key: str):
        self.w3 = Web3(Web3.HTTPProvider(rpc_url, request_kwargs={"timeout": 20}))
        abi = _abi("AnzenTreasury")
        self.treasury = self.w3.eth.contract(address=Web3.to_checksum_address(treasury), abi=abi)
        self.token = self.w3.eth.contract(address=Web3.to_checksum_address(token), abi=_abi("TestDollar"))
        self.relayer = Account.from_key(relayer_key)
        self.issuer = Account.from_key(issuer_key)
        self.errors = {"0x" + keccak(text=f"{e['name']}({','.join(i['type'] for i in e['inputs'])})")[:4].hex(): e["name"]
                       for e in abi if e["type"] == "error"}
        # ponytail: one lock serializes relayer nonces; a nonce manager if throughput matters.
        self.lock = threading.Lock()
        self._chain_id: int | None = None

    # ---------- reads

    @property
    def chain_id(self) -> int:
        if self._chain_id is None:
            self._chain_id = self._rpc(lambda: self.w3.eth.chain_id)
        return self._chain_id

    def now(self) -> int:
        """Chain time: the timestamp the next block will carry. On a quiet local chain the latest block can be minutes
        old, and a fast-forwarded one runs ahead of the wall clock; the pending block is right in both cases."""
        try:
            return self._rpc(lambda: self.w3.eth.get_block("pending")["timestamp"])
        except AnzenError:
            raise
        except Exception:
            return max(int(time.time()), self._rpc(lambda: self.w3.eth.get_block("latest")["timestamp"]))

    def event(self, event_id: int) -> tuple[dict, dict]:
        c, s = self._rpc(lambda: self.treasury.functions.getEvent(event_id).call())
        cfg = dict(zip(["organizer", "policy_hash", "issuer", "amount", "capacity", "commit_deadline",
                        "check_in_start", "check_in_end", "no_show"], c))
        state = dict(zip(["committed", "attended", "cancelled", "finalized", "attendee_payout"], s))
        return cfg, state

    def participation(self, event_id: int, key_id: bytes) -> dict:
        p = self._rpc(lambda: self.treasury.functions.participation(event_id, key_id).call())
        return dict(zip(["status", "attended", "checked_in_at", "settled_amount", "passport_id", "agent_ref"], p))

    def balance(self, key_id: bytes) -> int:
        return self._rpc(lambda: self.treasury.functions.balanceOf(key_id).call())

    def nonce(self, key_id: bytes) -> int:
        return self._rpc(lambda: self.treasury.functions.nonces(key_id).call())

    def delegation(self, key_id: bytes) -> dict:
        d = self._rpc(lambda: self.treasury.functions.delegationOf(key_id).call())
        return dict(zip(["agent", "cap", "spent", "expiry", "agent_ref"], d))

    @property
    def registries(self) -> tuple[str | None, str | None]:
        """The ERC-8004 identity and reputation registries this treasury reports to (None where not deployed)."""
        if not hasattr(self, "_registries"):
            ident = self._rpc(lambda: self.treasury.functions.identityRegistry().call())
            rep = self._rpc(lambda: self.treasury.functions.reputationRegistry().call())
            zero = "0x0000000000000000000000000000000000000000"
            self._registries = (None if ident == zero else ident, None if rep == zero else rep)
        return self._registries

    def show_up(self, agent_id: int) -> dict:
        """Show-up reputation as reported by THIS treasury only (the Sybil-proof client), from the ERC-8004
        reputation registry: count of agent reservations settled, and the share that ended in a check-in."""
        rep = self.registries[1]
        if rep is None:
            return {"count": 0, "showUpRate": None, "registry": None}
        abi = [{"name": "getSummary", "type": "function", "stateMutability": "view",
                "inputs": [{"type": "uint256"}, {"type": "address[]"}, {"type": "string"}, {"type": "string"}],
                "outputs": [{"type": "uint64"}, {"type": "int128"}, {"type": "uint8"}]}]
        c = self.w3.eth.contract(address=rep, abi=abi)
        count, value, decimals = self._rpc(lambda: c.functions.getSummary(agent_id, [self.treasury.address], "showUp", "anzen").call())
        return {"count": count, "showUpRate": (value / 10 ** decimals) if count else None, "registry": rep}

    def agent_nonce(self, agent: str) -> int:
        return self._rpc(lambda: self.treasury.functions.agentNonces(Web3.to_checksum_address(agent)).call())

    def relayer_balance(self) -> int:
        return self._rpc(lambda: self.w3.eth.get_balance(self.relayer.address))

    def receipt(self, tx: str) -> dict | None:
        try:
            return self._rpc(lambda: self.w3.eth.get_transaction_receipt(tx))
        except TransactionNotFound:
            return None

    def created_event_id(self, receipt: dict) -> int | None:
        for log in self.treasury.events.EventCreated().process_receipt(receipt, errors=DISCARD):
            return log["args"]["eventId"]
        return None

    # ---------- writes

    def sign_challenge(self, digest: bytes) -> bytes:
        return Account.sign_message(encode_defunct(primitive=digest), self.issuer.key).signature

    def send(self, call) -> str:
        """Simulate, then broadcast. A revert is reported with the contract's error name and nothing is sent."""
        with self.lock:
            try:
                gas = call.estimate_gas({"from": self.relayer.address}, block_identifier="pending")
            except ContractCustomError as e:
                data = e.data if isinstance(e.data, str) else ""
                name = self.errors.get(data[:10], "Reverted")
                reason = (Reason.INVALID_SIGNATURE if name.startswith("ECDSA")  # malformed issuer signature
                          else Reason(name) if name in Reason._value2member_map_ else Reason.REVERTED)
                raise AnzenError(reason, name) from None
            except ContractLogicError as e:
                raise AnzenError(Reason.REVERTED, str(e.message or "")) from None
            tx = call.build_transaction({
                "from": self.relayer.address, "gas": int(gas * 1.25) + 20_000,
                "nonce": self._rpc(lambda: self.w3.eth.get_transaction_count(self.relayer.address, "pending")),
            })
            signed = self.relayer.sign_transaction(tx)
            return "0x" + self._rpc(lambda: self.w3.eth.send_raw_transaction(signed.raw_transaction)).hex().removeprefix("0x")

    def send_value(self, to: str, wei: int) -> str:
        with self.lock:
            tx = {"to": to, "value": wei, "gas": 21_000, "chainId": self.chain_id,
                  "nonce": self._rpc(lambda: self.w3.eth.get_transaction_count(self.relayer.address, "pending")),
                  "maxFeePerGas": self._rpc(lambda: self.w3.eth.gas_price) * 2, "maxPriorityFeePerGas": 10**9}
            return "0x" + self._rpc(lambda: self.w3.eth.send_raw_transaction(
                self.relayer.sign_transaction(tx).raw_transaction)).hex().removeprefix("0x")

    def wait(self, tx: str, timeout: float = 30) -> dict:
        return self._rpc(lambda: self.w3.eth.wait_for_transaction_receipt(tx, timeout=timeout, poll_latency=0.25))

    @staticmethod
    def _rpc(fn):
        try:
            return fn()
        except (ContractLogicError, TransactionNotFound):
            raise
        except (ConnectionError, OSError, TimeoutError) as e:
            raise AnzenError(Reason.CHAIN_UNAVAILABLE, type(e).__name__) from None
        except Exception as e:  # requests/web3 wrap transport failures in many types
            if "connect" in str(e).lower() or "timed out" in str(e).lower():
                raise AnzenError(Reason.CHAIN_UNAVAILABLE, type(e).__name__) from None
            raise
