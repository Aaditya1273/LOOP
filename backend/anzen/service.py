"""ANZEN service. It never decides an outcome: the contract does. The service
  - publishes the policy document whose sha256 the organizer signed into the event,
  - relays signed actions (paying gas, unable to forge them),
  - issues short-lived check-in challenges to desks the organizer's key opened,
  - assembles proofs from onchain state and the transactions it relayed."""

import hashlib
import json
import logging
import secrets
import sqlite3
import time

from . import messages, p256
from web3 import Web3

from .chain import Chain
from .config import MAINNETS, NETWORKS
from .errors import AnzenError, Reason

log = logging.getLogger("anzen")

CHALLENGE_TTL = 45  # seconds a venue code stays valid
MAX_SIGNATURE_WINDOW = 15 * 60
AGENT_GAS_WEI = 50 * 10**15  # 0.05 MON: one ERC-8004 registration on Monad testnet (gas limit x fee is charged)
NO_SHOW = {0: "organizer", 1: "split"}
STATUS = {0: "none", 1: "committed", 2: "checked_in", 3: "settled"}

POLICY_TEXT = {"title": (1, 80), "description": (0, 2000), "creator": (1, 60), "venue": (1, 200),
               "timezone": (1, 64), "disputeContact": (1, 200)}


def _hex(b: bytes) -> str:
    return "0x" + b.hex()


def _auth(h: str) -> bytes:
    """An account authorization: 64-byte raw P-256 (r ‖ s) or an abi-encoded WebAuthn assertion."""
    try:
        b = bytes.fromhex(h.removeprefix("0x"))
    except ValueError:
        raise AnzenError(Reason.INVALID_SIGNATURE, "malformed hex") from None
    if not 64 <= len(b) <= 4096:
        raise AnzenError(Reason.INVALID_SIGNATURE, "bad authorization length")
    return b


def _bytes(h: str, n: int = 32) -> bytes:
    try:
        b = bytes.fromhex(h.removeprefix("0x"))
    except ValueError:
        raise AnzenError(Reason.INVALID_SIGNATURE, "malformed hex") from None
    if len(b) != n:
        raise AnzenError(Reason.INVALID_SIGNATURE, f"expected {n} bytes")
    return b


