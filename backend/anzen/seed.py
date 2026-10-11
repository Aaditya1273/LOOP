"""Development helper, LOCAL DEVNET OR TESTNET ONLY. Talks to a running service over HTTP like the app does.

  uv run python -m anzen.seed events        publish sample events from a seed organizer key
  uv run python -m anzen.seed quick <title>  publish one event: commitments close in 10 min, check-in in 15
  uv run python -m anzen.seed owner [agent cap id]  dev stand-in for the phone: fund the seed key, approve a budget
  uv run python -m anzen.seed desk <id>     print a live venue code (anzen:// link) for a seeded event
  uv run python -m anzen.seed checkin <id>  same, as an adb command that opens it in the app

The seed organizer's P-256 key is kept in backend/.dev/seed_organizer.pem (gitignored). Seeded events are
ordinary onchain events; their terms say they are test events.
"""

import hashlib
import json
import os
import sys
import time
import urllib.request
from pathlib import Path

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.asymmetric.utils import decode_dss_signature

from . import messages
from .p256 import N

BASE = os.environ.get("ANZEN_URL", "http://127.0.0.1:8790")
KEY_FILE = Path(__file__).resolve().parent.parent / ".dev" / "seed_organizer.pem"


def http(method: str, path: str, body: dict | None = None) -> dict:
    req = urllib.request.Request(BASE + path, method=method, headers={"Content-Type": "application/json"},
                                 data=json.dumps(body).encode() if body is not None else None)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return json.loads(r.read())
    except urllib.error.HTTPError as e:
        raise SystemExit(f"{method} {path} -> {e.code} {e.read().decode()}") from None


def key() -> ec.EllipticCurvePrivateKey:
    if KEY_FILE.exists():
        return serialization.load_pem_private_key(KEY_FILE.read_bytes(), None)
    KEY_FILE.parent.mkdir(exist_ok=True)
    sk = ec.generate_private_key(ec.SECP256R1())
    KEY_FILE.write_bytes(sk.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                          serialization.NoEncryption()))
    KEY_FILE.chmod(0o600)
    return sk


def sign(sk, message: bytes) -> dict:
    n = sk.public_key().public_numbers()
    r, s = decode_dss_signature(sk.sign(message, ec.ECDSA(hashes.SHA256())))
    return {"qx": "0x" + n.x.to_bytes(32, "big").hex(), "qy": "0x" + n.y.to_bytes(32, "big").hex(),
            "auth": "0x" + r.to_bytes(32, "big").hex() + min(s, N - s).to_bytes(32, "big").hex()}


def key_id(sk) -> bytes:
    n = sk.public_key().public_numbers()
    return messages.key_id(n.x.to_bytes(32, "big"), n.y.to_bytes(32, "big"))


def wait(tx: str) -> dict:
    for _ in range(120):
        r = http("GET", f"/tx/{tx}")
        if r["status"] != "submitted":
            return r
        time.sleep(0.5)
    raise SystemExit(f"{tx} still pending")


SAMPLES = [
    # title, creator, venue, description, artwork, amount (tUSD), capacity, noShow, (deadline, check-in start, end) offsets in s
    ("Rooftop listening session", "Mira K", "Studio 4, Bandra West, Mumbai",
     "Twenty seats for the first listen of the new record, start to finish, then a short Q&A. Test event.", 0, 10, 20, 1,
     (2 * 86_400, 3 * 86_400 - 1800, 3 * 86_400 + 7200)),
    ("Zine launch and signing", "Arjun Rao", "Champaca Books, Bengaluru",
     "Launch of issue 4. Early supporters get the passport and a signed copy. Test event.", 4, 5, 40, 0,
     (600, 900, 3 * 3600)),
    ("Open studio: risograph prints", "Lena Okafor", "Unit 12, Lower Parel, Mumbai",
     "Walk through the print process and take one home. Test event.", 5, 15, 12, 1,
     (4 * 86_400, 5 * 86_400, 5 * 86_400 + 4 * 3600)),
]


