# Building and running ANZEN · Shonko

Stage 1 of the protocol described in [README.md](README.md): the **Creator IRL Drop**, end to end, as an Android
app (Shonko), a Solidity treasury for Monad, and a small service that relays signed actions and issues venue
check-in codes.

## What's real, what isn't

| | Status |
|---|---|
| Treasury contract: commit, check-in, cancel, finalize, settle, passports, withdraw | **Real.** `contracts/src/AnzenTreasury.sol`, 26 Foundry tests |
| AI agent budgets | **Real.** The owner's key signs a cap and expiry for an agent's EVM key (`delegate`); the agent can only reserve within it (`commitByAgent`). Check-in, cancel and withdraw stay owner-only, enforced by the contract |
| OpenClaw skill | **Real.** `skills/anzen/` (SKILL.md + `anzen-agent` CLI). The CLI path is tested end to end; a live OpenClaw run needs your model key (see below) |
| P-256 signature verification | **Real.** OpenZeppelin `P256.verify`: Monad's precompile at `0x0100`, Solidity fallback elsewhere. Tests use real P-256 signatures, no mock verifier |
| Account keys | **Real.** Android Keystore P-256 key; every use needs biometric or screen lock, and the prompt names the action |
| Passkeys / WebAuthn | **Not used.** Keys are device-bound Keystore keys verified as raw P-256 signatures, not synced passkeys or WebAuthn assertions (those need a domain with Digital Asset Links) |
| Check-in evidence | **Real, with limits.** Issuer-signed venue code (valid 45 s) + participant signature, checked onchain. Shows this phone signed a fresh code; does **not** prove location |
| Asset | `tUSD`, a valueless test token (`TestDollar.sol`). No real stablecoin or equity |
| Network fees | Paid by the service's relayer. It can delay or refuse to submit; it cannot forge or alter a signed action |
| Push notifications | **Not configured.** Check-in reminders are local alarms; outcomes appear on the next app open |
| Monad testnet deployment | **Live.** Treasury `0xB6a9276948012c616e2cFd4299a05Ff985b62A04`, tUSD `0x03164E72ffEd3293350A9b66e40aF00E59645020` (`contracts/deployments/10143.json`). P-256 precompile verified there with a real signature. Reproduce the full lifecycle: `ANZEN_URL=… uv run python -m anzen.smoke` |
| Encrypted commitments (BTX), ERC-8004 agents, real equities | **Not built** (spec stages 5–7) |
| Disputes | Offchain, with the organizer's published contact. The onchain result is final |

## Layout

```
contracts/   Foundry: AnzenTreasury.sol, TestDollar.sol, Deploy.s.sol, tests
backend/     ANZEN service (FastAPI + SQLite + web3.py): policy store, relayer, check-in issuer, proofs
app/         Shonko for Android (Kotlin, Compose, Hilt, CameraX, ZXing)
  core/      Messages (byte-exact signing preimages), DeviceKey, Api, Repository, Rules (domain logic)
  ui/        theme (tokens), components (design system), screens, Root (navigation)
skills/      anzen/: OpenClaw skill and the agent CLI it drives (backend/anzen/agent.py)
scripts/     dev_stack.sh (local devnet), warp.sh (fast-forward chain time), device_e2e.py (on-device journeys)
```

### How an action flows

1. The app builds the exact bytes to sign (`core/Messages.kt`) from the terms it shows. These match
   `abi.encode(...)` in the contract byte for byte (golden vectors in `MessagesTest.kt` and `backend/tests/test_messages.py`).
2. The system prompt shows the action ("Commit 5 tUSD to “Rooftop listening session” · Monad testnet") and the
   Keystore key signs it (SHA256withECDSA, normalized to low-s).
3. The service simulates the call; if it would revert, nothing is broadcast and the contract's reason is returned.
   Otherwise it relays it and returns the transaction hash. The same signature always returns the same transaction.
4. The app tracks it: Submitted → Pending → Confirmed → **Verified** (contract state re-read and matches). A timeout is
   shown as *Still pending*, never as success or failure. Success copy and haptics appear only at Verified.

## Run it locally

Needs Foundry, `uv`, JDK 17 and the Android SDK (`. scripts/toolchain.env` puts them on PATH).

```bash
. scripts/toolchain.env
(cd contracts && forge build)
scripts/dev_stack.sh           # anvil (osaka, P-256 precompile) + contracts + service on :8790 + 3 sample events
./gradlew :app:installDebug    # emulator reaches the host at 10.0.2.2:8790
```

