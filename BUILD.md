# Building, running and testing ANZEN

The pitch and a two-minute walkthrough are in [README.md](README.md); the full protocol vision is in
[docs/SPEC.md](docs/SPEC.md). This file is for running and checking the code.

## Live deployment

| | |
|---|---|
| App + service | https://anzen-shonko.vercel.app (Vercel; Postgres on Neon) |
| Network | Monad testnet, chain 10143 |
| Treasury | `0xb87750460d648B0D7C953B42aE6FcDB9245F5d8f` (`contracts/deployments/10143.json`) |
| Test dollar (tUSD, no value) | `0x5799ab1E835225a824C550Fe3797Ce7a846Dff73` |
| ERC-8004 registries (official, Monad testnet) | identity `0x8004A818BFB912233c491871b3d84c89A494BD9e`, reputation `0x8004B663056A597Dffe9eCcC1965A193B7388713` |

## What's real, what isn't

| | Status |
|---|---|
| Treasury: commit, venue check-in, cancel, finalize, settle, passports, withdraw | **Real.** 33 Foundry tests |
| Accounts | **Real.** WebAuthn passkeys (browser) and Android Keystore keys, verified with OpenZeppelin `P256` / `WebAuthn` through Monad's precompile (0x0100). WebAuthn requires user verification |
| AI agent budgets | **Real.** The owner signs a cap and an expiry bound to the agent's ERC-8004 identity; the agent can only reserve within it. Check-in, cancel, withdraw stay owner-only |
| ERC-8004 show-up reputation | **Real, on the official registries.** On settlement of an agent's reservation, the treasury posts 100 (attended) or 0 (no-show); `getSummary(agentId, [treasury], "showUp", "anzen")` is the show-up rate. Fork test against Monad testnet; live run in [SUBMISSION.md](SUBMISSION.md) |
| Check-in evidence | **Real, with limits.** Issuer-signed venue code (45 s) plus the owner's signature. Proves a fresh code was signed with the passkey, not the exact location |
| OpenClaw | **Real.** `skills/anzen/` (SKILL.md + `anzen-agent` CLI), verified with a live OpenClaw agent (Gemini) |
| MCP | **Real.** `backend/anzen/mcp_server.py` (stdio), loaded by `.mcp.json`; same tools and limits as the skill. Tested with an MCP client |
| Show-up board | **Real.** `GET /agents` and `#/agents`: agents that owners bound a budget to, with `getSummary` from the registry |
| Asset | tUSD, a valueless test token |
| Fees | Paid by the service's relayer, which can delay a signed action but not forge or alter it |
| Not built | Synced passkeys for the Android app (device-bound keys), push notifications, iOS, multi-witness check-in, audit |

## Layout

```
contracts/   AnzenTreasury.sol, TestDollar.sol, Deploy.s.sol; tests incl. Erc8004Fork.t.sol (real registries)
backend/     ANZEN service (FastAPI): relayer, venue-code issuer, policy store (SQLite or Postgres), proofs,
             agent CLI (anzen/agent.py), MCP server (anzen/mcp_server.py), seeding, smoke run; serves web/
web/         ANZEN in the browser: passkeys (anzen.js), app (app.js); vectors.test.mjs, passkey.e2e.mjs
app/         ANZEN for Android (Kotlin, Compose, Hilt, CameraX, ZXing)
skills/      OpenClaw skill
scripts/     dev_stack.sh, warp.sh, device_e2e.py, agent_demo.sh, deploy_testnet.sh
index.py     Vercel entry point (service + web app)
```

### How an action flows

1. The client (browser, phone or agent) builds the exact bytes of the action, `abi.encode(TAG, chainId, treasury, ...)`.
   These are byte-for-byte identical in Solidity, Python, Kotlin and JavaScript, checked by shared golden vectors.
2. A passkey signs a WebAuthn assertion whose challenge is `sha256(bytes)`; an Android key signs `sha256(bytes)`
   directly; an agent signs an EIP-191 message for `commitByAgent` only.
3. The service simulates the call and returns the contract's reason if it would revert (nothing is broadcast), or relays it.
   The same signature always maps to the same transaction.
4. Clients track Submitted → Pending → Confirmed → **Verified** (contract state re-read). A timeout is "still pending",
   never success or failure.

## Run locally

Needs Foundry, `uv`, Node 20+, JDK 17 and the Android SDK (`. scripts/toolchain.env` puts them on PATH).

