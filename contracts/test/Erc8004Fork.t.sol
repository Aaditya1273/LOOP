// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import {Test} from "forge-std/Test.sol";
import {AnzenTreasury, IERC8004Identity, IERC8004Reputation} from "../src/AnzenTreasury.sol";
import {TestDollar} from "../src/TestDollar.sol";
import {IERC20} from "openzeppelin-contracts/contracts/token/ERC20/IERC20.sol";
import {MessageHashUtils} from "openzeppelin-contracts/contracts/utils/cryptography/MessageHashUtils.sol";

interface IIdentityFull is IERC8004Identity {
    function register() external returns (uint256);
}

interface IReputationFull {
    function getSummary(uint256 agentId, address[] calldata clients, string calldata tag1, string calldata tag2)
        external
        view
        returns (uint64 count, int128 summaryValue, uint8 summaryValueDecimals);
}

/// Runs against the official ERC-8004 registries deployed on Monad testnet, forked live:
///   MONAD_FORK=https://testnet-rpc.monad.xyz forge test --match-contract Erc8004Fork
/// Skipped unless MONAD_FORK is set (CI and offline runs).
contract Erc8004Fork is Test {
    address constant IDENTITY = 0x8004A818BFB912233c491871b3d84c89A494BD9e;
    address constant REPUTATION = 0x8004B663056A597Dffe9eCcC1965A193B7388713;
    uint256 constant N = 0xFFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551;

    function test_showUpFeedbackLandsInOfficialRegistry() public {
        string memory rpc = vm.envOr("MONAD_FORK", string(""));
        if (bytes(rpc).length == 0) return; // offline: nothing to fork
        vm.createSelectFork(rpc);
        uint256 t0 = vm.getBlockTimestamp();

        TestDollar token = new TestDollar(address(this));
        AnzenTreasury t = new AnzenTreasury(IERC20(address(token)), IERC8004Identity(IDENTITY), IERC8004Reputation(REPUTATION));
        token.mint(address(this), 100e6);
        token.approve(address(t), type(uint256).max);

        // the agent registers its own ERC-8004 identity in the real registry
        uint256 agentPk = 0xA6E7;
        address agent = vm.addr(agentPk);
        vm.prank(agent);
        uint256 agentId = IIdentityFull(IDENTITY).register();

        // alice: a P-256 owner key, funded, delegates a budget bound to that identity
        (uint256 x, uint256 y) = vm.publicKeyP256(0xa1);
        bytes32 qx = bytes32(x);
        bytes32 qy = bytes32(y);
        bytes32 alice = t.keyIdOf(qx, qy);
        t.depositFor(alice, 50e6);
        bytes memory m = abi.encode(bytes32("ANZEN/delegate/v1"), block.chainid, address(t), agent, agentId, uint128(20e6),
            uint64(t0 + 1 days), uint256(0), t0 + 600);
        t.delegate(qx, qy, agent, agentId, 20e6, uint64(t0 + 1 days), t0 + 600, _p256(0xa1, m));

        // an event; the agent reserves for alice; alice checks in; outcome applied
        (uint256 ox, uint256 oy) = vm.publicKeyP256(0x0b1);
        AnzenTreasury.EventConfig memory c = AnzenTreasury.EventConfig(t.keyIdOf(bytes32(ox), bytes32(oy)), sha256("p"),
            vm.addr(0x155), 10e6, 5, uint64(t0 + 100), uint64(t0 + 200), uint64(t0 + 300), AnzenTreasury.NoShow.ToOrganizer);
        bytes memory cm = abi.encode(bytes32("ANZEN/create/v1"), block.chainid, address(t), uint256(0), t0 + 600, c);
        uint256 id = t.createEvent(c, bytes32(ox), bytes32(oy), t0 + 600, _p256(0x0b1, cm));

        bytes32 h = keccak256(abi.encode(bytes32("ANZEN/agentcommit/v1"), block.chainid, address(t), id, alice, uint256(10e6), uint256(0), t0 + 600));
        (uint8 v, bytes32 r, bytes32 s) = vm.sign(agentPk, MessageHashUtils.toEthSignedMessageHash(h));
        t.commitByAgent(id, alice, t0 + 600, abi.encodePacked(r, s, v));

        vm.warp(t0 + 201);
        uint64 exp = uint64(t0 + 250);
        bytes32 ih = keccak256(abi.encode(bytes32("ANZEN/challenge/v1"), block.chainid, address(t), id, bytes32("qr"), exp));
        (v, r, s) = vm.sign(0x155, MessageHashUtils.toEthSignedMessageHash(ih));
        bytes memory checkIn = abi.encode(bytes32("ANZEN/checkin/v1"), block.chainid, address(t), id, bytes32("qr"), exp);
        t.checkIn(id, qx, qy, "qr", exp, abi.encodePacked(r, s, v), _p256(0xa1, checkIn));

        vm.warp(t0 + 301);
        t.finalize(id);
        t.settle(id, alice);

        address[] memory clients = new address[](1);
        clients[0] = address(t);
        (uint64 count, int128 value,) = IReputationFull(REPUTATION).getSummary(agentId, clients, "showUp", "anzen");
        assertEq(count, 1);
        assertEq(value, 100); // showed up
    }

    function _p256(uint256 pk, bytes memory message) internal pure returns (bytes memory) {
        (bytes32 r, bytes32 s) = vm.signP256(pk, sha256(message));
        if (uint256(s) > N / 2) s = bytes32(N - uint256(s));
        return abi.encodePacked(r, s);
    }
}
