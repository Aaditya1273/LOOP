"""The anzen_core Solana program as seen from the service: account reads, and relayed transactions paid by the
relayer key. It exposes the same surface as chain.Chain (the Monad treasury), so the service code is shared.

Owners sign exactly the same preimages as on Monad (messages.py). For each action this builds the precompile
instruction that carries the owner's P-256 signature (secp256r1, SIMD-0075) or an ed25519 signature (issuer, agent),
followed by the program instruction, which finds and checks that signature by introspection (programs/anzen_core)."""

import base64
import hashlib
import re
import struct
import threading
import time
from types import SimpleNamespace

from cryptography.hazmat.primitives.asymmetric import ec
from eth_abi import decode
import requests
from solders.instruction import AccountMeta, Instruction
from solders.keypair import Keypair
from solders.message import Message
from solders.pubkey import Pubkey
from solders.hash import Hash
from solders.transaction import Transaction

from . import messages
from .errors import AnzenError, Reason
from .p256 import N, WEBAUTHN_AUTH

SECP256R1 = Pubkey.from_string("Secp256r1SigVerify1111111111111111111111111")
ED25519 = Pubkey.from_string("Ed25519SigVerify111111111111111111111111111")
IXS = Pubkey.from_string("Sysvar1nstructions1111111111111111111111111")
CLOCK = Pubkey.from_string("SysvarC1ock11111111111111111111111111111111")
SYSTEM = Pubkey.from_string("11111111111111111111111111111111")
TOKEN = Pubkey.from_string("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA")
ATA = Pubkey.from_string("ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL")
ZERO = bytes(32)
NO_AGENT_ID = b"\xff" * 32
ERRORS = ["InvalidConfig", "InvalidSignature", "Expired", "UnknownEvent", "WrongPhase", "EventFull",
          "AlreadyCommitted", "NotCommitted", "InsufficientBalance", "NotOrganizer", "AlreadySettled", "NotDelegated",
          "BudgetExceeded", "CategoryNotAllowed", "FeeAboveLimit", "TooManyActive", "TooFarAhead",
          "WaitlistAccountMissing", "OwnEvent"]


class Rpc:
    """The handful of Solana JSON-RPC calls the service makes (confirmed commitment)."""

    def __init__(self, url: str, timeout: float = 20):
        self.url, self.timeout, self.s = url, timeout, requests.Session()

    def call(self, method: str, *params):
        try:
            r = self.s.post(self.url, json={"jsonrpc": "2.0", "id": 1, "method": method, "params": list(params)},
                            timeout=self.timeout).json()
        except (requests.RequestException, ValueError) as e:
            raise AnzenError(Reason.CHAIN_UNAVAILABLE, type(e).__name__) from None
        if "error" in r:
            raise AnzenError(Reason.CHAIN_UNAVAILABLE, str(r["error"].get("message", ""))[:200])
        return r["result"]

    def account(self, address: Pubkey) -> bytes | None:
        v = self.call("getAccountInfo", str(address), {"encoding": "base64", "commitment": "confirmed"})["value"]
        return base64.b64decode(v["data"][0]) if v else None

    def blockhash(self) -> Hash:
        return Hash.from_string(self.call("getLatestBlockhash", {"commitment": "confirmed"})["value"]["blockhash"])

    def simulate(self, tx: Transaction) -> dict:
        return self.call("simulateTransaction", base64.b64encode(bytes(tx)).decode(),
                         {"encoding": "base64", "commitment": "confirmed", "sigVerify": True})["value"]

    def send(self, tx: Transaction) -> str:
        return self.call("sendTransaction", base64.b64encode(bytes(tx)).decode(),
                         {"encoding": "base64", "skipPreflight": True, "maxRetries": 5})

    def status(self, sig: str) -> dict | None:
        return self.call("getSignatureStatuses", [sig], {"searchTransactionHistory": True})["value"][0]

    def transaction(self, sig: str) -> dict | None:
        return self.call("getTransaction", sig, {"encoding": "json", "commitment": "confirmed",
                                                 "maxSupportedTransactionVersion": 0})

    def balance(self, address: Pubkey) -> int:
        return self.call("getBalance", str(address), {"commitment": "confirmed"})["value"]


