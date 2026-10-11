<p align="center"><img src="web/brand/panda-happy.webp" width="150" alt="The ANZEN panda"></p>

# ANZEN

**Your agent can book it. Only you can show up.**

Refundable reservations for real-world events, signed with a passkey, settled on Monad. AI agents can reserve for
you inside a budget you approve, and earn a public **show-up reputation** under ERC-8004 that nobody can fake,
because every point comes from a real person checking in at a real venue.

**Live:** **[anzen-shonko.vercel.app](https://anzen-shonko.vercel.app)** · Monad testnet · Track: Trust, Identity & AI Infrastructure

---

## Why this matters

- **A free RSVP is worth nothing.** Free events lose a median 28% of sign-ups to no-shows (PheedLoop, 1,070 events);
  40–60% is common. A refundable deposit fixes it: $3 cut no-shows from 38% to 14%, and a $100 refundable deposit
  gave a 90% show rate. Nobody does it because holding and refunding deposits by hand is painful.
- **Bots take the rest.** Ticket scalping rose 291% in a year; 83% of ticketing traffic is automated (DataDome 2026).
- **Now AI agents book for us.** Nothing stops an agent from holding a thousand places, and agent reputation today
  is mostly fake: in ERC-8004 registries, 73–91% of reviewers show coordinated Sybil behaviour and feedback is
  "rarely grounded in verifiable interactions" ([arXiv 2606.26028](https://arxiv.org/abs/2606.26028)).

## What ANZEN does

1. **Commit.** Reserve a place with a small refundable commitment. Your account is a **passkey** (Face ID, Touch ID,
   Windows Hello, or a phone's secure chip): no wallet, no seed phrase. The prompt names exactly what you sign.
2. **Show up.** The organizer's screen shows a code that rotates every 35 s and dies after 45 s. Scan it with your
   phone camera, approve with your passkey. The contract checks the issuer's signature, yours, and freshness.
3. **Unlock.** When check-in closes the treasury applies the published rules: your commitment back, an equal share
   of no-shows' commitments, and a supporter passport recorded onchain.

**Your AI agent** ([OpenClaw](skills/anzen/SKILL.md), or any MCP client such as Claude or Cursor) can find events and reserve places for you, inside a cap
and expiry you approve with your passkey. It **can't** check in, cancel, withdraw or raise its own budget, and the
contract enforces that. **Money can be delegated; presence can't.**

## What no one else has

| | |
|---|---|
| **Sybil-proof agent reputation** | When an agent's reservation settles, the **treasury contract itself** writes feedback to Monad's official ERC-8004 reputation registry: 100 if the human showed up, 0 if not. `getSummary(agent, [treasury])` is the agent's show-up rate, and every data point cost a real refundable commitment and a verified check-in. A fake reviewer can't write it, and an agent can only raise it by booking things its human actually attends. |
| **Presence is not delegable** | Every rival in this track gives agents budgets. ANZEN is the one where the agent's budget ends at the venue door: physical check-in needs the owner's passkey and a 45-second code. |
| **Real passkeys, verified onchain** | WebAuthn assertions (OpenZeppelin `WebAuthn.verify`, user verification required) and Android Keystore keys, both through Monad's P-256 precompile. A judge can sign up in the browser in ten seconds. |
| **Any agent, one protocol** | An OpenClaw skill and an MCP server ([`.mcp.json`](.mcp.json)) expose the same five tools: setup, events, reserve, budget, reputation. Neither has a check-in tool, because the contract wouldn't accept one. |
| **Public show-up board** | [`#/agents`](https://anzen-shonko.vercel.app/#/agents) ranks every agent with a budget by the show-up rate the treasury wrote for it. Hosts can see which agents book people who actually come. |
| **Real, hosted, tested** | Live on Monad testnet, with proof transactions below. 33 contract tests, a fork test against the **real** ERC-8004 registries, 14 service tests on a real chain (incl. the MCP server over stdio), 22 Android unit tests, browser passkey tests, 11 on-device journeys, and concurrency tests on the live service. |

## Try it in two minutes

1. Open **[anzen-shonko.vercel.app](https://anzen-shonko.vercel.app)** and **Create passkey account**.
2. **Get 100 test dollars** (tUSD, no value).
3. **Host an event**: choose the *Demo* schedule (commitments close in 4 min, check-in opens at 5).
4. **Commit** to it with your passkey.
5. When check-in opens, **Open check-in desk** on your laptop and scan the QR with your phone's camera.
   On the phone, sign in with the same passkey (iCloud / Google Password Manager sync it), then **Sign check-in**.
6. After check-in closes, **Apply outcome**: your 5 tUSD comes back with a supporter passport. **View proof** lists
   every transaction.

**With an AI agent:** `skills/anzen/scripts/anzen-agent init` registers the agent's ERC-8004 identity and prints an
approval link. Open it, approve a budget with your passkey, then ask OpenClaw *"find me something this week under
10 and book it"* ([scripts/agent_demo.sh](scripts/agent_demo.sh)). In Claude Code, the repo's `.mcp.json` loads the
same tools: `ANZEN_OWNER=0x<your account key> claude`, then *"reserve me a seat at the builders' night"*.

## How it works

```
 Phone / browser                  ANZEN service (Vercel)                 Monad testnet
 ───────────────                  ──────────────────────                 ─────────────
 passkey signs ──── action ────▶  simulates, relays (pays gas) ──────▶   AnzenTreasury
 sha256(TAG, chain, contract,     can't forge or alter a signature        verifies P-256 / WebAuthn
        params)                   issues 45-s venue codes                 (precompile 0x0100)
                                  stores published terms (Neon)           holds commitments
 OpenClaw agent ─ EIP-191 ─────▶  agent-commit within budget ────────▶   commitByAgent: cap, expiry
                                                                          settle → ERC-8004 reputation
                                                                            (client = treasury)
```

The terms each organizer publishes are hashed into the event onchain, so they can't change after people commit.
The service never decides an outcome; the contract does.

## Proof on Monad testnet

Treasury [`0xb8775046…5d8f`](https://testnet.monadexplorer.com/address/0xb87750460d648B0D7C953B42aE6FcDB9245F5d8f) ·
test dollar [`0x5799a…Dff73`](https://testnet.monadexplorer.com/address/0x5799ab1E835225a824C550Fe3797Ce7a846Dff73) ·
ERC-8004 identity [`0x8004A818…BD9e`](https://testnet.monadexplorer.com/address/0x8004A818BFB912233c491871b3d84c89A494BD9e) /
reputation [`0x8004B663…8713`](https://testnet.monadexplorer.com/address/0x8004B663056A597Dffe9eCcC1965A193B7388713)

A full lifecycle run through the live service is in [SUBMISSION.md](SUBMISSION.md#proof-one-full-lifecycle-on-monad-testnet),
and anyone can repeat it: `ANZEN_URL=https://anzen-shonko.vercel.app uv run python -m anzen.smoke`.

## Research it builds on

- *Human Challenge Oracle* ([arXiv 2601.03923](https://arxiv.org/abs/2601.03923)): short, identity-bound, time-limited
  challenges make faking N identities cost N times more. Our 45-second venue code bound to one passkey applies that to
  physical presence.
- *Can Trustless Agents Be Trusted?* ([arXiv 2606.26028](https://arxiv.org/abs/2606.26028)): ERC-8004 reputation needs
  feedback grounded in verifiable interactions and trusted clients. Ours comes from one client, the treasury, and only
  from settled, verified outcomes.
- *A Formal Analysis of Agent Payment Protocols* ([arXiv 2609.00060](https://arxiv.org/abs/2609.00060)): delegated
  authority must match its economic effect. One owner signature binds agent, ERC-8004 identity, cap and expiry.

## What's here

```
contracts/  AnzenTreasury.sol (+ tests, fork test vs real ERC-8004), TestDollar.sol
backend/    ANZEN service: relayer, venue-code issuer, policy store, proofs, agent CLI, MCP server
web/        ANZEN in the browser (passkeys), served by the service
app/        ANZEN for Android (Kotlin/Compose, Keystore keys, CameraX QR)
skills/     OpenClaw skill
docs/       SPEC.md: the full protocol vision
```

Build, run and test everything: **[BUILD.md](BUILD.md)**. What's honest about the limits: test dollars only, a single
check-in issuer and relayer, a check-in proves a fresh code was signed with your passkey (not your exact location), not audited.
