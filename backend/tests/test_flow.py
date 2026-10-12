"""End-to-end over HTTP against real contracts on anvil: the Creator IRL Drop lifecycle and its failure modes."""

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.asymmetric.utils import decode_dss_signature

from anzen.p256 import N

from .conftest import Key


def test_full_lifecycle_split_forfeit(env):
    org, alice, bob = Key(), Key(), Key()
    env.fund(alice)
    env.fund(bob)
    eid = env.create_event(org, no_show=1)

    listed = env.client.get("/events", params={"key": alice.id}).json()
    assert [e["id"] for e in listed] == [eid]
    assert listed[0]["policyVerified"] and listed[0]["phase"] == "open" and listed[0]["me"]["status"] == "none"

    env.confirm(env.commit(alice, eid).json()["tx"])
    env.confirm(env.commit(bob, eid).json()["tx"])
    assert env.client.get(f"/accounts/{alice.id}").json()["balance"] == 90_000_000

    env.warp(2 * 86_400 + 60)
    token = env.open_desk(org, eid).json()["token"]
    code = env.client.get(f"/events/{eid}/challenge", params={"token": token}).json()
    assert code["uri"].startswith(f"anzen://checkin?e={eid}&")
    env.confirm(env.check_in(alice, eid, code).json()["tx"])  # bob doesn't come

    env.warp(4 * 3600)
    assert env.client.get(f"/events/{eid}").json()["phase"] == "awaiting_settlement"
    env.confirm(env.client.post(f"/events/{eid}/settle", json={"keyId": alice.id}).json()["tx"])
    env.confirm(env.client.post(f"/events/{eid}/settle", json={"keyId": bob.id}).json()["tx"])

    a = env.client.get(f"/accounts/{alice.id}").json()
    assert a["balance"] == 110_000_000  # stake back + bob's forfeited 10
    assert a["commitments"][0]["me"] == {"status": "settled", "attended": True,
                                         "checkedInAt": a["commitments"][0]["me"]["checkedInAt"],
                                         "settledAmount": 20_000_000, "passportId": 1, "agentId": None,
                                         "tier": 1, "byAgent": False}
    assert env.client.get(f"/accounts/{bob.id}").json()["balance"] == 90_000_000

    proof = env.client.get(f"/proof/{eid}/{alice.id}").json()
    assert [t["action"] for t in proof["transactions"]] == ["create", "commit", "checkin", "finalize", "settle"]
    assert all(t["status"] == "confirmed" for t in proof["transactions"])
    assert proof["event"]["policyVerified"] and proof["participation"]["passportId"] == 1

    acts = env.client.get(f"/accounts/{alice.id}/activity").json()
    assert {x["action"] for x in acts} == {"faucet", "commit", "checkin", "settle"}


def test_cancel_refunds(env):
    org, alice = Key(), Key()
    env.fund(alice)
    eid = env.create_event(org)
    env.confirm(env.commit(alice, eid).json()["tx"])
    from anzen import messages
    deadline = env.now() + 600
    nonce = env.svc.chain.nonce(bytes.fromhex(org.id[2:]))
    sig = org.sign(messages.cancel(env.chain_id, env.treasury, eid, nonce, deadline))
    env.confirm(env.client.post(f"/events/{eid}/cancel", json={"deadline": deadline, **sig}).json()["tx"])
    assert env.client.get(f"/events/{eid}").json()["phase"] == "cancelled"
    env.confirm(env.client.post(f"/events/{eid}/settle", json={"keyId": alice.id}).json()["tx"])
    a = env.client.get(f"/accounts/{alice.id}").json()
    assert a["balance"] == 100_000_000 and a["commitments"][0]["me"]["passportId"] == 0


def test_wrong_signer_rejected_before_broadcast(env):
    org, alice, mallory = Key(), Key(), Key()
    env.fund(alice)
    eid = env.create_event(org)
    sent = env.w3.eth.get_transaction_count(env.svc.chain.relayer.address)
    r = env.commit(alice, eid, signer=mallory)
    assert r.status_code == 401 and r.json()["error"] == "InvalidSignature"
    assert env.w3.eth.get_transaction_count(env.svc.chain.relayer.address) == sent  # nothing was broadcast


def test_resubmitted_signature_returns_same_tx(env):
    org, alice = Key(), Key()
    env.fund(alice)
    eid = env.create_event(org)
    e = env.client.get(f"/events/{eid}").json()
    from anzen import messages
    deadline = env.now() + 600
    sig = alice.sign(messages.commit(env.chain_id, env.treasury, eid, e["amount"], 0, deadline))
    first = env.client.post(f"/events/{eid}/commit", json={"deadline": deadline, **sig}).json()["tx"]
    second = env.client.post(f"/events/{eid}/commit", json={"deadline": deadline, **sig}).json()["tx"]
    assert first == second


