# ANZEN · Shonko

**Your agent can book it. Only you can show up.**

**Live:** https://anzen-shonko.vercel.app · **Code:** https://github.com/Aaditya1273/LOOP · **Network:** Monad testnet
**Track:** Trust, Identity & AI Infrastructure

ANZEN turns an RSVP into a refundable commitment signed with a passkey, settles it on Monad when you check in at the
venue, and gives AI agents that book on your behalf a **show-up reputation under ERC-8004 that can't be faked**:
the treasury contract itself writes every data point, and each one cost a real commitment and a verified check-in.

---

## The problem

- **A free RSVP is worth nothing.** Free events lose a median **28%** of sign-ups to no-shows; 40–60% is common
  (PheedLoop, 1,070 events). A refundable deposit fixes it: **$3 cut no-shows from 38% to 14%**; a $100 refundable
  deposit gave a **90% show rate** (37signals). Almost nobody does it, because holding and refunding money is painful.
- **Bots fill the gap.** Ticket scalping rose **291%** in a year; **83%** of ticketing traffic is automated (DataDome 2026).
- **AI agents now book for us, and their reputation is fake.** In the ERC-8004 registries, **73–91% of reviewers** show
  coordinated Sybil behaviour and feedback is "rarely grounded in verifiable interactions" (arXiv 2606.26028).

## What ANZEN does

1. **Commit.** Reserve with a small refundable commitment. The account is a **passkey**: Face ID, Touch ID, Windows Hello,
   or a phone's secure chip. No wallet, no seed phrase. The system prompt names exactly what you sign.
2. **Show up.** The organizer's screen shows a code that rotates every 35 s and expires in 45 s, signed by the event's
   issuer. You scan it with your phone camera and approve with your passkey. The contract checks both signatures, the
   window and the freshness.
3. **Unlock.** When check-in closes the treasury applies the rules the organizer published (their sha256 is fixed
   onchain): your commitment back, an equal share of no-shows' commitments, and a supporter passport.

**AI agents.** With the OpenClaw skill or the MCP server (Claude, Cursor, any MCP client), your agent finds events and reserves places inside a budget you approve with
your passkey: a cap and an expiry, bound to the agent's ERC-8004 identity and enforced by the contract. It can't check
in, cancel, withdraw or raise its own budget. **Money can be delegated; presence can't.**

## Why it's different

