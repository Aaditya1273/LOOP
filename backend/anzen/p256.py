"""Offchain P-256 checks, mirroring OpenZeppelin's P256.verify (low-s required)."""

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.asymmetric.utils import encode_dss_signature

N = 0xFFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551


def verify(qx: bytes, qy: bytes, r: bytes, s: bytes, message: bytes) -> bool:
    """True if (r, s) is a low-s ECDSA-P256 signature over sha256(message) by the key (qx, qy)."""
    ri, si = int.from_bytes(r, "big"), int.from_bytes(s, "big")
    if not (0 < ri < N and 0 < si <= N // 2):
        return False
    try:
        key = ec.EllipticCurvePublicNumbers(int.from_bytes(qx, "big"), int.from_bytes(qy, "big"),
                                            ec.SECP256R1()).public_key()
        key.verify(encode_dss_signature(ri, si), message, ec.ECDSA(hashes.SHA256()))
        return True
    except (InvalidSignature, ValueError):
        return False