The phone needs a screen lock (the key can only be created behind one). Helpers for the local chain:

```bash
scripts/warp.sh 3600                                            # move chain time forward
(cd backend && uv run python -m anzen.seed quick "My event")    # event whose check-in opens in 15 min
(cd backend && uv run python -m anzen.seed checkin <id>)        # adb command that opens a live venue code
```

## AI agent (OpenClaw)

Money can be delegated; presence can't. An agent gets its own EVM key and asks the owner for a budget:

```bash
skills/anzen/scripts/anzen-agent init            # prints anzen://delegate?agent=0x…&cap=20&days=7
# open that link on the phone → Shonko shows the request → approve with fingerprint/PIN
ANZEN_OWNER=0x<account key> skills/anzen/scripts/anzen-agent events
ANZEN_OWNER=0x<account key> skills/anzen/scripts/anzen-agent reserve <id>   # refused past the cap
```

With OpenClaw (one isolated turn, this repo as its workspace; your normal agents are untouched):

```bash
GEMINI_API_KEY=… ANZEN_OWNER=0x… scripts/agent_demo.sh "find me something in Bengaluru under 10 and book it"
```

## Tests

```bash
(cd contracts && forge test)                      # 26: lifecycle, agent budgets, split math, invalid/high-s/replayed signatures,
                                                  #     expired/foreign/forged venue codes, capacity, phases, double settle
(cd backend && uv run pytest)                     # 12: full HTTP lifecycle on anvil with the real contracts, failure
                                                  #     modes, idempotency, precompile present, shared golden vectors
./gradlew :app:testDebugUnitTest :app:lintDebug   # 22 unit tests (encoding, DER/low-s, state machine, validation)
python3 scripts/device_e2e.py emulator-5554 all   # on-device journeys
```

`device_e2e.py` drives the real app on an emulator against the local devnet, with real signatures and the system PIN
prompt: onboarding and key creation, faucet, discovery → details → commitment authorization, cancelled authorization
(nothing funded, retryable), approving an agent budget from its link and the agent reserving within it, session restore after restart, malformed / expired / fresh venue codes, settlement and
passport with its proof, wizard validation and draft saving, publishing an event from the wizard and running its
check-in desk QR, and the offline error state. It sets PIN 1111 for the run and clears it afterwards.
**Clearing the screen lock deletes the app's key**, as Android does for auth-bound Keystore keys.

## Deploy to Monad testnet

`scripts/deploy_testnet.sh` does all of this from `.env.local` (`PRIVATE_KEY`, `ANZEN_ISSUER_KEY`). By hand:

```bash
cd contracts
forge script script/Deploy.s.sol --rpc-url https://testnet-rpc.monad.xyz --private-key $RELAYER_KEY --broadcast
# writes deployments/10143.json
cd ../backend && cp .env.example .env   # fill in RELAYER_KEY (funded with testnet MON) and a separate ISSUER_KEY
set -a; . ./.env; set +a; uv run python -m anzen.app
./gradlew :app:assembleRelease -Panzen.backendUrl=https://<your service>
```

Release builds refuse plain HTTP; the service must be served over HTTPS.

## Design

Tokens live in `app/.../ui/theme/Theme.kt`: warm ivory and ink, one lime accent, semantic status colors whose text
steps pass WCAG AA, light, dark and high-contrast (Android 14 contrast setting) variants, three radii, one easing
curve, and durations of 140/240/300 ms (off when the system removes animations). Money and passports sit on the ink
"sealed" surface; the floating tab bar is the only chrome layer (Android has no Liquid Glass, so it's a near-opaque
elevated material). Status is never color alone: every pill carries a glyph shape and a label. The mark is a ring left
open and closed by a lime seal, as a vector adaptive icon, a themed monochrome icon, a notification icon and the
launch screen.

## Known limitations

- One operator: a single relayer and a single check-in issuer key. A venue code works for anyone who can see it for 45 s.
- The service lists events from its own database (one RPC call per event to read state): fine for a pilot, index logs beyond that.
- The key can't be exported or recovered. Losing the phone or removing its screen lock loses the account. A withdraw
  function exists in the contract but has no screen yet.
- Event artwork is generated (six styles), not uploaded: there's no image hosting.
- Portrait only; no tablet layout.
- Not audited. A testnet demo is not a production security review.
