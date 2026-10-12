// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import {Script, console} from "forge-std/Script.sol";
import {AnzenTreasury, IERC8004Identity, IERC8004Reputation} from "../src/AnzenTreasury.sol";
import {TestDollar} from "../src/TestDollar.sol";
import {IERC20} from "openzeppelin-contracts/contracts/token/ERC20/IERC20.sol";

/// forge script script/Deploy.s.sol --rpc-url $RPC_URL --private-key $RELAYER_KEY --broadcast
/// Writes deployments/<chainid>.json, which the backend reads.
contract Deploy is Script {
    function run() external {
        vm.startBroadcast();
        // The faucet mints from the relayer that runs the service; defaults to the deployer. TOKEN reuses a
        // deployed test dollar when only the treasury changes.
        address existing = vm.envOr("TOKEN", address(0));
        TestDollar token = existing != address(0) ? TestDollar(existing) : new TestDollar(vm.envOr("TOKEN_OWNER", msg.sender));
        AnzenTreasury treasury = new AnzenTreasury(
            IERC20(address(token)), IERC8004Identity(vm.envOr("ERC8004_IDENTITY", address(0))),
            IERC8004Reputation(vm.envOr("ERC8004_REPUTATION", address(0)))
        );
        vm.stopBroadcast();

        string memory o = "deployment";
        vm.serializeUint(o, "chainId", block.chainid);
        vm.serializeUint(o, "deployBlock", block.number);
        vm.serializeAddress(o, "token", address(token));
        string memory json = vm.serializeAddress(o, "treasury", address(treasury));
        vm.writeJson(json, string.concat("deployments/", vm.toString(block.chainid), ".json"));
        console.log("token", address(token));
        console.log("treasury", address(treasury));
    }
}
