#!/usr/bin/env bash
# LOCAL SOLANA: solana-test-validator with the anzen_core program (secp256r1 + ed25519 precompiles active), the
# test-dollar mint and config set up, and the ANZEN service + web on :8790. Chain id 104 (local). Nothing here
# touches devnet. Fresh every run: the ledger lives in .dev/solana-ledger.
#   scripts/solana_local.sh          # foreground; Ctrl-C stops everything
set -euo pipefail
cd "$(dirname "$0")/.."
export PATH="$HOME/.cargo/bin:$HOME/.local/share/solana/install/active_release/bin:$PATH"
mkdir -p .dev
(cd programs/anzen_core && cargo-build-sbf -- -q 2>/dev/null || cargo-build-sbf)
PROGRAM=$(solana-keygen pubkey programs/anzen_core/keys/anzen_core-keypair.json)
# Throwaway local keys (32-byte seeds); never use them anywhere else.
RELAYER_KEY=0x$(python3 -c "print('11'*32)")
ISSUER_KEY=0x$(python3 -c "print('22'*32)")
RPC_PORT=${SOLANA_RPC_PORT:-18899}; PORT=${ANZEN_PORT:-8790}
RPC=http://127.0.0.1:$RPC_PORT
solana-test-validator --reset --quiet --ledger .dev/solana-ledger-$RPC_PORT --rpc-port "$RPC_PORT" \
  --faucet-port $((RPC_PORT + 1001)) --gossip-port $((RPC_PORT + 1100)) --dynamic-port-range $((RPC_PORT + 2000))-$((RPC_PORT + 2030)) --bpf-program "$PROGRAM" programs/anzen_core/target/deploy/anzen_core.so &
VALIDATOR=$!
trap 'kill $VALIDATOR $SERVICE 2>/dev/null' EXIT
until solana -u "$RPC" cluster-version >/dev/null 2>&1; do sleep 0.5; done
cd backend
RELAYER=$(uv run python -c "from anzen.solana_chain import keypair_from; print(keypair_from('$RELAYER_KEY').pubkey())")
solana -u "$RPC" airdrop 100 "$RELAYER" >/dev/null
ANZEN_CHAIN_ID=104 ANZEN_RPC_URL=$RPC ANZEN_RELAYER_KEY=$RELAYER_KEY ANZEN_ISSUER_KEY=$ISSUER_KEY \
  uv run python -m anzen.solana_setup "$PROGRAM" >/dev/null
rm -f anzen-solana-local-$PORT.db*
ANZEN_CHAIN_ID=104 ANZEN_RPC_URL=$RPC ANZEN_RELAYER_KEY=$RELAYER_KEY ANZEN_ISSUER_KEY=$ISSUER_KEY \
  ANZEN_DB="anzen-solana-local-$PORT.db" ANZEN_PORT=$PORT uv run python -m anzen.app &
SERVICE=$!
until curl -sf http://127.0.0.1:$PORT/health >/dev/null; do sleep 0.3; done
echo "ANZEN on local Solana: http://localhost:$PORT  (program $PROGRAM, RPC $RPC)"
wait $SERVICE
