"""Every failure the API returns carries exactly one of these reasons. The app maps them to copy."""

from enum import StrEnum


class Reason(StrEnum):
    # contract custom errors (same names as AnzenTreasury.sol)
    INVALID_CONFIG = "InvalidConfig"
    INVALID_SIGNATURE = "InvalidSignature"
    EXPIRED = "Expired"
    UNKNOWN_EVENT = "UnknownEvent"
    WRONG_PHASE = "WrongPhase"
    EVENT_FULL = "EventFull"
    ALREADY_COMMITTED = "AlreadyCommitted"
    NOT_COMMITTED = "NotCommitted"
    INSUFFICIENT_BALANCE = "InsufficientBalance"
    NOT_ORGANIZER = "NotOrganizer"
    ALREADY_SETTLED = "AlreadySettled"
    NOT_DELEGATED = "NotDelegated"
    BUDGET_EXCEEDED = "BudgetExceeded"
    AGENT_IDENTITY_MISMATCH = "AgentIdentityMismatch"
    CATEGORY_NOT_ALLOWED = "CategoryNotAllowed"
    FEE_ABOVE_LIMIT = "FeeAboveLimit"
    TOO_MANY_ACTIVE = "TooManyActive"
    TOO_FAR_AHEAD = "TooFarAhead"
    OWN_EVENT = "OwnEvent"
    # service
    POLICY_MISMATCH = "PolicyMismatch"
    INVALID_DESK = "InvalidDesk"
    FAUCET_USED = "FaucetUsed"
    FAUCET_DISABLED = "FaucetDisabled"
    TX_NOT_FOUND = "TxNotFound"
    CHAIN_UNAVAILABLE = "ChainUnavailable"
    REVERTED = "Reverted"


STATUS = {
    Reason.INVALID_SIGNATURE: 401, Reason.NOT_ORGANIZER: 403, Reason.NOT_DELEGATED: 403, Reason.BUDGET_EXCEEDED: 402, Reason.INVALID_DESK: 403,
    Reason.UNKNOWN_EVENT: 404, Reason.TX_NOT_FOUND: 404,
    Reason.ALREADY_COMMITTED: 409, Reason.ALREADY_SETTLED: 409, Reason.FAUCET_USED: 409, Reason.WRONG_PHASE: 409,
    Reason.EVENT_FULL: 409, Reason.EXPIRED: 410, Reason.INSUFFICIENT_BALANCE: 402,
    Reason.CHAIN_UNAVAILABLE: 503,
    Reason.CATEGORY_NOT_ALLOWED: 403, Reason.FEE_ABOVE_LIMIT: 403, Reason.TOO_MANY_ACTIVE: 403,
    Reason.TOO_FAR_AHEAD: 403, Reason.AGENT_IDENTITY_MISMATCH: 403,
}


class AnzenError(Exception):
    def __init__(self, reason: Reason, detail: str = ""):
        super().__init__(f"{reason}: {detail}" if detail else reason)
        self.reason, self.detail = reason, detail
