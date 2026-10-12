"""ANZEN agent client: what an AI agent (OpenClaw skill, or anything with a shell) uses to reserve event places
for its owner inside the budget the owner signed on their phone.

  python -m anzen.agent init                  create this agent's key and ERC-8004 identity; print an approval link
  python -m anzen.agent budget                the owner's remaining budget for this agent
  python -m anzen.agent reputation            this agent's show-up rate, as reported by the ANZEN treasury
  python -m anzen.agent events [query]        open events (id, title, when, price, seats left)
  python -m anzen.agent reserve <event_id>    commit the owner's funds to one event (within budget)

Environment:
  ANZEN_URL           service URL (default: the live service, https://anzen-shonko.vercel.app)
  ANZEN_OWNER         the owner's account key id (0x..., shown in ANZEN > Account)
  ANZEN_AGENT_KEYFILE agent key file (default ~/.anzen/agent.key)

The agent key can only spend inside the owner's onchain cap, until the owner's expiry. It cannot check in,
withdraw, cancel, or raise its own budget: those need the owner's phone. Output is JSON for tools, plus a short
human line on stderr.
"""

import base64
import json
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path

from eth_account import Account
from eth_account.messages import encode_defunct

from . import messages

BASE = os.environ.get("ANZEN_URL", "https://anzen-shonko.vercel.app").rstrip("/")  # live service on Monad testnet
KEYFILE = Path(os.environ.get("ANZEN_AGENT_KEYFILE", Path.home() / ".anzen" / "agent.key"))
DEFAULT_CAP = 20  # tUSD suggested in the approval link; the owner chooses the real cap


def http(method: str, path: str, body: dict | None = None, headers: dict | None = None,
         allow: tuple[int, ...] = ()) -> dict | list:
    req = urllib.request.Request(BASE + path, method=method, headers={"Content-Type": "application/json", **(headers or {})},
                                 data=json.dumps(body).encode() if body is not None else None)
    try:
        with urllib.request.urlopen(req, timeout=90) as r:
            return json.loads(r.read())
    except urllib.error.HTTPError as e:
        if e.code in allow:
            return json.loads(e.read())
        err = json.loads(e.read() or b"{}")
        out({"ok": False, "error": err.get("error", f"HTTP {e.code}"), "detail": err.get("detail", "")},
            f"refused: {err.get('error')}")
        sys.exit(1)
    except urllib.error.URLError as e:
        out({"ok": False, "error": "Offline", "detail": str(e.reason)}, "can't reach ANZEN")
        sys.exit(1)


def out(data: dict, human: str) -> None:
    print(json.dumps(data))
    print(human, file=sys.stderr)


class Agent:
    """This agent's key. The same 32-byte secret is an EVM key on Monad and an ed25519 seed on Solana."""

    def __init__(self, secret: str):
        self.secret = secret
        self.svm = http("GET", "/health").get("vm") == "svm"
        if self.svm:
            from solders.keypair import Keypair
            self.kp = Keypair.from_seed(bytes.fromhex(secret.removeprefix("0x")))
            self.address = "0x" + bytes(self.kp.pubkey()).hex()
        else:
            self.acct = Account.from_key(secret)
            self.address = self.acct.address

    def sign_commit(self, chain_id: int, treasury: str, event_id: int, key_id: str, amount: int, nonce: int,
                    deadline: int) -> str:
        args = (chain_id, treasury, event_id, bytes.fromhex(key_id[2:]), amount, nonce, deadline)
        if self.svm:  # ed25519 over the preimage, checked by Solana's ed25519 precompile
            return "0x" + bytes(self.kp.sign_message(messages.agent_commit(*args))).hex()
        digest = messages.agent_commit_hash(*args)
        return "0x" + Account.sign_message(encode_defunct(primitive=digest), self.acct.key).signature.hex().removeprefix("0x")


def agent() -> Agent:
    if not KEYFILE.exists():
        out({"ok": False, "error": "NoAgentKey"}, "run `init` first")
        sys.exit(1)
    return Agent(KEYFILE.read_text().strip())


def owner() -> str:
    o = os.environ.get("ANZEN_OWNER", "")
    if not (o.startswith("0x") and len(o) == 66):
        out({"ok": False, "error": "NoOwner"}, "set ANZEN_OWNER to the owner's account key id")
        sys.exit(1)
    return o


IDFILE = KEYFILE.with_suffix(".id")


def agent_id() -> int | None:
    return int(IDFILE.read_text()) if IDFILE.exists() else None


def register_identity(a: Agent) -> int | None:
    """Mints this agent's ERC-8004 identity in the official registry (its own transaction, its own key). On Solana
    there is no ERC-8004: the agent's key is its identity and the program keeps its show-up record."""
    from web3 import Web3
    info = http("GET", f"/agents/{a.address}")
    if a.svm or not info.get("identityRegistry"):
        return None  # no ERC-8004 on this network
    w3 = Web3(Web3.HTTPProvider(info["rpc"]))
    if w3.eth.get_balance(a.address) == 0:
        http("POST", f"/agents/{a.address}/gas")  # testnet drip, once per agent
    abi = [{"name": "register", "type": "function", "stateMutability": "nonpayable", "inputs": [{"type": "string"}],
            "outputs": [{"type": "uint256"}]},
           {"name": "Registered", "type": "event", "anonymous": False, "inputs": [
               {"name": "agentId", "type": "uint256", "indexed": True}, {"name": "agentURI", "type": "string", "indexed": False},
               {"name": "owner", "type": "address", "indexed": True}]}]
    reg = w3.eth.contract(address=info["identityRegistry"], abi=abi)
    uri = f"{BASE}/agents/{a.address}/card"  # short URI: registry storage is paid by the agent
    call = reg.functions.register(uri)
    fee = w3.eth.gas_price  # Monad charges gas limit x max fee up front, so keep both tight
    tx = call.build_transaction({"from": a.address, "nonce": w3.eth.get_transaction_count(a.address),
                                 "gas": int(call.estimate_gas({"from": a.address}) * 1.15),
                                 "maxFeePerGas": fee, "maxPriorityFeePerGas": min(fee, 2 * 10**9)})
    receipt = w3.eth.wait_for_transaction_receipt(w3.eth.send_raw_transaction(a.acct.sign_transaction(tx).raw_transaction))
    from web3.logs import DISCARD
    return reg.events.Registered().process_receipt(receipt, errors=DISCARD)[0]["args"]["agentId"]


