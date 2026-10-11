"""Byte-exact preimages the participant's P-256 key signs. Must match AnzenTreasury.sol and the app's
Messages.kt (golden vectors in tests/test_messages.py and the app's MessagesTest)."""

import hashlib
from dataclasses import dataclass

from eth_abi import encode
from eth_utils import keccak


def tag(name: str) -> bytes:
    """Solidity bytes32 string literal: ASCII, right-padded with zeros."""
    return name.encode().ljust(32, b"\0")


def key_id(qx: bytes, qy: bytes) -> bytes:
    return hashlib.sha256(qx + qy).digest()


@dataclass(frozen=True)
class EventConfig:
    organizer: bytes
    policy_hash: bytes
    issuer: str
    amount: int
    capacity: int
    commit_deadline: int
    check_in_start: int
    check_in_end: int
    no_show: int  # 0 = to organizer, 1 = split among attendees

    def as_tuple(self) -> tuple:
        return (self.organizer, self.policy_hash, self.issuer, self.amount, self.capacity, self.commit_deadline,
                self.check_in_start, self.check_in_end, self.no_show)


CONFIG_TYPES = ["bytes32", "bytes32", "address", "uint128", "uint32", "uint64", "uint64", "uint64", "uint8"]


def create(chain_id: int, treasury: str, nonce: int, deadline: int, c: EventConfig) -> bytes:
    return encode(["bytes32", "uint256", "address", "uint256", "uint256", *CONFIG_TYPES],
                  [tag("ANZEN/create/v1"), chain_id, treasury, nonce, deadline, *c.as_tuple()])


def commit(chain_id: int, treasury: str, event_id: int, amount: int, nonce: int, deadline: int) -> bytes:
    return encode(["bytes32", "uint256", "address", "uint256", "uint256", "uint256", "uint256"],
                  [tag("ANZEN/commit/v1"), chain_id, treasury, event_id, amount, nonce, deadline])


def check_in(chain_id: int, treasury: str, event_id: int, challenge: bytes, expires_at: int) -> bytes:
    return encode(["bytes32", "uint256", "address", "uint256", "bytes32", "uint64"],
                  [tag("ANZEN/checkin/v1"), chain_id, treasury, event_id, challenge, expires_at])


def cancel(chain_id: int, treasury: str, event_id: int, nonce: int, deadline: int) -> bytes:
    return encode(["bytes32", "uint256", "address", "uint256", "uint256", "uint256"],
                  [tag("ANZEN/cancel/v1"), chain_id, treasury, event_id, nonce, deadline])


def desk(chain_id: int, treasury: str, event_id: int, deadline: int) -> bytes:
    """Offchain only: the organizer opens a check-in desk. Never accepted by the contract."""
    return encode(["bytes32", "uint256", "address", "uint256", "uint256"],
                  [tag("ANZEN/desk/v1"), chain_id, treasury, event_id, deadline])


def challenge_hash(chain_id: int, treasury: str, event_id: int, challenge: bytes, expires_at: int) -> bytes:
    """What the issuer signs (EIP-191 personal_sign over this 32-byte hash)."""
    return keccak(encode(["bytes32", "uint256", "address", "uint256", "bytes32", "uint64"],
                         [tag("ANZEN/challenge/v1"), chain_id, treasury, event_id, challenge, expires_at]))


def delegate(chain_id: int, treasury: str, agent: str, cap: int, expiry: int, nonce: int, deadline: int) -> bytes:
    """Owner's P-256 key grants (cap > 0) or revokes (agent 0x0, cap 0) an agent's commit budget."""
    return encode(["bytes32", "uint256", "address", "address", "uint128", "uint64", "uint256", "uint256"],
                  [tag("ANZEN/delegate/v1"), chain_id, treasury, agent, cap, expiry, nonce, deadline])


def agent_commit_hash(chain_id: int, treasury: str, event_id: int, key_id: bytes, amount: int, agent_nonce: int,
                      deadline: int) -> bytes:
    """What the agent's EVM key signs (EIP-191 personal_sign over this 32-byte hash)."""
    return keccak(encode(["bytes32", "uint256", "address", "uint256", "bytes32", "uint256", "uint256", "uint256"],
                         [tag("ANZEN/agentcommit/v1"), chain_id, treasury, event_id, key_id, amount, agent_nonce,
                          deadline]))
