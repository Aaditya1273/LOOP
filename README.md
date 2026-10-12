<p align="center"><img src="web/brand/panda-happy.webp" width="150" alt="The ANZEN panda"></p>

<h1 align="center">ANZEN</h1>

<p align="center"><b>Proof of Showing Up: the last signal AI can't fake.</b></p>

<p align="center">
<i>Your agent can book it. Only you can show up.</i>
</p>

<p align="center">
Bonded bookings · Verifiable presence · Agent-safe reservations · Private reliability reputation<br>
Built on <b>Solana</b>
</p>

---

## TL;DR

Every business that sells time (a table, a class, an appointment, a seat) loses money to people who book and never come. Now AI agents are booking for us, and they make the problem worse. They hoard slots, rack up fees their owners never approved, and get banned by the platforms they hit.

**ANZEN is the trust layer between bookings and the physical world.**

- **Businesses** get every empty seat turned back into revenue, automatically, and never have an argument about a fee again.
- **People** get bookings that protect their money, can never be wrongly charged, and a portable **Show-Up Score** that unlocks perks.
- **AI agents** get an **Agent Pass**: a human-approved, spend-capped, bonded credential that lets venues safely say *yes* to them.

Money can be delegated. **Presence can't.** ANZEN is built on that rule.

---

## Contents

