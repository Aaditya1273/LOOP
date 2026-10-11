"""End-to-end smoke run against a live ANZEN service (e.g. Monad testnet). Real keys, real chain, no time travel.

  ANZEN_URL=http://127.0.0.1:8791 uv run python -m anzen.smoke

Publishes an event whose check-in opens ~3 min after publishing and closes ~3 min later, then:
alice commits herself, bob's AI agent commits for bob inside a budget bob signed, alice checks in with a fresh
venue code, bob doesn't show; after close, both outcomes are applied and alice's passport is issued.
Prints every transaction with its explorer link. Takes ~8 minutes.
"""

import hashlib
import json
import time

from cryptography.hazmat.primitives.asymmetric import ec
from eth_account import Account
from eth_account.messages import encode_defunct

from . import messages
from .seed import http, key, key_id, sign, wait

LINKS: list[tuple[str, str]] = []


def step(label: str, tx: str) -> dict:
    r = wait(tx)
    assert r["status"] == "confirmed", r
    LINKS.append((label, r["explorer"] or tx))
    print(f"  ✓ {label:<38} {r['explorer'] or tx}")
    return r


def until(t: int) -> None:
    while (now := http("GET", "/health")["now"]) < t:
        print(f"  … waiting {t - now} s for chain time", flush=True)
        time.sleep(min(30, t - now + 2))


def main() -> None:
    h = http("GET", "/health")
    print(f"ANZEN smoke on {h['network']} (chain {h['chainId']}), treasury {h['treasury']}")
    org, alice, bob = key(), ec.generate_private_key(ec.SECP256R1()), ec.generate_private_key(ec.SECP256R1())
    a_id, b_id = "0x" + key_id(alice).hex(), "0x" + key_id(bob).hex()
    agent = Account.create()

    for who, kid in (("alice", a_id), ("bob", b_id)):
        step(f"faucet: 100 tUSD to {who}", http("POST", "/faucet", {"keyId": kid})["tx"])

    now = h["now"]
    terms = {"amount": 5_000_000, "capacity": 10, "commitDeadline": now + 150, "checkInStart": now + 180,
             "checkInEnd": now + 360, "noShow": "split", "issuer": h["issuer"]}
    policy = json.dumps({"version": 1, "title": f"Monad smoke {time.strftime('%H:%M')}", "description": "Automated test event.",
                         "creator": "ANZEN", "venue": "Online test", "startsAt": now + 200, "timezone": "UTC", "artwork": 0,
                         "disputeContact": "test@example.com", **terms}, separators=(",", ":"))
    cfg = messages.EventConfig(key_id(org), hashlib.sha256(policy.encode()).digest(), h["issuer"], terms["amount"], 10,
                               terms["commitDeadline"], terms["checkInStart"], terms["checkInEnd"], 1)
    nonce = http("GET", "/accounts/0x" + key_id(org).hex())["nonce"]
    sig = sign(org, messages.create(h["chainId"], h["treasury"], nonce, now + 600, cfg))
    config = {"organizer": "0x" + key_id(org).hex(), "policyHash": "0x" + cfg.policy_hash.hex(), "issuer": cfg.issuer,
              **{k: terms[k] for k in ("amount", "capacity", "commitDeadline", "checkInStart", "checkInEnd")}, "noShow": 1}
    eid = step("organizer publishes event", http("POST", "/events", {"policy": policy, "config": config, "deadline": now + 600, **sig})["tx"])["eventId"]

    # alice commits with her own key
    now = http("GET", "/health")["now"]
    sig = sign(alice, messages.commit(h["chainId"], h["treasury"], eid, 5_000_000, 0, now + 600))
    step("alice commits 5 tUSD (her key)", http("POST", f"/events/{eid}/commit", {"deadline": now + 600, **sig})["tx"])

    # bob delegates a 10 tUSD budget to his agent; the agent commits for him
    sig = sign(bob, messages.delegate(h["chainId"], h["treasury"], agent.address, 10_000_000, now + 86_400, 0, now + 600))
    step("bob approves agent budget 10 tUSD", http("POST", "/delegate", {"agent": agent.address, "cap": 10_000_000,
                                                                          "expiry": now + 86_400, "deadline": now + 600, **sig})["tx"])
    info = http("GET", f"/agents/{agent.address}")
    digest = messages.agent_commit_hash(h["chainId"], h["treasury"], eid, bytes.fromhex(b_id[2:]), 5_000_000, info["nonce"], info["now"] + 600)
    asig = "0x" + Account.sign_message(encode_defunct(primitive=digest), agent.key).signature.hex().removeprefix("0x")
    step("bob's agent commits 5 tUSD for bob", http("POST", f"/events/{eid}/agent-commit",
                                                   {"keyId": b_id, "deadline": info["now"] + 600, "agentSig": asig})["tx"])

    until(terms["checkInStart"] + 2)
    dl = http("GET", "/health")["now"] + 300
    desk = http("POST", f"/events/{eid}/desk", {"deadline": dl, **sign(org, messages.desk(h["chainId"], h["treasury"], eid, dl))})
    code = http("GET", f"/events/{eid}/challenge?token={desk['token']}")
    sig = sign(alice, messages.check_in(h["chainId"], h["treasury"], eid, bytes.fromhex(code["challenge"][2:]), code["expiresAt"]))
    step("alice checks in (fresh venue code)", http("POST", f"/events/{eid}/checkin", {
        "challenge": code["challenge"], "expiresAt": code["expiresAt"], "issuerSig": code["issuerSig"], **sig})["tx"])

    until(terms["checkInEnd"] + 2)
    step("alice's outcome applied", http("POST", f"/events/{eid}/settle", {"keyId": a_id})["tx"])
    step("bob's outcome applied (no-show)", http("POST", f"/events/{eid}/settle", {"keyId": b_id})["tx"])

    a, b = http("GET", f"/accounts/{a_id}"), http("GET", f"/accounts/{b_id}")
    me = a["commitments"][0]["me"]
    assert a["balance"] == 105_000_000 and me["passportId"] > 0 and b["balance"] == 95_000_000, (a, b)
    print(f"\nalice: 105 tUSD (5 back + bob's 5), passport no. {me['passportId']}. bob: 95 tUSD (no-show).")
    print(f"proof: GET /proof/{eid}/{a_id}")


if __name__ == "__main__":
    main()
