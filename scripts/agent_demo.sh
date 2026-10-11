#!/usr/bin/env bash
# Live OpenClaw agent using the ANZEN skill (skills/anzen) against the local devnet.
#   GEMINI_API_KEY=... ANZEN_OWNER=0x<your Shonko account key> scripts/agent_demo.sh "find me something in Bengaluru under 10 and book it"
# Runs one isolated agent turn with this repo as its workspace; your normal OpenClaw agents are untouched.
# First run: the agent creates its key and replies with an approval link; open it on the phone (or, without a
# phone, approve from the terminal: (cd backend && uv run python -m anzen.seed owner <agent address> 20)).
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANZEN_OWNER:?set ANZEN_OWNER to the owner account key id (Shonko > Profile > Account key)}"
export ANZEN_OWNER ANZEN_URL="${ANZEN_URL:-http://127.0.0.1:8790}"
exec openclaw agent exec --cwd "$PWD" --timeout 300 "${1:-What events can I go to? Book the cheapest one under 10 dollars without asking me.}"