1. [The problem](#1-the-problem)
2. [Why now](#2-why-now)
3. [What ANZEN is](#3-what-anzen-is)
4. [How it works](#4-how-it-works)
5. [Architecture: four layers](#5-architecture-four-layers)
6. [Everything is automatic](#6-everything-is-automatic)
7. [The consumer experience](#7-the-consumer-experience)
8. [Simple outside, powerful inside](#8-simple-outside-powerful-inside)
9. [Who it's for](#9-who-its-for)
10. [AI agent integration](#10-ai-agent-integration)
11. [Security model](#11-security-model)
12. [Privacy model](#12-privacy-model)
13. [Business model](#13-business-model)
14. [Market and competition](#14-market-and-competition)
15. [Research foundations](#15-research-foundations)
16. [Technical design](#16-technical-design)
17. [Status: built, building, roadmap](#17-status-built-building-roadmap)
18. [Try it](#18-try-it)
19. [Honest limits](#19-honest-limits)
20. [Sources](#20-sources)

---

## 1. The problem

### 1.1 No-shows are a silent tax on every business that sells time

| Sector | What happens |
|---|---|
| **Healthcare** | An August 2026 MGMA poll found a third of medical groups saw **higher** no-show rates than in 2025. Practices had already named no-shows their top patient-access priority for the year. A widely cited estimate puts the cost to US healthcare at **~$150B a year**, about $200 per missed visit. |
| **Fitness studios** | A "full" class with empty bikes is a capacity-allocation failure, not a demand problem. Studio guidance is clear that the goal is filling spots, not collecting fees. |
| **Restaurants** | Bots hoarding tables pushed New York (2024) and Florida to pass laws banning unauthorized reservation resale. One New York chef estimated he loses around 10% of tables a night to bots. |
| **Free events and meetups** | Organizers routinely report that 30–50% of RSVPs never arrive, and they plan around it with wasted food, rooms and speakers. |
| **Training and certification** | Attendance proof is weak. Institutions have had to fight fake fingerprints and proxy marking, and in one regulator's case moved from fingerprint to face-based authentication. |

### 1.2 The tools businesses use today don't work

Businesses fight no-shows with two tools, and both fail:

- **Reminders** get ignored.
- **Penalties** are manual and adversarial, and they're often wrong. One studio customer publicly complained of being charged a no-show fee **three times in a month for classes they actually attended**.

Nobody **proves presence**, **settles money automatically**, and **refills the seat in seconds**, all together.

### 1.3 AI agents are now booking the physical world, and breaking it

- In September 2026, a New York investor's personal AI agent hit a reservation platform **hundreds of times an hour** hunting for a table, and his account was banned. The platform states it doesn't permit unapproved third-party agents.
- Another user's agent **booked a restaurant he never asked for**, one with a 100% cancellation charge, leaving a $200 fee no one approved.
- Industry leaders say restaurants won't hand control to a crowd of bots bargaining with each other. One owner warned the experience is turning into a game.

Venues face an impossible choice: **ban all agents** and lose the customers who use them, or **let them in** and accept hoarding, no-shows and chaos. **No infrastructure exists in between.**

### 1.4 Agent reputation today is mostly fake

The first large empirical study of ERC-8004 agent registries (arXiv 2606.26028) found widespread coordinated Sybil behaviour among reviewers. Feedback is rarely grounded in verifiable interactions. An agent's "rating" today means almost nothing.

---

## 2. Why now

| Shift | Evidence |
|---|---|
| **Personal agents went mainstream in 2026** | OpenClaw became the most-starred project on GitHub (350k+ stars by May 2026). Instinct raised $350M; Meta's Muse agent passed 2M downloads. |
| **Agents are a security risk** | In early 2026, researchers found 341 malicious OpenClaw skills on ClawHub, 335 of them in the "ClawHavoc" campaign, stealing crypto wallets and credentials. Users need **hard limits** agents can't bypass. |
| **Agents can now pay natively** | x402 (HTTP 402 + stablecoins) has processed 180M+ transactions and is referenced by Cloudflare, Stripe and AWS. The Solana Foundation is a core backer. |
| **Passkeys verify onchain** | Solana's SIMD-0075 precompile verifies P-256 / WebAuthn signatures natively, so Face ID can sign a contract action with no wallet and no seed phrase. |
| **Encrypted compute is live** | Arcium's Mainnet Alpha brings computation over fully encrypted data to Solana, which makes **private** reputation possible. |
| **Regulators are moving** | Anti-bot reservation laws in New York and Florida show demand for booking integrity. |

---

## 3. What ANZEN is

ANZEN is **B2B2C infrastructure with a native agent layer**:

```
                  ┌──────────────────────────────────────────┐
                  │                 ANZEN                    │
                  │  Bonded bookings · Presence proofs ·     │
                  │  Agent Passes · Private Show-Up Score    │
                  └───────┬───────────────┬──────────────┬───┘
                          │               │              │
                 ┌────────▼──────┐ ┌──────▼──────┐ ┌─────▼──────────┐
                 │  BUSINESSES   │ │   PEOPLE    │ │   AI AGENTS    │
                 │ studios,      │ │ book, show  │ │ OpenClaw,      │
                 │ clinics,      │ │ up, build a │ │ Claude (MCP),  │
                 │ restaurants,  │ │ reputation  │ │ any x402 agent │
                 │ events,       │ │             │ │                │
                 │ institutes    │ │             │ │                │
                 └───────────────┘ └─────────────┘ └────────────────┘
                   pay: SaaS +       free; earn        pay: per
                   recovered         perks and         verified
                   revenue share     waived deposits   booking
```

**Three promises:**

1. **To businesses:** never lose a seat.
2. **To people:** never be wrongly charged.
3. **To the agent economy:** book the real world safely.

---

## 4. How it works

> Sections 4–12 describe the full design. What runs today, and what is still being built, is listed in [Status](#17-status-built-building-roadmap).

### 4.1 The person

1. **Approve once.** Face ID (passkey) creates the account. No wallet, no seed phrase. Optionally approve an **Agent Pass**: "my assistant can book up to $150 this month, fitness and dining only, never with a cancellation fee above $20."
2. **Book.** Directly, or by asking an agent. A small **refundable commitment** is held. The person sees "$5 held", never "crypto".
3. **Get a smart nudge.** *"Leave in 10 minutes. Can't make it? Tap to release your seat and get your $5 back."*
4. **Show up.** Tap the phone on the venue's tag, or let staff scan you. One second.
5. **Get it back.** Refund the instant check-in confirms. Show-Up Score and streak update automatically.

### 4.2 The business

1. **Connect once.** Plug ANZEN into the existing booking system (calendar, scheduling or ticketing tool) with the SDK or an integration.
2. **Set the rules once.** Deposit size, cancellation window, waitlist behaviour, which agents to accept.
3. **Never touch it again.** Settlement, refill, disputes and agent gating all run automatically.
4. **Watch one number:** revenue recovered this month.

### 4.3 The agent

1. Finds a slot. The venue answers **`402 Payment Required: $5 commitment`**.
2. Checks the owner's **Agent Pass**: is it within cap, category, guardrails and expiry?
3. Pays the commitment through x402, inside the mandate.
4. **Cannot** check in, cancel into a fee, withdraw, or raise its own budget. The contract enforces this.
5. Earns **show-up reputation** for its owner only when the human physically arrives.

---

## 5. Architecture: four layers

### Layer 1: Agent Pass (the mandate)

A single passkey signature binds **owner → agent identity → spending cap → categories → guardrails → expiry**.

| Field | Example |
|---|---|
| Spend cap | $150 / month |
| Booking cap | Max 2 active bookings |
| Categories | Fitness, dining |
| Guardrails | Never accept a cancellation fee > $20 · never double-book the same time slot · never book more than 7 days out |
| Expiry | 30 days |

Every guardrail is checked **onchain** at booking time. A compromised agent is limited to its cap. It can never touch check-in, refunds or the mandate itself.

### Layer 2: Bonded bookings (x402 escrow)

| Outcome | What happens automatically |
|---|---|
| **Shows up** | Commitment refunded instantly. Score goes up. |
| **Cancels in time** | Refunded. Earns a **streak shield**. Seat goes to auto-refill. |
| **Late cancel** | Partial forfeit per published venue terms. |
| **No-show** | Venue compensated per published terms. Score goes down. |

Venue terms are **hashed onchain when the booking is made**, so they can't change after someone commits. ANZEN's service never decides an outcome. **The contract does.**

### Layer 3: Presence Oracle (confidence tiers)

| Tier | Method | Resists |
|---|---|---|
| 🥇 **Gold** | Phone taps a venue NFC tag, plus hardware attestation (e.g. Solana Seeker's Seed Vault and TEEPIN attestation layer) | Remote check-in, emulators |
| 🥈 **Silver** | Venue staff scan the guest's rotating, passkey-signed code | QR relay attacks |
| 🥉 **Bronze** | Guest scans the venue's rotating code (35 s rotation, 45 s validity) and signs with a passkey | Casual fraud |

Higher tiers carry more weight in the Show-Up Score. Each check-in requires a **fresh, identity-bound, time-limited challenge**, so faking N people costs N real people.

### Layer 4: Private reputation (encrypted compute)

The **Show-Up Score** is computed over encrypted history using Arcium. A venue learns only a predicate (*"verified human · score ≥ 85 · deposit waived"*). It never sees identity, location history, or where else someone has been. Presence data is sensitive by nature, so ANZEN treats it that way by design.

---

## 6. Everything is automatic

| Automation | What it does | Who it helps |
|---|---|---|
| **⚡ Auto-refill** | When someone releases a seat, waitlisted users' agents claim it **within seconds**, each inside its owner's own mandate. | Venue keeps the revenue; the waitlisted person gets in |
| **💸 Auto-settlement** | Show, cancel or ghost: money moves by published rule. Nobody chases payments. | Venue and person |
| **🧾 Auto-disputes** | "I was there!" The presence proof settles it instantly, in either direction. | Ends wrongful charges |
| **📈 Smart overbooking** | The reliability mix of a booked session predicts real attendance, so venues can overbook safely, like airlines but with verified data. | Venue |
| **🛡️ Agent gate** | Trusted Agent Passes are accepted automatically. Unknown bots are rejected automatically, with no captcha war. | Venue |
| **🔔 Context-aware nudges** | Reminders that offer *release and refund* instead of guilt. | Person, and the next person in line |
| **🎖️ Auto-credentials** | Attendance at courses, workshops and trainings issues a verifiable credential. | Institutes, learners, employers |

---

## 7. The consumer experience

### Show-Up Score: a credit score for reliability

It's portable across every ANZEN venue and **unlocks real value**:

| Score | Unlocks |
|---|---|
| 90+ | No deposits anywhere · priority waitlist · partner perks |
| 70–89 | Reduced deposits · standard waitlist |
| < 70 | Standard deposits; rebuild by showing up |

### Habit and engagement

- **🔥 Staked streaks.** *"Gym Mon/Wed/Fri, $3 each."* Every verified check-in returns the stake and grows a streak multiplier.
- **🛡️ Streak shields.** Cancelling in good time earns a freeze. Honesty is rewarded, so one bad week doesn't end the habit.
- **👥 Squads.** Friends commit together. Everyone shows: a squad bonus. Someone flakes: the others split the stake.
- **🔁 Seat swap.** Can't go? Hand your seat to the waitlist and get your commitment back. Nobody loses.
- **🏆 City seasons.** Monthly reliability leaderboards, sponsored by venues from recovered no-show revenue.
- **📊 Monthly recap.** *"You showed up 23 times, saved $46 in deposits, and you're in the top 8% for reliability in your city."* Built to be shared.
- **🤖 Agent as coach.** *"Two gym check-ins left this week to keep your 14-week streak. Book Thursday 7am?"* The agent can book. It can never check in for you.
- **✅ Never wrongly charged.** Your presence proof belongs to you. No business can charge a fee for a session you attended.

---

## 8. Simple outside, powerful inside

| Complexity inside | What the person sees |
|---|---|
| Solana programs, escrow | "Your $5 is held safely" |
| Wallets, keys, gas | Face ID. No fees (gas is sponsored) |
| Stablecoins, conversion | Card, Apple Pay, Google Pay, local payment methods |
| x402, mandates, permissions | "Your assistant can book up to $150 this month. Change?" |
| Encrypted computation | "Your history stays private" |
| Presence proofs, attestation | "Tap to check in" |

**Design rules**

1. Never show a blockchain term on a consumer screen.
2. A review screen before every signature, stating exactly what happens if you come and if you don't.
3. Show only what the chain has **confirmed**: *authorizing → submitting → pending → confirmed*.
4. Every action works in one tap or one sentence to an agent.
5. Meet people where they are: web, mobile, messaging apps (WhatsApp, Telegram), and the agent they already use.

---

## 9. Who it's for

| Segment | Pain | ANZEN value |
|---|---|---|
| **Fitness studios** *(launch wedge)* | Empty bikes in "full" classes; fee disputes | Auto-refill, proof-backed fees, daily habit loop |
| **Clinics and practices** | Rising no-shows; staff time spent chasing | Commitments, release-and-refund nudges, auto-refill |
| **Restaurants** | Bot hoarding; agents they can't trust | Agent gate, bonded bookings, overbooking intelligence |
| **Events, meetups, hackathons** | 30–50% no-shows at free events | Commitment RSVPs, verified attendance |
| **Training providers and institutes** | Weak attendance proof | Tamper-proof attendance credentials |
| **Startups and enterprises** | Demo-day, hiring-event and offsite no-shows | Commitment RSVPs, attendance analytics |
| **AI agent companies** | Users getting banned from booking platforms | Agent Pass integration = agents venues accept |
| **Individuals** | Wrongful fees; flaky friends; broken habits | Never wrongly charged, streaks, squads, perks |

### Launch wedge: fitness studios

- **Daily usage** means the fastest habit loop and reputation growth.
- **Proven pain.** Studios already charge no-show fees and already fight disputes about them.
- **Clear ROI.** Every refilled spot is directly recovered revenue.
- **Easy to show live.** A cancellation triggers an auto-refill in seconds.

Then: events → clinics → restaurants (via booking-platform partnerships) → training and credentials.

---

## 10. AI agent integration

### One protocol, every agent

| Interface | Status |
|---|---|
| **OpenClaw skill** (`skills/anzen/`) | ✅ Prototype |
| **MCP server** (`.mcp.json`) for Claude, Cursor and any MCP client | ✅ Prototype |
| **x402 endpoint** for any HTTP-native agent | 🚧 Building |
| **Venue SDK** to make any booking page "agent-ready" | 🚧 Building |

### Agent tools

| Tool | What it does |
|---|---|
| `setup` | Register the agent and print a passkey approval link for the owner |
| `events` | Find bookable sessions |
| `reserve` | Book within the mandate (pays the commitment via x402) |
| `budget` | Read remaining cap, categories, guardrails, expiry |
| `reputation` | Read the owner's / agent's show-up record |

**There is deliberately no check-in, cancel-with-fee, withdraw or raise-budget tool.** The contract would reject them anyway.

### Example

```
You:    "Find me a spin class Thursday morning under $25 and book it."
Agent:  Found 7:00am at Studio North ($22). Venue requires a $5 commitment.
        Within your pass (cap $150, fitness ✓, cancel fee $10 ≤ $20 ✓).
        Booked. Your $5 is held and comes back when you check in.
```

```
Agent:  Found a table at 8pm, but it has a $200 cancellation fee.
        Your pass blocks fees above $20. Not booked.
```

---

## 11. Security model

| Threat | Defense |
|---|---|
| Compromised or malicious agent (e.g. a poisoned skill) | Spend cap, category list, guardrails and expiry enforced onchain. No path to check-in, refunds or mandate changes. |
| Agent hoarding / scalping | Booking caps per pass; one active booking per time slot; venue-level agent gate |
| Remote check-in / QR relay | Silver (venue scans guest) and Gold (NFC + hardware attestation) tiers |
| Fake reputation / Sybil reviewers | Reputation is written **only by the settlement program**, only from settled, verified outcomes, each backed by a real commitment |
| Reputation reset via new agent identity | Reputation is bound to the **human's passkey**, so a fresh agent inherits its owner's record |
| Organizer collusion (refusing check-ins) | Check-in proofs are signed by the guest; disputes resolved by proof; venue reputation is tracked too |
| Terms changed after booking | Terms are hashed into the booking onchain |
| Relayer misbehaviour | The relayer pays gas only. It cannot forge or alter a passkey signature. |

---

## 12. Privacy model

- **Minimum disclosure.** Venues receive predicates (*score ≥ X*, *verified human*), not identities or histories.
- **Encrypted computation.** Score computation runs over encrypted data (Arcium).
- **No location trail.** A check-in proves a fresh venue challenge was signed by your passkey. ANZEN does not track GPS.
- **User-owned proofs.** Your presence proofs are yours to present, for disputes, credentials, or not at all.

---

## 13. Business model

| Revenue stream | Payer | Logic |
|---|---|---|
| **Venue SaaS** | Studios, clinics, restaurants, event hosts | Agent gate, auto-refill, dashboard, integrations |
| **Recovered-revenue share** | Built into settlement | A small share of forfeited commitments, only when the venue was protected |
| **Agent booking fee** | Agent platforms or venues | Per verified agent booking |
| **Credential issuance** | Training providers, institutes | Per verifiable attendance credential |
| **Reliability underwriting** *(long term)* | Venues | ANZEN guarantees high-score customers with **no deposit at all**, priced from verified reliability data |

### Moat

**The presence graph:** every verified check-in, at every venue, tied to real humans. It compounds with every booking, can't be generated by AI, and can't be bought. Venues adopt ANZEN because agents carry Passes; agents carry Passes because venues accept them. That two-sided network effect strengthens with scale.

---

## 14. Market and competition

| | Reminders / SMS tools | Fee automation (studio software add-ons) | Commitment-RSVP dapps (Kickback, sunset 2023; Unlock "I'm Going!") | Reservation platforms | **ANZEN** |
|---|---|---|---|---|---|
| Reduces no-shows | Partly | Partly | ✅ | Partly | ✅ |
| Proves presence | ❌ | ❌ | Organizer marks attendance | ❌ | ✅ Cryptographic, tiered |
| Protects customers from wrongful fees | ❌ | ❌ | ❌ | ❌ | ✅ |
| Automatic seat refill | ❌ | ❌ | ❌ | Waitlist only | ✅ Agent-driven, seconds |
| Safe AI-agent bookings | ❌ | ❌ | ❌ | ❌ Bans agents | ✅ Agent Pass |
| Unfakeable reputation | ❌ | ❌ | ❌ | ❌ | ✅ |
| Private by design | n/a | n/a | ❌ Public | n/a | ✅ Encrypted compute |
| No wallet / no crypto UX | n/a | n/a | ❌ Wallet required | n/a | ✅ Passkeys |

**What we learned from Kickback:** deposit-RSVP for crypto meetups alone isn't a business, because the audience was too narrow and the experience too crypto-native. ANZEN differs in four ways: **mainstream UX** (passkeys, cards), **daily-use verticals** (fitness, health), **business ROI** (auto-refill, not just penalties), and **the agent layer**, which didn't exist until 2026.

---

## 15. Research foundations

| Paper | How ANZEN applies it |
|---|---|
| **Can Trustless Agents Be Trusted? An Empirical Study of the ERC-8004 Ecosystem** (arXiv [2606.26028](https://arxiv.org/abs/2606.26028)) | Agent reputation must come from verifiable interactions and trusted clients. ANZEN's reputation comes from one client, the settlement program, and only from verified outcomes. |
| **Tempting the Agent: The Economics of Reputation without Persistent Identity in AI Agent Markets** (arXiv [2609.02992](https://arxiv.org/abs/2609.02992)) | Reputation fails when identities are disposable. ANZEN binds reputation to the human's passkey. |
| **Bonded Recourse for Smart-Contract Settlement of Compensable Agent Side Effects** (arXiv [2609.01939](https://arxiv.org/abs/2609.01939)) | Agent actions in the world need bonded, automatic compensation. ANZEN's commitments are exactly that, for bookings. |
| **Inter-Agent Trust Models: Brief, Claim, Proof, Stake, Reputation and Constraint** (arXiv [2511.03434](https://arxiv.org/abs/2511.03434)) | ANZEN combines **stake** (commitments), **constraint** (Agent Pass), **proof** (presence) and **reputation** (Show-Up Score) in one protocol. |
| **Privacy-preserving and reward-based mechanisms of proof of engagement** (arXiv [2506.12523](https://arxiv.org/abs/2506.12523)) | Prior work on proof-of-attendance. ANZEN extends it with tiered physical verification, agents and encrypted reputation. |
| **Human Challenge Oracle** (arXiv [2601.03923](https://arxiv.org/abs/2601.03923)) | Short, identity-bound, time-limited challenges make faking N identities cost N times more. That is the design of the rotating venue code. |
| **A Formal Analysis of Agent Payment Protocols** (arXiv [2609.00060](https://arxiv.org/abs/2609.00060)) | Delegated authority must match its economic effect. One owner signature binds agent, cap, scope and expiry. |

---

## 16. Technical design

```
 Person (phone / browser)         ANZEN service                      Solana
 ────────────────────────         ─────────────                      ──────
 Passkey (Face ID) signs ───────▶ simulates, relays (pays gas) ────▶ anzen_core program
  sha256(tag, cluster,            can't forge or alter a signature     · verifies P-256 / WebAuthn
         program, params)         issues rotating venue challenges       (SIMD-0075 precompile)
                                  stores published venue terms         · holds commitments (escrow)
                                                                       · enforces Agent Pass mandates
 AI agent ── x402 / MCP ────────▶ agent gateway: mandate check ─────▶  · settles outcomes
 (OpenClaw, Claude, any)          402 → pay → book                     · writes Show-Up records

 Venue (NFC tag / scanner) ─────▶ presence oracle (tiered) ─────────▶ check-in verification

                                  Arcium (encrypted compute) ◀──────▶ private score predicates
```

| Component | Role |
|---|---|
| `anzen_core` (Solana program) | Escrow, mandates, guardrails, settlement, show-up records |
| Passkey verification | Solana SIMD-0075 secp256r1 precompile + WebAuthn assertion checks |
| Agent gateway | x402 endpoint, MCP server, OpenClaw skill |
| Presence oracle | Rotating challenges, NFC, staff scan, hardware attestation |
| Private score | Arcium encrypted computation → predicates for venues |
| Relayer | Sponsors fees; cannot alter signed intents |
| Clients | Web (passkeys), Android (Kotlin/Compose, Keystore, CameraX), messaging bots |

---

## 17. Status: built, building, roadmap

> **Prior-work disclosure:** ANZEN v1 was prototyped on Monad testnet before this competition. That earlier work, and everything built during the competition window, are listed separately below.

### ✅ Built (prototype v1, Monad testnet)

- Passkey accounts (WebAuthn, verified onchain via P-256 precompile); Android Keystore keys
- Refundable commitments, rotating 45-second venue codes, automatic settlement
- Agent budgets (cap + expiry) via OpenClaw skill and MCP server; no check-in tool for agents
- Show-up reputation written by the treasury to the ERC-8004 reputation registry
- Public agent show-up board
- Confirmed-only money UI (*authorizing → submitting → pending → confirmed*)
- Tests: 33 contract tests, a fork test against real ERC-8004 registries, 14 service tests, 22 Android unit tests, browser passkey tests, 11 on-device journeys

### 🚧 Building (this competition)

- Port to **Solana** (`anzen_core` program, SIMD-0075 passkey verification)
- **Agent Pass guardrails** (categories, cancellation-fee ceiling, booking caps)
- **x402** commitment payments for agents
- **Auto-refill** waitlist with agent claims
- **Silver tier** check-in (venue scans guest)
- Show-Up Score, streaks and streak shields
- Venue dashboard (seats refilled, revenue recovered)
- Pilot with a real venue

### 🗺️ Roadmap

| Phase | Focus |
|---|---|
| **1. Wedge** | Fitness studios and events: auto-refill, streaks, squads, venue SDK |
| **2. Trust** | Arcium private score predicates · Gold tier (NFC + hardware attestation) · disputes |
| **3. Agents at scale** | Agent-platform integrations · restaurant booking-platform partnerships |
| **4. Expansion** | Clinics · training credentials · reliability underwriting (no-deposit guarantees) |

---

## 18. Try it

**Prototype v1:** **[anzen-shonko.vercel.app](https://anzen-shonko.vercel.app)** (Monad testnet, test dollars only)

1. **Create passkey account.**
2. **Get 100 test dollars** (tUSD, no value).
3. **Host an event** with the *Demo* schedule (commitments close in 4 min, check-in opens at 5).
4. **Review commitment**: one screen shows what you put in and what happens if you come or don't. Then the passkey prompt.
5. When check-in opens, **Open check-in desk** on a laptop and scan the QR with your phone. Sign in with the same passkey, then **Sign check-in**.
6. After check-in closes, **Apply outcome**. Your commitment returns. **View proof** lists every transaction.

**With an AI agent:** `skills/anzen/scripts/anzen-agent init` registers the agent and prints an approval link. Approve a budget with your passkey, then ask OpenClaw *"find me something this week under 10 and book it"*. In Claude Code, the repo's `.mcp.json` loads the same tools.

Prototype contracts (Monad testnet): treasury [`0xb8775046…5d8f`](https://testnet.monadexplorer.com/address/0xb87750460d648B0D7C953B42aE6FcDB9245F5d8f) · test dollar [`0x5799a…Dff73`](https://testnet.monadexplorer.com/address/0x5799ab1E835225a824C550Fe3797Ce7a846Dff73). A full lifecycle run is in [SUBMISSION.md](SUBMISSION.md).

### Repository

```
contracts/  v1 treasury (+ tests, fork test vs real ERC-8004), test dollar
backend/    relayer, presence challenges, policy store, agent gateway, MCP server
web/        browser app (passkeys)
app/        Android app (Kotlin/Compose, Keystore keys, CameraX)
skills/     OpenClaw skill
docs/       SPEC.md: full protocol design
```

The `anzen_core` Solana program will live in `programs/` once the port starts.

Build, run and test everything: **[BUILD.md](BUILD.md)**.

---

## 19. Honest limits

- Test money only. Not audited.
- Prototype v1 uses a single presence issuer and a single relayer.
- Bronze check-in proves a fresh code was signed with your passkey, not your exact location. Silver and Gold tiers address this.
- Forfeit redistribution rules must be reviewed against local consumer and promotion law in each market before launch.
- The Solana port, Arcium private scoring and hardware-attested check-ins are in progress (see [Status](#17-status-built-building-roadmap)).

---

## 20. Sources

- MGMA: *About 1 in 3 medical groups see higher no-shows in 2026* — https://www.mgma.com/mgma-stat/about-1-in-3-medical-groups-see-higher-no-shows-in-2026-as-patients-face-higher-costs
- BillFlash: *The $150 billion no-show problem* — https://billflash.com/?p=116513
- MyFitHive: *Fitness studio no-show policy* — https://myfithive.com/blog/view/fitness-studio-no-show-policy
- Trustpilot: 1Rebel Oxford Circus reviews — https://uk.trustpilot.com/review/1rebel.com/location/oxford-circus
- CNN (via KRDO): *Now AI is trying to gobble up dinner reservations* (Sep 2026) — https://krdo.com/money/cnn-business-consumer/2026/09/23/now-ai-is-trying-to-gobble-up-dinner-reservations/
- Bloomberg (via BNN): New York reservation anti-piracy law — https://bnnbloomberg.ca/business/2024/12/19/ny-black-market-for-hot-restaurant-reservations-shut-down-by-law
- Miami New Times: Florida reservation anti-piracy law — https://www.miaminewtimes.com/food-drink/annoying-era-of-scalping-florida-restaurant-reservations-may-be-over-23448855/
- Indie Hackers: meetup attendance rates — https://www.indiehackers.com/post/ec7d1dcba1
- The South First: NMC moves to face-based attendance — https://thesouthfirst.com/health/fingerprint-biometric-system-fails-in-medical-colleges-nmc-switches-to-face-based-attendance-for-faculty/
- Naoma: *What is OpenClaw* (2026) — https://naoma.ai/articles/what-is-openclaw
- The Hacker News: *Researchers find 341 malicious ClawHub skills* (Feb 2026) — https://thehackernews.com/2026/02/researchers-find-341-malicious-clawhub.html
- Solana Foundation: *Agentic payments with x402* — https://solana.com/news/webinar-recap-agentic-payments
- Helius: *Solana passkeys / SIMD-0075* — https://www.helius.dev/blog/solana-passkeys
- The Block: *Arcium Mainnet Alpha on Solana* — https://www.theblock.co/post/387564/
- Solana Mobile docs: Seeker — https://docs.solanamobile.com/solana-mobile-stack/seeker
- Unlock Protocol: *"I'm Going!" commitment staking* (Kickback history) — https://unlock-protocol.com/guides/unlock-commitment-staking-kickback-refund/

---

<p align="center"><b>Show up. It's the one thing AI can't do for you.</b></p>