def keypair_from(secret: str) -> Keypair:
    """A 32-byte hex seed (the same secrets the Monad service uses) or a solana-keygen JSON array."""
    s = secret.strip()
    if s.startswith("["):
        return Keypair.from_bytes(bytes(eval(s, {}, {})))  # noqa: S307 - a list of ints from a keyfile
    return Keypair.from_seed(bytes.fromhex(s.removeprefix("0x")))


def disc(kind: str, name: str) -> bytes:
    return hashlib.sha256(f"{kind}:{name}".encode()).digest()[:8]


def addr20(k: Pubkey) -> str:
    return "0x" + bytes(k)[:20].hex()


# ---------------------------------------------------------------- borsh (the few shapes the program uses)

def u8(v): return struct.pack("<B", v)
def u16(v): return struct.pack("<H", v)
def u32(v): return struct.pack("<I", v)
def u64(v): return struct.pack("<Q", v)
def i64(v): return struct.pack("<q", v)


class Reader:
    def __init__(self, data: bytes):
        self.d, self.o = data, 8  # skip the account discriminator

    def take(self, n: int) -> bytes:
        b = self.d[self.o:self.o + n]
        self.o += n
        return b

    def fmt(self, f: str):
        v = struct.unpack_from("<" + f, self.d, self.o)
        self.o += struct.calcsize("<" + f)
        return v if len(v) > 1 else v[0]


def _auth(auth: bytes, message: bytes, qx: bytes, qy: bytes) -> tuple[bytes, Instruction]:
    """Borsh `Auth` for the program, and the secp256r1 precompile instruction carrying the signature."""
    if len(auth) == 64:
        r, s, signed, arg = auth[:32], auth[32:], message, u8(0)
    else:
        r, s, ci, ti, auth_data, client_data = decode([WEBAUTHN_AUTH], auth)[0]
        cd = client_data.encode()
        signed = auth_data + hashlib.sha256(cd).digest()
        arg = u8(1) + u32(len(cd)) + cd + u16(ci) + u16(ti)
    s_int = int.from_bytes(s, "big")
    s = min(s_int, N - s_int).to_bytes(32, "big")  # the precompile accepts low-s only; both are the same signature
    pk = bytes([2 + (qy[31] & 1)]) + qx
    return arg, _precompile(SECP256R1, pk, r + s, signed)


def _precompile(program: Pubkey, pubkey: bytes, sig: bytes, message: bytes) -> Instruction:
    """One-signature precompile instruction (ed25519 / secp256r1 share this layout), data inline."""
    pk_off = 16
    sig_off = pk_off + len(pubkey)
    msg_off = sig_off + 64
    own = 0xFFFF
    data = (u8(1) + u8(0) + u16(sig_off) + u16(own) + u16(pk_off) + u16(own) + u16(msg_off) + u16(len(message)) + u16(own)
            + pubkey + sig + message)
    return Instruction(program, data, [])


class Account(SimpleNamespace):
    pass


