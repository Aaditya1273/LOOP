# ANZEN · Shonko

**Your agent can book it. Only you can show up.**

Refundable commitments for real-world events, bound to the phone's secure hardware, with AI agents that can
reserve for you but can never fake your presence. Built on Monad's native P-256 verification.

**Track:** Trust, Identity & AI Infrastructure

---

## The problem

A free RSVP costs nothing and isn't tied to a real person, so it's worth nothing.

- **Free events lose a median 28% of RSVPs to no-shows**, and 40–60% is common; paid events lose 17%
  (PheedLoop, 1,070 events). Creators over-book, cater for ghosts, and turn real fans away.
- **A refundable deposit fixes most of it.** A $3 deposit cut no-shows from 38% to 14%; a $100 refundable deposit
  produced a 90% show rate at a 37signals event. But collecting, holding and refunding deposits by hand is painful,
  so almost nobody does it.
- **Bots fill the gap.** Ticket scalping rose 291% in a year and 83% of ticketing traffic is automated (DataDome
  2026). One ring used thousands of fake accounts to take 379,000 tickets.
- **AI agents make it worse.** They can now book on our behalf, and nothing stops one agent from holding a thousand
  places. ERC-8004 reputation, the standard answer, is mostly Sybil today: 73–91% of reviewers show coordinated Sybil
  behaviour and feedback is "rarely grounded in verifiable interactions" (arXiv 2606.26028).

## What ANZEN does

1. **Commit.** Reserve a place with a small refundable commitment. Your account is a P-256 key in your phone's
   secure hardware, so there's no seed phrase. Every action shows a system prompt naming exactly what you approve
   ("Commit 5 tUSD to Zine launch · Monad testnet").
2. **Show up.** At the venue the organizer's phone shows a code that rotates every 35 s and expires in 45 s,
   signed by the event's issuer. You scan it and your phone's key signs it. The contract checks both signatures,
   the window and the freshness.
3. **Unlock.** When check-in closes, the treasury applies the rules the organizer published: your commitment back,
   plus an equal share of no-shows' commitments if the event says so, and a supporter passport recorded onchain.
   Cancelled events refund everyone.

**And your AI agent.** With the OpenClaw skill, your agent can find events and reserve places for you, inside a
budget you approved with your fingerprint (a cap and an expiry, enforced by the contract). It can't check in,
cancel, withdraw or raise its own budget. **Money can be delegated; presence can't.**

## Why it's real, not a mock

- **Live AI agent.** A real OpenClaw agent (Gemini) read our skill and was asked, in plain English, to "find
  something cool in Bengaluru under 10 dollars and book it". It picked the right event and reserved it onchain
  within its budget. Asked to "check me in remotely", it refused, and the contract would have rejected it anyway.
- **Real cryptography.** Signatures are verified with OpenZeppelin `P256.verify`, which uses Monad's precompile at
  `0x0100`. Tests use real P-256 signatures; there's no mock verifier. Low-s is enforced. App, backend and contract
  share byte-exact golden test vectors.
- **Tested end to end.**
  - Contract: 26 Foundry tests: lifecycle, split maths, forged, replayed and expired signatures, foreign venue
    codes, agent caps, expiry and revocation, and "agent can't check in".
  - Service: 12 HTTP tests on a real chain.
  - App: 22 unit tests.
  - Device: 11 journeys driven on an Android emulator with the real system PIN prompt.
- **Honest about limits.** The app says what a check-in proves (this phone signed a fresh code from this event's
  desk) and what it doesn't (exact location). Success copy only appears after the contract state is re-read and
  matches; timeouts are "still pending", never success.

## Proof: one full lifecycle on Monad testnet

Run by `backend/anzen/smoke.py` against the live deployment, with real keys and real time (no fast-forwarding). Alice
committed herself; Bob's AI agent committed for him inside the budget Bob signed. Alice checked in with a fresh
venue code; Bob didn't come. Result: Alice 105 tUSD (5 back + Bob's 5) and passport no. 1; Bob 95 tUSD.

