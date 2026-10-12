"""v4 over HTTP against real contracts on anvil: published terms, waitlist refill, release, staff (silver) check-in,
show-up records, Agent Pass rules, x402 reservations and the organizer dashboard."""

import base64
import hashlib
import json

from eth_account import Account
from eth_account.messages import encode_defunct

from anzen import messages

from .conftest import Key


def create(env, org: Key, *, capacity=2, category=1, late_fee_bps=2000, amount=10_000_000) -> int:
    now = env.now()
    terms = {"amount": amount, "capacity": capacity, "commitDeadline": now + 86_400,
             "checkInStart": now + 2 * 86_400, "checkInEnd": now + 2 * 86_400 + 3 * 3600,
             "noShow": "organizer", "issuer": env.svc.chain.issuer.address}
    policy = json.dumps({"version": 1, "title": "Spin 7am", "description": "", "creator": "Studio North",
                         "venue": "Studio North", "startsAt": terms["checkInStart"] + 1800, "timezone": "UTC",
                         "artwork": 1, "disputeContact": "desk@example.com", **terms, "category": category,
                         "lateFeeBps": late_fee_bps}, separators=(",", ":"))
    cfg = messages.EventConfig(bytes.fromhex(org.id[2:]), hashlib.sha256(policy.encode()).digest(), terms["issuer"],
                               amount, capacity, terms["commitDeadline"], terms["checkInStart"], terms["checkInEnd"], 0)
    deadline = now + 600
    sig = org.sign(messages.create_v2(env.chain_id, env.treasury, env.svc.chain.nonce(cfg.organizer), deadline, cfg,
                                      category, late_fee_bps))
    config = {"organizer": org.id, "policyHash": "0x" + cfg.policy_hash.hex(), "issuer": cfg.issuer, "amount": amount,
              "capacity": capacity, "commitDeadline": cfg.commit_deadline, "checkInStart": cfg.check_in_start,
              "checkInEnd": cfg.check_in_end, "noShow": 0}
    r = env.client.post("/events", json={"policy": policy, "config": config, "deadline": deadline, **sig,
                                         "terms": {"category": category, "lateFeeBps": late_fee_bps}})
    assert r.status_code == 200, r.text
    return env.confirm(r.json()["tx"])["eventId"]


def release(env, key: Key, event_id: int):
    deadline = env.now() + 600
    sig = key.sign(messages.release(env.chain_id, env.treasury, event_id, env.svc.chain.nonce(bytes.fromhex(key.id[2:])),
                                    deadline))
    return env.client.post(f"/events/{event_id}/release", json={"deadline": deadline, **sig})


def me(env, key: Key, event_id: int) -> dict:
    return env.client.get(f"/events/{event_id}", params={"key": key.id}).json()["me"]


def test_terms_waitlist_refill_and_late_fee(env):
    org, alice, bob, dave, erin = Key(), Key(), Key(), Key(), Key()
    for k in (alice, bob, dave, erin):
        env.fund(k)
    eid = create(env, org, capacity=2)
    e = env.client.get(f"/events/{eid}").json()
    assert (e["category"], e["categoryName"], e["lateFeeBps"], e["policyVerified"]) == (1, "Fitness", 2000, True)

    for k in (alice, bob, dave):
        env.confirm(env.commit(k, eid).json()["tx"])
    assert me(env, dave, eid)["status"] == "waitlisted"

    env.confirm(release(env, alice, eid).json()["tx"])  # in time: free, earns a shield, dave takes the seat
    assert me(env, dave, eid)["status"] == "committed" and me(env, alice, eid)["status"] == "released"
    a = env.client.get(f"/accounts/{alice.id}").json()
    assert a["balance"] == 100_000_000 and a["record"]["shields"] == 1 and a["score"] == 75
    assert eid not in {c["id"] for c in a["commitments"]}

    env.warp(86_400 + 60)  # after the commit deadline, nobody waiting: the published 20% late fee applies
    env.confirm(release(env, bob, eid).json()["tx"])
    assert env.client.get(f"/accounts/{bob.id}").json()["balance"] == 98_000_000
    e = env.client.get(f"/events/{eid}").json()
    assert (e["refilled"], e["released"], e["recovered"], e["committed"]) == (1, 2, 2_000_000, 1)

    env.warp(86_400)
    assert release(env, dave, eid).json()["error"] == "WrongPhase"  # check-in has opened