class SolanaChain:
    def __init__(self, rpc_url: str, program_id: str, mint: str, chain_id: int, relayer_key: str, issuer_key: str):
        self.rpc = Rpc(rpc_url)
        self.program = Pubkey.from_string(program_id)
        self.mint = Pubkey.from_string(mint)
        self._chain_id = chain_id
        self.payer = keypair_from(relayer_key)
        self.issuer_kp = keypair_from(issuer_key)
        # What the shared service code reads from a chain: the 20-byte "treasury" and "issuer" slots of every preimage.
        self.treasury = SimpleNamespace(address=addr20(self.program), functions=_Functions(self), program=str(self.program))
        self.token = SimpleNamespace(address=str(self.mint))
        self.issuer = SimpleNamespace(address=addr20(self.issuer_kp.pubkey()), key=str(self.issuer_kp.pubkey()))
        self.relayer = SimpleNamespace(address=str(self.payer.pubkey()))
        self.config = self.pda(b"config")
        self.vault = self.pda(b"vault")
        self.lock = threading.Lock()
        self.nonce_store = None  # Solana has no account nonces to coordinate

    vm = "svm"

    # ---------- addresses

    def pda(self, *seeds: bytes) -> Pubkey:
        return Pubkey.find_program_address(list(seeds), self.program)[0]

    def member_pda(self, key: bytes) -> Pubkey:
        return self.pda(b"member", key)

    def event_pda(self, event_id: int) -> Pubkey:
        return self.pda(b"event", u64(event_id))

    def part_pda(self, event_id: int, key: bytes) -> Pubkey:
        return self.pda(b"part", u64(event_id), key)

    def agent_pda(self, agent: Pubkey) -> Pubkey:
        return self.pda(b"agent", bytes(agent))

    def ata(self, owner: Pubkey) -> Pubkey:
        return Pubkey.find_program_address([bytes(owner), bytes(TOKEN), bytes(self.mint)], ATA)[0]

    # ---------- reads

    @property
    def chain_id(self) -> int:
        return self._chain_id

    def _data(self, address: Pubkey) -> bytes | None:
        return self.rpc.account(address)

    def now(self) -> int:
        d = self._data(CLOCK)
        return struct.unpack_from("<q", d, 32)[0] if d else int(time.time())

    def _event(self, event_id: int) -> dict | None:
        d = self._data(self.event_pda(event_id))
        if d is None:
            return None
        r = Reader(d)
        e = {"id": r.fmt("Q"), "organizer": r.take(32), "policy_hash": r.take(32), "amount": r.fmt("Q"),
             "capacity": r.fmt("I"), "commit_deadline": r.fmt("q"), "check_in_start": r.fmt("q"),
             "check_in_end": r.fmt("q"), "no_show": r.fmt("B"), "category": r.fmt("B"), "late_fee_bps": r.fmt("H"),
             "committed": r.fmt("I"), "attended": r.fmt("I"), "cancelled": bool(r.fmt("B")), "finalized": bool(r.fmt("B")),
             "attendee_payout": r.fmt("Q"), "waitlisted": r.fmt("I"), "refilled": r.fmt("I"), "released": r.fmt("I"),
             "recovered": r.fmt("Q")}
        e["waitlist"] = [(r.take(32), bool(r.fmt("B"))) for _ in range(r.fmt("I"))]
        return e

    def event(self, event_id: int) -> tuple[dict, dict]:
        e = self._event(event_id)
        if e is None:
            raise AnzenError(Reason.UNKNOWN_EVENT, str(event_id))
        cfg = {k: e[k] for k in ("organizer", "policy_hash", "amount", "capacity", "commit_deadline", "check_in_start",
                                 "check_in_end", "no_show", "category", "late_fee_bps")}
        cfg["issuer"] = self.issuer.address
        state = {k: e[k] for k in ("committed", "attended", "cancelled", "finalized", "attendee_payout", "waitlisted",
                                   "refilled", "released", "recovered")}
        return cfg, state

    def participation(self, event_id: int, key_id: bytes) -> dict:
        d = self._data(self.part_pda(event_id, key_id))
        if d is None:
            return {"status": 0, "attended": False, "checked_in_at": 0, "settled_amount": 0, "passport_id": 0,
                    "agent_ref": 0, "tier": 0, "by_agent": False, "agent": None}
        r = Reader(d)
        r.fmt("Q"), r.take(32)
        p = {"status": r.fmt("B"), "attended": bool(r.fmt("B")), "checked_in_at": r.fmt("q"),
             "settled_amount": r.fmt("Q"), "passport_id": r.fmt("Q"), "tier": r.fmt("B"), "by_agent": bool(r.fmt("B"))}
        agent = r.take(32)
        p["agent"] = "0x" + agent.hex() if p["by_agent"] else None
        p["agent_ref"] = 0
        return p

    def _member(self, key_id: bytes) -> dict:
        d = self._data(self.member_pda(key_id))
        if d is None:
            return {"balance": 0, "nonce": 0, "record": dict.fromkeys(
                        ["attended", "missed", "releasedInTime", "streak", "bestStreak", "shields"], 0),
                    "agent": ZERO, "cap": 0, "spent": 0, "expiry": 0,
                    "rules": {"categories": 0, "maxLateFeeBps": 0, "maxActive": 0, "maxLeadDays": 0}, "active": 0}
        r = Reader(d)
        r.take(32)
        m = {"balance": r.fmt("Q"), "nonce": r.fmt("Q")}
        m["record"] = dict(zip(["attended", "missed", "releasedInTime", "streak", "bestStreak", "shields"], r.fmt("6I")))
        m["agent"], m["cap"], m["spent"], m["expiry"] = r.take(32), r.fmt("Q"), r.fmt("Q"), r.fmt("q")
        m["rules"] = dict(zip(["categories", "maxLateFeeBps", "maxActive", "maxLeadDays"], r.fmt("IHBH")))
        m["active"] = r.fmt("B")
        return m

    def balance(self, key_id: bytes) -> int:
        return self._member(key_id)["balance"]

    def nonce(self, key_id: bytes) -> int:
        return self._member(key_id)["nonce"]

    def record(self, key_id: bytes) -> dict:
        return self._member(key_id)["record"]

    def score(self, key_id: bytes) -> int:
        r = self.record(key_id)  # same formula the Monad contract's showUpScore uses
        return (r["attended"] * 100 + 300) // (r["attended"] + r["missed"] + 4)

    def pass_rules(self, key_id: bytes) -> tuple[dict, int]:
        m = self._member(key_id)
        return m["rules"], m["active"]

    def delegation(self, key_id: bytes) -> dict:
        m = self._member(key_id)
        agent = "0x" + m["agent"].hex() if m["agent"] != ZERO else "0x" + "0" * 40
        return {"agent": agent, "cap": m["cap"], "spent": m["spent"], "expiry": m["expiry"], "agent_ref": 0}

    registries = (None, None)  # no ERC-8004 here: agent show-up records live in the program's AgentRecord accounts

    def show_up(self, agent: str) -> dict:
        """An agent's show-up record, written only by the program's settle instruction."""
        d = self._data(self.agent_pda(self.agent_key(agent)))
        if d is None:
            return {"count": 0, "showUpRate": None, "registry": None}
        r = Reader(d)
        r.take(32), r.fmt("Q")
        reservations, settled, showed = r.fmt("III")
        return {"count": settled, "showUpRate": (showed / settled * 100) if settled else None,
                "reservations": reservations, "registry": str(self.program)}

    @staticmethod
    def agent_key(agent: str) -> Pubkey:
        a = agent.strip()
        return Pubkey.from_bytes(bytes.fromhex(a[2:])) if a.startswith("0x") else Pubkey.from_string(a)

    def agent_nonce(self, agent: str) -> int:
        d = self._data(self.agent_pda(self.agent_key(agent)))
        return struct.unpack_from("<Q", d, 8 + 32)[0] if d else 0

    def relayer_balance(self) -> int:
        return self.rpc.balance(self.payer.pubkey())

    def receipt(self, tx: str) -> dict | None:
        st = self.rpc.status(tx)
        if st is None or st.get("confirmationStatus") not in ("confirmed", "finalized"):
            return None
        return {"status": 0 if st.get("err") else 1, "blockNumber": st["slot"], "tx": tx}

    def created_event_id(self, receipt: dict) -> int | None:
        tx = self.rpc.transaction(receipt["tx"])
        logs = (tx or {}).get("meta", {}).get("logMessages") or []
        want = disc("event", "EventCreated")
        for line in logs or []:
            if line.startswith("Program data: "):
                b = base64.b64decode(line.removeprefix("Program data: "))
                if b[:8] == want:
                    return struct.unpack_from("<Q", b, 8)[0]
        return None

    # ---------- writes

    def sign_challenge(self, event_id: int, challenge: bytes, expires_at: int) -> bytes:
        return bytes(self.issuer_kp.sign_message(
            messages.challenge(self.chain_id, self.treasury.address, event_id, challenge, expires_at)))

    def send(self, ixs: list[Instruction]) -> str:
        """Simulate, then broadcast. A failure is reported with the program's error name and nothing is sent."""
        with self.lock:
            tx = self._tx(ixs)
            sim = self.rpc.simulate(tx)
            if sim.get("err"):
                raise AnzenError(*self._reason(sim["err"], sim.get("logs") or []))
            return self.rpc.send(tx)

    def _tx(self, ixs: list[Instruction]) -> Transaction:
        bh = self.rpc.blockhash()
        msg = Message.new_with_blockhash(ixs, self.payer.pubkey(), bh)
        return Transaction([self.payer], msg, bh)

    @staticmethod
    def _reason(err, logs: list[str]) -> tuple[Reason, str]:
        for line in logs:
            m = re.search(r"Error Code: (\w+)", line)
            if m:
                name = m.group(1)
                return (Reason(name) if name in Reason._value2member_map_ else Reason.REVERTED), name
        text = str(err)
        m = re.search(r"Custom\((\d+)\)", text)
        if m and 6000 <= int(m.group(1)) < 6000 + len(ERRORS):
            name = ERRORS[int(m.group(1)) - 6000]
            return (Reason(name) if name in Reason._value2member_map_ else Reason.REVERTED), name
        if "secp256r1" in " ".join(logs).lower() or "precompile" in text.lower():
            return Reason.INVALID_SIGNATURE, "precompile rejected the signature"
        return Reason.REVERTED, text[:200]

    def wait(self, tx: str, timeout: float = 30) -> dict:
        end = time.time() + timeout
        while time.time() < end:
            r = self.receipt(tx)
            if r is not None:
                return r
            time.sleep(0.4)
        raise AnzenError(Reason.CHAIN_UNAVAILABLE, "not confirmed in time")

    def send_value(self, to: str, lamports: int) -> str:
        from solders.system_program import TransferParams, transfer
        return self.send([transfer(TransferParams(from_pubkey=self.payer.pubkey(), to_pubkey=self.agent_key(to),
                                                  lamports=lamports))])

    def faucet_deposit(self, key_id: bytes, amount: int) -> list[Instruction]:
        """Mint test dollars to the relayer's token account and deposit them for `key_id`, in one transaction."""
        src = self.ata(self.payer.pubkey())
        mint_to = Instruction(TOKEN, u8(7) + u64(amount), [AccountMeta(self.mint, False, True), AccountMeta(src, False, True),
                                                           AccountMeta(self.payer.pubkey(), True, False)])
        return [mint_to, *self.treasury.functions.depositFor(key_id, amount, src)]

    def ix(self, name: str, args: bytes, accounts: list[tuple[Pubkey, bool]], extra: list[Instruction] = (),
           remaining: list[tuple[Pubkey, bool]] = ()) -> list[Instruction]:
        metas = [AccountMeta(self.payer.pubkey(), True, True) if a == "payer" else AccountMeta(a, False, w)
                 for a, w in [*accounts, *remaining]]
        return [*extra, Instruction(self.program, disc("global", name) + args, metas)]


