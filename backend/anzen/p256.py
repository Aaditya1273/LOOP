"""Offchain mirror of the contract's account check (AnzenTreasury._verifyKey): a raw low-s P-256 signature
(64 bytes r ‖ s, Android Keystore) or a WebAuthn assertion (passkeys) whose challenge is sha256(message)."""

import base64
import hashlib
import json

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.asymmetric.utils import Prehashed, encode_dss_signature
from eth_abi import decode

N = 0xFFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551
WEBAUTHN_AUTH = "(bytes32,bytes32,uint256,uint256,bytes,string)"  # r, s, challengeIndex, typeIndex, authData, clientDataJSON


def _verify_digest(qx: bytes, qy: bytes, r: int, s: int, digest: bytes) -> bool:
    if not (0 < r < N and 0 < s <= N // 2):
        return False
    try:
        key = ec.EllipticCurvePublicNumbers(int.from_bytes(qx, "big"), int.from_bytes(qy, "big"), ec.SECP256R1()).public_key()
        key.verify(encode_dss_signature(r, s), digest, ec.ECDSA(Prehashed(hashes.SHA256())))
        return True
    except (InvalidSignature, ValueError):
        return False


def verify(qx: bytes, qy: bytes, auth: bytes, message: bytes) -> bool:
    digest = hashlib.sha256(message).digest()
    if len(auth) == 64:
        return _verify_digest(qx, qy, int.from_bytes(auth[:32], "big"), int.from_bytes(auth[32:], "big"), digest)
    try:
        r, s, _ci, _ti, auth_data, client_data = decode([WEBAUTHN_AUTH], auth)[0]
        cd = json.loads(client_data)
    except Exception:
        return False
    challenge = base64.urlsafe_b64encode(digest).rstrip(b"=").decode()
    flags = auth_data[32] if len(auth_data) >= 37 else 0
    if cd.get("type") != "webauthn.get" or cd.get("challenge") != challenge or flags & 0x05 != 0x05:
        return False  # wrong ceremony, wrong action, or no user presence + verification
    signed = hashlib.sha256(auth_data + hashlib.sha256(client_data.encode()).digest()).digest()
    return _verify_digest(qx, qy, int.from_bytes(r, "big"), int.from_bytes(s, "big"), signed)