def test_staff_check_in_records_and_dashboard(env):
    org, alice, bob = Key(), Key(), Key()
    env.fund(alice)
    env.fund(bob)
    eid = create(env, org)
    env.confirm(env.commit(alice, eid).json()["tx"])
    env.confirm(env.commit(bob, eid).json()["tx"])
    env.warp(2 * 86_400 + 60)

    issued = env.now()
    guest = alice.sign(messages.present(env.chain_id, env.treasury, eid, issued))
    staff = org.sign(messages.staff(env.chain_id, env.treasury, eid, bytes.fromhex(alice.id[2:]), issued))
    imposter = bob.sign(messages.staff(env.chain_id, env.treasury, eid, bytes.fromhex(alice.id[2:]), issued))
    r = env.client.post(f"/events/{eid}/staff-checkin", json={"guest": guest, "issuedAt": issued, "staff": imposter})
    assert r.json()["error"] == "NotOrganizer"
    env.confirm(env.client.post(f"/events/{eid}/staff-checkin",
                                json={"guest": guest, "issuedAt": issued, "staff": staff}).json()["tx"])
    assert me(env, alice, eid)["tier"] == 2

    env.warp(4 * 3600)
    for k in (alice, bob):
        env.confirm(env.client.post(f"/events/{eid}/settle", json={"keyId": k.id}).json()["tx"])
    a, b = (env.client.get(f"/accounts/{k.id}").json() for k in (alice, bob))
    assert a["record"]["attended"] == 1 and a["record"]["streak"] == 1 and a["score"] == 80 and a["scoreBasis"] == 1
    assert b["record"]["missed"] == 1 and b["score"] == 60

    d = env.client.get(f"/organizers/{org.id}/dashboard").json()
    assert d["totals"]["events"] == 1 and d["totals"]["attended"] == 1 and d["totals"]["recovered"] == 10_000_000
    assert d["events"][0]["showRate"] == 0.5


def test_agent_pass_rules_and_x402(env):
    org, alice = Key(), Key()
    agent = Account.create()
    env.fund(alice)
    spin = create(env, org, category=1, late_fee_bps=1000)
    pricey = create(env, org, category=2, late_fee_bps=5000)
    meetup = create(env, org, category=3, late_fee_bps=0)

    now = env.now()
    deadline, expiry, cap = now + 600, now + 30 * 86_400, 50_000_000
    rules = {"categories": 1 << 1 | 1 << 2, "maxLateFeeBps": 1000, "maxActive": 2, "maxLeadDays": 7}
    sig = alice.sign(messages.grant_pass(env.chain_id, env.treasury, agent.address, messages.NO_AGENT_ID, cap, expiry,
                                         *rules.values(), env.svc.chain.nonce(bytes.fromhex(alice.id[2:])), deadline))
    r = env.client.post("/pass", json={"agent": agent.address, "agentId": messages.NO_AGENT_ID, "cap": cap,
                                      "expiry": expiry, "deadline": deadline, "rules": rules, **sig})
    env.confirm(r.json()["tx"])
    d = env.client.get(f"/accounts/{alice.id}").json()["delegation"]
    assert d["rules"] == rules and d["activeBookings"] == 0

    def x402(event_id: int):
        path = f"/x402/events/{event_id}/reserve"
        r = env.client.get(path)
        assert r.status_code == 402
        req = r.json()["accepts"][0]
        x = req["extra"]
        nonce = env.client.get(f"/agents/{agent.address}").json()["nonce"]
        dl = x["now"] + 600
        h = messages.agent_commit_hash(x["chainId"], x["treasury"], event_id, bytes.fromhex(alice.id[2:]), x["amount"],
                                       nonce, dl)
        s = "0x" + Account.sign_message(encode_defunct(primitive=h), agent.key).signature.hex().removeprefix("0x")
        pay = {"x402Version": 1, "scheme": req["scheme"], "network": req["network"],
               "payload": {"keyId": alice.id, "deadline": dl, "signature": s}}
        return env.client.get(path, headers={"X-PAYMENT": base64.b64encode(json.dumps(pay).encode()).decode()})

    assert x402(meetup).json()["error"] == "CategoryNotAllowed"
    assert x402(pricey).json()["error"] == "FeeAboveLimit"
    r = x402(spin)
    assert r.status_code == 200 and r.json()["reservation"] == "committed", r.text
    receipt = json.loads(base64.b64decode(r.headers["X-PAYMENT-RESPONSE"]))
    assert receipt["success"] and receipt["transaction"] == r.json()["tx"]
    p = me(env, alice, spin)
    assert p["byAgent"] and p["status"] == "committed"
    assert env.client.get(f"/accounts/{alice.id}").json()["delegation"]["activeBookings"] == 1
