// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import {Test} from "forge-std/Test.sol";
import {AnzenTreasury} from "../src/AnzenTreasury.sol";
import {TestDollar} from "../src/TestDollar.sol";
import {IERC20} from "openzeppelin-contracts/contracts/token/ERC20/IERC20.sol";
import {MessageHashUtils} from "openzeppelin-contracts/contracts/utils/cryptography/MessageHashUtils.sol";
import {WebAuthn} from "openzeppelin-contracts/contracts/utils/cryptography/WebAuthn.sol";
import {Base64} from "openzeppelin-contracts/contracts/utils/Base64.sol";
import {IERC8004Identity, IERC8004Reputation} from "../src/AnzenTreasury.sol";

/// Stand-in for the official ERC-8004 registries (third-party contracts deployed on Monad), reduced to what
/// ANZEN calls. Same rules that matter: feedback is keyed by msg.sender (the client), self-feedback by the
/// agent's owner is refused, getSummary averages over the clients you name. A fork test runs against the
/// real registries on Monad testnet.
contract Registry8004 is IERC8004Identity, IERC8004Reputation {
    mapping(uint256 => address) public ownerOf;
    mapping(uint256 => address) public getAgentWallet;
    mapping(uint256 => mapping(address => int128[])) public feedback;
    uint256 public lastId;

    function register() external returns (uint256 id) {
        id = lastId++;
        ownerOf[id] = msg.sender;
        getAgentWallet[id] = msg.sender;
    }

    function giveFeedback(uint256 agentId, int128 value, uint8, string calldata tag1, string calldata, string calldata,
                          string calldata, bytes32) external {
        require(ownerOf[agentId] != address(0), "no agent");
        require(ownerOf[agentId] != msg.sender, "Self-feedback not allowed");
        require(keccak256(bytes(tag1)) == keccak256("showUp"), "tag");
        feedback[agentId][msg.sender].push(value);
    }

    function getSummary(uint256 agentId, address client) external view returns (uint256 count, int128 avg) {
        int128[] storage f = feedback[agentId][client];
        count = f.length;
        int256 sum;
        for (uint256 i; i < count; i++) sum += f[i];
        if (count > 0) avg = int128(sum / int256(count));
    }
}

