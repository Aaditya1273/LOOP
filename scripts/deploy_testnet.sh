#!/usr/bin/env bash
# Deploy ANZEN to Monad testnet and run the service against it.
# Reads .env.local (gitignored): PRIVATE_KEY = funded deployer/relayer, ANZEN_ISSUER_KEY = check-in issuer.
#   scripts/deploy_testnet.sh            # deploy (once) + start service on :8790
#   scripts/deploy_testnet.sh --serve    # start service against the existing deployment
set -euo pipefail
cd "$(dirname "$0")/.."
. scripts/toolchain.env
set -a; . ./.env.local; set +a
RPC=${ANZEN_RPC_URL:-https://testnet-rpc.monad.xyz}
RELAYER_KEY=0x${PRIVATE_KEY#0x}
ADDR=$(cast wallet address --private-key "$RELAYER_KEY")
[ "$(cast chain-id --rpc-url "$RPC")" = 10143 ] || { echo "RPC is not Monad testnet (10143)"; exit 1; }
BAL=$(cast balance "$ADDR" --rpc-url "$RPC")
echo "relayer $ADDR balance $(cast from-wei "$BAL") MON"
[ "$BAL" != 0 ] || { echo "Fund $ADDR at https://testnet.monad.xyz first"; exit 1; }

if [ "${1:-}" != "--serve" ] && [ ! -f contracts/deployments/10143.json ]; then
  (cd contracts && forge script script/Deploy.s.sol --rpc-url "$RPC" --private-key "$RELAYER_KEY" --broadcast --slow)
  echo "deployed: $(cat contracts/deployments/10143.json)"
fi

cd backend
ANZEN_CHAIN_ID=10143 ANZEN_RPC_URL="$RPC" ANZEN_RELAYER_KEY="$RELAYER_KEY" ANZEN_ISSUER_KEY="$ANZEN_ISSUER_KEY" \
  ANZEN_DB=anzen-testnet.db ANZEN_PORT=${ANZEN_PORT:-8790} uv run python -m anzen.app