```bash
. scripts/toolchain.env
(cd contracts && forge build)
scripts/dev_stack.sh --fresh   # local fork of Monad testnet (real precompile + ERC-8004 registries) on :8545,
                               # contracts, service + web app on http://localhost:8790, sample events
scripts/warp.sh 3600           # fast-forward the local chain
./gradlew :app:installDebug    # Android; the emulator reaches the host at 10.0.2.2:8790
```

## Tests

```bash
(cd contracts && forge test)                                  # 33
(cd contracts && MONAD_FORK=https://testnet-rpc.monad.xyz forge test --match-contract Erc8004Fork)  # real registries
(cd backend && uv run pytest)                                 # 14, real contracts on a local chain + MCP over stdio
(cd web && npm install && npm test && npm run e2e)            # encodings + passkey browser end to end (local stack)
./gradlew :app:testDebugUnitTest :app:lintDebug               # 22 Android unit tests, lint
python3 scripts/device_e2e.py emulator-5554 all               # 11 Android journeys (local stack)
(cd backend && ANZEN_URL=https://anzen-shonko.vercel.app uv run python -m anzen.smoke)  # live testnet lifecycle
```

## AI agent (OpenClaw)

```bash
skills/anzen/scripts/anzen-agent init        # agent key + ERC-8004 identity; prints anzen://delegate?agent=…&id=…
# open the link in the ANZEN app (Android) or https://anzen-shonko.vercel.app/#/delegate?agent=…&id=…&cap=20&days=7 (web)
ANZEN_OWNER=0x<account key> skills/anzen/scripts/anzen-agent events
ANZEN_OWNER=0x<account key> skills/anzen/scripts/anzen-agent reserve <id>
skills/anzen/scripts/anzen-agent reputation  # show-up rate written by the treasury
GEMINI_API_KEY=… ANZEN_OWNER=0x… scripts/agent_demo.sh "find me something this week under 10 and book it"
ANZEN_OWNER=0x… claude                       # Claude Code picks up .mcp.json: tools setup/events/reserve/budget/reputation
```

## Solana

The `anzen_core` program (`programs/anzen_core`, Anchor 0.32) carries the same rules as the Monad contract. Toolchain:
Agave 4.x (`solana`, `solana-test-validator`, `cargo-build-sbf`) and rustup in `~/.cargo/bin`.

```
cd programs/anzen_core && cargo-build-sbf          # → target/deploy/anzen_core.so (~420 KB)
cd backend && uv run pytest tests/test_solana.py  # full lifecycle on a local validator with the real program (~1 min)
scripts/solana_local.sh                            # validator + program + service + web on :8790 (chain id 104)
scripts/deploy_solana.sh                           # devnet: deploy/upgrade the program, set up mint + config once
ANZEN_URL=… uv run python -m anzen.smoke           # live run: publish, waitlist, x402 agent, give-back, check-in, settle
```

The service picks the chain from its deployment file: `contracts/deployments/solana-<chain id>.json` (103 = devnet)
means Solana, `<chain id>.json` means EVM. Same HTTP API and the same signed messages on both; on Solana an
"address" slot holds a key's first 20 bytes.

## Deploy

- **Live events for judging:** `ANZEN_URL=https://anzen-shonko.vercel.app uv run python -m anzen.seed showcase`
  (commitments stay open 19 days).
- **Contracts:** `scripts/deploy_testnet.sh` (reads `.env.local`). Set `ERC8004_IDENTITY` / `ERC8004_REPUTATION` to
  wire the registries and `TOKEN_OWNER` to the relayer that will run the faucet.
- **Service + web:** Vercel project `anzen-shonko` from this repo (`index.py`, `requirements.txt`, `.vercelignore`).
  Environment: `ANZEN_CHAIN_ID`, `ANZEN_RPC_URL`, `ANZEN_PUBLIC_RPC`, `ANZEN_RELAYER_KEY`, `ANZEN_ISSUER_KEY`,
  `ANZEN_DATABASE_URL` (Postgres; relayer nonces are serialized across instances with an advisory lock).
  A Dockerfile is included for any container host.

## Known limits

One relayer and one check-in issuer. A venue code works for anyone who can see it for 45 s. The service lists events
from its own database (fine for a pilot; index logs beyond that). Android keys can't be exported or recovered.
Not audited.