/// Every signature here is a real P-256 signature (vm.signP256) verified by OpenZeppelin's P256 library:
/// no mock verifier. forge's EVM has no P-256 precompile, so these runs exercise the Solidity fallback;
/// the precompile path is exercised only on a chain that has it (Monad testnet, anvil --hardfork osaka).
contract AnzenTreasuryTest is Test {
    uint256 constant N = 0xFFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551;
    uint128 constant AMOUNT = 10e6;

    AnzenTreasury t;
    Registry8004 reg;
    TestDollar token;
    uint256 issuerPk = 0xA11CE;
    address issuer;

    struct Key {
        uint256 pk;
        bytes32 qx;
        bytes32 qy;
        bytes32 id;
    }

    Key org;
    Key alice;
    Key bob;
    Key carol;

    function setUp() public {
        vm.warp(1_800_000_000);
        token = new TestDollar(address(this));
        reg = new Registry8004();
        t = new AnzenTreasury(IERC20(address(token)), reg, reg);
        issuer = vm.addr(issuerPk);
        org = _key(0x0b1);
        alice = _key(0xa1);
        bob = _key(0xb2);
        carol = _key(0xc3);
        token.mint(address(this), 1_000e6);
        token.approve(address(t), type(uint256).max);
        t.depositFor(alice.id, 100e6);
        t.depositFor(bob.id, 100e6);
        t.depositFor(carol.id, 5e6); // not enough to commit
    }

    // ---------------------------------------------------------------- helpers

    /// via-ir caches block.timestamp across vm.warp; read it from the cheatcode instead.
    function _now() internal view returns (uint256) {
        return vm.getBlockTimestamp();
    }

    bytes4 constant OK = bytes4(0);

    function _expect(bytes4 err) internal {
        if (err != OK) vm.expectRevert(err);
    }

    function _key(uint256 pk) internal returns (Key memory k) {
        (uint256 x, uint256 y) = vm.publicKeyP256(pk);
        k = Key(pk, bytes32(x), bytes32(y), sha256(abi.encode(bytes32(x), bytes32(y))));
    }

    function _rs(Key memory k, bytes memory message) internal pure returns (bytes32 r, bytes32 s) {
        (r, s) = vm.signP256(k.pk, sha256(message));
        if (uint256(s) > N / 2) s = bytes32(N - uint256(s)); // low-s, as the app does
    }

    /// The Android path: a raw low-s P-256 signature, 64 bytes r ‖ s.
    function _sign(Key memory k, bytes memory message) internal pure returns (bytes memory) {
        (bytes32 r, bytes32 s) = _rs(k, message);
        return abi.encodePacked(r, s);
    }

    function _config(AnzenTreasury.NoShow rule) internal view returns (AnzenTreasury.EventConfig memory) {
        return AnzenTreasury.EventConfig({
            organizer: org.id,
            policyHash: sha256("policy"),
            issuer: issuer,
            amount: AMOUNT,
            capacity: 2,
            commitDeadline: uint64(_now() + 1 days),
            checkInStart: uint64(_now() + 2 days),
            checkInEnd: uint64(_now() + 2 days + 3 hours),
            noShow: rule
        });
    }

    function _create(AnzenTreasury.NoShow rule) internal returns (uint256) {
        AnzenTreasury.EventConfig memory c = _config(rule);
        uint256 deadline = _now() + 600;
        bytes memory sig =
            _sign(org, abi.encode(bytes32("ANZEN/create/v1"), block.chainid, address(t), t.nonces(org.id), deadline, c));
        return t.createEvent(c, org.qx, org.qy, deadline, sig);
    }

    function _commit(uint256 id, Key memory k) internal {
        _commit(id, k, OK);
    }

    function _commit(uint256 id, Key memory k, bytes4 err) internal {
        uint256 deadline = _now() + 600;
        bytes memory sig =
            _sign(k, abi.encode(bytes32("ANZEN/commit/v1"), block.chainid, address(t), id, AMOUNT, t.nonces(k.id), deadline));
        _expect(err);
        t.commit(id, k.qx, k.qy, deadline, sig);
    }

    function _challenge(uint256 id, bytes32 challenge, uint64 exp, uint256 signer) internal view returns (bytes memory) {
        bytes32 h = keccak256(abi.encode(bytes32("ANZEN/challenge/v1"), block.chainid, address(t), id, challenge, exp));
        (uint8 v, bytes32 r, bytes32 s) = vm.sign(signer, MessageHashUtils.toEthSignedMessageHash(h));
        return abi.encodePacked(r, s, v);
    }

    function _checkIn(uint256 id, Key memory k, bytes32 challenge, uint64 exp, bytes memory issuerSig, bytes4 err)
        internal
    {
        bytes memory sig =
            _sign(k, abi.encode(bytes32("ANZEN/checkin/v1"), block.chainid, address(t), id, challenge, exp));
        _expect(err);
        t.checkIn(id, k.qx, k.qy, challenge, exp, issuerSig, sig);
    }

    function _checkInNow(uint256 id, Key memory k) internal {
        _checkInNow(id, k, OK);
    }

    function _checkInNow(uint256 id, Key memory k, bytes4 err) internal {
        uint64 exp = uint64(_now() + 60);
        _checkIn(id, k, "qr-1", exp, _challenge(id, "qr-1", exp, issuerPk), err);
    }

    // ---------------------------------------------------------------- happy paths

    function test_fullLifecycle_splitForfeit() public {
        uint256 id = _create(AnzenTreasury.NoShow.SplitAmongAttendees);
        _commit(id, alice);
        _commit(id, bob);
        assertEq(t.balanceOf(alice.id), 90e6);

        vm.warp(_now() + 2 days + 1);
        _checkInNow(id, alice); // bob doesn't show

        vm.warp(_now() + 4 hours);
        t.finalize(id);
        t.settle(id, alice.id);
        t.settle(id, bob.id);

        assertEq(t.balanceOf(alice.id), 110e6); // 90 + 10 back + bob's 10
        assertEq(t.balanceOf(bob.id), 90e6);
        assertEq(t.participation(id, alice.id).passportId, 1);
        assertEq(t.participation(id, bob.id).passportId, 0);
        assertEq(uint8(t.participation(id, bob.id).status), uint8(AnzenTreasury.Status.Settled));
    }

    function test_forfeitToOrganizer() public {
        uint256 id = _create(AnzenTreasury.NoShow.ToOrganizer);
        _commit(id, alice);
        _commit(id, bob);
        vm.warp(_now() + 2 days + 1);
        _checkInNow(id, alice);
        vm.warp(_now() + 4 hours);
        t.finalize(id);
        t.settle(id, alice.id);
        assertEq(t.balanceOf(alice.id), 100e6);
        assertEq(t.balanceOf(org.id), 10e6);
    }

    function test_cancelRefundsEveryone_noPassport() public {
        uint256 id = _create(AnzenTreasury.NoShow.SplitAmongAttendees);
        _commit(id, alice);
        uint256 deadline = _now() + 600;
        bytes memory sig =
            _sign(org, abi.encode(bytes32("ANZEN/cancel/v1"), block.chainid, address(t), id, t.nonces(org.id), deadline));
        t.cancelEvent(id, org.qx, org.qy, deadline, sig);
        t.settle(id, alice.id);
        assertEq(t.balanceOf(alice.id), 100e6);
        assertEq(t.participation(id, alice.id).passportId, 0);
    }

    function test_withdraw() public {
        address to = address(0xBEEF);
        uint256 deadline = _now() + 600;
        bytes memory sig = _sign(
            alice, abi.encode(bytes32("ANZEN/withdraw/v1"), block.chainid, address(t), to, uint256(40e6), t.nonces(alice.id), deadline)
        );
        t.withdraw(alice.qx, alice.qy, to, 40e6, deadline, sig);
        assertEq(token.balanceOf(to), 40e6);
        assertEq(t.balanceOf(alice.id), 60e6);
    }

    // ---------------------------------------------------------------- failures

    function test_invalidSignature_rejected() public {
        uint256 id = _create(AnzenTreasury.NoShow.ToOrganizer);
        uint256 deadline = _now() + 600;
        // bob signs alice's commit
        bytes memory sig = _sign(
            bob, abi.encode(bytes32("ANZEN/commit/v1"), block.chainid, address(t), id, AMOUNT, t.nonces(alice.id), deadline)
        );
        vm.expectRevert(AnzenTreasury.InvalidSignature.selector);
        t.commit(id, alice.qx, alice.qy, deadline, sig);
    }

    function test_highS_rejected() public {
        uint256 id = _create(AnzenTreasury.NoShow.ToOrganizer);
        uint256 deadline = _now() + 600;
        (bytes32 r, bytes32 s) = _rs(
            alice, abi.encode(bytes32("ANZEN/commit/v1"), block.chainid, address(t), id, AMOUNT, t.nonces(alice.id), deadline)
        );
        vm.expectRevert(AnzenTreasury.InvalidSignature.selector);
        t.commit(id, alice.qx, alice.qy, deadline, abi.encodePacked(r, bytes32(N - uint256(s))));
    }

    function test_commitReplay_rejected() public {
        uint256 id = _create(AnzenTreasury.NoShow.ToOrganizer);
        uint256 deadline = _now() + 600;
        bytes memory sig = _sign(
            alice, abi.encode(bytes32("ANZEN/commit/v1"), block.chainid, address(t), id, AMOUNT, t.nonces(alice.id), deadline)
        );
        t.commit(id, alice.qx, alice.qy, deadline, sig);
        vm.expectRevert(AnzenTreasury.InvalidSignature.selector); // nonce moved on
        t.commit(id, alice.qx, alice.qy, deadline, sig);
    }

    function test_expiredAuthorization_rejected() public {
        uint256 id = _create(AnzenTreasury.NoShow.ToOrganizer);
        uint256 deadline = _now() + 600;
        bytes memory sig = _sign(
            alice, abi.encode(bytes32("ANZEN/commit/v1"), block.chainid, address(t), id, AMOUNT, t.nonces(alice.id), deadline)
        );
        vm.warp(deadline + 1);
        vm.expectRevert(AnzenTreasury.Expired.selector);
        t.commit(id, alice.qx, alice.qy, deadline, sig);
    }

    function test_capacityAndBalance() public {
        uint256 id = _create(AnzenTreasury.NoShow.ToOrganizer);
        Key memory dave = _key(0xd4);
        t.depositFor(dave.id, 50e6);
        _commit(id, carol, AnzenTreasury.InsufficientBalance.selector);
        _commit(id, alice);
        _commit(id, bob);
        _commit(id, dave); // full: dave's commitment waits on the waitlist (capped at one per seat)
        assertEq(uint8(t.participation(id, dave.id).status), uint8(AnzenTreasury.Status.Waitlisted));
        assertEq(t.balanceOf(dave.id), 40e6);
        Key memory erin = _key(0xe5);
        Key memory finn = _key(0xf6);
        t.depositFor(erin.id, 50e6);
        t.depositFor(finn.id, 50e6);
        _commit(id, erin);
        _commit(id, finn, AnzenTreasury.EventFull.selector);
    }

    // ---------------------------------------------------------------- v4: release, waitlist, staff, records, pass

    function _release(uint256 id, Key memory k, bytes4 err) internal {
        uint256 deadline = _now() + 600;
        bytes memory sig =
            _sign(k, abi.encode(bytes32("ANZEN/release/v1"), block.chainid, address(t), id, t.nonces(k.id), deadline));
        _expect(err);
        t.release(id, k.qx, k.qy, deadline, sig);
    }

    function _createWithTerms(uint8 category, uint16 lateFeeBps) internal returns (uint256) {
        AnzenTreasury.EventConfig memory c = _config(AnzenTreasury.NoShow.ToOrganizer);
        AnzenTreasury.EventTerms memory terms = AnzenTreasury.EventTerms(category, lateFeeBps);
        uint256 deadline = _now() + 600;
        bytes memory sig = _sign(
            org, abi.encode(bytes32("ANZEN/create/v2"), block.chainid, address(t), t.nonces(org.id), deadline, c, terms)
        );
        return t.createEventWithTerms(c, terms, org.qx, org.qy, deadline, sig);
    }

    function test_releaseInTime_fullRefundAndShield() public {
        uint256 id = _createWithTerms(1, 5000);
        _commit(id, alice);
        _release(id, alice, OK);
        assertEq(t.balanceOf(alice.id), 100e6);
        assertEq(uint8(t.participation(id, alice.id).status), uint8(AnzenTreasury.Status.Released));
        (, , uint32 releasedInTime, , , uint32 shields) = t.recordOf(alice.id);
        assertEq(releasedInTime, 1);
        assertEq(shields, 1);
        _commit(id, alice, AnzenTreasury.AlreadyCommitted.selector); // a released place isn't re-bookable by the same key
        _release(id, alice, AnzenTreasury.NotCommitted.selector);
    }

    function test_lateRelease_paysPublishedFee() public {
        uint256 id = _createWithTerms(1, 2000); // 20%
        _commit(id, alice);
        vm.warp(_now() + 1 days + 1); // past the commit deadline, before check-in
        _release(id, alice, OK);
        assertEq(t.balanceOf(alice.id), 98e6);
        assertEq(t.balanceOf(org.id), 2e6);
        (, AnzenTreasury.EventState memory st) = t.getEvent(id);
        assertEq(st.recovered, 2e6);
        vm.warp(_now() + 1 days);
        _release(id, bob, AnzenTreasury.WrongPhase.selector); // check-in open: too late to release
    }

    function test_releaseRefillsFromWaitlist_noFee() public {
        uint256 id = _createWithTerms(1, 2000);
        Key memory dave = _key(0xd4);
        t.depositFor(dave.id, 50e6);
        _commit(id, alice);
        _commit(id, bob);
        _commit(id, dave); // waitlisted
        vm.warp(_now() + 1 days + 1);
        _release(id, alice, OK); // late, but the seat is refilled at once, so no fee
        assertEq(t.balanceOf(alice.id), 100e6);
        assertEq(uint8(t.participation(id, dave.id).status), uint8(AnzenTreasury.Status.Committed));
        (, AnzenTreasury.EventState memory st) = t.getEvent(id);
        assertEq(st.committed, 2);
        assertEq(st.refilled, 1);
        assertEq(st.waitlisted, 0);
        vm.warp(_now() + 1 days);
        _checkInNow(id, dave);
        vm.warp(_now() + 4 hours);
        t.finalize(id);
        t.settle(id, dave.id);
        assertEq(t.balanceOf(dave.id), 50e6);
        assertEq(t.participation(id, dave.id).passportId, 1);
    }

    function test_waitlistedNeverPromoted_refundedAtSettlement() public {
        uint256 id = _create(AnzenTreasury.NoShow.SplitAmongAttendees);
        Key memory dave = _key(0xd4);
        t.depositFor(dave.id, 50e6);
        _commit(id, alice);
        _commit(id, bob);
        _commit(id, dave);
        vm.warp(_now() + 2 days + 1);
        _checkInNow(id, dave, AnzenTreasury.NotCommitted.selector); // no seat, no check-in
        _checkInNow(id, alice);
        vm.warp(_now() + 4 hours);
        t.finalize(id);
        t.settle(id, dave.id);
        t.settle(id, alice.id);
        assertEq(t.balanceOf(dave.id), 50e6); // whole commitment back
        assertEq(t.balanceOf(alice.id), 110e6); // dave's held money is not part of the forfeit pool
        (uint32 attended, uint32 missed, , , , ) = t.recordOf(dave.id);
        assertEq(attended + missed, 0);
    }

    function _staffCheckIn(uint256 id, Key memory guest, Key memory staff, uint64 issuedAt, bytes4 err) internal {
        bytes memory g = _sign(guest, abi.encode(bytes32("ANZEN/present/v1"), block.chainid, address(t), id, issuedAt));
        bytes memory s =
            _sign(staff, abi.encode(bytes32("ANZEN/staff/v1"), block.chainid, address(t), id, guest.id, issuedAt));
        _expect(err);
        t.checkInByStaff(id, guest.qx, guest.qy, issuedAt, g, staff.qx, staff.qy, s);
    }

    function test_staffCheckIn_silverTier() public {
        uint256 id = _create(AnzenTreasury.NoShow.ToOrganizer);
        _commit(id, alice);
        _commit(id, bob);
        vm.warp(_now() + 2 days + 1);
        _staffCheckIn(id, alice, bob, uint64(_now()), AnzenTreasury.NotOrganizer.selector); // only the organizer's key
        _staffCheckIn(id, alice, org, uint64(_now() - 46), AnzenTreasury.Expired.selector); // stale code
        _staffCheckIn(id, alice, org, uint64(_now() - 10), OK);
        assertEq(t.participation(id, alice.id).tier, 2);
        _staffCheckIn(id, alice, org, uint64(_now()), AnzenTreasury.NotCommitted.selector); // once
        _checkInNow(id, bob);
        assertEq(t.participation(id, bob.id).tier, 1);
    }

    function test_records_streakShieldsAndScore() public {
        assertEq(t.showUpScore(alice.id), 75); // no history
        // attend once
        uint256 a = _create(AnzenTreasury.NoShow.ToOrganizer);
        _commit(a, alice);
        // release another in time: earns a shield
        uint256 b = _create(AnzenTreasury.NoShow.ToOrganizer);
        _commit(b, alice);
        _release(b, alice, OK);
        uint256 c = _create(AnzenTreasury.NoShow.ToOrganizer);
        _commit(c, alice);
        vm.warp(_now() + 2 days + 1);
        _checkInNow(a, alice);
        vm.warp(_now() + 4 hours);
        t.finalize(a);
        t.finalize(c);
        t.settle(a, alice.id);
        t.settle(c, alice.id); // missed, but the shield keeps the streak
        (uint32 attended, uint32 missed, uint32 inTime, uint32 streak, uint32 best, uint32 shields) = t.recordOf(alice.id);
        assertEq(attended, 1);
        assertEq(missed, 1);
        assertEq(inTime, 1);
        assertEq(streak, 1);
        assertEq(best, 1);
        assertEq(shields, 0);
        assertEq(t.showUpScore(alice.id), (100 + 300) / uint256(6));
    }

    function _grant(Key memory owner, address agent, uint128 cap, AnzenTreasury.PassRules memory rules) internal {
        uint256 deadline = _now() + 600;
        uint64 expiry = uint64(_now() + 30 days);
        bytes memory sig = _sign(
            owner,
            abi.encode(bytes32("ANZEN/pass/v1"), block.chainid, address(t), agent, t.NO_AGENT_ID(), cap, expiry, rules, t.nonces(owner.id), deadline)
        );
        t.grantPass(owner.qx, owner.qy, agent, t.NO_AGENT_ID(), cap, expiry, rules, deadline, sig);
    }

    function test_agentPassRules() public {
        // fitness (1) and dining (2) only, late fee at most 10%, one active booking, at most 3 days ahead
        _grant(alice, vm.addr(AGENT_PK), 100e6, AnzenTreasury.PassRules(uint32(1 << 1 | 1 << 2), 1000, 1, 3));
        uint256 meetup = _createWithTerms(3, 0);
        uint256 pricey = _createWithTerms(2, 5000);
        uint256 spin = _createWithTerms(1, 1000);
        uint256 dinner = _createWithTerms(2, 0);
        _agentCommit(meetup, alice, AGENT_PK, AnzenTreasury.CategoryNotAllowed.selector);
        _agentCommit(pricey, alice, AGENT_PK, AnzenTreasury.FeeAboveLimit.selector);
        _agentCommit(spin, alice, AGENT_PK, OK);
        _agentCommit(dinner, alice, AGENT_PK, AnzenTreasury.TooManyActive.selector);
        (, uint8 active) = t.passRules(alice.id);
        assertEq(active, 1);
        _release(spin, alice, OK); // the owner (never the agent) frees the slot
        _agentCommit(dinner, alice, AGENT_PK, OK);

        _grant(bob, vm.addr(AGENT_PK), 100e6, AnzenTreasury.PassRules(0, 10_000, 0, 1));
        _agentCommit(spin, bob, AGENT_PK, AnzenTreasury.TooFarAhead.selector); // check-in is 2 days out
    }

    function test_commitAfterDeadline_rejected() public {
        uint256 id = _create(AnzenTreasury.NoShow.ToOrganizer);
        vm.warp(_now() + 1 days + 1);
        _commit(id, alice, AnzenTreasury.WrongPhase.selector);
    }

    function test_doubleCommit_rejected() public {
        uint256 id = _create(AnzenTreasury.NoShow.ToOrganizer);
        _commit(id, alice);
        _commit(id, alice, AnzenTreasury.AlreadyCommitted.selector);
    }

    function test_checkIn_outsideWindow_rejected() public {
        uint256 id = _create(AnzenTreasury.NoShow.ToOrganizer);
        _commit(id, alice);
        _checkInNow(id, alice, AnzenTreasury.WrongPhase.selector);
        vm.warp(_now() + 2 days + 3 hours + 1);
        _checkInNow(id, alice, AnzenTreasury.WrongPhase.selector);
    }

    function test_checkIn_expiredChallenge_rejected() public {
        uint256 id = _create(AnzenTreasury.NoShow.ToOrganizer);
        _commit(id, alice);
        vm.warp(_now() + 2 days + 1);
        uint64 exp = uint64(_now() + 60);
        bytes memory sig = _challenge(id, "qr-1", exp, issuerPk);
        vm.warp(exp + 1);
        _checkIn(id, alice, "qr-1", exp, sig, AnzenTreasury.Expired.selector);
    }

    function test_checkIn_unauthorizedIssuer_rejected() public {
        uint256 id = _create(AnzenTreasury.NoShow.ToOrganizer);
        _commit(id, alice);
        vm.warp(_now() + 2 days + 1);
        uint64 exp = uint64(_now() + 60);
        _checkIn(id, alice, "qr-1", exp, _challenge(id, "qr-1", exp, 0xBAD), AnzenTreasury.InvalidSignature.selector);
    }

    function test_checkIn_challengeForOtherEvent_rejected() public {
        uint256 id = _create(AnzenTreasury.NoShow.ToOrganizer);
        uint256 other = _create(AnzenTreasury.NoShow.ToOrganizer);
        _commit(id, alice);
        vm.warp(_now() + 2 days + 1);
        uint64 exp = uint64(_now() + 60);
        _checkIn(id, alice, "qr-1", exp, _challenge(other, "qr-1", exp, issuerPk), AnzenTreasury.InvalidSignature.selector);
    }

    function test_checkIn_replay_and_notCommitted_rejected() public {
        uint256 id = _create(AnzenTreasury.NoShow.ToOrganizer);
        _commit(id, alice);
        vm.warp(_now() + 2 days + 1);
        _checkInNow(id, alice);
        _checkInNow(id, alice, AnzenTreasury.NotCommitted.selector);
        _checkInNow(id, bob, AnzenTreasury.NotCommitted.selector);
    }

    function test_settleBeforeFinalize_and_doubleSettle_rejected() public {
        uint256 id = _create(AnzenTreasury.NoShow.ToOrganizer);
        _commit(id, alice);
        vm.expectRevert(AnzenTreasury.WrongPhase.selector);
        t.settle(id, alice.id);
        vm.warp(_now() + 3 days);
        vm.expectRevert(AnzenTreasury.WrongPhase.selector);
        t.settle(id, alice.id);
        t.finalize(id);
        t.settle(id, alice.id);
        vm.expectRevert(AnzenTreasury.AlreadySettled.selector);
        t.settle(id, alice.id);
        vm.expectRevert(AnzenTreasury.WrongPhase.selector);
        t.finalize(id);
    }

    function test_cancel_onlyOrganizer_beforeCheckIn() public {
        uint256 id = _create(AnzenTreasury.NoShow.ToOrganizer);
        uint256 deadline = _now() + 600;
        bytes memory sig =
            _sign(alice, abi.encode(bytes32("ANZEN/cancel/v1"), block.chainid, address(t), id, t.nonces(alice.id), deadline));
        vm.expectRevert(AnzenTreasury.NotOrganizer.selector);
        t.cancelEvent(id, alice.qx, alice.qy, deadline, sig);

        vm.warp(_now() + 2 days);
        deadline = _now() + 600;
        sig = _sign(org, abi.encode(bytes32("ANZEN/cancel/v1"), block.chainid, address(t), id, t.nonces(org.id), deadline));
        vm.expectRevert(AnzenTreasury.WrongPhase.selector);
        t.cancelEvent(id, org.qx, org.qy, deadline, sig);
    }

    function test_invalidConfig_rejected() public {
        AnzenTreasury.EventConfig memory c = _config(AnzenTreasury.NoShow.ToOrganizer);
        c.checkInEnd = c.checkInStart; // empty window
        uint256 deadline = _now() + 600;
        bytes memory sig =
            _sign(org, abi.encode(bytes32("ANZEN/create/v1"), block.chainid, address(t), t.nonces(org.id), deadline, c));
        vm.expectRevert(AnzenTreasury.InvalidConfig.selector);
        t.createEvent(c, org.qx, org.qy, deadline, sig);

        c = _config(AnzenTreasury.NoShow.ToOrganizer);
        c.organizer = alice.id; // someone else's key
        vm.expectRevert(AnzenTreasury.InvalidConfig.selector);
        t.createEvent(c, org.qx, org.qy, deadline, sig);
    }

    function test_withdraw_wrongRecipient_rejected() public {
        uint256 deadline = _now() + 600;
        bytes memory sig = _sign(
            alice, abi.encode(bytes32("ANZEN/withdraw/v1"), block.chainid, address(t), address(1), uint256(40e6), t.nonces(alice.id), deadline)
        );
        vm.expectRevert(AnzenTreasury.InvalidSignature.selector);
        t.withdraw(alice.qx, alice.qy, address(2), 40e6, deadline, sig);
    }

    function test_splitRoundingDust_goesToOrganizer() public {
        AnzenTreasury.EventConfig memory c = _config(AnzenTreasury.NoShow.SplitAmongAttendees);
        c.capacity = 4;
        uint256 deadline = _now() + 600;
        bytes memory sig =
            _sign(org, abi.encode(bytes32("ANZEN/create/v1"), block.chainid, address(t), t.nonces(org.id), deadline, c));
        uint256 id = t.createEvent(c, org.qx, org.qy, deadline, sig);
        Key memory dave = _key(0xd4);
        Key memory erin = _key(0xe5);
        t.depositFor(dave.id, 10e6);
        t.depositFor(erin.id, 10e6);
        _commit(id, alice);
        _commit(id, bob);
        _commit(id, dave);
        _commit(id, erin);
        vm.warp(_now() + 2 days + 1);
        _checkInNow(id, alice);
        _checkInNow(id, bob);
        _checkInNow(id, dave); // erin no-shows: 10e6 split 3 ways
        vm.warp(_now() + 4 hours);
        t.finalize(id);
        (, AnzenTreasury.EventState memory st) = t.getEvent(id);
        assertEq(st.attendeePayout, 10e6 + 3_333_333);
        assertEq(t.balanceOf(org.id), 1); // dust
    }

    // ---------------------------------------------------------------- agent delegation

    uint256 constant AGENT_PK = 0xA6E7;

    function _delegate(Key memory owner, address agent, uint128 cap, uint64 expiry) internal {
        _delegate(owner, agent, t.NO_AGENT_ID(), cap, expiry, OK);
    }

    function _delegate(Key memory owner, address agent, uint256 agentId, uint128 cap, uint64 expiry, bytes4 err) internal {
        uint256 deadline = _now() + 600;
        bytes memory sig = _sign(owner, abi.encode(bytes32("ANZEN/delegate/v1"), block.chainid, address(t), agent, agentId, cap, expiry, t.nonces(owner.id), deadline));
        _expect(err);
        t.delegate(owner.qx, owner.qy, agent, agentId, cap, expiry, deadline, sig);
    }

    function _agentCommit(uint256 id, Key memory owner, uint256 agentPk, bytes4 err) internal {
        uint256 deadline = _now() + 600;
        bytes32 h = keccak256(abi.encode(bytes32("ANZEN/agentcommit/v1"), block.chainid, address(t), id, owner.id, AMOUNT, t.agentNonces(vm.addr(agentPk)), deadline));
        (uint8 v, bytes32 r, bytes32 s) = vm.sign(agentPk, MessageHashUtils.toEthSignedMessageHash(h));
        _expect(err);
        t.commitByAgent(id, owner.id, deadline, abi.encodePacked(r, s, v));
    }

    function test_agentCommitsWithinBudget_ownerChecksIn() public {
        uint256 id = _create(AnzenTreasury.NoShow.ToOrganizer);
        address agent = vm.addr(AGENT_PK);
        _delegate(alice, agent, 25e6, uint64(_now() + 7 days));
        _agentCommit(id, alice, AGENT_PK, OK);
        assertEq(t.balanceOf(alice.id), 90e6); // alice's funds, alice's participation
        (, , uint128 spent, , , , ) = t.delegationOf(alice.id);
        assertEq(spent, AMOUNT);
        assertEq(uint8(t.participation(id, alice.id).status), uint8(AnzenTreasury.Status.Committed));

        // only alice's own key can check in
        vm.warp(_now() + 2 days + 1);
        _checkInNow(id, alice);
        assertTrue(t.participation(id, alice.id).attended);
    }

    function test_agentBudgetCap() public {
        uint256 id = _create(AnzenTreasury.NoShow.ToOrganizer);
        uint256 other = _create(AnzenTreasury.NoShow.ToOrganizer);
        uint256 third = _create(AnzenTreasury.NoShow.ToOrganizer);
        _delegate(alice, vm.addr(AGENT_PK), 20e6, uint64(_now() + 7 days));
        _agentCommit(id, alice, AGENT_PK, OK);
        _agentCommit(other, alice, AGENT_PK, OK);
        _agentCommit(third, alice, AGENT_PK, AnzenTreasury.BudgetExceeded.selector);
    }

    function test_agentExpiryRevokeAndWrongAgent() public {
        uint256 id = _create(AnzenTreasury.NoShow.ToOrganizer);
        _agentCommit(id, alice, AGENT_PK, AnzenTreasury.NotDelegated.selector); // never delegated
        _delegate(alice, vm.addr(AGENT_PK), 50e6, uint64(_now() + 1 hours));
        _agentCommit(id, alice, 0xBAD, AnzenTreasury.InvalidSignature.selector); // another key
        vm.warp(_now() + 2 hours);
        _agentCommit(id, alice, AGENT_PK, AnzenTreasury.NotDelegated.selector); // expired
        _delegate(alice, vm.addr(AGENT_PK), 50e6, uint64(_now() + 1 days));
        _delegate(alice, address(0), 0, 0); // revoke
        _agentCommit(id, alice, AGENT_PK, AnzenTreasury.NotDelegated.selector);
    }

    function test_agentSignatureReplay_rejected() public {
        uint256 id = _create(AnzenTreasury.NoShow.ToOrganizer);
        uint256 other = _create(AnzenTreasury.NoShow.ToOrganizer);
        _delegate(alice, vm.addr(AGENT_PK), 50e6, uint64(_now() + 1 days));
        uint256 deadline = _now() + 600;
        bytes32 h = keccak256(abi.encode(bytes32("ANZEN/agentcommit/v1"), block.chainid, address(t), id, alice.id, AMOUNT, uint256(0), deadline));
        (uint8 v, bytes32 r, bytes32 s) = vm.sign(AGENT_PK, MessageHashUtils.toEthSignedMessageHash(h));
        bytes memory sig = abi.encodePacked(r, s, v);
        t.commitByAgent(id, alice.id, deadline, sig);
        vm.expectRevert(AnzenTreasury.InvalidSignature.selector); // nonce moved
        t.commitByAgent(id, alice.id, deadline, sig);
        vm.expectRevert(AnzenTreasury.InvalidSignature.selector); // and it is bound to the event
        t.commitByAgent(other, alice.id, deadline, sig);
    }

    function test_agentCannotCheckInWithOwnKey() public {
        uint256 id = _create(AnzenTreasury.NoShow.ToOrganizer);
        _delegate(alice, vm.addr(AGENT_PK), 50e6, uint64(_now() + 1 days));
        _agentCommit(id, alice, AGENT_PK, OK);
        vm.warp(_now() + 2 days + 1);
        // The agent has no P-256 key for alice; signing the check-in with any other key fails.
        _checkInNow(id, bob, AnzenTreasury.NotCommitted.selector);
        uint64 exp = uint64(_now() + 60);
        Key memory impostor = _key(AGENT_PK);
        _checkIn(id, impostor, "qr-1", exp, _challenge(id, "qr-1", exp, issuerPk), AnzenTreasury.NotCommitted.selector);
    }

    // ---------------------------------------------------------------- WebAuthn passkeys

    /// Builds a real WebAuthn assertion the way a browser + platform authenticator does: authenticatorData with
    /// the UP and UV flags, clientDataJSON carrying base64url(challenge), signed over authData ‖ sha256(clientData).
    function _webauthn(Key memory k, bytes memory message, bytes1 flags) internal pure returns (bytes memory) {
        bytes memory authData = abi.encodePacked(sha256("anzen.app"), flags, uint32(1));
        string memory clientData = string.concat(
            '{"type":"webauthn.get","challenge":"', Base64.encodeURL(abi.encodePacked(sha256(message))),
            '","origin":"https://anzen.app","crossOrigin":false}'
        );
        (bytes32 r, bytes32 s) = vm.signP256(k.pk, sha256(abi.encodePacked(authData, sha256(bytes(clientData)))));
        if (uint256(s) > N / 2) s = bytes32(N - uint256(s));
        return abi.encode(WebAuthn.WebAuthnAuth(r, s, 23, 1, authData, clientData));
    }

    function test_webauthnPasskey_commitAndCheckIn() public {
        uint256 id = _create(AnzenTreasury.NoShow.SplitAmongAttendees);
        uint256 deadline = _now() + 600;
        bytes memory commitMsg = abi.encode(bytes32("ANZEN/commit/v1"), block.chainid, address(t), id, AMOUNT, t.nonces(alice.id), deadline);
        t.commit(id, alice.qx, alice.qy, deadline, _webauthn(alice, commitMsg, 0x05));
        assertEq(t.balanceOf(alice.id), 90e6);

        vm.warp(_now() + 2 days + 1);
        uint64 exp = uint64(_now() + 60);
        bytes memory issuerSig = _challenge(id, "qr-1", exp, issuerPk);
        bytes memory checkInMsg = abi.encode(bytes32("ANZEN/checkin/v1"), block.chainid, address(t), id, bytes32("qr-1"), exp);
        t.checkIn(id, alice.qx, alice.qy, "qr-1", exp, issuerSig, _webauthn(alice, checkInMsg, 0x05));
        assertTrue(t.participation(id, alice.id).attended);
    }

    function test_webauthn_requiresUserVerification_andRightChallenge() public {
        uint256 id = _create(AnzenTreasury.NoShow.ToOrganizer);
        uint256 deadline = _now() + 600;
        bytes memory m = abi.encode(bytes32("ANZEN/commit/v1"), block.chainid, address(t), id, AMOUNT, t.nonces(alice.id), deadline);
        bytes memory other = abi.encode(bytes32("ANZEN/commit/v1"), block.chainid, address(t), id + 1, AMOUNT, t.nonces(alice.id), deadline);
        bytes memory presenceOnly = _webauthn(alice, m, 0x01); // no biometric/PIN
        bytes memory wrongAction = _webauthn(alice, other, 0x05); // assertion for a different action
        bytes memory wrongKey = _webauthn(bob, m, 0x05); // someone else's passkey
        vm.expectRevert(AnzenTreasury.InvalidSignature.selector);
        t.commit(id, alice.qx, alice.qy, deadline, presenceOnly);
        vm.expectRevert(AnzenTreasury.InvalidSignature.selector);
        t.commit(id, alice.qx, alice.qy, deadline, wrongAction);
        vm.expectRevert(AnzenTreasury.InvalidSignature.selector);
        t.commit(id, alice.qx, alice.qy, deadline, wrongKey);
        t.commit(id, alice.qx, alice.qy, deadline, _webauthn(alice, m, 0x05)); // and the right one works
    }

    // ---------------------------------------------------------------- ERC-8004 show-up reputation

    function _registeredAgent() internal returns (uint256 agentId, address agent) {
        agent = vm.addr(AGENT_PK);
        vm.prank(agent);
        agentId = reg.register();
    }

    function test_showUpReputation_postedByTreasury() public {
        (uint256 agentId, address agent) = _registeredAgent();
        _delegate(alice, agent, agentId, 50e6, uint64(_now() + 7 days), OK);
        _delegate(bob, agent, agentId, 50e6, uint64(_now() + 7 days), OK);
        uint256 id = _create(AnzenTreasury.NoShow.SplitAmongAttendees);
        _agentCommit(id, alice, AGENT_PK, OK);
        _agentCommit(id, bob, AGENT_PK, OK);
        assertEq(t.participation(id, alice.id).agentRef, agentId + 1);

        vm.warp(_now() + 2 days + 1);
        _checkInNow(id, alice); // bob's reservation was a no-show
        vm.warp(_now() + 4 hours);
        t.finalize(id);
        t.settle(id, alice.id);
        t.settle(id, bob.id);

        (uint256 count, int128 avg) = reg.getSummary(agentId, address(t));
        assertEq(count, 2);
        assertEq(avg, 50); // show-up rate: 1 of 2
    }

    function test_showUp_notReportedForOwnerCommitsOrCancelledEvents() public {
        (uint256 agentId, address agent) = _registeredAgent();
        _delegate(alice, agent, agentId, 50e6, uint64(_now() + 7 days), OK);
        uint256 id = _create(AnzenTreasury.NoShow.ToOrganizer);
        _commit(id, bob); // bob committed himself: no agent involved
        _agentCommit(id, alice, AGENT_PK, OK);
        uint256 deadline = _now() + 600;
        bytes memory sig = _sign(org, abi.encode(bytes32("ANZEN/cancel/v1"), block.chainid, address(t), id, t.nonces(org.id), deadline));
        t.cancelEvent(id, org.qx, org.qy, deadline, sig);
        t.settle(id, alice.id);
        t.settle(id, bob.id);
        (uint256 count,) = reg.getSummary(agentId, address(t));
        assertEq(count, 0); // a cancellation says nothing about the agent
    }

    function test_delegate_rejectsSomeoneElsesAgentIdentity() public {
        (uint256 agentId,) = _registeredAgent();
        _delegate(alice, address(0xBEEF), agentId, 50e6, uint64(_now() + 7 days), AnzenTreasury.AgentIdentityMismatch.selector);
    }

    function test_lowGasCannotSilentlySkipReputation() public {
        (uint256 agentId, address agent) = _registeredAgent();
        _delegate(alice, agent, agentId, 50e6, uint64(_now() + 7 days), OK);
        uint256 id = _create(AnzenTreasury.NoShow.ToOrganizer);
        _agentCommit(id, alice, AGENT_PK, OK);
        vm.warp(_now() + 3 days);
        t.finalize(id);
        vm.expectRevert(AnzenTreasury.InsufficientGasForReputation.selector);
        t.settle{gas: 120_000}(id, alice.id); // enough to settle, not enough to report: must revert, not skip
        t.settle(id, alice.id);
        (uint256 count,) = reg.getSummary(agentId, address(t));
        assertEq(count, 1);
    }

    function test_reputationFailureNeverBlocksSettlement() public {
        (uint256 agentId, address agent) = _registeredAgent();
        _delegate(alice, agent, agentId, 50e6, uint64(_now() + 7 days), OK);
        uint256 id = _create(AnzenTreasury.NoShow.ToOrganizer);
        _agentCommit(id, alice, AGENT_PK, OK);
        vm.warp(_now() + 3 days);
        t.finalize(id);
        vm.mockCallRevert(address(reg), abi.encodeWithSelector(IERC8004Reputation.giveFeedback.selector), "down");
        t.settle(id, alice.id); // still settles
        assertEq(uint8(t.participation(id, alice.id).status), uint8(AnzenTreasury.Status.Settled));
    }
}
