// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import {Test} from "forge-std/Test.sol";
import {AnzenTreasury} from "../src/AnzenTreasury.sol";
import {TestDollar} from "../src/TestDollar.sol";
import {IERC20} from "openzeppelin-contracts/contracts/token/ERC20/IERC20.sol";
import {MessageHashUtils} from "openzeppelin-contracts/contracts/utils/cryptography/MessageHashUtils.sol";

/// Every signature here is a real P-256 signature (vm.signP256) verified by OpenZeppelin's P256 library:
/// no mock verifier. forge's EVM has no P-256 precompile, so these runs exercise the Solidity fallback;
/// the precompile path is exercised only on a chain that has it (Monad testnet, anvil --hardfork osaka).
contract AnzenTreasuryTest is Test {
    uint256 constant N = 0xFFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551;
    uint128 constant AMOUNT = 10e6;

    AnzenTreasury t;
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
        t = new AnzenTreasury(IERC20(address(token)));
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

    function _sign(Key memory k, bytes memory message) internal pure returns (bytes32 r, bytes32 s) {
        (r, s) = vm.signP256(k.pk, sha256(message));
        if (uint256(s) > N / 2) s = bytes32(N - uint256(s)); // low-s, as the app does
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
        (bytes32 r, bytes32 s) =
            _sign(org, abi.encode(bytes32("ANZEN/create/v1"), block.chainid, address(t), t.nonces(org.id), deadline, c));
        return t.createEvent(c, org.qx, org.qy, deadline, r, s);
    }

    function _commit(uint256 id, Key memory k) internal {
        _commit(id, k, OK);
    }

    function _commit(uint256 id, Key memory k, bytes4 err) internal {
        uint256 deadline = _now() + 600;
        (bytes32 r, bytes32 s) =
            _sign(k, abi.encode(bytes32("ANZEN/commit/v1"), block.chainid, address(t), id, AMOUNT, t.nonces(k.id), deadline));
        _expect(err);
        t.commit(id, k.qx, k.qy, deadline, r, s);
    }

    function _challenge(uint256 id, bytes32 challenge, uint64 exp, uint256 signer) internal view returns (bytes memory) {
        bytes32 h = keccak256(abi.encode(bytes32("ANZEN/challenge/v1"), block.chainid, address(t), id, challenge, exp));
        (uint8 v, bytes32 r, bytes32 s) = vm.sign(signer, MessageHashUtils.toEthSignedMessageHash(h));
        return abi.encodePacked(r, s, v);
    }

    function _checkIn(uint256 id, Key memory k, bytes32 challenge, uint64 exp, bytes memory issuerSig, bytes4 err)
        internal
    {
        (bytes32 r, bytes32 s) =
            _sign(k, abi.encode(bytes32("ANZEN/checkin/v1"), block.chainid, address(t), id, challenge, exp));
        _expect(err);
        t.checkIn(id, k.qx, k.qy, challenge, exp, issuerSig, r, s);
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
        (bytes32 r, bytes32 s) =
            _sign(org, abi.encode(bytes32("ANZEN/cancel/v1"), block.chainid, address(t), id, t.nonces(org.id), deadline));
        t.cancelEvent(id, org.qx, org.qy, deadline, r, s);
        t.settle(id, alice.id);
        assertEq(t.balanceOf(alice.id), 100e6);
        assertEq(t.participation(id, alice.id).passportId, 0);
    }

    function test_withdraw() public {
        address to = address(0xBEEF);
        uint256 deadline = _now() + 600;
        (bytes32 r, bytes32 s) = _sign(
            alice, abi.encode(bytes32("ANZEN/withdraw/v1"), block.chainid, address(t), to, uint256(40e6), t.nonces(alice.id), deadline)
        );
        t.withdraw(alice.qx, alice.qy, to, 40e6, deadline, r, s);
        assertEq(token.balanceOf(to), 40e6);
        assertEq(t.balanceOf(alice.id), 60e6);
    }

    // ---------------------------------------------------------------- failures

    function test_invalidSignature_rejected() public {
        uint256 id = _create(AnzenTreasury.NoShow.ToOrganizer);
        uint256 deadline = _now() + 600;
        // bob signs alice's commit
        (bytes32 r, bytes32 s) = _sign(
            bob, abi.encode(bytes32("ANZEN/commit/v1"), block.chainid, address(t), id, AMOUNT, t.nonces(alice.id), deadline)
        );
        vm.expectRevert(AnzenTreasury.InvalidSignature.selector);
        t.commit(id, alice.qx, alice.qy, deadline, r, s);
    }

    function test_highS_rejected() public {
        uint256 id = _create(AnzenTreasury.NoShow.ToOrganizer);
        uint256 deadline = _now() + 600;
        (bytes32 r, bytes32 s) = _sign(
            alice, abi.encode(bytes32("ANZEN/commit/v1"), block.chainid, address(t), id, AMOUNT, t.nonces(alice.id), deadline)
        );
        vm.expectRevert(AnzenTreasury.InvalidSignature.selector);
        t.commit(id, alice.qx, alice.qy, deadline, r, bytes32(N - uint256(s)));
    }

    function test_commitReplay_rejected() public {
        uint256 id = _create(AnzenTreasury.NoShow.ToOrganizer);
        uint256 deadline = _now() + 600;
        (bytes32 r, bytes32 s) = _sign(
            alice, abi.encode(bytes32("ANZEN/commit/v1"), block.chainid, address(t), id, AMOUNT, t.nonces(alice.id), deadline)
        );
        t.commit(id, alice.qx, alice.qy, deadline, r, s);
        vm.expectRevert(AnzenTreasury.InvalidSignature.selector); // nonce moved on
        t.commit(id, alice.qx, alice.qy, deadline, r, s);
    }

    function test_expiredAuthorization_rejected() public {
        uint256 id = _create(AnzenTreasury.NoShow.ToOrganizer);
        uint256 deadline = _now() + 600;
        (bytes32 r, bytes32 s) = _sign(
            alice, abi.encode(bytes32("ANZEN/commit/v1"), block.chainid, address(t), id, AMOUNT, t.nonces(alice.id), deadline)
        );
        vm.warp(deadline + 1);
        vm.expectRevert(AnzenTreasury.Expired.selector);
        t.commit(id, alice.qx, alice.qy, deadline, r, s);
    }

    function test_capacityAndBalance() public {
        uint256 id = _create(AnzenTreasury.NoShow.ToOrganizer);
        Key memory dave = _key(0xd4);
        t.depositFor(dave.id, 50e6);
        _commit(id, carol, AnzenTreasury.InsufficientBalance.selector);
        _commit(id, alice);
        _commit(id, bob);
        _commit(id, dave, AnzenTreasury.EventFull.selector);
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
        (bytes32 r, bytes32 s) =
            _sign(alice, abi.encode(bytes32("ANZEN/cancel/v1"), block.chainid, address(t), id, t.nonces(alice.id), deadline));
        vm.expectRevert(AnzenTreasury.NotOrganizer.selector);
        t.cancelEvent(id, alice.qx, alice.qy, deadline, r, s);

        vm.warp(_now() + 2 days);
        deadline = _now() + 600;
        (r, s) = _sign(org, abi.encode(bytes32("ANZEN/cancel/v1"), block.chainid, address(t), id, t.nonces(org.id), deadline));
        vm.expectRevert(AnzenTreasury.WrongPhase.selector);
        t.cancelEvent(id, org.qx, org.qy, deadline, r, s);
    }

    function test_invalidConfig_rejected() public {
        AnzenTreasury.EventConfig memory c = _config(AnzenTreasury.NoShow.ToOrganizer);
        c.checkInEnd = c.checkInStart; // empty window
        uint256 deadline = _now() + 600;
        (bytes32 r, bytes32 s) =
            _sign(org, abi.encode(bytes32("ANZEN/create/v1"), block.chainid, address(t), t.nonces(org.id), deadline, c));
        vm.expectRevert(AnzenTreasury.InvalidConfig.selector);
        t.createEvent(c, org.qx, org.qy, deadline, r, s);

        c = _config(AnzenTreasury.NoShow.ToOrganizer);
        c.organizer = alice.id; // someone else's key
        vm.expectRevert(AnzenTreasury.InvalidConfig.selector);
        t.createEvent(c, org.qx, org.qy, deadline, r, s);
    }

    function test_withdraw_wrongRecipient_rejected() public {
        uint256 deadline = _now() + 600;
        (bytes32 r, bytes32 s) = _sign(
            alice, abi.encode(bytes32("ANZEN/withdraw/v1"), block.chainid, address(t), address(1), uint256(40e6), t.nonces(alice.id), deadline)
        );
        vm.expectRevert(AnzenTreasury.InvalidSignature.selector);
        t.withdraw(alice.qx, alice.qy, address(2), 40e6, deadline, r, s);
    }

    function test_splitRoundingDust_goesToOrganizer() public {
        AnzenTreasury.EventConfig memory c = _config(AnzenTreasury.NoShow.SplitAmongAttendees);
        c.capacity = 4;
        uint256 deadline = _now() + 600;
        (bytes32 r, bytes32 s) =
            _sign(org, abi.encode(bytes32("ANZEN/create/v1"), block.chainid, address(t), t.nonces(org.id), deadline, c));
        uint256 id = t.createEvent(c, org.qx, org.qy, deadline, r, s);
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
        uint256 deadline = _now() + 600;
        (bytes32 r, bytes32 s) = _sign(owner, abi.encode(bytes32("ANZEN/delegate/v1"), block.chainid, address(t), agent, cap, expiry, t.nonces(owner.id), deadline));
        t.delegate(owner.qx, owner.qy, agent, cap, expiry, deadline, r, s);
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
        (, , uint128 spent, ) = t.delegationOf(alice.id);
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
}