def test_insufficient_balance_and_capacity(env):
    org, alice, bob, carol = Key(), Key(), Key(), Key()
    eid = env.create_event(org, capacity=1)
    r = env.commit(alice, eid)
    assert r.status_code == 402 and r.json()["error"] == "InsufficientBalance"
    env.fund(alice)
    env.fund(bob)
    env.confirm(env.commit(alice, eid).json()["tx"])
    env.confirm(env.commit(bob, eid).json()["tx"])  # full: bob waits on the waitlist with his commitment held
    assert env.client.get(f"/events/{eid}", params={"key": bob.id}).json()["me"]["status"] == "waitlisted"
    env.fund(carol)
    r = env.commit(carol, eid)  # the waitlist holds at most one person per seat
    assert r.json()["error"] == "EventFull"
    assert env.client.post("/faucet", json={"keyId": alice.id}).json()["error"] == "FaucetUsed"


def test_policy_must_match_signed_terms(env):
    org = Key()
    import hashlib
    import json

    from anzen import messages
    now = env.now()
    policy = json.dumps({"version": 1, "title": "x", "description": "", "creator": "c", "venue": "v",
                         "startsAt": now, "timezone": "UTC", "artwork": 0, "disputeContact": "d",
                         "amount": 999, "capacity": 1, "commitDeadline": now + 10, "checkInStart": now + 20,
                         "checkInEnd": now + 30, "noShow": "split", "issuer": env.svc.chain.issuer.address})
    cfg = messages.EventConfig(bytes.fromhex(org.id[2:]), hashlib.sha256(policy.encode()).digest(),
                               env.svc.chain.issuer.address, 1_000, 1, now + 10, now + 20, now + 30, 1)  # amount differs
    sig = org.sign(messages.create(env.chain_id, env.treasury, 0, now + 600, cfg))
    config = {"organizer": org.id, "policyHash": "0x" + cfg.policy_hash.hex(), "issuer": cfg.issuer, "amount": 1_000,
              "capacity": 1, "commitDeadline": now + 10, "checkInStart": now + 20, "checkInEnd": now + 30, "noShow": 1}
    r = env.client.post("/events", json={"policy": policy, "config": config, "deadline": now + 600, **sig})
    assert r.status_code == 400 and r.json()["error"] == "PolicyMismatch"


def test_check_in_failures(env):
    org, alice, bob = Key(), Key(), Key()
    env.fund(alice)
    eid = env.create_event(org)
    env.confirm(env.commit(alice, eid).json()["tx"])

    assert env.open_desk(alice, eid).json()["error"] == "NotOrganizer"
    token = env.open_desk(org, eid).json()["token"]
    # before the window: no codes are issued
    assert env.client.get(f"/events/{eid}/challenge", params={"token": token}).json()["error"] == "WrongPhase"
    assert env.client.get(f"/events/{eid}/challenge", params={"token": "forged"}).json()["error"] == "InvalidDesk"

    env.warp(2 * 86_400 + 60)
    code = env.client.get(f"/events/{eid}/challenge", params={"token": token}).json()
    env.warp(60)  # the code expired (45 s)
    assert env.check_in(alice, eid, code).json()["error"] == "Expired"

    code = env.client.get(f"/events/{eid}/challenge", params={"token": token}).json()
    assert env.check_in(bob, eid, code).json()["error"] == "NotCommitted"  # never committed
    env.confirm(env.check_in(alice, eid, code).json()["tx"])
    code = env.client.get(f"/events/{eid}/challenge", params={"token": token}).json()
    assert env.check_in(alice, eid, code).json()["error"] == "NotCommitted"  # second check-in

    forged = dict(code, issuerSig="0x" + "11" * 65)
    assert env.check_in(alice, eid, forged).status_code >= 400


def test_webauthn_passkey_assertion_accepted(env):
    """A browser passkey: the service and contract accept a WebAuthn assertion whose challenge is the action digest."""
    import base64
    import hashlib

    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.primitives.asymmetric import ec
    from cryptography.hazmat.primitives.asymmetric.utils import decode_dss_signature
    from eth_abi import encode

    from anzen import messages
    from anzen.p256 import N, WEBAUTHN_AUTH
    org, alice = Key(), Key()
    env.fund(alice)
    eid = env.create_event(org)
    deadline = env.now() + 600
    msg = messages.commit(env.chain_id, env.treasury, eid, 10_000_000, 0, deadline)
    challenge = base64.urlsafe_b64encode(hashlib.sha256(msg).digest()).rstrip(b"=").decode()
    client = '{"type":"webauthn.get","challenge":"' + challenge + '","origin":"https://anzen.app","crossOrigin":false}'
    auth_data = hashlib.sha256(b"anzen.app").digest() + bytes([0x05]) + (1).to_bytes(4, "big")
    signed = auth_data + hashlib.sha256(client.encode()).digest()
    r, s = decode_dss_signature(alice.sk.sign(signed, ec.ECDSA(hashes.SHA256())))
    auth = encode([WEBAUTHN_AUTH], [(r.to_bytes(32, "big"), min(s, N - s).to_bytes(32, "big"), 23, 1, auth_data, client)])
    body = {"qx": "0x" + alice.qx.hex(), "qy": "0x" + alice.qy.hex(), "auth": "0x" + auth.hex(), "deadline": deadline}
    env.confirm(env.client.post(f"/events/{eid}/commit", json=body).json()["tx"])
    assert env.client.get(f"/events/{eid}", params={"key": alice.id}).json()["me"]["status"] == "committed"


