// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import {ERC20} from "openzeppelin-contracts/contracts/token/ERC20/ERC20.sol";
import {Ownable} from "openzeppelin-contracts/contracts/access/Ownable.sol";

/// @notice Valueless test asset for ANZEN on testnets and local chains. Not a stablecoin, not redeemable.
contract TestDollar is ERC20, Ownable {
    constructor(address owner_) ERC20("ANZEN Test Dollar (no value)", "tUSD") Ownable(owner_) {}

    function decimals() public pure override returns (uint8) {
        return 6;
    }

    function mint(address to, uint256 amount) external onlyOwner {
        _mint(to, amount);
    }
}
