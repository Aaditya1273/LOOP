// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import {Script, console} from "forge-std/Script.sol";
import {AnzenTreasury} from "../src/AnzenTreasury.sol";
import {TestDollar} from "../src/TestDollar.sol";
import {IERC20} from "openzeppelin-contracts/contracts/token/ERC20/IERC20.sol";

/// forge script script/Deploy.s.sol --rpc-url $RPC_URL --private-key $RELAYER_KEY --broadcast
/// Writes deployments/<chainid>.json, which the backend reads.
contract Deploy is Script {
    function run() external {
        vm.startBroadcast();
        TestDollar token = new TestDollar(msg.sender);
        AnzenTreasury treasury = new AnzenTreasury(IERC20(address(token)));
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
