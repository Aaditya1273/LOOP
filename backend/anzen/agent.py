"""ANZEN agent client: what an AI agent (OpenClaw skill, or anything with a shell) uses to reserve event places
for its owner inside the budget the owner signed on their phone.

  python -m anzen.agent init                  create this agent's key and ERC-8004 identity; print an approval link
  python -m anzen.agent budget                the owner's remaining budget for this agent
  python -m anzen.agent reputation            this agent's show-up rate, as reported by the ANZEN treasury
  python -m anzen.agent events [query]        open events (id, title, when, price, seats left)
  python -m anzen.agent reserve <event_id>    commit the owner's funds to one event (within budget)

Environment:
  ANZEN_URL           service URL (default http://127.0.0.1:8790)
  ANZEN_OWNER         the owner's account key id (0x..., shown in Shonko > Profile)
  ANZEN_AGENT_KEYFILE agent key file (default ~/.anzen/agent.key)

The agent key can only spend inside the owner's onchain cap, until the owner's expiry. It cannot check in,
withdraw, cancel, or raise its own budget: those need the owner's phone. Output is JSON for tools, plus a short
human line on stderr.
"""

import json
import os
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

from eth_account import Account
from eth_account.messages import encode_defunct

from . import messages

BASE = os.environ.get("ANZEN_URL", "http://127.0.0.1:8790").rstrip("/")
KEYFILE = Path(os.environ.get("ANZEN_AGENT_KEYFILE", Path.home() / ".anzen" / "agent.key"))
DEFAULT_CAP = 20  # tUSD suggested in the approval link; the owner chooses the real cap


def http(method: str, path: str, body: dict | None = None) -> dict | list:
    req = urllib.request.Request(BASE + path, method=method, headers={"Content-Type": "application/json"},
                                 data=json.dumps(body).encode() if body is not None else None)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return json.loads(r.read())
    except urllib.error.HTTPError as e:
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


def agent() -> Account:
    if not KEYFILE.exists():
        out({"ok": False, "error": "NoAgentKey"}, "run `init` first")
        sys.exit(1)
    return Account.from_key(KEYFILE.read_text().strip())


def owner() -> str:
    o = os.environ.get("ANZEN_OWNER", "")
    if not (o.startswith("0x") and len(o) == 66):
        out({"ok": False, "error": "NoOwner"}, "set ANZEN_OWNER to the owner's account key id")
        sys.exit(1)
    return o


IDFILE = KEYFILE.with_suffix(".id")
AGENT_CARD = {"type": "https://eips.ethereum.org/EIPS/eip-8004#registration-v1", "name": "ANZEN event agent",
              "description": "Finds creator events and reserves places for its owner within a budget the owner "
                             "signed. Cannot check in: presence is the owner's.",
              "services": [{"name": "anzen", "endpoint": BASE}]}


def agent_id() -> int | None:
    return int(IDFILE.read_text()) if IDFILE.exists() else None


def register_identity(a) -> int | None:
    """Mints this agent's ERC-8004 identity in the official registry (its own transaction, its own key)."""
    from web3 import Web3
    info = http("GET", f"/agents/{a.address}")
    if not info.get("identityRegistry"):
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
    import base64
    uri = "data:application/json;base64," + base64.b64encode(json.dumps(AGENT_CARD).encode()).decode()
    call = reg.functions.register(uri)
    tx = call.build_transaction({"from": a.address, "nonce": w3.eth.get_transaction_count(a.address),
                                 "gas": int(call.estimate_gas({"from": a.address}) * 1.3)})
    receipt = w3.eth.wait_for_transaction_receipt(w3.eth.send_raw_transaction(a.sign_transaction(tx).raw_transaction))
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
    link = f"anzen://delegate?agent={a.address}&cap={DEFAULT_CAP}&days=7" + (f"&id={aid}" if aid is not None else "")
    out({"ok": True, "agent": a.address, "erc8004AgentId": aid, "approvalLink": link},
        f"agent {a.address}" + (f", ERC-8004 identity #{aid}" if aid is not None else "") +
        f". Ask the owner to open this link on their phone and approve a budget: {link}")


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
         "expiry": d["expiry"] if mine else 0, "ownerBalance": acct["balance"]},
        f"budget left: {remaining / 1e6:g} tUSD" + ("" if d["active"] and mine else " (no active budget for this agent)"))


def cmd_events(query: str) -> None:
    rows = http("GET", f"/events?q={urllib.request.quote(query)}&key={owner()}")
    open_ = [{"id": e["id"], "title": e["title"], "creator": e["creator"], "venue": e["venue"],
              "startsAt": e["startsAt"], "commitDeadline": e["commitDeadline"], "amount": e["amount"],
              "seatsLeft": e["capacity"] - e["committed"], "alreadyReserved": e["me"]["status"] != "none"}
             for e in rows if e["phase"] == "open" and e["committed"] < e["capacity"] and e["policyVerified"]]
    out({"ok": True, "events": open_}, f"{len(open_)} open events")


def cmd_reserve(event_id: int) -> None:
    a, key_id = agent(), owner()
    e = http("GET", f"/events/{event_id}?key={key_id}")
    info = http("GET", f"/agents/{a.address}")
    deadline = info["now"] + 600
    digest = messages.agent_commit_hash(info["chainId"], info["treasury"], event_id, bytes.fromhex(key_id[2:]),
                                        e["amount"], info["nonce"], deadline)
    sig = "0x" + Account.sign_message(encode_defunct(primitive=digest), a.key).signature.hex().removeprefix("0x")
    tx = http("POST", f"/events/{event_id}/agent-commit", {"keyId": key_id, "deadline": deadline, "agentSig": sig})["tx"]
    for _ in range(90):
        s = http("GET", f"/tx/{tx}")
        if s["status"] != "submitted":
            break
        time.sleep(0.7)
    ok = s["status"] == "confirmed"
    out({"ok": ok, "tx": tx, "status": s["status"], "eventId": event_id, "amount": e["amount"]},
        (f"reserved “{e['title']}” for {e['amount'] / 1e6:g} tUSD. The owner must check in in person."
         if ok else f"transaction {s['status']}: {tx}"))


if __name__ == "__main__":
    args = sys.argv[1:] or ["help"]
    match args[0]:
        case "init": cmd_init()
        case "budget": cmd_budget()
        case "reputation": cmd_reputation()
        case "events": cmd_events(" ".join(args[1:]))
        case "reserve" if len(args) == 2 and args[1].isdigit(): cmd_reserve(int(args[1]))
        case _: print(__doc__)