| Step | Transaction |
|---|---|
| faucet: 100 tUSD to alice | [0x30ccd11f…](https://testnet.monadexplorer.com/tx/0x30ccd11f6cda4df41133aaa14b1d2921d3a9a86a32fcb2223b9b1e80bdc234cb) |
| faucet: 100 tUSD to bob | [0x34c56319…](https://testnet.monadexplorer.com/tx/0x34c563191ae287421c3d80784db84388b725a729c099f5dd53e989bac32c3f75) |
| organizer publishes event | [0xe8a3b36f…](https://testnet.monadexplorer.com/tx/0xe8a3b36f16ce87a08a0759cf02d5586c2ef63cb40947809337082afe4a1a0e9f) |
| alice commits 5 tUSD (her key) | [0x94b6448e…](https://testnet.monadexplorer.com/tx/0x94b6448ea44f7d4957a80568940fae6b0ce12f073857a9bb41b948bf81682016) |
| bob approves agent budget 10 tUSD | [0x47c5f098…](https://testnet.monadexplorer.com/tx/0x47c5f098c7c361ad435104567c5d61d60ccf207c5e4cdf108cf887a1791d50c0) |
| bob's agent commits 5 tUSD for bob | [0xcaac50af…](https://testnet.monadexplorer.com/tx/0xcaac50afabb375af2b1ffdcc3f45ee6afacde7852d3d5cffb0dc7e7f45d64e5a) |
| alice checks in (fresh venue code) | [0x2f38458f…](https://testnet.monadexplorer.com/tx/0x2f38458f0041f20d65d5b833895fa066b70f0477d6cfa9a55e61247e83d550b7) |
| alice's outcome applied | [0x19b434c7…](https://testnet.monadexplorer.com/tx/0x19b434c74f584629d7bbbc08df45383d258d7f20a0377c93fc0fb2d84f59452b) |
| bob's outcome applied (no-show) | [0x192ae28a…](https://testnet.monadexplorer.com/tx/0x192ae28aa221a57f52b39488f1cef843b72680482f4a61932772018cb875e143) |

## Why Monad

- **P-256 is native.** Phone hardware keys verify onchain cheaply, so the account is the phone itself.
- **Check-in needs to be instant.** A 45-second code is only practical with fast blocks: a door queue can't wait
  for confirmations.
- **Many small payments.** Fees don't eat a 5-dollar commitment.

## Business model

Creators and venues pay a small fee per checked-in attendee (they keep the show-rate upside). Forfeited
commitments can fund the event instead of vanishing. Agent platforms integrate the skill so their users' agents can
book real-world things without being a Sybil vector.

## Research grounding

- *Human Challenge Oracle* (arXiv 2601.03923): time-bound, identity-bound challenges make faking N identities cost
  N times more. Our rotating venue code bound to one hardware key is this, applied to physical presence.
- *Can Trustless Agents Be Trusted?* (arXiv 2606.26028): agent reputation fails without verifiable interactions.
  Check-ins and settlements are exactly that ground truth.
- *A Formal Analysis of Agent Payment Protocols* (arXiv 2609.00060): delegated authority must match its economic
  effect. Our delegation binds agent, cap, expiry, event and amount in one signature.

## What's built

| | |
|---|---|
| Contracts | `AnzenTreasury.sol` (events, commitments, check-in, settlement, passports, agent budgets), `TestDollar.sol` |
| Service | FastAPI: policy store (terms hash fixed onchain), relayer (pays gas, can't forge), check-in issuer, proofs |
| App | Shonko for Android (Kotlin/Compose): 18 screens, design system with light, dark and high-contrast themes |
| Agent | OpenClaw skill `skills/anzen` + `anzen-agent` CLI |
| Network | **Monad testnet (10143)**: treasury [`0xB6a9…2A04`](https://testnet.monadexplorer.com/address/0xB6a9276948012c616e2cFd4299a05Ff985b62A04), tUSD [`0x0316…5020`](https://testnet.monadexplorer.com/address/0x03164E72ffEd3293350A9b66e40aF00E59645020) |

**Not built yet:** synced passkeys/WebAuthn (we use device-bound Keystore keys), push notifications, iOS,
ERC-8004 feedback from settlements, multi-witness check-in. Test dollars only; not audited.

---

## Demo video script (3:00)

| Time | Shot | Say |
|---|---|---|
| 0:00 | Stat cards | "A free RSVP is worth nothing. A quarter of people don't show. Bots take the rest. And now AI agents book for us." |
| 0:15 | Phone: onboarding → Create key | "Shonko's account is a key in your phone's secure chip. No seed phrase." |
| 0:30 | Explore → event → Review commitment → PIN prompt naming the amount | "Reserve with a refundable 5 tUSD. The prompt says exactly what you sign." |
| 0:50 | Pending → Confirmed → Verified | "We only say done after the contract confirms it." |
| 1:00 | Terminal: OpenClaw "find something in Bengaluru under 10 and book it" → reply | "My OpenClaw agent books for me, inside a budget I approved on my phone." |
| 1:25 | Phone: agent link → approve 20 tUSD → Home "15 of 20 left" | "It can spend that, nothing more. The contract enforces the cap." |
| 1:45 | OpenClaw "check me in remotely" → refusal | "But it can never show up for me. Money can be delegated; presence can't." |
| 2:00 | Organizer desk QR rotating → attendee scans → "You're checked in" | "At the door: a code that dies in 45 seconds, signed by your phone." |
| 2:25 | Outcome: commitment returned + passport + proof screen | "Check-in closes, the treasury pays back, and you get a supporter passport. Every step is verifiable." |
| 2:45 | Explorer link on Monad testnet | "Live on Monad testnet, using its native P-256 verification. ANZEN: your agent can book it, only you can show up." |
