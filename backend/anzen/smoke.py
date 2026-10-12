"""End-to-end smoke run against a live ANZEN service (Solana devnet or Monad testnet). Real keys, real chain, no
time travel.

  ANZEN_URL=https://anzen-shonko.vercel.app uv run python -m anzen.smoke

Publishes a fitness event (one seat, 10% late give-back fee) whose check-in opens ~3 min after publishing, then:
alice takes the seat with her own key; bob's AI agent books for bob over x402 inside an Agent Pass bob signed
(fitness only) and lands on the waitlist; alice gives her place back in time, which hands the seat to bob at once
and earns her a streak shield; bob checks in with a fresh venue code; after close bob's outcome is settled and his
agent's show-up record is written. Prints every transaction with its explorer link. Takes ~7 minutes.
"""

import base64
import hashlib
import json
import secrets
import time

from cryptography.hazmat.primitives.asymmetric import ec

from . import messages
from .agent import Agent
from .seed import http, key, key_id, sign, wait


def step(label: str, tx: str) -> dict:
    r = wait(tx)
    assert r["status"] == "confirmed", r
    print(f"  ✓ {label:<44} {r['explorer'] or tx}")
    return r


def until(t: int) -> None:
    while (now := http("GET", "/health")["now"]) < t:
        print(f"  … waiting {t - now} s for chain time", flush=True)
        time.sleep(min(30, t - now + 2))


def main() -> None:
    h = http("GET", "/health")
    cid, t = h["chainId"], h["treasury"]
    print(f"ANZEN smoke on {h['network']} (chain {cid}), {h.get('programId') or t}")
    org, alice, bob = key(), ec.generate_private_key(ec.SECP256R1()), ec.generate_private_key(ec.SECP256R1())
    a_id, b_id = "0x" + key_id(alice).hex(), "0x" + key_id(bob).hex()
    agent = Agent("0x" + secrets.token_hex(32))
    nonce = lambda sk: http("GET", "/accounts/0x" + key_id(sk).hex())["nonce"]  # noqa: E731

    for who, kid in (("alice", a_id), ("bob", b_id)):
        step(f"faucet: 100 tUSD to {who}", http("POST", "/faucet", {"keyId": kid})["tx"])

    now = h["now"]
    terms = {"amount": 5_000_000, "capacity": 1, "commitDeadline": now + 150, "checkInStart": now + 180,
             "checkInEnd": now + 330, "noShow": "split", "issuer": h["issuer"]}
    policy = json.dumps({"version": 1, "title": f"Smoke spin {time.strftime('%H:%M')}", "description": "Automated test event.",
                         "creator": "ANZEN", "venue": "Online test", "startsAt": now + 200, "timezone": "UTC", "artwork": 1,
                         "disputeContact": "test@example.com", **terms, "category": 1, "lateFeeBps": 1000},
                        separators=(",", ":"))
    cfg = messages.EventConfig(key_id(org), hashlib.sha256(policy.encode()).digest(), h["issuer"], 5_000_000, 1,
                               terms["commitDeadline"], terms["checkInStart"], terms["checkInEnd"], 1)
    sig = sign(org, messages.create_v2(cid, t, nonce(org), now + 600, cfg, 1, 1000))
    config = {"organizer": "0x" + key_id(org).hex(), "policyHash": "0x" + cfg.policy_hash.hex(), "issuer": cfg.issuer,
              **{k: terms[k] for k in ("amount", "capacity", "commitDeadline", "checkInStart", "checkInEnd")}, "noShow": 1}
    eid = step("organizer publishes (fitness, 10% late fee)", http("POST", "/events", {
        "policy": policy, "config": config, "deadline": now + 600, **sig,
        "terms": {"category": 1, "lateFeeBps": 1000}})["tx"])["eventId"]

    now = http("GET", "/health")["now"]
    sig = sign(alice, messages.commit(cid, t, eid, 5_000_000, nonce(alice), now + 600))
    step("alice takes the only seat (her key)", http("POST", f"/events/{eid}/commit", {"deadline": now + 600, **sig})["tx"])

    rules = {"categories": 1 << 1, "maxLateFeeBps": 2000, "maxActive": 2, "maxLeadDays": 7}
    agent_slot = "0x" + bytes.fromhex(agent.address[2:])[:20].hex()  # an address slot holds a key's first 20 bytes
    sig = sign(bob, messages.grant_pass(cid, t, agent_slot, messages.NO_AGENT_ID, 10_000_000, now + 86_400,
                                        *rules.values(), nonce(bob), now + 600))
    step("bob signs an Agent Pass (fitness, ≤20% fee)", http("POST", "/pass", {
        "agent": agent.address, "agentId": messages.NO_AGENT_ID, "cap": 10_000_000, "expiry": now + 86_400,
        "rules": rules, "deadline": now + 600, **sig})["tx"])

    req = http("GET", f"/x402/events/{eid}/reserve", allow=(402,))["accepts"][0]
    x = req["extra"]
    asig = agent.sign_commit(x["chainId"], x["treasury"], eid, b_id, x["amount"],
                             http("GET", f"/agents/{agent.address}")["nonce"], x["now"] + 600)
    pay = {"x402Version": 1, "scheme": req["scheme"], "network": req["network"],
           "payload": {"keyId": b_id, "deadline": x["now"] + 600, "signature": asig}}
    r = http("GET", f"/x402/events/{eid}/reserve", headers={"X-PAYMENT": base64.b64encode(json.dumps(pay).encode()).decode()})
    assert r["reservation"] == "waitlisted", r
    step("bob's agent books over x402 → waitlist", r["tx"])

    now = http("GET", "/health")["now"]
    sig = sign(alice, messages.release(cid, t, eid, nonce(alice), now + 600))
    step("alice gives it back in time → bob's seat", http("POST", f"/events/{eid}/release", {"deadline": now + 600, **sig})["tx"])
    assert http("GET", f"/events/{eid}?key={b_id}")["me"]["status"] == "committed"

    until(terms["checkInStart"] + 2)
    dl = http("GET", "/health")["now"] + 300
    desk = http("POST", f"/events/{eid}/desk", {"deadline": dl, **sign(org, messages.desk(cid, t, eid, dl))})
    code = http("GET", f"/events/{eid}/challenge?token={desk['token']}")
    sig = sign(bob, messages.check_in(cid, t, eid, bytes.fromhex(code["challenge"][2:]), code["expiresAt"]))
    step("bob checks in (fresh venue code)", http("POST", f"/events/{eid}/checkin", {
        "challenge": code["challenge"], "expiresAt": code["expiresAt"], "issuerSig": code["issuerSig"], **sig})["tx"])

    until(terms["checkInEnd"] + 2)
    step("bob's outcome applied", http("POST", f"/events/{eid}/settle", {"keyId": b_id})["tx"])
    rep = http("GET", f"/agents/{agent.address}")["reputation"]
    assert rep and rep["count"] == 1 and rep["showUpRate"] == 100, rep
    print(f"  ✓ agent show-up record: {rep['count']} settled, {rep['showUpRate']:g}% showed up")
    a, b = http("GET", f"/accounts/{a_id}"), http("GET", f"/accounts/{b_id}")
    assert a["balance"] == 100_000_000 and a["record"]["shields"] == 1, a
    assert b["balance"] == 100_000_000 and b["record"]["attended"] == 1 and b["score"] == 80, b
    print(f"\nalice: 100 tUSD back + 1 streak shield. bob: checked in, score {b['score']}, passport issued.")
    print(f"proof: GET /proof/{eid}/{b_id}")


if __name__ == "__main__":
    main()