def test_settle_before_close_rejected(env):
    org, alice = Key(), Key()
    env.fund(alice)
    eid = env.create_event(org)
    env.confirm(env.commit(alice, eid).json()["tx"])
    assert env.client.post(f"/events/{eid}/settle", json={"keyId": alice.id}).json()["error"] == "WrongPhase"


def test_p256_precompile_present_on_this_chain(env):
    """Monad exposes P-256 verification at 0x0100. anvil --hardfork osaka has the same precompile."""
    sk = ec.generate_private_key(ec.SECP256R1())
    msg = b"anzen"
    import hashlib
    h = hashlib.sha256(msg).digest()
    r, s = decode_dss_signature(sk.sign(msg, ec.ECDSA(hashes.SHA256())))
    n = sk.public_key().public_numbers()
    data = h + r.to_bytes(32, "big") + min(s, N - s).to_bytes(32, "big") + n.x.to_bytes(32, "big") + n.y.to_bytes(32, "big")
    out = env.w3.eth.call({"to": "0x0000000000000000000000000000000000000100", "data": "0x" + data.hex()})
    assert int.from_bytes(out, "big") == 1


def test_agent_reserves_within_owner_budget_but_cannot_check_in(env):
    from eth_account import Account
    from eth_account.messages import encode_defunct

    from anzen import messages
    org, alice = Key(), Key()
    agent = Account.create()
    env.fund(alice)
    first, second, third = (env.create_event(org, title=t) for t in ("A", "B", "C"))

    # Alice's phone signs a 20 tUSD budget for her agent, valid 7 days.
    now = env.now()
    deadline, expiry, cap = now + 600, now + 7 * 86_400, 20_000_000
    sig = alice.sign(messages.delegate(env.chain_id, env.treasury, agent.address, messages.NO_AGENT_ID, cap, expiry,
                                       env.svc.chain.nonce(bytes.fromhex(alice.id[2:])), deadline))
    r = env.client.post("/delegate", json={"agent": agent.address, "agentId": messages.NO_AGENT_ID, "cap": cap,
                                          "expiry": expiry, "deadline": deadline, **sig})
    env.confirm(r.json()["tx"])
    d = env.client.get(f"/accounts/{alice.id}").json()["delegation"]
    assert d["active"] and d["remaining"] == cap

    def reserve(event_id: int):
        info = env.client.get(f"/agents/{agent.address}").json()
        dl = info["now"] + 600
        h = messages.agent_commit_hash(env.chain_id, env.treasury, event_id, bytes.fromhex(alice.id[2:]), 10_000_000,
                                       info["nonce"], dl)
        s = "0x" + Account.sign_message(encode_defunct(primitive=h), agent.key).signature.hex().removeprefix("0x")
        return env.client.post(f"/events/{event_id}/agent-commit", json={"keyId": alice.id, "deadline": dl, "agentSig": s})

    env.confirm(reserve(first).json()["tx"])
    env.confirm(reserve(second).json()["tx"])
    r = reserve(third)
    assert r.status_code == 402 and r.json()["error"] == "BudgetExceeded"

    a = env.client.get(f"/accounts/{alice.id}").json()
    assert a["balance"] == 80_000_000 and a["delegation"]["remaining"] == 0
    assert {e["id"] for e in a["commitments"]} == {first, second}

    # Presence is not delegable: only Alice's own key can sign the check-in.
    env.warp(2 * 86_400 + 60)
    code = env.client.get(f"/events/{first}/challenge", params={"token": env.open_desk(org, first).json()["token"]}).json()
    assert env.check_in(Key(), first, code).json()["error"] == "NotCommitted"
    env.confirm(env.check_in(alice, first, code).json()["tx"])

    acts = {x["action"] for x in env.client.get(f"/accounts/{alice.id}/activity").json()}
    assert {"delegate", "agent_commit", "checkin"} <= acts
