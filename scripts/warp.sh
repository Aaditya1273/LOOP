#!/usr/bin/env bash
# Fast-forward the LOCAL devnet by N seconds (default 1 hour) and mine a block.
set -euo pipefail
cast rpc evm_increaseTime "${1:-3600}" --rpc-url http://127.0.0.1:8545 >/dev/null
cast rpc evm_mine --rpc-url http://127.0.0.1:8545 >/dev/null
echo "chain time: $(cast block latest -f timestamp --rpc-url http://127.0.0.1:8545)"
