#!/usr/bin/env bash
# LOCAL DEVNET: anvil (with the P-256 precompile) + ANZEN contracts + the ANZEN service on :8790, seeded with
# sample events. Everything is real onchain on a throwaway local chain; tUSD has no value.
#   scripts/dev_stack.sh            # foreground; Ctrl-C stops everything
#   scripts/dev_stack.sh --fresh    # wipe the local chain and redeploy (after contract changes)
#   scripts/warp.sh 3600            # fast-forward the local chain (check-in windows, settlement)
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p .dev
if [ "${1:-}" = "--fresh" ]; then
  rm -f .dev/anvil-state.json contracts/deployments/31337.json backend/anzen-local-*.db*
  (cd contracts && forge build -q)
fi

# anvil's well-known dev keys (public; never use on a real network)
RELAYER_KEY=0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80
ISSUER_KEY=0x59c6995e998f97a5a0044966f0945389dc9e86dae88c7a8412f4603b6b78690d

anvil --hardfork osaka --port 8545 --silent --state .dev/anvil-state.json &
ANVIL=$!
trap 'kill $ANVIL $SERVICE 2>/dev/null' EXIT
until cast chain-id --rpc-url http://127.0.0.1:8545 >/dev/null 2>&1; do sleep 0.2; done

if [ ! -f contracts/deployments/31337.json ] || [ -z "$(cast code "$(python3 -c "import json;print(json.load(open('contracts/deployments/31337.json'))['treasury'])")" --rpc-url http://127.0.0.1:8545 | sed 's/^0x$//')" ]; then
  (cd contracts && forge script script/Deploy.s.sol --rpc-url http://127.0.0.1:8545 --private-key $RELAYER_KEY --broadcast -q)
fi

TREASURY=$(python3 -c "import json;print(json.load(open('contracts/deployments/31337.json'))['treasury'])")
cd backend
# One database per deployment, so a redeployed chain never shows stale events.
ANZEN_CHAIN_ID=31337 ANZEN_RPC_URL=http://127.0.0.1:8545 ANZEN_RELAYER_KEY=$RELAYER_KEY ANZEN_ISSUER_KEY=$ISSUER_KEY \
  ANZEN_DB="anzen-local-$TREASURY.db" uv run python -m anzen.app &
SERVICE=$!
until curl -sf http://127.0.0.1:8790/health >/dev/null; do sleep 0.3; done
# Seed sample events into an empty service.
[ "$(curl -s http://127.0.0.1:8790/events)" = "[]" ] && uv run python -m anzen.seed events
echo "ANZEN local devnet ready: service http://127.0.0.1:8790 (emulator: http://10.0.2.2:8790)"
wait $SERVICE