class Anzen:
    def __init__(self, db: sqlite3.Connection, chain: Chain, *, faucet_amount: int = 100_000_000,
                 public_rpc: str | None = None):
        self.db, self.chain, self.faucet_amount, self.public_rpc = db, chain, faucet_amount, public_rpc
        if not isinstance(db, sqlite3.Connection):  # Postgres: instances must agree on the relayer's nonces
            chain.nonce_store = db

    # ---------------------------------------------------------------- reads

    def health(self) -> dict:
        cid = self.chain.chain_id
        name, explorer = NETWORKS.get(cid, (f"Chain {cid}", None))
        return {"ok": True, "chainId": cid, "network": name, "explorer": explorer,
                "treasury": self.chain.treasury.address, "token": self.chain.token.address,
                "issuer": self.chain.issuer.address, "relayer": self.chain.relayer.address,
                "relayerFunded": self.chain.relayer_balance() > 0, "faucet": cid not in MAINNETS,
                "asset": {"symbol": "tUSD", "decimals": 6, "label": "Test dollar, no value"},
                "erc8004": dict(zip(["identity", "reputation"], self.chain.registries)),
                "now": self.chain.now()}

    def events(self, query: str = "", key_id: str | None = None) -> list[dict]:
        rows = self.db.execute("SELECT * FROM events ORDER BY id DESC").fetchall()
        q = query.strip().lower()
        out = []
        for row in rows:
            doc = json.loads(row["policy"])
            if q and q not in (doc["title"] + " " + doc["creator"] + " " + doc["venue"]).lower():
                continue
            out.append(self._event(row, key_id))
        return out

    def event(self, event_id: int, key_id: str | None = None) -> dict:
        row = self.db.execute("SELECT * FROM events WHERE id=?", (event_id,)).fetchone()
        if row is None:
            raise AnzenError(Reason.UNKNOWN_EVENT, str(event_id))
        return self._event(row, key_id)

    def _event(self, row: sqlite3.Row, key_id: str | None) -> dict:
        cfg, st = self.chain.event(row["id"])
        doc = json.loads(row["policy"])
        now = self.chain.now()
        if st["cancelled"]:
            phase = "cancelled"
        elif st["finalized"]:
            phase = "finalized"
        elif now <= cfg["commit_deadline"]:
            phase = "open"
        elif now < cfg["check_in_start"]:
            phase = "closed"
        elif now <= cfg["check_in_end"]:
            phase = "check_in"
        else:
            phase = "awaiting_settlement"
        e = {
            "id": row["id"], "title": doc["title"], "description": doc["description"], "creator": doc["creator"],
            "venue": doc["venue"], "startsAt": doc["startsAt"], "timezone": doc["timezone"],
            "artwork": doc["artwork"], "disputeContact": doc["disputeContact"],
            "organizer": _hex(cfg["organizer"]), "policyHash": _hex(cfg["policy_hash"]),
            "policyVerified": hashlib.sha256(row["policy"].encode()).digest() == cfg["policy_hash"],
            "issuer": cfg["issuer"], "amount": cfg["amount"], "capacity": cfg["capacity"],
            "commitDeadline": cfg["commit_deadline"], "checkInStart": cfg["check_in_start"],
            "checkInEnd": cfg["check_in_end"], "noShow": NO_SHOW[cfg["no_show"]],
            "committed": st["committed"], "attended": st["attended"], "cancelled": st["cancelled"],
            "finalized": st["finalized"], "attendeePayout": st["attendee_payout"],
            "phase": phase, "now": now, "createTx": row["create_tx"],
        }
        if key_id:
            e["me"] = self._participation(row["id"], _bytes(key_id))
        return e

    def _participation(self, event_id: int, key_id: bytes) -> dict:
        p = self.chain.participation(event_id, key_id)
        return {"status": STATUS[p["status"]], "attended": p["attended"], "checkedInAt": p["checked_in_at"],
                "settledAmount": p["settled_amount"], "passportId": p["passport_id"],
                "agentId": p["agent_ref"] - 1 if p["agent_ref"] else None}

    def account(self, key_id: str) -> dict:
        kid = _bytes(key_id)
        # ponytail: one RPC per event; index EventCreated/Committed logs once events number in the thousands.
        events = self.events(key_id=key_id)
        d = self.chain.delegation(kid)
        active = d["agent"] != "0x0000000000000000000000000000000000000000" and d["expiry"] > self.chain.now()
        d["agentId"] = d.pop("agent_ref") - 1 if d["agent_ref"] else None
        d["reputation"] = self.chain.show_up(d["agentId"]) if d["agentId"] is not None else None
        return {
            "keyId": key_id, "balance": self.chain.balance(kid), "nonce": self.chain.nonce(kid),
            "delegation": {**d, "active": active, "remaining": max(d["cap"] - d["spent"], 0) if active else 0},
            "faucetUsed": self.db.execute("SELECT 1 FROM faucet WHERE key_id=?", (key_id,)).fetchone() is not None,
            "commitments": [e for e in events if e["me"]["status"] != "none"],
            "organized": [e for e in events if e["organizer"] == key_id],
        }

    def activity(self, key_id: str) -> list[dict]:
        rows = self.db.execute(
            "SELECT r.*, e.policy FROM relays r LEFT JOIN events e ON e.id = r.event_id "
            "WHERE r.key_id=? ORDER BY r.created_at DESC LIMIT 100", (key_id,)).fetchall()
        return [{"action": r["action"], "tx": r["tx"], "status": self._refresh(r)["status"],
                 "eventId": r["event_id"], "eventTitle": json.loads(r["policy"])["title"] if r["policy"] else None,
                 "at": r["created_at"], "explorer": self._explorer(r["tx"])} for r in rows]

    def tx(self, tx: str) -> dict:
        row = self.db.execute("SELECT * FROM relays WHERE tx=?", (tx,)).fetchone()
        if row is None:
            raise AnzenError(Reason.TX_NOT_FOUND, tx)
        row = self._refresh(row)
        return {"tx": tx, "action": row["action"], "status": row["status"], "block": row["block"],
                "eventId": row["event_id"], "explorer": self._explorer(tx)}

    def _refresh(self, row: sqlite3.Row) -> dict:
        """Moves a submitted relay to confirmed/failed from its receipt. Records a created event's id."""
        r = dict(row)
        if r["status"] != "submitted":
            return r
        receipt = self.chain.receipt(r["tx"])
        if receipt is None:
            return r
        r["status"], r["block"] = ("confirmed" if receipt["status"] == 1 else "failed"), receipt["blockNumber"]
        if r["action"] == "create" and r["status"] == "confirmed":
            r["event_id"] = self.chain.created_event_id(receipt)
            self.db.execute("INSERT OR IGNORE INTO events(id, policy, organizer, create_tx, created_at) VALUES (?,?,?,?,?)",
                            (r["event_id"], r["payload"], r["key_id"], r["tx"], r["created_at"]))
        self.db.execute("UPDATE relays SET status=?, block=?, event_id=? WHERE tx=?",
                        (r["status"], r["block"], r["event_id"], r["tx"]))
        self.db.commit()
        return r

    def proof(self, event_id: int, key_id: str) -> dict:
        e = self.event(event_id, key_id)
        txs = self.db.execute("SELECT * FROM relays WHERE event_id=? AND (key_id=? OR action IN ('create','finalize')) "
                              "ORDER BY created_at", (event_id, key_id)).fetchall()
        return {
            "event": e, "keyId": key_id, "participation": e["me"],
            "transactions": [{"action": r["action"], "tx": r["tx"], "status": self._refresh(r)["status"],
                              "block": r["block"], "explorer": self._explorer(r["tx"])} for r in txs],
            "chainId": self.chain.chain_id, "treasury": self.chain.treasury.address,
            "limitations": [
                "The check-in proves this key signed a venue code that the event's issuer signed and that "
                "expired within 45 seconds. It does not prove where the key holder physically was.",
                "Venue codes are issued by the ANZEN service to a desk the organizer's key opened. Anyone who "
                "can see a live code can use it until it expires.",
                "The policy document is stored by the ANZEN service; its sha256 is fixed onchain, so any change "
                "to it is detectable, but the service could stop serving it.",
                "Keys are Android Keystore P-256 keys verified as raw signatures, not WebAuthn assertions.",
                "A commitment may have been placed by the account's AI agent within a budget the owner signed. "
                "Check-in can only be signed by the owner's own key.",
            ],
        }

    def _explorer(self, tx: str) -> str | None:
        base = NETWORKS.get(self.chain.chain_id, (None, None))[1]
        return base + tx if base else None

    # ---------------------------------------------------------------- relayed actions

    def create_event(self, policy: str, cfg: messages.EventConfig, qx: str, qy: str, deadline: int,
                     auth: str) -> dict:
        doc = self._validate_policy(policy, cfg)
        call = self.chain.treasury.functions.createEvent(cfg.as_tuple(), _bytes(qx), _bytes(qy), deadline,
                                                         _auth(auth))
        tx = self._relay("create", f"create|{auth}", call, key_id=_hex(cfg.organizer), payload=policy)
        log.info(json.dumps({"stage": "create", "tx": tx, "title": doc["title"]}))
        return {"tx": tx}

    def commit(self, event_id: int, qx: str, qy: str, deadline: int, auth: str) -> dict:
        call = self.chain.treasury.functions.commit(event_id, _bytes(qx), _bytes(qy), deadline, _auth(auth))
        return {"tx": self._relay("commit", f"commit|{event_id}|{auth}", call, event_id,
                                  _hex(messages.key_id(_bytes(qx), _bytes(qy))))}

    def cancel(self, event_id: int, qx: str, qy: str, deadline: int, auth: str) -> dict:
        call = self.chain.treasury.functions.cancelEvent(event_id, _bytes(qx), _bytes(qy), deadline, _auth(auth))
        return {"tx": self._relay("cancel", f"cancel|{event_id}|{auth}", call, event_id,
                                  _hex(messages.key_id(_bytes(qx), _bytes(qy))))}

    def check_in(self, event_id: int, qx: str, qy: str, challenge: str, expires_at: int, issuer_sig: str,
                 auth: str) -> dict:
        call = self.chain.treasury.functions.checkIn(event_id, _bytes(qx), _bytes(qy), _bytes(challenge), expires_at,
                                                     _bytes(issuer_sig, 65), _auth(auth))
        return {"tx": self._relay("checkin", f"checkin|{event_id}|{auth}", call, event_id,
                                  _hex(messages.key_id(_bytes(qx), _bytes(qy))))}

    def delegate(self, agent: str, agent_id: int, cap: int, expiry: int, qx: str, qy: str, deadline: int,
                 auth: str) -> dict:
        x, y = _bytes(qx), _bytes(qy)
        call = self.chain.treasury.functions.delegate(x, y, Web3.to_checksum_address(agent), agent_id, cap, expiry,
                                                      deadline, _auth(auth))
        tx = self._relay("delegate", f"delegate|{auth}", call, key_id=_hex(messages.key_id(x, y)))
        if agent_id != messages.NO_AGENT_ID and cap > 0:
            self.db.execute("INSERT OR IGNORE INTO agents(agent_id, agent, first_seen) VALUES (?,?,?)",
                            (str(agent_id), Web3.to_checksum_address(agent), time.time()))
        return {"tx": tx}

    def agents(self) -> list[dict]:
        """Every agent an owner has bound a budget to, with the show-up reputation the treasury wrote for it."""
        rows = self.db.execute("SELECT * FROM agents ORDER BY first_seen DESC LIMIT 100").fetchall()
        out = [{"agentId": int(r["agent_id"]), "agent": r["agent"], **self.chain.show_up(int(r["agent_id"]))} for r in rows]
        return sorted(out, key=lambda a: (-(a["count"]), -(a["showUpRate"] or 0)))

    def agent_info(self, agent: str, agent_id: int | None = None) -> dict:
        """What an agent needs to sign its next commit, and (with its ERC-8004 id) its show-up reputation."""
        ident, rep = self.chain.registries
        return {"agent": Web3.to_checksum_address(agent), "nonce": self.chain.agent_nonce(agent),
                "chainId": self.chain.chain_id, "treasury": self.chain.treasury.address, "now": self.chain.now(),
                "rpc": self.public_rpc, "identityRegistry": ident, "reputationRegistry": rep,
                "reputation": self.chain.show_up(agent_id) if agent_id is not None else None}

    def agent_card(self, agent: str, base_url: str) -> dict:
        """The ERC-8004 registration file an ANZEN agent points its identity at."""
        return {"type": "https://eips.ethereum.org/EIPS/eip-8004#registration-v1", "name": "ANZEN event agent",
                "description": "Finds creator events and reserves places for its owner within a budget the owner "
                               "signed on their phone. It cannot check in: presence is the owner's.",
                "services": [{"name": "anzen", "endpoint": base_url}],
                "registrations": [{"agentRegistry": f"eip155:{self.chain.chain_id}:{self.chain.registries[0]}"}],
                "supportedTrust": ["reputation"], "wallet": Web3.to_checksum_address(agent)}

    def agent_gas(self, agent: str) -> dict:
        """Testnet only: a little MON so a new agent can register its own ERC-8004 identity. Once per agent."""
        if self.chain.chain_id in MAINNETS:
            raise AnzenError(Reason.FAUCET_DISABLED)
        addr = Web3.to_checksum_address(agent)
        try:
            with self.db:
                self.db.execute("INSERT INTO faucet(key_id, created_at) VALUES (?,?)", ("gas:" + addr.lower(), time.time()))
        except sqlite3.IntegrityError:
            raise AnzenError(Reason.FAUCET_USED) from None
        tx = self.chain.send_value(addr, AGENT_GAS_WEI)
        self.chain.wait(tx)
        return {"tx": tx, "amount": AGENT_GAS_WEI}

    def commit_by_agent(self, event_id: int, key_id: str, deadline: int, agent_sig: str) -> dict:
        call = self.chain.treasury.functions.commitByAgent(event_id, _bytes(key_id), deadline, _bytes(agent_sig, 65))
        return {"tx": self._relay("agent_commit", f"agent_commit|{event_id}|{agent_sig}", call, event_id, key_id)}

    def settle(self, event_id: int, key_id: str) -> dict:
        """Finalizes the event if nobody has yet (waiting for it), then settles this participant."""
        e = self.event(event_id)
        if not e["cancelled"] and not e["finalized"]:
            if e["phase"] != "awaiting_settlement":
                raise AnzenError(Reason.WRONG_PHASE, "check-in has not closed")
            fin = self._relay("finalize", f"finalize|{event_id}", self.chain.treasury.functions.finalize(event_id),
                              event_id)
            if self.chain.wait(fin)["status"] != 1:
                raise AnzenError(Reason.REVERTED, "finalize failed")
            self.tx(fin)
        call = self.chain.treasury.functions.settle(event_id, _bytes(key_id))
        return {"tx": self._relay("settle", f"settle|{event_id}|{key_id}", call, event_id, key_id)}

    def faucet(self, key_id: str) -> dict:
        if self.chain.chain_id in MAINNETS:
            raise AnzenError(Reason.FAUCET_DISABLED)
        kid = _bytes(key_id)
        try:
            with self.db:
                self.db.execute("INSERT INTO faucet(key_id, created_at) VALUES (?,?)", (key_id, time.time()))
        except sqlite3.IntegrityError:
            raise AnzenError(Reason.FAUCET_USED) from None
        try:
            c, me = self.chain, self.chain.relayer.address
            if c.token.functions.allowance(me, c.treasury.address).call() < self.faucet_amount:
                c.wait(c.send(c.token.functions.approve(c.treasury.address, 2**256 - 1)))
            c.wait(c.send(c.token.functions.mint(me, self.faucet_amount)))
            tx = self._relay("faucet", f"faucet|{key_id}", c.treasury.functions.depositFor(kid, self.faucet_amount),
                             key_id=key_id)
        except Exception:
            with self.db:
                self.db.execute("DELETE FROM faucet WHERE key_id=?", (key_id,))
            raise
        return {"tx": tx, "amount": self.faucet_amount}

    def _relay(self, action: str, idem: str, call, event_id: int | None = None, key_id: str | None = None,
               payload: str | None = None) -> str:
        """Idempotent: the same signed action returns the transaction it already produced."""
        idem = hashlib.sha256(idem.encode()).hexdigest()
        row = self.db.execute("SELECT tx, status FROM relays WHERE idem=?", (idem,)).fetchone()
        if row and row["status"] != "failed":
            return row["tx"]
        tx = self.chain.send(call)
        with self.db:
            self.db.execute("DELETE FROM relays WHERE idem=?", (idem,))
            self.db.execute("INSERT INTO relays(tx, idem, action, event_id, key_id, payload, status, created_at) "
                            "VALUES (?,?,?,?,?,?, 'submitted', ?)",
                            (tx, idem, action, event_id, key_id, payload, time.time()))
        return tx

    # ---------------------------------------------------------------- check-in desk

    def open_desk(self, event_id: int, qx: str, qy: str, deadline: int, auth: str) -> dict:
        if deadline < self.chain.now() or deadline > self.chain.now() + MAX_SIGNATURE_WINDOW:
            raise AnzenError(Reason.EXPIRED)
        e = self.event(event_id)
        x, y = _bytes(qx), _bytes(qy)
        if _hex(messages.key_id(x, y)) != e["organizer"]:
            raise AnzenError(Reason.NOT_ORGANIZER)
        msg = messages.desk(self.chain.chain_id, self.chain.treasury.address, event_id, deadline)
        if not p256.verify(x, y, _auth(auth), msg):
            raise AnzenError(Reason.INVALID_SIGNATURE)
        token = secrets.token_urlsafe(24)
        with self.db:
            self.db.execute("INSERT INTO desks(token, event_id, expires_at) VALUES (?,?,?)",
                            (token, event_id, e["checkInEnd"] + 3600))
        return {"token": token, "checkInStart": e["checkInStart"], "checkInEnd": e["checkInEnd"]}

    def challenge(self, event_id: int, token: str) -> dict:
        desk = self.db.execute("SELECT * FROM desks WHERE token=? AND event_id=?", (token, event_id)).fetchone()
        now = self.chain.now()
        if desk is None or desk["expires_at"] < now:
            raise AnzenError(Reason.INVALID_DESK)
        e = self.event(event_id)
        if e["phase"] != "check_in":
            raise AnzenError(Reason.WRONG_PHASE, e["phase"])
        challenge = secrets.token_bytes(32)
        expires_at = min(now + CHALLENGE_TTL, e["checkInEnd"])
        digest = messages.challenge_hash(self.chain.chain_id, self.chain.treasury.address, event_id, challenge,
                                         expires_at)
        sig = _hex(self.chain.sign_challenge(digest))
        return {"eventId": event_id, "challenge": _hex(challenge), "expiresAt": expires_at, "issuerSig": sig,
                "uri": f"anzen://checkin?e={event_id}&c={_hex(challenge)}&x={expires_at}&s={sig}"}

    # ---------------------------------------------------------------- validation

    def _validate_policy(self, policy: str, cfg: messages.EventConfig) -> dict:
        if hashlib.sha256(policy.encode()).digest() != cfg.policy_hash:
            raise AnzenError(Reason.POLICY_MISMATCH, "sha256(policy) != policyHash")
        try:
            doc = json.loads(policy)
        except json.JSONDecodeError:
            raise AnzenError(Reason.POLICY_MISMATCH, "policy is not JSON") from None
        for k, (lo, hi) in POLICY_TEXT.items():
            if not isinstance(doc.get(k), str) or not lo <= len(doc[k].strip()) <= hi:
                raise AnzenError(Reason.POLICY_MISMATCH, f"{k} missing or wrong length")
        expected = {"version": 1, "amount": cfg.amount, "capacity": cfg.capacity, "commitDeadline": cfg.commit_deadline,
                    "checkInStart": cfg.check_in_start, "checkInEnd": cfg.check_in_end,
                    "noShow": NO_SHOW.get(cfg.no_show), "issuer": cfg.issuer.lower()}
        for k, v in expected.items():
            got = doc.get(k).lower() if isinstance(doc.get(k), str) and k == "issuer" else doc.get(k)
            if got != v:
                raise AnzenError(Reason.POLICY_MISMATCH, f"{k} in the document differs from the signed terms")
        if not isinstance(doc.get("startsAt"), int) or not isinstance(doc.get("artwork"), int):
            raise AnzenError(Reason.POLICY_MISMATCH, "startsAt/artwork")
        if cfg.issuer.lower() != self.chain.issuer.address.lower():
            raise AnzenError(Reason.INVALID_CONFIG, "issuer must be this service's check-in issuer")
        return doc
