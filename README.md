# ANZEN v3.2 — Presence Treasury Protocol
## Turning Real-World Commitments into Programmable Financial Outcomes

**Product:** ANZEN Presence Treasury Protocol  
**Consumer experience:** Shonko  
**Tagline:** Commit to Show. Prove Your Presence. Unlock Your Outcome.  
**Category:** Programmable Finance · Event-Linked Verification · Digital Identity · Creator Economy  
**Primary ecosystem:** Monad  
**Long-term ambition:** Make real-world participation a verifiable input into programmable digital finance.

---

# 1. Executive Summary

ANZEN is a protocol for creating, funding and settling financial commitments whose outcomes depend on verifiable real-world participation or other explicitly defined conditions.

Today, a creator can announce an event, sell tickets, collect deposits and issue digital passes. However, connecting an attendee's actual participation to the financial consequences of that commitment often requires separate platforms, centralized records and manual administration.

ANZEN connects those elements.

A creator establishes an event treasury with a predetermined policy. Participants commit a specified digital asset. The event's check-in process produces event-specific evidence, which is evaluated against the treasury's conditions. The protocol then applies the agreed outcome: returning a commitment, distributing a permitted reward, allocating a forfeited contribution or issuing a supporter credential.

The core lifecycle is:

**Commit → Authenticate → Check In → Verify → Settle → Recognize**

The system is designed around three technical foundations:

1. **Passkey-based authorization and contextual trust.** Users can authenticate and manage accounts using supported passkey infrastructure, while action-specific policies determine which credentials and permissions are necessary.
2. **Programmable treasury and conditional settlement.** Smart contracts lock assets and enforce predetermined financial rules.
3. **Independent verification and digital provenance.** The protocol records evidence of the declared conditions and checks whether the resulting financial outcome follows those conditions.

Three additional capabilities expand the system's potential:

- **Encrypted commitments:** Conceal selected commitment information before a predefined reveal or settlement stage, using a threshold-encryption integration where technically supported.
- **Content passports:** Give verified attendees or early supporters a persistent, inspectable record of participation and provenance.
- **AI-agent participation:** Allow registered agents to assist with event administration and other delegated tasks, using standardized agent-identity and validation interfaces where appropriate.

The initial product is a creator-focused experience built around small, capacity-limited events. The consumer-facing Shonko feature handles discovery, commitments, check-in and supporter recognition. ANZEN provides the underlying treasury, verification and settlement logic.

The first implementation should use a clearly labelled test asset and one supported event-verification path. Real tokenized equity, encrypted-mempool functionality and agent-registry integrations are advanced milestones rather than assumed components of the initial release.

**Core promise:** ANZEN connects a financial commitment to an explicit rule, the evidence needed to evaluate that rule and the financial outcome that follows.

---

# 2. The Problem ANZEN Solves

## 2.1 Real-world participation is difficult to turn into programmable financial logic

Creators, communities and event organizers frequently depend on participation.

An independent musician wants to reward the earliest supporters who attend a private show. A creator wants to give early members privileged access to future releases. An event organizer needs to distinguish a genuine attendee from someone who registered but never appeared. A community wants its rewards to follow documented participation rather than an administrator's subjective decision.

Current workflows typically separate these activities:

- Registration happens on an event platform.
- Financial commitments happen through a payment service.
- Attendance is recorded through a QR code, ticket scanner or manual list.
- Rewards are distributed through another platform.
- Evidence remains scattered across different services.

The result is an administrative gap between what a participant promises to do and the benefit or financial outcome they receive.

ANZEN proposes a unified, programmable workflow.

## 2.2 Commitment contracts are only as useful as their conditions

A simple commitment contract can lock a user's assets and return them when a specified condition is satisfied.

The challenge is determining whether that condition actually happened.

For an online action, a contract may verify a transaction or another onchain state. For an offline event, attendance is not natively visible to a blockchain.

The protocol therefore needs an event-verification mechanism that connects an actual check-in process to a verifiable authorization and a predefined settlement rule.

A passkey signature is useful for authentication, but it is not independent proof of physical location. ANZEN must distinguish proof of credential control from evidence of onsite participation.

## 2.3 Participation records have limited portability

An attendee may have participated in a small event, supported a creator before they became popular or contributed to a community at an early stage. That information may be valuable to future communities, creators or access programs, but it is often trapped in a centralized database.

ANZEN introduces a concept of a **content passport**: a digital record of a qualifying event, with provenance and issuer information that an application can inspect.

The passport can support access, recognition and other clearly defined benefits. It should not automatically expose personal identity or private event details.

## 2.4 Digital assets are often treated as balances instead of programmable commitments

Holding a token does not demonstrate that its owner can use it in a meaningful, conditional workflow.

ANZEN's long-term financial vision is to make supported assets actionable by contracts: locked before an event, returned after a qualifying check-in, distributed under predetermined rules or used as collateral for another supported activity.

