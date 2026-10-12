#!/usr/bin/env bash
# Deploy anzen_core to Solana devnet and set it up (mint, config, vault). Reads .env.local (gitignored):
#   PRIVATE_KEY        32-byte seed of the deployer / upgrade authority (needs ~3.5 devnet SOL for a 420 KB program)
#   HOSTED_RELAYER_KEY 32-byte seed of the relayer the hosted service pays fees and rent with (needs ~1 SOL)
#   ANZEN_ISSUER_KEY   32-byte seed of the ed25519 key that signs venue codes
# Writes contracts/deployments/solana-103.json, which the service reads when ANZEN_CHAIN_ID=103.
#   scripts/deploy_solana.sh            # deploy (or upgrade) the program, then set it up once
set -euo pipefail
cd "$(dirname "$0")/.."
export PATH="$HOME/.cargo/bin:$HOME/.local/share/solana/install/active_release/bin:$PATH"
set -a; . ./.env.local; set +a
RPC=${SOLANA_RPC_URL:-https://api.devnet.solana.com}
umask 077; mkdir -p .dev
# solana-keygen JSON for the deployer, derived from the seed (stays in .dev, which is gitignored)
(cd backend && uv run python -c "
import json, os; from anzen.solana_chain import keypair_from
print(json.dumps(list(bytes(keypair_from(os.environ['PRIVATE_KEY'])))))") > .dev/solana-deployer.json
DEPLOYER=$(solana-keygen pubkey .dev/solana-deployer.json)
echo "deployer $DEPLOYER: $(solana balance -u "$RPC" "$DEPLOYER")"
(cd programs/anzen_core && cargo-build-sbf)
solana program deploy -u "$RPC" --keypair .dev/solana-deployer.json --upgrade-authority .dev/solana-deployer.json \
  --program-id programs/anzen_core/keys/anzen_core-keypair.json programs/anzen_core/target/deploy/anzen_core.so
PROGRAM=$(solana-keygen pubkey programs/anzen_core/keys/anzen_core-keypair.json)
if [ ! -f contracts/deployments/solana-103.json ]; then
  (cd backend && ANZEN_CHAIN_ID=103 ANZEN_RPC_URL="$RPC" ANZEN_RELAYER_KEY="$HOSTED_RELAYER_KEY" \
    ANZEN_ISSUER_KEY="$ANZEN_ISSUER_KEY" uv run python -m anzen.solana_setup "$PROGRAM")
fi
cat contracts/deployments/solana-103.json