def cmd_init() -> None:
    if not KEYFILE.exists():
        KEYFILE.parent.mkdir(parents=True, exist_ok=True)
        KEYFILE.write_text(Account.create().key.hex())
        KEYFILE.chmod(0o600)
    a = agent()
    if agent_id() is None and (aid := register_identity(a)) is not None:
        IDFILE.write_text(str(aid))
    aid = agent_id()
    query = f"agent={a.address}&cap={DEFAULT_CAP}&days=7" + (f"&id={aid}" if aid is not None else "")
    web, app = f"{BASE}/#/delegate?{query}", f"anzen://delegate?{query}"
    out({"ok": True, "agent": a.address, "erc8004AgentId": aid, "approvalLink": web, "appLink": app},
        f"agent {a.address}" + (f", ERC-8004 identity #{aid}" if aid is not None else "") +
        f". Ask the owner to open this link and approve a budget with their passkey: {web}")


def cmd_reputation() -> None:
    aid = agent_id()
    info = http("GET", f"/agents/{agent().address}" + (f"?agentId={aid}" if aid is not None else ""))
    rep = info.get("reputation") or {"count": 0, "showUpRate": None}
    rate = rep["showUpRate"]
    out({"ok": True, "erc8004AgentId": aid, **rep, "reportedBy": info["treasury"]},
        f"show-up rate {rate:g}% over {rep['count']} reservations" if rate is not None
        else "no settled reservations yet")


def cmd_budget() -> None:
    acct = http("GET", f"/accounts/{owner()}")
    d = acct["delegation"]
    mine = d["agent"].lower() == agent().address.lower()
    remaining = d["remaining"] if mine else 0
    out({"ok": True, "active": d["active"] and mine, "remaining": remaining, "cap": d["cap"] if mine else 0,
         "expiry": d["expiry"] if mine else 0, "ownerBalance": acct["balance"],
         "rules": d["rules"] if mine else None, "activeBookings": d["activeBookings"] if mine else 0},
        f"budget left: {remaining / 1e6:g} tUSD" + ("" if d["active"] and mine else " (no active budget for this agent)"))


def cmd_events(query: str) -> None:
    rows = http("GET", f"/events?q={urllib.request.quote(query)}&key={owner()}")
    open_ = [{"id": e["id"], "title": e["title"], "creator": e["creator"], "venue": e["venue"],
              "startsAt": e["startsAt"], "commitDeadline": e["commitDeadline"], "amount": e["amount"],
              "seatsLeft": e["capacity"] - e["committed"], "waitlist": e["committed"] >= e["capacity"],
              "category": e["categoryName"], "lateFeeBps": e["lateFeeBps"],
              "alreadyReserved": e["me"]["status"] not in ("none", "released")}
             for e in rows if e["phase"] == "open" and e["policyVerified"]]
    out({"ok": True, "events": open_}, f"{len(open_)} open events")


def cmd_reserve(event_id: int) -> None:
    """x402: ask for the resource, get 402 + what to pay, sign it from the owner's Agent Pass, retry with X-PAYMENT."""
    a, key_id = agent(), owner()
    path = f"/x402/events/{event_id}/reserve"
    req = http("GET", path, allow=(402,))["accepts"][0]
    x = req["extra"]
    nonce = http("GET", f"/agents/{a.address}")["nonce"]
    deadline = x["now"] + 600
    sig = a.sign_commit(x["chainId"], x["treasury"], event_id, key_id, x["amount"], nonce, deadline)
    payment = {"x402Version": 1, "scheme": req["scheme"], "network": req["network"],
               "payload": {"keyId": key_id, "deadline": deadline, "signature": sig}}
    r = http("GET", path, headers={"X-PAYMENT": base64.b64encode(json.dumps(payment).encode()).decode()}, allow=(409,))
    held = r.get("reservation")
    out({"ok": r["ok"], "tx": r["tx"], "status": r["txStatus"], "reservation": held, "eventId": event_id,
         "amount": x["amount"]},
        (f"on the waitlist with {x['amount'] / 1e6:g} tUSD held: the seat is theirs if someone releases one"
         if held == "waitlisted" else
         f"reserved for {x['amount'] / 1e6:g} tUSD. The owner must check in in person.") if r["ok"]
        else f"transaction {r['txStatus']}: {r['tx']}")


if __name__ == "__main__":
    args = sys.argv[1:] or ["help"]
    match args[0]:
        case "init": cmd_init()
        case "budget": cmd_budget()
        case "reputation": cmd_reputation()
        case "events": cmd_events(" ".join(args[1:]))
        case "reserve" if len(args) == 2 and args[1].isdigit(): cmd_reserve(int(args[1]))
        case _: print(__doc__)