def publish_samples() -> None:
    for sample in SAMPLES:
        print(f"#{publish(sample)}  {sample[0]}")


def publish(sample: tuple) -> int:
    sk = key()
    org = key_id(sk)
    title, creator, venue, desc, art, amount, cap, no_show, (dl, cs, ce) = sample
    h = http("GET", "/health")
    now = h["now"]
    terms = {"amount": amount * 1_000_000, "capacity": cap, "commitDeadline": now + dl, "checkInStart": now + cs,
             "checkInEnd": now + ce, "noShow": ["organizer", "split"][no_show], "issuer": h["issuer"]}
    policy = json.dumps({"version": 1, "title": title, "description": desc, "creator": creator, "venue": venue,
                         "startsAt": now + cs + 1800, "timezone": "Asia/Kolkata", "artwork": art,
                         "disputeContact": "organizer@example.com", **terms}, separators=(",", ":"))
    cfg = messages.EventConfig(org, hashlib.sha256(policy.encode()).digest(), h["issuer"], terms["amount"], cap,
                               terms["commitDeadline"], terms["checkInStart"], terms["checkInEnd"], no_show)
    nonce = http("GET", "/accounts/0x" + org.hex())["nonce"]
    deadline = now + 600
    sig = sign(sk, messages.create(h["chainId"], h["treasury"], nonce, deadline, cfg))
    config = {"organizer": "0x" + org.hex(), "policyHash": "0x" + cfg.policy_hash.hex(), "issuer": cfg.issuer,
              **{k: terms[k] for k in ("amount", "capacity", "commitDeadline", "checkInStart", "checkInEnd")},
              "noShow": no_show}
    tx = http("POST", "/events", {"policy": policy, "config": config, "deadline": deadline, **sig})["tx"]
    return wait(tx)["eventId"]


def venue_code(event_id: int) -> str:
    sk = key()
    h = http("GET", "/health")
    deadline = h["now"] + 300
    desk = http("POST", f"/events/{event_id}/desk",
                {"deadline": deadline, **sign(sk, messages.desk(h["chainId"], h["treasury"], event_id, deadline))})
    return http("GET", f"/events/{event_id}/challenge?token={desk['token']}")["uri"]


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "events"
    if cmd == "events":
        publish_samples()
    elif cmd == "quick":  # one event whose commitments close in 10 min and check-in opens in 15
        print(publish((sys.argv[2], *SAMPLES[1][1:])))
    elif cmd == "owner":  # dev stand-in for the phone: fund the seed key and approve an agent budget
        sk, h = key(), http("GET", "/health")
        kid = "0x" + key_id(sk).hex()
        if not http("GET", f"/accounts/{kid}")["faucetUsed"]:
            wait(http("POST", "/faucet", {"keyId": kid})["tx"])
        if len(sys.argv) > 2:
            agent, cap = sys.argv[2], int(float(sys.argv[3] if len(sys.argv) > 3 else 20) * 1_000_000)
            agent_id = int(sys.argv[4]) if len(sys.argv) > 4 else messages.NO_AGENT_ID
            expiry, deadline = h["now"] + 7 * 86_400, h["now"] + 600
            nonce = http("GET", f"/accounts/{kid}")["nonce"]
            sig = sign(sk, messages.delegate(h["chainId"], h["treasury"], agent, agent_id, cap, expiry, nonce, deadline))
            wait(http("POST", "/delegate", {"agent": agent, "agentId": agent_id, "cap": cap, "expiry": expiry,
                                            "deadline": deadline, **sig})["tx"])
        print(kid)
    elif cmd == "desk":
        print(venue_code(int(sys.argv[2])))
    elif cmd == "checkin":
        uri = venue_code(int(sys.argv[2]))
        print(f"adb shell am start -a android.intent.action.VIEW -d '{uri}' com.anzen.shonko")
    else:
        raise SystemExit(__doc__)