This is particularly relevant to the programmable-asset thesis in onchain finance. However, a mock ERC-20 demonstrates conditional token logic; it does not demonstrate the same thing as controlling an actual tokenized security.

## 2.5 Privacy and accountability must coexist

Participants may not want other attendees to see their exact contribution amounts or commitment details before the event.

At the same time, a treasury needs to enforce its rules and produce enough evidence to settle correctly.

ANZEN therefore separates the public rules and settlement outcomes from the information that can potentially remain private.

Its future encrypted-commitment design aims to restrict what observers learn before a commitment is revealed. It cannot assume that normal public ERC-20 transfers will remain confidential after settlement.

---

# 3. The Core Product: Creator IRL Drop Treasury

## 3.1 The initial use case

An independent creator announces a private rooftop performance in Shimla.

There are twenty places. Supporters must reserve a place and commit a specified test asset. Attendees who complete the required check-in qualify for the announced outcome. People who fail to check in are handled according to the event's published forfeiture policy.

Participants who genuinely attended may also receive an early-supporter passport.

This is not primarily a library booking tool, trip-splitting application or generic savings pool. Its focus is the relationship between a creator event, an agreed financial commitment, verifiable participation and conditional settlement.

The concept can later expand to creator releases, community meetups, workshops, limited drops and other activities where participation and financial consequences need a common, inspectable rule system.

## 3.2 Who uses the product?

**Creator or organizer:** Defines the event, available capacity, commitment terms, check-in policy and settlement rules.

**Participant or supporter:** Reviews the terms, commits the asset, checks in through the specified process and receives the outcome they qualify for.

**Venue or check-in operator:** Provides the event-specific onsite interaction or signed check-in evidence according to the organizer's declared verification policy.

**Treasury contract:** Holds the committed assets, validates supported proofs and executes the contractually defined settlement.

**Verification service:** Checks evidence that cannot be safely evaluated directly by the contract, where necessary, and produces a signed or otherwise verifiable result.

**Passport recipient:** Receives a record of participation or early-supporter status, subject to the event's issuance and privacy rules.

## 3.3 Example event configuration

An organizer creates the following event:

| Parameter | Example |
|---|---|
| Event | Creator Secret Show |
| Location | Shimla |
| Capacity | 20 attendees |
| Commitment asset | Clearly labelled testnet ERC-20 |
| Commitment amount | 0.01 test asset per participant |
| Registration | Opens before the event |
| Check-in | Event-specific passkey challenge plus onsite interaction |
| Attendance window | Configured start and end time |
| Successful outcome | Return of principal and eligibility for a defined benefit |
| No-show outcome | Predetermined forfeiture or redistribution policy |
| Event cancellation | Refunds under the published cancellation policy |
| Supporter record | Early-supporter content passport, if eligible |

The test asset is not AAPL and must not be presented as real equity. Real tokenized-equity functionality requires a supported asset whose actual rights, issuer and transfer conditions have been verified.

## 3.4 The organizer creates the treasury

The organizer supplies the event details and chooses among the settlement policies supported by the application.

The draft specifies:

- Commitment asset and amount.
- Maximum participant count.
- Registration deadline.
- Event start and end times.
- Check-in opening and closing times.
- Accepted check-in evidence.
- Required credential or permission checks.
- Treatment of successful attendance.
- Treatment of no-shows.
- Cancellation and dispute procedures.
- Passport issuance conditions.
- Any additional reward and its funding source.

ANZEN validates the configuration before the organizer confirms it.

The deployed treasury stores a commitment to the event's terms so that the rules can be inspected and cannot be silently changed after participants have committed.

Protected parameters should require a new authorization or an explicit, predefined amendment procedure.

## 3.5 Participants commit their assets

A participant reviews the event and its terms.

Before signing, the interface displays the precise amount, asset, recipient, conditions and financial consequences.

The participant then explicitly authorizes the commitment.

After successful execution, ANZEN records the participant's position in the treasury and the applicable status.

The commitment is not equivalent to a promise created by a text message. It is a defined contract interaction with specific conditions.

## 3.6 Check-in and attendance verification

When the event begins, the organizer or an authorized event operator makes the check-in mechanism available.

A participant uses a supported passkey to authenticate a challenge associated with that event and that participant.

The challenge must be unique, time-limited, bound to the intended event and protected against replay.

The venue's onsite interaction—such as an event-controlled rotating QR code, NFC interaction or authorized operator-issued challenge—supplies the additional evidence that connects the authentication to the event check-in process.

The passkey proves use of a registered credential. The check-in policy determines how that evidence, together with the onsite interaction and any trusted issuer, supports the event's attendance claim.

ANZEN must not claim that Face ID, WebAuthn, GPS or a valid wallet signature alone makes physical presence impossible to fake.

## 3.7 The treasury settles under the agreed rules

Once the attendance window and any necessary verification grace period end, the treasury evaluates each participant's status.

