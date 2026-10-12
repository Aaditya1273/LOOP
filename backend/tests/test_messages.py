"""Golden vectors shared with the app (app/src/test/.../MessagesTest.kt). The contract-side match is proven by
test_flow.py, where the contract accepts signatures over exactly these preimages."""

import hashlib

from anzen import messages as m
from anzen.service import Anzen

T = "0x5FbDB2315678afecb367f032d93F642f64180aa3"
QX, QY = bytes(range(1, 33)), bytes(range(33, 65))
DELEGATE = "1b93458a85a0df0e491a02d9b363f53722a038ff5d9b02ba1d1e4ce081e98049"
ISSUER = "0x70997970C51812dc3A010C7d01b50e0d17dc79C8"  # anvil account 1 = the test issuer key

# Exactly what DraftRules.policy() in the app produces for MessagesTest's draft.
POLICY = ('{"version":1,"title":"Rooftop","description":"","creator":"Mira","venue":"Studio 4","startsAt":1800180000,'
          '"timezone":"Asia/Kolkata","artwork":2,"disputeContact":"hi@example.com","amount":10000000,"capacity":20,'
          '"commitDeadline":1800086400,"checkInStart":1800172800,"checkInEnd":1800183600,"noShow":"split",'
          f'"issuer":"{ISSUER}"}}')


def h(b: bytes) -> str:
    return hashlib.sha256(b).hexdigest()


def test_golden_vectors():
    org = m.key_id(QX, QY)
    cfg = m.EventConfig(org, hashlib.sha256(b"policy").digest(), ISSUER, 10_000_000, 20, 1_800_086_400, 1_800_172_800,
                        1_800_183_600, 1)
    assert org.hex() == "20a7ec84684f7fe124cb3727d049734ab0b7da2f52fcafbcef989ecfd91e870b"
    assert h(m.create(10143, T, 3, 1_800_000_600, cfg)) == "18c0fcdd83456a3a8cbc13e0d31c678f844edfb511bcf20d15541c85ee05e3e4"
    assert h(m.commit(10143, T, 7, 10_000_000, 4, 1_800_000_600)) == "d7a0febc16a64dd5461c4d7bdb9804bde3796ce86bcec11ad8780a54c03c27c5"
    assert h(m.check_in(10143, T, 7, b"\xab" * 32, 1_800_172_845)) == "3ec77fc75c2c93950e193badf20080c19bee8f33706087c4df501058cd5707e1"
    assert h(m.cancel(10143, T, 7, 5, 1_800_000_600)) == "4d1419ebdaf75c90bdf278e1a891c8dea667214e9df00fb351211aa760ac5224"
    assert h(m.delegate(10143, T, ISSUER, 7, 20_000_000, 1_800_604_800, 6, 1_800_000_600)) == DELEGATE
    assert h(m.desk(10143, T, 7, 1_800_000_300)) == "ae130c5e88daa4d2c6aac044edeb9e72ece35b4a6cfec8bf8511d82a266430ab"
    # v4
    assert h(m.create_v2(10143, T, 3, 1_800_000_600, cfg, 1, 2000)) == "532166810d76b6c89fc99c75c68a1f1d615f0e8c359d54ad0da98e9dabd68d5e"
    assert h(m.grant_pass(10143, T, ISSUER, 7, 20_000_000, 1_800_604_800, 6, 1000, 2, 7, 6, 1_800_000_600)) == \
        "b64e4b0a1e07c8e3dd202df1fa5e6aaae09556ce69195e1440e34025877cd888"
    assert h(m.release(10143, T, 7, 5, 1_800_000_600)) == "cd7cb7cae83abcf6e043551f107b92354780c8a7f166d981884f8072f4df3bd6"
    assert h(m.present(10143, T, 7, 1_800_172_845)) == "437f254f2caaf77565fb8b1d3ac2ff942a6b1e0f50518c955cc564b966b9d590"
    assert h(m.staff(10143, T, 7, org, 1_800_172_845)) == "a9f55ee9ff83aacad83b8e254b20fa2bacdbe1be9a2475139db4b2aa44c2632e"


def test_app_policy_document_is_accepted():
    class Issuer:
        address = ISSUER

    class ChainStub:
        issuer = Issuer()

    svc = Anzen.__new__(Anzen)
    svc.chain = ChainStub()
    cfg = m.EventConfig(b"\0" * 32, hashlib.sha256(POLICY.encode()).digest(), ISSUER, 10_000_000, 20, 1_800_086_400,
                        1_800_172_800, 1_800_183_600, 1)
    assert svc._validate_policy(POLICY, cfg)["title"] == "Rooftop"