class _Functions:
    """The treasury's write surface, with the Monad contract's names and arguments (see service.py)."""

    def __init__(self, c: SolanaChain):
        self.c = c

    def _ctx(self):
        c = self.c
        return c, c.chain_id, c.treasury.address

    def depositFor(self, key: bytes, amount: int, src: Pubkey | None = None):
        c = self.c
        src = src or c.ata(c.payer.pubkey())
        return c.ix("deposit_for", key + u64(amount), [("payer", True), (c.config, False), (c.member_pda(key), True),
                                                       (src, True), (c.vault, True), (TOKEN, False), (SYSTEM, False)])

    def createEvent(self, *_a):
        raise AnzenError(Reason.INVALID_CONFIG, "publish with terms (create v2)")

    def createEventWithTerms(self, cfg: tuple, terms: tuple, qx: bytes, qy: bytes, deadline: int, auth: bytes):
        c, cid, t = self._ctx()
        organizer, policy_hash, issuer, amount, capacity, cd, cs, ce, no_show = cfg
        category, fee = terms
        nonce = c.nonce(organizer)
        cfg_obj = messages.EventConfig(organizer, policy_hash, issuer, amount, capacity, cd, cs, ce, no_show)
        arg, pre = _auth(auth, messages.create_v2(cid, t, nonce, deadline, cfg_obj, category, fee), qx, qy)
        cfg_state = c._data(c.config)
        event_id = struct.unpack_from("<Q", cfg_state, 8 + 128 + 8)[0] + 1
        if issuer.lower() != c.issuer.address.lower():
            raise AnzenError(Reason.INVALID_CONFIG, "issuer must be this program's check-in issuer")
        args = (u64(event_id) + qx + qy + policy_hash + u64(amount) + u32(capacity) + i64(cd) + i64(cs) + i64(ce) + u8(no_show) + u8(category) + u16(fee) + i64(deadline) + arg)
        return c.ix("create_event", args, [("payer", True), (c.config, True), (c.member_pda(organizer), True),
                                           (c.event_pda(event_id), True), (IXS, False), (SYSTEM, False)], [pre])

    def commit(self, event_id: int, qx: bytes, qy: bytes, deadline: int, auth: bytes):
        c, cid, t = self._ctx()
        key = messages.key_id(qx, qy)
        e = c._event(event_id)
        if e is None:
            raise AnzenError(Reason.UNKNOWN_EVENT, str(event_id))
        arg, pre = _auth(auth, messages.commit(cid, t, event_id, e["amount"], c.nonce(key), deadline), qx, qy)
        return c.ix("commit", u64(event_id) + key + qx + qy + i64(deadline) + arg,
                    [("payer", True), (c.config, False), (c.event_pda(event_id), True), (c.member_pda(key), True),
                     (c.part_pda(event_id, key), True), (IXS, False), (SYSTEM, False)], [pre])

    def _pass(self, name: str, x: bytes, y: bytes, agent: str, agent_id: int, cap: int, expiry: int, rules, deadline: int,
              auth: bytes):
        c, cid, t = self._ctx()
        key = messages.key_id(x, y)
        a = c.agent_key(agent)
        aid = (agent_id if agent_id != messages.NO_AGENT_ID else 2**256 - 1).to_bytes(32, "big")
        nonce = c.nonce(key)
        if rules is None:
            pre_bytes = messages.delegate(cid, t, addr20(a), agent_id, cap, expiry, nonce, deadline)
            tail = u64(cap) + i64(expiry) + i64(deadline)
        else:
            pre_bytes = messages.grant_pass(cid, t, addr20(a), agent_id, cap, expiry, *rules, nonce, deadline)
            tail = u64(cap) + i64(expiry) + u32(rules[0]) + u16(rules[1]) + u8(rules[2]) + u16(rules[3]) + i64(deadline)
        arg, pre = _auth(auth, pre_bytes, x, y)
        return c.ix(name, key + x + y + bytes(a) + aid + tail + arg,
                    [("payer", True), (c.config, False), (c.member_pda(key), True), (c.agent_pda(a), True), (IXS, False),
                     (SYSTEM, False)], [pre])

    def delegate(self, x, y, agent, agent_id, cap, expiry, deadline, auth):
        return self._pass("delegate", x, y, agent, agent_id, cap, expiry, None, deadline, auth)

    def grantPass(self, x, y, agent, agent_id, cap, expiry, rules, deadline, auth):
        return self._pass("grant_pass", x, y, agent, agent_id, cap, expiry, rules, deadline, auth)

    def commitByAgent(self, event_id: int, key: bytes, deadline: int, agent_sig: bytes):
        c, cid, t = self._ctx()
        e = c._event(event_id)
        if e is None:
            raise AnzenError(Reason.UNKNOWN_EVENT, str(event_id))
        m = c._member(key)
        if m["agent"] == ZERO:
            raise AnzenError(Reason.NOT_DELEGATED)
        agent = Pubkey.from_bytes(m["agent"])
        pre = messages.agent_commit(cid, t, event_id, key, e["amount"], c.agent_nonce(str(agent)), deadline)
        sig = _precompile(ED25519, bytes(agent), agent_sig[:64], pre)
        return c.ix("commit_by_agent", u64(event_id) + key + i64(deadline),
                    [("payer", True), (c.config, False), (c.event_pda(event_id), True), (c.member_pda(key), True),
                     (c.agent_pda(agent), True), (c.part_pda(event_id, key), True), (IXS, False), (SYSTEM, False)], [sig])

    def release(self, event_id: int, qx: bytes, qy: bytes, deadline: int, auth: bytes):
        c, cid, t = self._ctx()
        key = messages.key_id(qx, qy)
        e = c._event(event_id)
        if e is None:
            raise AnzenError(Reason.UNKNOWN_EVENT, str(event_id))
        arg, pre = _auth(auth, messages.release(cid, t, event_id, c.nonce(key), deadline), qx, qy)
        waiting = [k for k, w in e["waitlist"] if w]
        remaining = [(c.part_pda(event_id, waiting[0]), True)] if waiting else []
        return c.ix("release", u64(event_id) + key + qx + qy + i64(deadline) + arg,
                    [("payer", False), (c.config, False), (c.event_pda(event_id), True), (c.member_pda(key), True),
                     (c.part_pda(event_id, key), True), (c.member_pda(e["organizer"]), True), (IXS, False)], [pre], remaining)

    def checkIn(self, event_id: int, qx: bytes, qy: bytes, challenge: bytes, expires_at: int, issuer_sig: bytes, auth: bytes):
        c, cid, t = self._ctx()
        key = messages.key_id(qx, qy)
        issued = _precompile(ED25519, bytes(c.issuer_kp.pubkey()), issuer_sig[:64],
                             messages.challenge(cid, t, event_id, challenge, expires_at))
        arg, pre = _auth(auth, messages.check_in(cid, t, event_id, challenge, expires_at), qx, qy)
        return c.ix("check_in", u64(event_id) + key + qx + qy + challenge + i64(expires_at) + arg,
                    [("payer", False), (c.config, False), (c.event_pda(event_id), True), (c.part_pda(event_id, key), True),
                     (IXS, False)], [issued, pre])

    def checkInByStaff(self, event_id: int, gx: bytes, gy: bytes, issued_at: int, guest_auth: bytes, sx: bytes, sy: bytes,
                       staff_auth: bytes):
        c, cid, t = self._ctx()
        key = messages.key_id(gx, gy)
        g_arg, g_pre = _auth(guest_auth, messages.present(cid, t, event_id, issued_at), gx, gy)
        s_arg, s_pre = _auth(staff_auth, messages.staff(cid, t, event_id, key, issued_at), sx, sy)
        return c.ix("check_in_by_staff", u64(event_id) + key + gx + gy + i64(issued_at) + g_arg + sx + sy + s_arg,
                    [("payer", False), (c.config, False), (c.event_pda(event_id), True), (c.part_pda(event_id, key), True),
                     (IXS, False)], [g_pre, s_pre])

    def cancelEvent(self, event_id: int, qx: bytes, qy: bytes, deadline: int, auth: bytes):
        c, cid, t = self._ctx()
        e = c._event(event_id)
        if e is None:
            raise AnzenError(Reason.UNKNOWN_EVENT, str(event_id))
        arg, pre = _auth(auth, messages.cancel(cid, t, event_id, c.nonce(e["organizer"]), deadline), qx, qy)
        return c.ix("cancel_event", u64(event_id) + qx + qy + i64(deadline) + arg,
                    [("payer", False), (c.config, False), (c.event_pda(event_id), True), (c.member_pda(e["organizer"]), True),
                     (IXS, False)], [pre])

    def finalize(self, event_id: int):
        c = self.c
        e = c._event(event_id)
        return c.ix("finalize", u64(event_id), [(c.event_pda(event_id), True), (c.member_pda(e["organizer"]), True)])

    def settle(self, event_id: int, key: bytes):
        c = self.c
        p = c.participation(event_id, key)
        agent = c.agent_pda(c.agent_key(p["agent"])) if p["by_agent"] else c.program  # program id = "None"
        return c.ix("settle", u64(event_id) + key,
                    [(c.config, True), (c.event_pda(event_id), False), (c.member_pda(key), True),
                     (c.part_pda(event_id, key), True), (agent, p["by_agent"])])


def p256_public(sk: ec.EllipticCurvePrivateKey) -> tuple[bytes, bytes]:
    n = sk.public_key().public_numbers()
    return n.x.to_bytes(32, "big"), n.y.to_bytes(32, "big")