A successful participant can receive the outcome declared in the event's policy.

A participant who fails to check in is handled according to the same previously declared rules. For example, the commitment might be forfeited to the organizer's event treasury or redistributed among qualifying attendees, provided the policy, asset and applicable rules permit that outcome.

If the creator cancels the event, the cancellation rules determine whether funds must be returned.

If the verification evidence is unavailable or disputed, ANZEN should not automatically treat the participant as a no-show. It should enter the applicable pending, disputed or refund state.

## 3.8 Shonko issues the content passport

Participants who satisfy the event's conditions may receive a digital passport.

The passport records the event identifier, issuer, issuance policy and the relevant evidence commitment. The public representation should disclose only what is necessary to verify the claim.

Its primary initial benefits could include:

- Recognition as an early supporter.
- Eligibility for future access.
- Participation history within the creator's community.
- Invitations to selected future events.
- Eligibility for clearly defined creator rewards.

Transferability should be configurable rather than automatic. A passport intended to represent a particular individual's participation may need to be non-transferable or bound to a credential, whereas a collectible ticket may have different transfer rules.

A passport does not automatically carry legal ownership of an asset or a right to future revenue.

---

# 4. The Product and Protocol Relationship

ANZEN is the protocol and treasury infrastructure. Shonko is the consumer-facing feature that makes the protocol useful to creators and supporters.

## 4.1 ANZEN protocol

Responsible for:

- Event and treasury configuration.
- Commitment locking and release rules.
- Credential and proof-verification interfaces.
- Conditional settlement.
- Event-specific evidence and status records.
- Passport issuance conditions.
- Optional agent identity and validation integration.
- Future encrypted-commitment interfaces.

The protocol should expose a small, documented set of operations that other applications can call.

## 4.2 Shonko experience

Responsible for:

- Creator event pages.
- Event discovery and invitations.
- Registration and commitment confirmation.
- Passkey-based onboarding through supported account infrastructure.
- Event check-in.
- Attendance status.
- Treasury outcome and transaction records.
- Early-supporter passport display.

Shonko should feel like an ordinary creator product. Users should not need to understand a blockchain, a curve or a cryptographic proof system just to reserve a place.

## 4.3 Why separate these layers?

A single creator application cannot necessarily serve every future use case.

The protocol may be useful to event platforms, ticketing applications, creator communities, DAOs and other applications that need event-linked commitments.

Separating the core rules from the user interface enables those applications to integrate the protocol without having to reproduce all treasury and verification logic.

This remains a product hypothesis until external developers demonstrate a reason to adopt the protocol rather than build comparable functionality themselves.

---

# 5. The Five Core Engines

## 5.1 Passkey Account and Authorization Engine

**Technology direction:** Mera, WebAuthn and supported EVM account infrastructure.