| | |
|---|---|
| **Sybil-proof agent reputation** | When an agent's reservation settles, `AnzenTreasury` itself calls Monad's official ERC-8004 reputation registry: **100 if the human showed up, 0 if not**. `getSummary(agentId, [treasury], "showUp", "anzen")` is the agent's show-up rate. Every data point cost a refundable commitment and a verified physical check-in, so a Sybil reviewer can't write one. And the incentive points the right way: **an agent's reputation rises only when its human actually goes**, so good agents book things their people will attend. |
| **Presence is not delegable** | Agent budgets are common in this track. ANZEN is where the budget stops at the venue door: check-in needs the owner's passkey and a code that died 45 seconds after it appeared. |
| **Real passkeys, verified onchain** | WebAuthn assertions (OpenZeppelin `WebAuthn.verify`, user verification required) and Android Keystore keys, both via Monad's P-256 precompile. The challenge is sha256 of the exact action, so a signature can't be reused for anything else. |
| **Any agent, public scores** | The same five tools ship as an OpenClaw skill and an MCP server, with no check-in tool in either. The [show-up board](https://anzen-shonko.vercel.app/#/agents) ranks every agent with a budget by the rate the treasury wrote. |
| **Live, not a slide** | Anyone can sign up at the link above in ten seconds and run the whole loop, alone, with a laptop and a phone. |

## Proof: one full lifecycle on Monad testnet

Run through the **live service** with `backend/anzen/smoke.py`, real keys and real time. Alice commits with her own key.
Bob's AI agent registers its **own ERC-8004 identity in Monad's official registry**, and Bob approves a budget bound to
it. The agent reserves for Bob. Alice checks in with a fresh venue code; Bob doesn't come. Outcomes: Alice gets 105 tUSD
(her 5 back + Bob's 5) and passport no. 1; Bob ends at 95. The treasury writes the agent's show-up result (0%) to the
ERC-8004 reputation registry.

| Step | Transaction |
|---|---|
| faucet: 100 tUSD to alice | [0x374d2be4…](https://testnet.monadexplorer.com/tx/0x374d2be476a0802493520dc97f5dd92796476af7e5a1485a3542d8210bcfbc2d) |
| faucet: 100 tUSD to bob | [0xf6dac1fa…](https://testnet.monadexplorer.com/tx/0xf6dac1fa829458dd3eab4fab122c1fcc8f61e271f341056c719537a758314db8) |
| organizer publishes event | [0x119a87eb…](https://testnet.monadexplorer.com/tx/0x119a87eb40587112fc78ed543c877288bcab1dae6ca336c63a1304e93d5c040d) |
| alice commits 5 tUSD (her key) | [0x38882fa9…](https://testnet.monadexplorer.com/tx/0x38882fa9861d1f8213f913d1361696fc333b98c325afb6bfe1c62bf11636f21d) |
| agent registers its ERC-8004 identity | [#2183 in the identity registry](https://testnet.monadexplorer.com/address/0x8004A818BFB912233c491871b3d84c89A494BD9e) |
| bob approves agent budget 10 tUSD | [0x2c85ab4a…](https://testnet.monadexplorer.com/tx/0x2c85ab4a2cc33a1fd460f2d369e6834dc85c2ed615516cf72d33765843fac8d0) |
| bob's agent commits 5 tUSD for bob | [0x0b344e7d…](https://testnet.monadexplorer.com/tx/0x0b344e7d3da0e1c36a18923635433a4f574f4653aa268b78ad347401751c95ca) |
| alice checks in (fresh venue code) | [0xc78da274…](https://testnet.monadexplorer.com/tx/0xc78da27478935f6fe3b37a0bbcfed8ab2fef87a9423699222c9fab1c90b04ec4) |
| alice's outcome applied | [0x7a6ea55f…](https://testnet.monadexplorer.com/tx/0x7a6ea55f39957ae3e940848c2485ad96177cfdb2a6db33a2f3f123622a8dbe5a) |
| bob's outcome applied (no-show) | [0xd95f118c…](https://testnet.monadexplorer.com/tx/0xd95f118ca6c50c602105085fc49fb39b51a56b960613ce1b12f4e605bc0c9297) |
| treasury writes the agent's show-up result (0%) to the ERC-8004 reputation registry | same transaction as Bob's outcome ↑ (`getSummary(2183, [treasury])` → count 1, 0%) |

Contracts: treasury [`0xb8775046…5d8f`](https://testnet.monadexplorer.com/address/0xb87750460d648B0D7C953B42aE6FcDB9245F5d8f) ·
test dollar [`0x5799a…Dff73`](https://testnet.monadexplorer.com/address/0x5799ab1E835225a824C550Fe3797Ce7a846Dff73) ·
ERC-8004 identity [`0x8004A818…BD9e`](https://testnet.monadexplorer.com/address/0x8004A818BFB912233c491871b3d84c89A494BD9e) /
reputation [`0x8004B663…8713`](https://testnet.monadexplorer.com/address/0x8004B663056A597Dffe9eCcC1965A193B7388713)

## How it's verified

- **Contracts:** 33 Foundry tests. They cover the lifecycle and split maths; forged, high-s, replayed and expired
  signatures; WebAuthn without user verification or for another action; agent caps, expiry and revocation; agents
  that can't check in; reputation posting, skipping, gas sufficiency and registry failure. Plus **a fork test against
  the real ERC-8004 registries on Monad testnet**.
- **Service:** 14 tests over HTTP against real contracts on a local chain, including a browser-style WebAuthn assertion
  and the MCP server driven by a real MCP client over stdio.
- **Encodings:** golden vectors shared by Solidity, Python, Kotlin and the browser.
- **Browser:** `web/passkey.e2e.mjs` runs a real WebAuthn ceremony in headless Chromium. It publishes, commits, decodes
  the desk QR **from its pixels**, then checks in, settles, issues the passport and verifies the proof.
- **Android:** 22 unit tests, 0 lint errors, and 11 journeys on an emulator with the real system PIN prompt.
- **Live:** concurrent requests on the hosted service share one relayer nonce sequence through a Postgres advisory lock
  (tested at 6 parallel requests).

## Why Monad

- **P-256 is native.** Passkeys and phone hardware keys verify onchain through the precompile; the account is the device.
- **Check-in must be instant.** A 45-second code only works with fast blocks; a door queue can't wait for confirmations.
- **ERC-8004 is deployed on Monad.** We write to the official registries; we didn't deploy a toy one.
- **Small amounts.** Fees don't eat a 5-dollar commitment.

## Business model

Creators and venues pay a small fee per **checked-in** attendee; they keep the show-rate upside, and forfeited
commitments can fund the event. Agent platforms integrate the skill so their agents can book real-world things and
carry a reputation that event hosts can trust.

## Research grounding

- *Human Challenge Oracle* (arXiv 2601.03923): time-bound, identity-bound challenges make faking N identities cost N
  times more. The rotating venue code bound to one passkey applies this to physical presence.
- *Can Trustless Agents Be Trusted?* (arXiv 2606.26028): ERC-8004 reputation needs feedback grounded in verifiable
  interactions from trusted clients. Ours has exactly one client, the treasury, and only settled outcomes.
- *A Formal Analysis of Agent Payment Protocols* (arXiv 2609.00060): delegated authority must match its economic effect.
  One owner signature binds agent, ERC-8004 identity, cap and expiry.

## Honest limits

Test dollars only. One check-in issuer and one relayer (the relayer can delay, never forge). A check-in proves a fresh
code was signed with your passkey, not your exact location. Android keys are device-bound (no sync). Not audited.

---

## Demo video script (3:00)

| Time | Shot | Say |
|---|---|---|
| 0:00 | Stat cards: 28% · 291% · 73–91% | "A free RSVP is worth nothing. Bots take the rest. Now AI agents book for us, and their reputation is fake." |
| 0:15 | Browser: anzen-shonko.vercel.app → Create passkey → Touch ID | "Your account is a passkey. No wallet, no seed phrase." |
| 0:30 | Event → Commit 5 tUSD → passkey → Pending → Verified | "Reserve with a refundable 5 dollars. We only say done when the contract confirms it." |
| 0:50 | Terminal: OpenClaw "find something in Bengaluru under 10 and book it" → reply | "My OpenClaw agent books for me, inside a budget I approved with my passkey." |
| 1:10 | Approval link → "Your agent is asking for a budget", ERC-8004 #id, show-up rate | "It has a public ERC-8004 identity. Its reputation is its show-up rate." |
| 1:30 | OpenClaw "check me in remotely" → refusal | "But it can never show up for me. Money can be delegated; presence can't." |
| 1:45 | Laptop desk QR rotating → phone camera → passkey → "You're checked in" | "At the door: a code that dies in 45 seconds, signed with my passkey." |
| 2:10 | Outcome → passport → proof screen | "Check-in closes, the treasury pays back, and I get a passport. Every step is a transaction." |
| 2:30 | Explorer: settle tx → ERC-8004 reputation write by the treasury | "And the treasury itself writes my agent's reputation. Only real check-ins count, so it can't be faked." |
| 2:50 | Logo + link | "ANZEN. Your agent can book it. Only you can show up." |
