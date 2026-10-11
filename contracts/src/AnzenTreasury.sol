// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import {IERC20} from "openzeppelin-contracts/contracts/token/ERC20/IERC20.sol";
import {SafeERC20} from "openzeppelin-contracts/contracts/token/ERC20/utils/SafeERC20.sol";
import {P256} from "openzeppelin-contracts/contracts/utils/cryptography/P256.sol";
import {ECDSA} from "openzeppelin-contracts/contracts/utils/cryptography/ECDSA.sol";
import {MessageHashUtils} from "openzeppelin-contracts/contracts/utils/cryptography/MessageHashUtils.sol";
import {WebAuthn} from "openzeppelin-contracts/contracts/utils/cryptography/WebAuthn.sol";

/// ERC-8004 registries (the official deployments on Monad), only the functions ANZEN uses.
interface IERC8004Identity {
    function ownerOf(uint256 agentId) external view returns (address);
    function getAgentWallet(uint256 agentId) external view returns (address);
}

interface IERC8004Reputation {
    function giveFeedback(
        uint256 agentId,
        int128 value,
        uint8 valueDecimals,
        string calldata tag1,
        string calldata tag2,
        string calldata endpoint,
        string calldata feedbackURI,
        bytes32 feedbackHash
    ) external;
}

/// @title ANZEN Presence Treasury
/// @notice Event treasuries whose settlement depends on an event-specific check-in.
///
/// Accounts are P-256 keys, identified by keyId = sha256(qx ‖ qy): an Android Keystore key, or a WebAuthn
/// passkey (Face ID, Touch ID, Windows Hello, security keys). Every action that moves or commits a
/// participant's funds is authorized over d = sha256(abi.encode(TAG, chainid, this, ...params)), as either
///   - a raw low-s P-256 signature of d (64 bytes: r ‖ s), or
///   - a WebAuthn assertion whose challenge is d, with user verification required (abi-encoded WebAuthnAuth).
/// Both paths use Monad's P-256 precompile (0x0100) through OpenZeppelin. A signature never proves where
/// its holder physically was; the venue check-in adds an issuer-signed, 45-second code for that.
///
/// ERC-8004 show-up reputation: when an AI agent reserved a place (commitByAgent) and the outcome is
/// applied, this contract itself posts feedback for the agent's ERC-8004 identity: 100 if the owner
/// checked in, 0 if not. Its getSummary over [this contract] is the agent's show-up rate, and every data
/// point is backed by a real commitment and a verified check-in, so it can't be farmed with Sybil reviewers.
///
/// An owner may delegate a capped, expiring commit budget to an AI agent's EVM key (`delegate`). The agent can
/// reserve places with the owner's funds inside that budget and nothing more; check-in, cancel and withdraw
/// always need the owner's own key, so money can be delegated but presence cannot.
///
/// Gas is paid by whoever submits (a relayer). The relayer cannot forge signatures; it can only delay
/// or refuse to submit them.
contract AnzenTreasury {
    using SafeERC20 for IERC20;

    enum NoShow {
        ToOrganizer,
        SplitAmongAttendees
    }

    enum Status {
        None,
        Committed,
        CheckedIn,
        Settled
    }

    struct EventConfig {
        bytes32 organizer; // keyId of the organizer's key
        bytes32 policyHash; // sha256 of the published policy document (stored offchain)
        address issuer; // signs check-in challenges shown at the venue
        uint128 amount; // commitment per participant, in asset base units
        uint32 capacity;
        uint64 commitDeadline;
        uint64 checkInStart;
        uint64 checkInEnd;
        NoShow noShow;
    }

    struct EventState {
        uint32 committed;
        uint32 attended;
        bool cancelled;
        bool finalized;
        uint128 attendeePayout; // set by finalize: amount + share of forfeits
    }

    struct Participation {
        Status status;
        bool attended;
        uint64 checkedInAt;
        uint128 settledAmount;
        uint64 passportId; // 0 = none
        uint64 agentRef; // ERC-8004 agentId + 1 when an agent reserved this place; 0 = the owner did
    }

    bytes32 internal constant CREATE = "ANZEN/create/v1";
    bytes32 internal constant COMMIT = "ANZEN/commit/v1";
    bytes32 internal constant CHECKIN = "ANZEN/checkin/v1";
    bytes32 internal constant CHALLENGE = "ANZEN/challenge/v1";
    bytes32 internal constant CANCEL = "ANZEN/cancel/v1";
    bytes32 internal constant WITHDRAW = "ANZEN/withdraw/v1";
    bytes32 internal constant DELEGATE = "ANZEN/delegate/v1";
    bytes32 internal constant AGENT_COMMIT = "ANZEN/agentcommit/v1";
    uint256 public constant NO_AGENT_ID = type(uint256).max;
    /// Gas reserved for the ERC-8004 feedback call. Measured on Monad testnet at ~280-296k (Monad prices cold
    /// storage above Ethereum's schedule, where the same call is ~179k), so this keeps ~100k of headroom.
    uint256 public constant REPUTATION_GAS = 400_000;

    /// A scoped budget an owner key grants to an AI agent's EOA: the agent may commit the owner's balance to
    /// events, up to `cap` in total, until `expiry`. It can do nothing else: no check-in, no withdraw, no cancel.
    struct Delegation {
        address agent;
        uint128 cap;
        uint128 spent;
        uint64 expiry;
        uint64 agentRef; // ERC-8004 agentId + 1; 0 = no registered identity
    }

    IERC20 public immutable asset;
    IERC8004Identity public immutable identityRegistry; // zero where ERC-8004 isn't deployed
    IERC8004Reputation public immutable reputationRegistry;

    uint256 public eventCount;
    uint64 public passportCount;
    mapping(uint256 => EventConfig) internal _config;
    mapping(uint256 => EventState) internal _state;
    mapping(uint256 => mapping(bytes32 => Participation)) internal _participation;
    mapping(bytes32 => uint256) public balanceOf;
    mapping(bytes32 => uint256) public nonces;
    mapping(bytes32 => Delegation) public delegationOf; // one per owner key
    mapping(address => uint256) public agentNonces;

    event Deposited(bytes32 indexed keyId, address indexed from, uint256 amount);
    event Withdrawn(bytes32 indexed keyId, address indexed to, uint256 amount);
    event EventCreated(uint256 indexed eventId, bytes32 indexed organizer, bytes32 policyHash);
    event Committed(uint256 indexed eventId, bytes32 indexed keyId, uint256 amount);
    event CheckedIn(uint256 indexed eventId, bytes32 indexed keyId, bytes32 challenge);
    event EventCancelled(uint256 indexed eventId);
    event EventFinalized(uint256 indexed eventId, uint32 attended, uint128 attendeePayout, uint256 organizerShare);
    event Settled(uint256 indexed eventId, bytes32 indexed keyId, bool attended, uint256 amount);
    event PassportIssued(uint256 indexed eventId, bytes32 indexed keyId, uint64 passportId);
    event Delegated(bytes32 indexed keyId, address indexed agent, uint128 cap, uint64 expiry);
    event AgentCommitted(uint256 indexed eventId, bytes32 indexed keyId, address indexed agent, uint256 amount);
    event ShowUpReported(uint256 indexed agentId, uint256 indexed eventId, bytes32 indexed keyId, bool attended, bool posted);

    error InvalidConfig();
    error InvalidSignature();
    error Expired();
    error UnknownEvent();
    error WrongPhase();
    error EventFull();
    error AlreadyCommitted();
    error NotCommitted();
    error InsufficientBalance();
    error NotOrganizer();
    error AlreadySettled();
    error NotDelegated();
    error BudgetExceeded();
    error AgentIdentityMismatch();
    error InsufficientGasForReputation();

    constructor(IERC20 asset_, IERC8004Identity identity_, IERC8004Reputation reputation_) {
        asset = asset_;
        identityRegistry = identity_;
        reputationRegistry = reputation_;
    }

    // ---------------------------------------------------------------- views

    function keyIdOf(bytes32 qx, bytes32 qy) public pure returns (bytes32) {
        return sha256(abi.encode(qx, qy));
    }

    function getEvent(uint256 eventId) external view returns (EventConfig memory, EventState memory) {
        return (_config[eventId], _state[eventId]);
    }

    function participation(uint256 eventId, bytes32 keyId) external view returns (Participation memory) {
        return _participation[eventId][keyId];
    }

    // ---------------------------------------------------------------- funds

    /// @notice Credit `amount` of the asset to a key's balance. Anyone may fund any key.
    function depositFor(bytes32 keyId, uint256 amount) external {
        asset.safeTransferFrom(msg.sender, address(this), amount);
        balanceOf[keyId] += amount;
        emit Deposited(keyId, msg.sender, amount);
    }

    function withdraw(bytes32 qx, bytes32 qy, address to, uint256 amount, uint256 deadline, bytes calldata auth)
        external
    {
        bytes32 keyId = keyIdOf(qx, qy);
        _authorize(qx, qy, deadline, auth, abi.encode(WITHDRAW, block.chainid, address(this), to, amount, nonces[keyId]++, deadline));
        if (balanceOf[keyId] < amount) revert InsufficientBalance();
        balanceOf[keyId] -= amount;
        asset.safeTransfer(to, amount);
        emit Withdrawn(keyId, to, amount);
    }

    // ---------------------------------------------------------------- lifecycle

    function createEvent(EventConfig calldata c, bytes32 qx, bytes32 qy, uint256 deadline, bytes calldata auth)
        external
        returns (uint256 eventId)
    {
        if (
            c.organizer != keyIdOf(qx, qy) || c.amount == 0 || c.capacity == 0 || c.issuer == address(0)
                || c.commitDeadline <= block.timestamp || c.commitDeadline > c.checkInStart || c.checkInStart >= c.checkInEnd
        ) revert InvalidConfig();
        _authorize(qx, qy, deadline, auth, abi.encode(CREATE, block.chainid, address(this), nonces[c.organizer]++, deadline, c));
        eventId = ++eventCount;
        _config[eventId] = c;
        emit EventCreated(eventId, c.organizer, c.policyHash);
    }

    function commit(uint256 eventId, bytes32 qx, bytes32 qy, uint256 deadline, bytes calldata auth) external {
        EventConfig storage c = _existing(eventId);
        bytes32 keyId = keyIdOf(qx, qy);
        _authorize(qx, qy, deadline, auth, abi.encode(COMMIT, block.chainid, address(this), eventId, c.amount, nonces[keyId]++, deadline));
        _commit(eventId, keyId);
    }

    /// @notice Owner grants (or replaces/revokes with cap 0) an agent's scoped budget. Signed by the owner's key.
    /// @param agentId the agent's ERC-8004 identity (its registered wallet must be `agent`), or NO_AGENT_ID
    function delegate(
        bytes32 qx,
        bytes32 qy,
        address agent,
        uint256 agentId,
        uint128 cap,
        uint64 expiry,
        uint256 deadline,
        bytes calldata auth
    ) external {
        bytes32 keyId = keyIdOf(qx, qy);
        _authorize(qx, qy, deadline, auth, abi.encode(DELEGATE, block.chainid, address(this), agent, agentId, cap, expiry, nonces[keyId]++, deadline));
        uint64 ref;
        if (agentId != NO_AGENT_ID && address(identityRegistry) != address(0)) {
            if (identityRegistry.getAgentWallet(agentId) != agent && identityRegistry.ownerOf(agentId) != agent) {
                revert AgentIdentityMismatch();
            }
            ref = uint64(agentId) + 1;
        }
        delegationOf[keyId] = Delegation(agent, cap, 0, expiry, ref);
        emit Delegated(keyId, agent, cap, expiry);
    }

    /// @notice An agent commits its owner's balance within the owner's budget. The agent signs with an ordinary
    /// EVM key (EIP-191); the owner's balance and participation are used, never the agent's. The agent cannot
    /// check in: presence needs the owner's own key.
    function commitByAgent(uint256 eventId, bytes32 keyId, uint256 deadline, bytes calldata agentSig) external {
        EventConfig storage c = _existing(eventId);
        Delegation storage d = delegationOf[keyId];
        if (block.timestamp > deadline) revert Expired();
        if (d.agent == address(0) || block.timestamp > d.expiry) revert NotDelegated();
        bytes32 h = keccak256(abi.encode(AGENT_COMMIT, block.chainid, address(this), eventId, keyId, c.amount, agentNonces[d.agent]++, deadline));
        if (ECDSA.recover(MessageHashUtils.toEthSignedMessageHash(h), agentSig) != d.agent) revert InvalidSignature();
        if (d.spent + c.amount > d.cap) revert BudgetExceeded();
        d.spent += c.amount;
        _commit(eventId, keyId);
        _participation[eventId][keyId].agentRef = d.agentRef;
        emit AgentCommitted(eventId, keyId, d.agent, c.amount);
    }

    function _commit(uint256 eventId, bytes32 keyId) internal {
        EventConfig storage c = _config[eventId];
        EventState storage st = _state[eventId];
        if (st.cancelled || block.timestamp > c.commitDeadline) revert WrongPhase();
        if (st.committed >= c.capacity) revert EventFull();
        Participation storage p = _participation[eventId][keyId];
        if (p.status != Status.None) revert AlreadyCommitted();
        if (balanceOf[keyId] < c.amount) revert InsufficientBalance();
        balanceOf[keyId] -= c.amount;
        p.status = Status.Committed;
        st.committed++;
        emit Committed(eventId, keyId, c.amount);
    }

    /// @param challenge  value shown at the venue (rotating QR), signed by the event's issuer
    /// @param expiresAt  challenge expiry; keeps a photographed code from working for long
    function checkIn(
        uint256 eventId,
        bytes32 qx,
        bytes32 qy,
        bytes32 challenge,
        uint64 expiresAt,
        bytes calldata issuerSig,
        bytes calldata auth
    ) external {
        EventConfig storage c = _existing(eventId);
        EventState storage st = _state[eventId];
        if (st.cancelled || block.timestamp < c.checkInStart || block.timestamp > c.checkInEnd) revert WrongPhase();
        if (block.timestamp > expiresAt) revert Expired();
        bytes32 issued = keccak256(abi.encode(CHALLENGE, block.chainid, address(this), eventId, challenge, expiresAt));
        if (ECDSA.recover(MessageHashUtils.toEthSignedMessageHash(issued), issuerSig) != c.issuer) revert InvalidSignature();
        bytes32 h = sha256(abi.encode(CHECKIN, block.chainid, address(this), eventId, challenge, expiresAt));
        if (!_verifyKey(qx, qy, h, auth)) revert InvalidSignature();

        bytes32 keyId = keyIdOf(qx, qy);
        Participation storage p = _participation[eventId][keyId];
        if (p.status != Status.Committed) revert NotCommitted(); // also blocks a second check-in
        p.status = Status.CheckedIn;
        p.attended = true;
        p.checkedInAt = uint64(block.timestamp);
        st.attended++;
        emit CheckedIn(eventId, keyId, challenge);
    }

    /// @notice Organizer cancels before check-in opens; every participant can then be refunded in full.
    function cancelEvent(uint256 eventId, bytes32 qx, bytes32 qy, uint256 deadline, bytes calldata auth) external {
        EventConfig storage c = _existing(eventId);
        if (keyIdOf(qx, qy) != c.organizer) revert NotOrganizer();
        _authorize(qx, qy, deadline, auth, abi.encode(CANCEL, block.chainid, address(this), eventId, nonces[c.organizer]++, deadline));
        EventState storage st = _state[eventId];
        if (st.cancelled || block.timestamp >= c.checkInStart) revert WrongPhase();
        st.cancelled = true;
        emit EventCancelled(eventId);
    }

    /// @notice After check-in closes, fixes the payout per attendee. Permissionless and one-time.
    function finalize(uint256 eventId) external {
        EventConfig storage c = _existing(eventId);
        EventState storage st = _state[eventId];
        if (st.cancelled || st.finalized || block.timestamp <= c.checkInEnd) revert WrongPhase();
        uint256 forfeited = uint256(st.committed - st.attended) * c.amount;
        uint256 share = (c.noShow == NoShow.SplitAmongAttendees && st.attended > 0) ? forfeited / st.attended : 0;
        uint256 organizerShare = forfeited - share * st.attended; // includes rounding dust
        st.attendeePayout = uint128(c.amount + share);
        st.finalized = true;
        balanceOf[c.organizer] += organizerShare;
        emit EventFinalized(eventId, st.attended, st.attendeePayout, organizerShare);
    }

    /// @notice Applies the published outcome to one participant. Permissionless: funds only ever go to
    /// that participant's own balance, and each participation settles once.
    function settle(uint256 eventId, bytes32 keyId) external {
        _existing(eventId);
        EventState storage st = _state[eventId];
        Participation storage p = _participation[eventId][keyId];
        if (p.status == Status.None) revert NotCommitted();
        if (p.status == Status.Settled) revert AlreadySettled();

        uint256 amount;
        if (st.cancelled) {
            amount = _config[eventId].amount;
        } else {
            if (!st.finalized) revert WrongPhase();
            if (p.attended) amount = st.attendeePayout;
        }
        p.status = Status.Settled;
        p.settledAmount = uint128(amount);
        balanceOf[keyId] += amount;
        emit Settled(eventId, keyId, p.attended, amount);
        if (p.attended && !st.cancelled) {
            p.passportId = ++passportCount;
            emit PassportIssued(eventId, keyId, p.passportId);
        }
        if (p.agentRef != 0 && !st.cancelled) _reportShowUp(eventId, keyId, p.agentRef - 1, p.attended);
    }

    /// @dev A registry failure never blocks settlement (the data point just isn't posted); too little gas does.
    function _reportShowUp(uint256 eventId, bytes32 keyId, uint256 agentId, bool attended) internal {
        bool posted;
        if (address(reputationRegistry) != address(0)) {
            // Without this, a gas estimate can settle on the amount where feedback runs out of gas and is
            // silently caught. Requiring the full budget up front makes "too little gas" revert instead.
            if (gasleft() < REPUTATION_GAS + 10_000) revert InsufficientGasForReputation();
            try reputationRegistry.giveFeedback{gas: REPUTATION_GAS}(
                agentId,
                attended ? int128(100) : int128(0),
                0,
                "showUp",
                "anzen",
                "",
                "",
                keccak256(abi.encode(block.chainid, address(this), eventId, keyId))
            ) {
                posted = true;
            } catch {}
        }
        emit ShowUpReported(agentId, eventId, keyId, attended, posted);
    }

    // ---------------------------------------------------------------- internals

    function _existing(uint256 eventId) internal view returns (EventConfig storage c) {
        c = _config[eventId];
        if (c.organizer == bytes32(0)) revert UnknownEvent();
    }

    function _authorize(bytes32 qx, bytes32 qy, uint256 deadline, bytes calldata auth, bytes memory message)
        internal
        view
    {
        if (block.timestamp > deadline) revert Expired();
        if (!_verifyKey(qx, qy, sha256(message), auth)) revert InvalidSignature();
    }

    /// @dev 64 bytes: raw low-s P-256 (r ‖ s) over `digest`. Otherwise: a WebAuthn assertion over challenge
    /// `digest`, requiring user presence and user verification (biometric or device PIN).
    function _verifyKey(bytes32 qx, bytes32 qy, bytes32 digest, bytes calldata auth) internal view returns (bool) {
        if (auth.length == 64) return P256.verify(digest, bytes32(auth[0:32]), bytes32(auth[32:64]), qx, qy);
        return WebAuthn.verify(abi.encodePacked(digest), abi.decode(auth, (WebAuthn.WebAuthnAuth)), qx, qy, true);
    }
}