Mera is a TypeScript library developed by Category Labs for creating accounts from passkeys. Its documented design derives account material using the WebAuthn PRF extension. See the [Mera repository](https://github.com/category-labs/mera) and [Monad's Mera guide](https://monad.docsbot.app/guides/mera).

ANZEN can use Mera to reduce the onboarding burden of conventional seed phrases for supported devices and passkey providers.

### Responsibilities

- Create and restore the supported passkey-derived account.
- Bind the user's account to the applicable account-creation flow.
- Authorize commitment transactions.
- Bind approvals to the intended contract and parameters.
- Protect against replayed or altered action requests.
- Expose a clear signing and recovery experience.

### Important distinction

Mera account derivation and direct WebAuthn signature verification are not interchangeable.

Mera's PRF-derived account flow and the P-256 public-key verification flow are separate mechanisms. The application must implement the correct cryptographic workflow for each. Mera's documented library is also in preview, so compatibility should be tested on the actual supported devices and passkey providers.

A passkey is an authentication mechanism, not a guarantee that a participant is physically present at the event.

## 5.2 Presence Evidence Engine

**Purpose:** Evaluate whether the evidence required by a particular event's check-in policy has been provided.

### Challenge structure

A challenge can be bound to:

- Event identifier.
- Participant identifier or approved credential.
- Unique nonce.
- Chain identifier and relevant contract.
- Expiration time.
- Verification-policy version.

The system should ensure that each accepted proof is associated with the correct event and cannot be reused for a different event or participant.

### Monad P-256 verification

Monad's official documentation specifies a P-256 signature-verification precompile at `0x0100`, following EIP-7951. The documented interface accepts 160 bytes containing the message hash, signature components and public-key coordinates, with a listed precompile cost of 6,900 gas. See the [official Monad precompile documentation](https://docs.monad.xyz/developer-essentials/precompiles).

The precompile verifies the P-256 signature. It does not independently perform every check required for a complete WebAuthn assertion.

ANZEN must also correctly process the assertion data and validate the intended challenge, origin, relying-party context, flags and other required conditions. The implementation should use an established WebAuthn verification library where appropriate and be tested against genuine assertions.

### Physical attendance is a separate question

A valid passkey signature establishes the relevant credential's participation in an authentication ceremony.

The event's attendance claim also depends on how the challenge was issued.

For an initial implementation, the organizer or authorized venue operator can issue a short-lived challenge after an onsite interaction. The policy and trust assumptions must be documented.

Future implementations might introduce stronger venue attestations, multiple independent check-in issuers or other anti-relay measures.

The protocol should describe the result precisely as event-verified check-in under the specified policy, not as universal proof of personhood or impossible-to-fake physical presence.

## 5.3 Programmable Treasury and Settlement Engine

**Purpose:** Lock the specified commitment asset and execute the event's predefined financial policy.

### Core operations

- Create an event treasury.
- Register participant commitments.
- Record the required authorizations.
- Enforce capacity and deadline rules.
- Accept supported check-in evidence.
- Prevent duplicate check-ins and claims.
- Close the attendance window.
- Settle eligible commitments.
- Process no-shows according to the published policy.
- Refund participants when the agreed cancellation policy requires it.
- Maintain an event-level accounting record.

### Example outcome policy

For a successful check-in:

1. Verify the participant's commitment.
2. Check the accepted event evidence.
3. Confirm the event and claim conditions.
4. Release the participant's principal according to the policy.
5. Apply any funded and authorized reward.
6. Record the result and passport eligibility.

For a no-show:

1. Confirm that the relevant deadline and grace period have expired.
2. Confirm that no valid attendance proof was accepted.
3. Respect any pending dispute or exception process.
4. Apply the predeclared forfeiture policy.
5. Record the outcome and the reason for settlement.

The implementation should distinguish between a transfer and a streaming payment. A normal token transfer does not become a streaming payment merely because it occurs after a deadline. Streaming requires an appropriate contract and accounting mechanism.

## 5.4 Encrypted Commitment Engine

**Purpose:** Conceal selected commitment information before it is intended to become public.

The proposed advanced design uses batch threshold encryption, with an implementation informed by Category Labs' BTX research.

A public example, [Betex on Monad](https://github.com/Muhammed5500/Betex), describes an encrypted-order workflow using a threshold-encryption implementation. This makes BTX a research and integration opportunity, not a mechanism that ANZEN can claim is unique merely by adopting it.

### Intended workflow

1. The participant prepares a commitment.
2. The client encrypts the protected payload for the configured threshold-encryption system.
3. The protocol records the necessary commitment or encrypted-message reference.
4. Authorized committee participants provide the required decryption contributions under the scheme.
5. The commitment becomes available for the defined ordering or settlement stage.
6. ANZEN verifies the resulting commitment against the treasury policy.

The system must specify the threshold assumptions, key setup, committee behavior, decryption conditions, malicious-participant handling and failure recovery.

### What privacy does and does not mean

Encryption can conceal selected payload contents under the encryption scheme's assumptions. It does not automatically conceal all transaction metadata.

Public blockchain transactions may still expose sender addresses, timing, gas expenditure, contract interactions and other information. After a conventional ERC-20 transfer settles, the asset movement may also be publicly observable.

If ANZEN wants to conceal commitment amounts after settlement, it needs an appropriate privacy-preserving asset or transaction architecture, not just an encrypted-mempool integration.

BTX is therefore an advanced subsystem. It should be described as operational only after the actual client encryption, threshold-key and decryption flow has been integrated and tested.

## 5.5 Content Passport and Entitlement Engine

**Purpose:** Turn verified participation into a reusable, inspectable credential or benefit.

A content passport is a record of a defined event or supporter status.

### Example passport fields

- Passport identifier.
- Event identifier.
- Issuer or creator.
- Issuance-policy version.
- Relevant event or issuance timestamp.
- Evidence commitment.
- Verification status.
- Defined utility and restrictions.
- Optional revocation or correction reference where supported.

The protocol should avoid publishing private ticket details, personal information or raw attendance data unnecessarily.

### Utility

The initial passport may provide access to future events, early registration, community membership or other explicitly defined rewards.

The future possibility of transferable benefits or revenue-linked assets should be handled by a separate instrument and ruleset.

A content passport is not automatically proof of human uniqueness. If a program needs protection against one person claiming multiple benefits, the issuer and protocol must use an appropriate eligibility mechanism.

---

# 6. The Role of AI Agents and ERC-8004

ERC-8004 proposes standardized registries for agent identity, reputation and validation. Its [official specification](https://ercs.ethereum.org/ERCS/erc-8004) describes mechanisms for discovering agents and obtaining trust signals without relying exclusively on a pre-existing relationship.

ANZEN can eventually use agent infrastructure to support event administration.

Possible delegated actions include:

- Creating a draft event from the creator's instructions.
- Checking registration and commitment status.
- Preparing settlement reports.
- Explaining why a commitment qualifies or fails.
- Requesting an authorized validation process.
- Helping a participant inspect their passport or financial record.

The agent should not independently change treasury rules, authorize unapproved transfers or declare its own work successfully verified.

### Agent participation boundary

An AI agent can perform a delegated digital task on behalf of a user. Its registry identity or reputation does not prove that the human user physically attended an event.

If the product permits agents to sponsor commitments, that sponsorship must be represented separately from human attendance and any attendance-linked benefit.

### Trust policy

High-risk operations should require explicit human authorization and the relevant contract-level permissions.

Agent identity and reputation are useful signals, but they are not sufficient guarantees of honest behavior. ANZEN should define what an agent may do, which independent evidence validates its work, and who bears responsibility for the resulting action.

An ERC-8004 integration is planned only where a compatible registry deployment and interface are available and have been tested.

---

# 7. End-to-End Architecture

The intended architecture separates user interfaces, proof generation, policy evaluation and treasury execution.

```text
                 CREATOR / SUPPORTER
                         |
                         v
                   SHONKO APP
              Event discovery and check-in
                         |
                         v
             INTENT AND POLICY SERVICE
        Event rules, eligibility and approvals
                         |
             +-----------+-----------+
             |                       |
             v                       v
      PASSKEY ACCOUNT         PRESENCE EVIDENCE
       Mera / WebAuthn       Event challenge and proof
             |                       |
             +-----------+-----------+
                         |
                         v
                 ANZEN TREASURY
           Commitments and settlement rules
                         |
             +-----------+-----------+
             |                       |
             v                       v
     VERIFICATION SERVICE     ENCRYPTION MODULE
     Evidence and outcomes    Future threshold encryption
             |                       |
             +-----------+-----------+
                         |
                         v
               CONDITIONAL SETTLEMENT
                         |
               +---------+---------+
               |                   |
               v                   v
           ASSET OUTCOME      CONTENT PASSPORT
               |                   |
               +---------+---------+
                         |
                         v
                 AUDITABLE RECORD
```

## 7.1 Client layer

Provides the event interface, registration, passkey onboarding, commitment approval, check-in flow, transaction status and passport display.

It must never independently determine that a financial action has been completed.

## 7.2 Event and policy service

Validates the event configuration and represents the rules in a typed schema.

It prevents ambiguous terms from becoming deployed financial conditions and identifies unsupported configurations before deployment.

## 7.3 Account and signature layer

Integrates the selected passkey account system and the relevant signature-verification mechanism.

Each cryptographic path must be implemented according to its actual interface. The architecture must not assume that Mera account derivation is identical to a direct P-256 WebAuthn assertion.

## 7.4 Treasury contracts

Maintain the asset commitments, accepted event policies, participant status, valid check-in records and settlement results.

The contracts should enforce deterministic constraints, such as capacity limits, deadline checks and prevention of duplicate settlement.

## 7.5 Verification service

Handles the evidence checks that the selected event policy requires.

Where offchain issuers are involved, the system should record the issuer and the relevant evidence reference and explain the trust assumptions. A backend response should not be treated as cryptographic proof unless it is backed by the required cryptographic verification.

## 7.6 Passport contract

Issues the configured passport after the relevant conditions are met.

The issuance rules must make clear which evidence qualifies a participant and whether the passport can be transferred, revoked or used to claim additional benefits.

## 7.7 Evidence storage

Stores the data needed to reproduce the result and explain the outcome.

Private attendee details and unnecessary event information should not be placed on a public blockchain merely because an auditable record is desirable. Public hashes and commitments must not be presented as proof of the truth of their underlying data without a valid verification procedure.

---

# 8. Security and Failure Handling

Financial commitments require explicit security boundaries and recovery procedures.

## 8.1 Invalid or replayed check-in proof

An assertion for one event must not automatically be valid for another event. Event-specific challenges, nonces, expirations and replay protection are mandatory.

## 8.2 Misconfigured event

An organizer might accidentally configure an incorrect amount, deadline or payout policy.

The application must validate mandatory fields, present the complete configuration for review and bind the published policy to the deployed treasury. Any permitted amendment should be explicit and auditable.

## 8.3 Organizer abuse

An organizer could attempt to mark attendees absent or cancel an event improperly.

The protocol should define who is authorized to issue check-in evidence, how exceptions are handled, whether a dispute window exists and how cancellation affects all participant funds.

## 8.4 False positives and missing evidence

A participant might attend but encounter a failed network connection, a malfunctioning scanner or an unavailable check-in operator.

The policy must distinguish an explicit failed check-in from missing or inconclusive evidence. Where the event's rules require an appeal or grace period, the treasury must not settle prematurely.

## 8.5 Double claims and duplicate execution

Repeated transactions, requests or proof submissions must not cause a participant to receive the same refund, reward or passport twice.

Unique claim identifiers, explicit status transitions and idempotent execution paths should be covered by tests.

## 8.6 Encrypted-commitment failure

Threshold-encryption systems can fail if the required participants do not cooperate or if key setup assumptions are violated.

ANZEN must define timeouts, fallback behavior and refund or dispute rules. An encrypted commitment should never leave participant funds trapped indefinitely just because decryption did not occur.

## 8.7 Asset and settlement restrictions

Not every token is freely transferable or suitable for conditional escrow.

A real tokenized-equity integration must be evaluated against its issuer terms, transfer restrictions, custody model, corporate-action handling and applicable legal requirements. If the asset cannot support the necessary lock-and-release workflow, ANZEN must not promise that outcome.

## 8.8 Contract and operational security

Before handling real funds, the implementation needs tests for reentrancy, access control, signature malleability, invalid parameters, arithmetic boundaries, duplicate settlement, cancellation, failed token transfers and incorrect state transitions.

High-value deployments require independent security review and operational controls. A successful testnet demo is not a production security audit.

---

# 9. Conditional Finance and Tokenized Equity

The financial vision of ANZEN is to make supported assets interact with real-world conditions instead of existing solely as wallet balances.

A token can be locked in a treasury before an event, returned after qualifying participation, transferred under a published rule or used as an input to a supported financial operation.

## 9.1 Why programmable assets matter

Smart contracts can create deterministic relationships between asset balances and predefined conditions.

Examples include:

- A contribution is returned after the event's verified check-in.
- A reward is issued to qualifying supporters.
- A commitment is forfeited following a verified failure under the published rules.
- A supported asset is streamed or distributed according to an approved schedule.
- A benefit can only be claimed once per qualifying event.

The essential idea is that the financial action changes according to a verifiable condition.

## 9.2 Test assets versus real equity

During development, an ERC-20 test token can demonstrate the escrow, lock, refund and distribution mechanics.

It must be clearly labelled as a test asset and must not be marketed as ownership of an actual company share.

If the finance track requires a tokenized equity itself to be programmed by a contract, the final implementation must use an appropriate supported equity token and demonstrate its actual onchain rights. If that asset is unavailable or incompatible, ANZEN must state the limitation rather than substitute a mock token without disclosure.

## 9.3 Streaming

A streaming feature should use a contract or accounting mechanism that actually releases value according to the specified schedule.

A one-time transfer at settlement is a conditional payout, not necessarily a stream.

The protocol should select the implementation appropriate to the asset, the recipients and the conditions.

## 9.4 Financial benefits and legal boundaries

The initial supporter passport should provide clearly defined non-financial utility unless a separate, properly supported instrument governs an economic entitlement.

Promising holders a percentage of future ticket sales or transferable yield-bearing rights may introduce securities, investment-contract, consumer-protection and other legal questions.

Such a feature should not be implemented as a casual NFT extension. It needs an appropriate legal and commercial structure, with precise definitions of the entitlement, transferability, issuer responsibilities and distribution rules.

---

# 10. Business Model

ANZEN should initially sell the usefulness of the event and treasury workflow, not the novelty of its cryptography.

## Phase 1 — Creator and community pilots

Offer event creation, participant commitments, check-in and verified outcomes for a small group of creators and communities.

The objective is to understand whether conditional commitments improve attendance reliability or reduce administration enough that organizers want to use the product again.

## Phase 2 — Organizer tools

Potential paid features include:

- Multiple event treasuries.
- Advanced settlement policies.
- Participant-management tools.
- Verification and audit reports.
- Passport issuance and community analytics.
- Reusable creator identity and access configuration.

Pricing should be based on the value organizers receive and tested through real pilots.

## Phase 3 — Protocol integrations

External applications can integrate supported treasury and verification operations.

Potential revenue could come from contract deployment services, organizer subscriptions, authorized workflow fees or infrastructure services, where justified by actual operational costs and legal requirements.

## Phase 4 — Financial-asset and encrypted-commitment integrations

Advanced integrations may support additional assets, conditional distributions or protected pre-settlement commitments.

These require demonstrated technical feasibility and a defensible user need. They should not be treated as established revenue sources before the integrations exist.

---

# 11. Go-to-Market Strategy

The initial customer segment should consist of creators who repeatedly organize small, capacity-limited events and care about recognizing early supporters.

The proposed starting market includes independent musicians, local creators, community organizers and small event producers.

Shimla and Dharamshala can serve as potential pilot markets if real creator relationships and suitable events are available. A pilot should not be claimed until the participants and event are confirmed.

## Distribution

**Direct creator outreach:** Demonstrate the workflow to creators who already organize ticketed or RSVP-based events.

**Community partnerships:** Work with local groups and student communities that can provide a practical testing environment.

**Creator referrals:** Allow creators to invite their audience and reuse the treasury workflow for future events.

**Developer integrations:** Publish the protocol interfaces and verification requirements once the underlying APIs are stable enough for independent integration.

## Demand validation

Measure whether creators encounter a genuine problem with attendance, deposits, reward eligibility or supporter recognition.

A strong pilot would compare the current workflow against ANZEN's workflow and record the actual effect on administrative time, check-in disputes, completion rates and willingness to reuse the system.

The project should not claim 1,000 groups, significant transaction volume or a large market opportunity unless those numbers are supported by evidence.

---

# 12. Competitive Positioning

ANZEN sits at the intersection of event coordination, programmable finance, passkey authorization and digital credentials.

It should not claim that no other application supports passkeys, encrypted orders, check-in credentials or programmable escrow. Those are existing research and implementation areas.

Mera already provides passkey-based account functionality; Monad documents its P-256 precompile; BTX-based encrypted-order implementations exist; and ERC-8004 defines agent identity, reputation and validation registries.

The proposed differentiation is the **event-linked relationship between a commitment, check-in evidence, conditional treasury settlement and an early-supporter credential**.

The product's competitive strength depends on demonstrating that the combination solves a recurring creator problem more effectively than separate ticketing, payment and loyalty tools.

The moat is not simply using multiple cryptographic primitives. It must develop through trusted integrations, reliable settlement logic, creator distribution, reusable event policies and an ecosystem of applications that use the protocol.

---

# 13. Research Foundations

The following sources inform the design. They are research references and technical documentation, not proof that the proposed ANZEN integrations have been implemented or independently audited.

### Passkeys and account infrastructure

- [Monad P-256 Precompile Documentation](https://docs.monad.xyz/developer-essentials/precompiles): The documented `0x0100` precompile for P-256 signature verification, including the input format and listed gas cost.
- [Mera by Category Labs](https://github.com/category-labs/mera): Passkey-based account generation, PRF-derived account material and supported client environments.
- [OpenZeppelin WebAuthn Documentation](https://docs.openzeppelin.com/contracts/5.x/api/utils/cryptography): Reference implementations and considerations for WebAuthn and P-256 verification.

### Intent and autonomous-agent authorization

- [Know Your Intent: An Autonomous Multi-Perspective LLM Agent Framework for DeFi User Transaction Intent Mining](https://arxiv.org/abs/2511.15456): Research into multi-perspective transaction-intent analysis and an evaluator intended to reduce hallucinations.
- [Secure Autonomous Agent Payments: Verifying Authenticity and Intent in a Trustless Environment](https://arxiv.org/abs/2511.15712): A proposed framework connecting agent identity, authorization intent and privacy-oriented policy checking.
- [ERC-8004 Trustless Agents](https://ercs.ethereum.org/ERCS/erc-8004): A specification proposing identity, reputation and validation registries for discovering and evaluating agents.

### Personhood and privacy

- [Personhood Credentials: Artificial Intelligence and the Value of Privacy-Preserving Tools to Distinguish Who Is Real Online](https://arxiv.org/abs/2408.07892): Research on credentials that allow people to demonstrate personhood without disclosing personal information.
- [Betex: Encrypted Order Book on Monad](https://github.com/Muhammed5500/Betex): A public implementation describing a BTX threshold-encryption-based encrypted-order workflow. This establishes an existing implementation example, not an independent security audit.

### Important interpretation

ANZEN draws architectural inspiration from these resources. It must not attribute an implementation to a paper that only proposes a framework, assume a precompile verifies an entire authentication protocol, or describe a simulated component as a production integration.

---

# 14. Hackathon Track Strategy

Monad Metropolis publicly describes four tracks: Onchain Finance & Trading, Consumer Products & Payments, Social, Attention & Culture, and Trust, Identity & AI Infrastructure. Its published overview frames them around financial infrastructure, accessible consumer payments, social and cultural applications, and identity or AI-native trust primitives. See the [event overview](https://luma.com/metropolis-lon-oct-2026).

ANZEN has a potential fit across these categories, but the strength of each entry depends on what the actual product demonstrates.

## Consumer Products & Payments

**Core story:** An attendee makes a commitment through a passkey-friendly experience, checks in at an event and receives the outcome specified by the treasury policy.

The judges should be able to reproduce the event-commitment and settlement flow rather than only see a wallet connection or polished interface.

## Social, Attention & Culture

**Core story:** Verified participation creates a provenance-bearing supporter passport that connects an audience member to a creator's event and future community benefits.

This is likely the most natural primary positioning for Creator IRL Drop because the initial customer, distribution channel and core user experience are centered on creators and their audiences.

## Trust, Identity & AI Infrastructure

**Core story:** An application can verify an event-specific authorization and check-in policy, then use the evidence to enforce an outcome. Passkeys, credential checks and agent interfaces support the workflow.

This positioning becomes stronger when the verification interfaces are reusable, documented and demonstrated independently of the Shonko frontend.

## Onchain Finance & Trading

**Core story:** A supported financial asset is locked and its return, forfeiture or distribution is controlled by a contract implementing real conditions.

This track requires particular care if the prompt specifies tokenized equity. A mock ERC-20 demonstrates programmable settlement mechanics, but does not establish that actual tokenized equity is being used.

ANZEN should choose its primary track based on the strongest working demonstration and obey the competition's submission rules. It should not assume that a single project automatically qualifies for every track or that combining several track themes guarantees first place.

---

# 15. Development Roadmap

## Stage 1 — The verifiable Creator IRL Drop

Build one complete event workflow:

1. Create and configure a treasury.
2. Register a participant.
3. Commit a clearly labelled test asset.
4. Issue an event-specific check-in challenge.
5. Validate a genuine supported WebAuthn assertion and the associated check-in policy.
6. Apply the predefined settlement rule.
7. Issue the qualifying supporter passport.
8. Verify the outcome and show the transaction references.

The demonstration must identify which actions are real and which are simulated.

## Stage 2 — Security and failure handling

Add reproducible tests for:

- Invalid WebAuthn signatures.
- Mismatched or expired challenges.
- Replayed check-ins.
- Incorrect participant or event identifiers.
- Duplicate claims.
- Invalid treasury configuration.
- Cancellation and refunds.
- Missing attendance evidence.
- Failed transfers.
- Unauthorized changes to protected parameters.

Run the tests, record their results and distinguish mock-verifier tests from actual precompile integration tests.

## Stage 3 — Improve attendance assurance

Test the onsite challenge process, including expired codes, unauthorized issuers, replay attempts and challenges shared outside the venue.

Document the limits of the trusted check-in issuer and define the procedure for disputed attendance.

## Stage 4 — Content passports and developer interface

Implement passport issuance, display and verification. Publish the relevant policy interface and allow an external application to inspect the credential's provenance without accessing unnecessary personal information.

## Stage 5 — Encrypted commitments

Integrate the threshold-encryption scheme as a separate subsystem.

Verify key setup, encryption, threshold decryption, reveal conditions, committee failure handling and the information still visible onchain.

This stage is not complete merely because a user interface includes an encrypted toggle or the transaction includes a ciphertext.

## Stage 6 — Real programmable-asset integration

Identify a supported, compatible financial asset and confirm its actual rights and transfer restrictions. Implement lock-and-settle logic appropriate to that instrument and subject it to legal and security review.

Only then describe the financial-asset workflow as a real tokenized-equity implementation.

## Stage 7 — Agent and external protocol integration

Add ERC-8004 identity, reputation or validation interfaces where they solve a specific operational problem.

Agent actions must be scoped by explicit permissions, and attendance-linked benefits must remain governed by the event's actual evidence policy.

---

# 16. Success Metrics

ANZEN should measure whether the product improves event operations and creates durable value.

### Product metrics

- Number of events created and completed.
- Percentage of commitments settled according to policy.
- Check-in completion rate.
- Number of disputed or unresolved attendance claims.
- Time required to reconcile a completed event.
- Creator repeat usage.
- Participant completion and return rates.

### Technical metrics

- Successful and failed treasury execution tests.
- Invalid proof detection.
- Replay prevention.
- Duplicate-settlement prevention.
- Settlement correctness under defined conditions.
- Percentage of completed actions with reproducible evidence.
- Time to recover from a verification or execution failure.

### Business metrics

- Number of creators who complete a second event.
- Cost of onboarding and supporting an organizer.
- Willingness to pay for advanced organizer tooling.
- Conversion from free pilot to paid usage.
- Referral rate between creators.
- Support and infrastructure costs per completed event.

These metrics should be collected from actual use and defined test runs. Projections must remain clearly distinguished from observed results.

---

# 17. Final Product Definition

**ANZEN is a programmable treasury protocol that connects real-world participation to predefined financial outcomes.**

It allows creators to establish commitments, participants to authorize and fund them, supported check-in systems to provide event-linked evidence, and smart contracts to apply the published settlement rules.

Its passkey layer improves account access and authorization. Its presence-evidence engine evaluates the event's check-in policy. Its treasury contracts hold and distribute supported assets. Its passport layer records provenance-bearing participation credentials. Its future encrypted-commitment and agent integrations extend the system when their technical and commercial requirements justify them.

Shonko is the initial consumer experience through which creators and supporters use the protocol.

The product starts with Creator IRL Drop because it combines a clear customer, a recurring event workflow, programmable financial consequences and a meaningful reason to recognize early supporters.

The long-term ambition is to make ANZEN reusable beyond one creator application: a protocol that allows other applications to create and settle event-linked commitments, provided they implement the required verification, authorization and asset integrations.

The core promise is not that a passkey proves physical presence or that every asset is already private and programmable.

It is this:

**ANZEN makes the rules explicit, the required evidence inspectable and the financial outcome enforceable by the supported protocol.**

That is the foundation of the company. The differentiation must be earned through a real end-to-end implementation, trustworthy evidence, a useful creator experience and repeated adoption—not simply through the number of cryptographic primitives named in the pitch.