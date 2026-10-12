//! ANZEN on Solana: refundable commitments for real-world bookings, settled by verified presence.
//!
//! Accounts are P-256 keys (browser passkeys or Android Keystore), identified by keyId = sha256(qx ‖ qy) and verified
//! through the secp256r1 precompile (see auth.rs), so people need no wallet and no SOL: a relayer pays fees and rent,
//! and it can't forge or alter what a key signed. Money is an SPL token held in the program's vault.
//!
//! The rules are the same as the Monad v4 contract (contracts/src/AnzenTreasury.sol): commit or join the waitlist,
//! give the place back (free before the commit deadline, which earns a streak shield; the published late fee after
//! it unless the waitlist refills the seat), check in with a 45-second venue code or by staff scanning the guest's
//! own code, then settle by the published rule. An AI agent can book inside an Agent Pass its owner signed (cap,
//! expiry, categories, late-fee ceiling, active bookings, lead time) and nothing more: presence needs the owner's key.
//! Every settled agent booking updates that agent's show-up record, written only here.

use anchor_lang::prelude::*;
use anchor_lang::solana_program::sysvar::instructions as ix_sysvar;
use anchor_spl::token::{self, Mint, Token, TokenAccount, Transfer};

pub mod auth;
use auth::{addr20, key_id, verify_ed25519, verify_p256, Auth, Pre};

declare_id!("EvZ3WxWjkmSgreZimTqqhrGWs7gMNGk4Tun9D57Rkwq4");

pub const MAX_WAITLIST: usize = 32;
pub const PRESENT_WINDOW: i64 = 45;
pub const MAX_SHIELDS: u32 = 2;

pub mod status {
    pub const NONE: u8 = 0;
    pub const COMMITTED: u8 = 1;
    pub const CHECKED_IN: u8 = 2;
    pub const SETTLED: u8 = 3;
    pub const WAITLISTED: u8 = 4;
    pub const RELEASED: u8 = 5;
}

#[program]
pub mod anzen_core {
    use super::*;

    pub fn initialize(ctx: Context<Initialize>, chain_id: u64, issuer: Pubkey) -> Result<()> {
        let c = &mut ctx.accounts.config;
        c.admin = ctx.accounts.admin.key();
        c.mint = ctx.accounts.mint.key();
        c.vault = ctx.accounts.vault.key();
        c.issuer = issuer;
        c.chain_id = chain_id;
        c.bump = ctx.bumps.config;
        Ok(())
    }

    /// Credit `amount` to a key's balance. Anyone may fund any key.
    pub fn deposit_for(ctx: Context<DepositFor>, key: [u8; 32], amount: u64) -> Result<()> {
        token::transfer(CpiContext::new(ctx.accounts.token_program.to_account_info(), Transfer {
            from: ctx.accounts.from.to_account_info(),
            to: ctx.accounts.vault.to_account_info(),
            authority: ctx.accounts.payer.to_account_info(),
        }), amount)?;
        let m = &mut ctx.accounts.member;
        m.key = key;
        m.balance += amount;
        Ok(())
    }

    /// The owner moves funds out to any token account. "to" in the preimage is addr20 of that account's owner.
    pub fn withdraw(ctx: Context<Withdraw>, qx: [u8; 32], qy: [u8; 32], amount: u64, deadline: i64, auth: Auth) -> Result<()> {
        let c = &ctx.accounts.config;
        let m = &mut ctx.accounts.member;
        require!(m.key == key_id(&qx, &qy), AnzenError::InvalidSignature);
        let pre = Pre::new(b"ANZEN/withdraw/v1", c.chain_id).addr(&addr20(&ctx.accounts.to.owner)).u(amount as u128)
            .u(m.nonce as u128).u(deadline as u128);
        authorize(&ctx.accounts.ixs, &qx, &qy, deadline, &pre.0, &auth)?;
        m.nonce += 1;
        require!(m.balance >= amount, AnzenError::InsufficientBalance);
        m.balance -= amount;
        let seeds: &[&[u8]] = &[b"config", &[c.bump]];
        token::transfer(CpiContext::new_with_signer(ctx.accounts.token_program.to_account_info(), Transfer {
            from: ctx.accounts.vault.to_account_info(),
            to: ctx.accounts.to.to_account_info(),
            authority: ctx.accounts.config.to_account_info(),
        }, &[seeds]), amount)
    }

    /// Publish an event: the terms (and the sha256 of the policy document) under one organizer signature.
    pub fn create_event(ctx: Context<CreateEvent>, a: CreateArgs) -> Result<()> {
        let now = Clock::get()?.unix_timestamp;
        let c = &mut ctx.accounts.config;
        let org = &mut ctx.accounts.organizer;
        let key = key_id(&a.qx, &a.qy);
        require!(a.event_id == c.event_count + 1, AnzenError::InvalidConfig);
        require!(org.key == [0u8; 32] || org.key == key, AnzenError::InvalidConfig);
        require!(
            a.amount > 0 && a.capacity > 0 && a.commit_deadline > now && a.commit_deadline <= a.check_in_start
                && a.check_in_start < a.check_in_end && a.no_show <= 1 && a.category <= 31 && a.late_fee_bps <= 10_000,
            AnzenError::InvalidConfig
        );
        // The signed terms name this program's check-in issuer (its first 20 bytes, like every address slot).
        let pre = Pre::new(b"ANZEN/create/v2", c.chain_id).u(org.nonce as u128).u(a.deadline as u128)
            .b32(&key).b32(&a.policy_hash).addr(&addr20(&c.issuer)).u(a.amount as u128).u(a.capacity as u128)
            .u(a.commit_deadline as u128).u(a.check_in_start as u128).u(a.check_in_end as u128).u(a.no_show as u128)
            .u(a.category as u128).u(a.late_fee_bps as u128);
        authorize(&ctx.accounts.ixs, &a.qx, &a.qy, a.deadline, &pre.0, &a.auth)?;
        org.key = key;
        org.nonce += 1;
        c.event_count += 1;
        let e = &mut ctx.accounts.event;
        e.id = a.event_id;
        e.organizer = key;
        e.policy_hash = a.policy_hash;
        e.amount = a.amount;
        e.capacity = a.capacity;
        e.commit_deadline = a.commit_deadline;
        e.check_in_start = a.check_in_start;
        e.check_in_end = a.check_in_end;
        e.no_show = a.no_show;
        e.category = a.category;
        e.late_fee_bps = a.late_fee_bps;
        emit!(EventCreated { event_id: e.id, organizer: key, policy_hash: a.policy_hash });
        Ok(())
    }

    pub fn commit(ctx: Context<Commit>, event_id: u64, key: [u8; 32], qx: [u8; 32], qy: [u8; 32], deadline: i64, auth: Auth) -> Result<()> {
        let m = &mut ctx.accounts.member;
        require!(key_id(&qx, &qy) == key && m.key == key, AnzenError::InvalidSignature);
        let pre = Pre::new(b"ANZEN/commit/v1", ctx.accounts.config.chain_id).u(event_id as u128)
            .u(ctx.accounts.event.amount as u128).u(m.nonce as u128).u(deadline as u128);
        authorize(&ctx.accounts.ixs, &qx, &qy, deadline, &pre.0, &auth)?;
        m.nonce += 1;
        place(&mut ctx.accounts.event, m, &mut ctx.accounts.participation, key)
    }

    /// The owner grants an Agent Pass (cap 0 revokes). `agent_id` is kept in the signed preimage for parity with the
    /// apps (NO_AGENT_ID = 2^256-1); on Solana the agent's identity is its ed25519 key.
    pub fn grant_pass(ctx: Context<GrantPass>, key: [u8; 32], qx: [u8; 32], qy: [u8; 32], agent: Pubkey, agent_id: [u8; 32],
                      cap: u64, expiry: i64, rules: PassRules, deadline: i64, auth: Auth) -> Result<()> {
        let m = &mut ctx.accounts.member;
        require!(key_id(&qx, &qy) == key && (m.key == [0u8; 32] || m.key == key), AnzenError::InvalidSignature);
        let pre = Pre::new(b"ANZEN/pass/v1", ctx.accounts.config.chain_id).addr(&addr20(&agent)).b32(&agent_id)
            .u(cap as u128).u(expiry as u128).u(rules.categories as u128).u(rules.max_late_fee_bps as u128)
            .u(rules.max_active as u128).u(rules.max_lead_days as u128).u(m.nonce as u128).u(deadline as u128);
        authorize(&ctx.accounts.ixs, &qx, &qy, deadline, &pre.0, &auth)?;
        m.key = key;
        m.nonce += 1;
        m.pass = Pass { agent, cap, spent: 0, expiry, rules, active: 0 };
        let r = &mut ctx.accounts.agent_record;
        r.agent = agent;
        emit!(PassGranted { key, agent, cap, expiry });
        Ok(())
    }

    /// A pass without rules (cap and expiry only), as the v1 apps sign it.
    pub fn delegate(ctx: Context<GrantPass>, key: [u8; 32], qx: [u8; 32], qy: [u8; 32], agent: Pubkey, agent_id: [u8; 32],
                    cap: u64, expiry: i64, deadline: i64, auth: Auth) -> Result<()> {
        let m = &mut ctx.accounts.member;
        require!(key_id(&qx, &qy) == key && (m.key == [0u8; 32] || m.key == key), AnzenError::InvalidSignature);
        let pre = Pre::new(b"ANZEN/delegate/v1", ctx.accounts.config.chain_id).addr(&addr20(&agent)).b32(&agent_id)
            .u(cap as u128).u(expiry as u128).u(m.nonce as u128).u(deadline as u128);
        authorize(&ctx.accounts.ixs, &qx, &qy, deadline, &pre.0, &auth)?;
        m.key = key;
        m.nonce += 1;
        let rules = PassRules { categories: 0, max_late_fee_bps: u16::MAX, max_active: 0, max_lead_days: 0 };
        m.pass = Pass { agent, cap, spent: 0, expiry, rules, active: 0 };
        ctx.accounts.agent_record.agent = agent;
        emit!(PassGranted { key, agent, cap, expiry });
        Ok(())
    }

    /// An agent books with its owner's balance inside the Agent Pass. It signs with its own ed25519 key.
    pub fn commit_by_agent(ctx: Context<CommitByAgent>, event_id: u64, key: [u8; 32], deadline: i64) -> Result<()> {
        let now = Clock::get()?.unix_timestamp;
        let e = &ctx.accounts.event;
        let m = &mut ctx.accounts.member;
        let r = &mut ctx.accounts.agent_record;
        require!(now <= deadline, AnzenError::Expired);
        require!(m.pass.agent != Pubkey::default() && now <= m.pass.expiry && r.agent == m.pass.agent, AnzenError::NotDelegated);
        let pre = Pre::new(b"ANZEN/agentcommit/v1", ctx.accounts.config.chain_id).u(event_id as u128).b32(&key)
            .u(e.amount as u128).u(r.nonce as u128).u(deadline as u128);
        verify_ed25519(&ctx.accounts.ixs, &m.pass.agent, &pre.0)?;
        r.nonce += 1;
        require!(m.pass.spent + e.amount <= m.pass.cap, AnzenError::BudgetExceeded);
        let rules = &m.pass.rules;
        require!(rules.categories == 0 || rules.categories & (1u32 << e.category) != 0, AnzenError::CategoryNotAllowed);
        require!(e.late_fee_bps <= rules.max_late_fee_bps, AnzenError::FeeAboveLimit);
        require!(rules.max_active == 0 || m.pass.active < rules.max_active, AnzenError::TooManyActive);
        require!(rules.max_lead_days == 0 || e.check_in_start <= now + rules.max_lead_days as i64 * 86_400, AnzenError::TooFarAhead);
        m.pass.spent += e.amount;
        m.pass.active += 1;
        r.reservations += 1;
        let p = &mut ctx.accounts.participation;
        place(&mut ctx.accounts.event, m, p, key)?;
        p.by_agent = true;
        p.agent = r.agent;
        Ok(())
    }

    /// The owner gives the place back (or leaves the waitlist) before check-in opens. When a waitlist exists, the
    /// first person still waiting must be passed as the first remaining account; they take the seat at once.
    pub fn release<'info>(ctx: Context<'_, '_, 'info, 'info, Release<'info>>, event_id: u64, key: [u8; 32], qx: [u8; 32],
                          qy: [u8; 32], deadline: i64, auth: Auth) -> Result<()> {
        let now = Clock::get()?.unix_timestamp;
        let m = &mut ctx.accounts.member;
        require!(key_id(&qx, &qy) == key && m.key == key, AnzenError::InvalidSignature);
        let pre = Pre::new(b"ANZEN/release/v1", ctx.accounts.config.chain_id).u(event_id as u128).u(m.nonce as u128)
            .u(deadline as u128);
        authorize(&ctx.accounts.ixs, &qx, &qy, deadline, &pre.0, &auth)?;
        m.nonce += 1;
        let e = &mut ctx.accounts.event;
        require!(!e.cancelled && now < e.check_in_start, AnzenError::WrongPhase);
        let p = &mut ctx.accounts.participation;
        let mut fee = 0u64;
        if p.status == status::WAITLISTED {
            e.waitlisted -= 1;
            if let Some(w) = e.waitlist.iter_mut().find(|w| w.key == key) {
                w.waiting = false;
            }
        } else if p.status == status::COMMITTED {
            e.committed -= 1;
            e.released += 1;
            let refilled = promote(e, ctx.remaining_accounts)?;
            if now <= e.commit_deadline {
                m.record.released_in_time += 1;
                if m.record.shields < MAX_SHIELDS {
                    m.record.shields += 1;
                }
            } else if !refilled {
                fee = e.amount * e.late_fee_bps as u64 / 10_000;
                ctx.accounts.organizer.balance += fee;
                e.recovered += fee;
            }
        } else {
            return err!(AnzenError::NotCommitted);
        }
        p.status = status::RELEASED;
        if p.by_agent && m.pass.active > 0 {
            m.pass.active -= 1;
        }
        m.balance += e.amount - fee;
        emit!(Released { event_id, key, refunded: e.amount - fee, fee });
        Ok(())
    }

    /// Bronze: the guest's key signs a venue code the issuer signed, valid 45 s.
    pub fn check_in(ctx: Context<CheckIn>, event_id: u64, key: [u8; 32], qx: [u8; 32], qy: [u8; 32], challenge: [u8; 32],
                    expires_at: i64, auth: Auth) -> Result<()> {
        let now = Clock::get()?.unix_timestamp;
        let c = &ctx.accounts.config;
        let e = &mut ctx.accounts.event;
        require!(!e.cancelled && now >= e.check_in_start && now <= e.check_in_end, AnzenError::WrongPhase);
        require!(now <= expires_at, AnzenError::Expired);
        require!(key_id(&qx, &qy) == key, AnzenError::InvalidSignature);
        let issued = Pre::new(b"ANZEN/challenge/v1", c.chain_id).u(event_id as u128).b32(&challenge).u(expires_at as u128);
        verify_ed25519(&ctx.accounts.ixs, &c.issuer, &issued.0)?;
        let pre = Pre::new(b"ANZEN/checkin/v1", c.chain_id).u(event_id as u128).b32(&challenge).u(expires_at as u128);
        verify_p256(&ctx.accounts.ixs, &qx, &qy, &pre.0, &auth)?;
        attend(e, &mut ctx.accounts.participation, now, 1)
    }

    /// Silver: staff scan the guest's own 45-second code; the organizer's key countersigns that guest and time.
    pub fn check_in_by_staff(ctx: Context<CheckIn>, event_id: u64, key: [u8; 32], qx: [u8; 32], qy: [u8; 32], issued_at: i64,
                             guest_auth: Auth, sx: [u8; 32], sy: [u8; 32], staff_auth: Auth) -> Result<()> {
        let now = Clock::get()?.unix_timestamp;
        let c = &ctx.accounts.config;
        let e = &mut ctx.accounts.event;
        require!(!e.cancelled && now >= e.check_in_start && now <= e.check_in_end, AnzenError::WrongPhase);
        require!(issued_at <= now + 5 && now <= issued_at + PRESENT_WINDOW, AnzenError::Expired);
        require!(key_id(&sx, &sy) == e.organizer, AnzenError::NotOrganizer);
        require!(key_id(&qx, &qy) == key, AnzenError::InvalidSignature);
        let present = Pre::new(b"ANZEN/present/v1", c.chain_id).u(event_id as u128).u(issued_at as u128);
        verify_p256(&ctx.accounts.ixs, &qx, &qy, &present.0, &guest_auth)?;
        let staff = Pre::new(b"ANZEN/staff/v1", c.chain_id).u(event_id as u128).b32(&key).u(issued_at as u128);
        verify_p256(&ctx.accounts.ixs, &sx, &sy, &staff.0, &staff_auth)?;
        attend(e, &mut ctx.accounts.participation, now, 2)
    }

    /// The organizer cancels before check-in opens; everyone can then be refunded in full.
    pub fn cancel_event(ctx: Context<CancelEvent>, event_id: u64, qx: [u8; 32], qy: [u8; 32], deadline: i64, auth: Auth) -> Result<()> {
        let now = Clock::get()?.unix_timestamp;
        let e = &mut ctx.accounts.event;
        let org = &mut ctx.accounts.organizer;
        require!(key_id(&qx, &qy) == e.organizer, AnzenError::NotOrganizer);
        let pre = Pre::new(b"ANZEN/cancel/v1", ctx.accounts.config.chain_id).u(event_id as u128).u(org.nonce as u128)
            .u(deadline as u128);
        authorize(&ctx.accounts.ixs, &qx, &qy, deadline, &pre.0, &auth)?;
        org.nonce += 1;
        require!(!e.cancelled && now < e.check_in_start, AnzenError::WrongPhase);
        e.cancelled = true;
        Ok(())
    }

    /// After check-in closes, fixes the payout per attendee. Permissionless and one-time.
    pub fn finalize(ctx: Context<Finalize>, event_id: u64) -> Result<()> {
        let _ = event_id; // selects the event account
        let now = Clock::get()?.unix_timestamp;
        let e = &mut ctx.accounts.event;
        require!(!e.cancelled && !e.finalized && now > e.check_in_end, AnzenError::WrongPhase);
        let forfeited = (e.committed - e.attended) as u64 * e.amount;
        let share = if e.no_show == 1 && e.attended > 0 { forfeited / e.attended as u64 } else { 0 };
        let organizer_share = forfeited - share * e.attended as u64;
        e.attendee_payout = e.amount + share;
        e.finalized = true;
        e.recovered += organizer_share;
        ctx.accounts.organizer.balance += organizer_share;
        Ok(())
    }

    /// Applies the published outcome to one participant. Permissionless: money only goes to that participant.
    pub fn settle(ctx: Context<Settle>, event_id: u64, key: [u8; 32]) -> Result<()> {
        let e = &ctx.accounts.event;
        let p = &mut ctx.accounts.participation;
        let m = &mut ctx.accounts.member;
        require!(p.status != status::NONE, AnzenError::NotCommitted);
        require!(p.status != status::SETTLED && p.status != status::RELEASED, AnzenError::AlreadySettled);
        let waited = p.status == status::WAITLISTED;
        let amount = if e.cancelled || (waited && e.finalized) {
            e.amount
        } else {
            require!(e.finalized, AnzenError::WrongPhase);
            if p.attended { e.attendee_payout } else { 0 }
        };
        p.status = status::SETTLED;
        p.settled_amount = amount;
        m.balance += amount;
        if p.by_agent && m.pass.agent == p.agent && m.pass.active > 0 {
            m.pass.active -= 1;
        }
        emit!(Settled { event_id, key, attended: p.attended, amount });
        if e.cancelled || waited {
            return Ok(());
        }
        let r = &mut m.record;
        if p.attended {
            r.attended += 1;
            r.streak += 1;
            r.best_streak = r.best_streak.max(r.streak);
            let c = &mut ctx.accounts.config;
            c.passport_count += 1;
            p.passport_id = c.passport_count;
        } else {
            r.missed += 1;
            if r.shields > 0 { r.shields -= 1 } else { r.streak = 0 }
        }
        if p.by_agent {
            let a = ctx.accounts.agent_record.as_mut().ok_or(AnzenError::NotDelegated)?;
            require!(a.agent == p.agent, AnzenError::NotDelegated);
            a.settled += 1;
            if p.attended {
                a.showed_up += 1;
            }
            emit!(ShowUpReported { agent: a.agent, event_id, key, attended: p.attended });
        }
        Ok(())
    }
}

// ---------------------------------------------------------------- shared rules

fn authorize(ixs: &AccountInfo, qx: &[u8; 32], qy: &[u8; 32], deadline: i64, preimage: &[u8], auth: &Auth) -> Result<()> {
    require!(Clock::get()?.unix_timestamp <= deadline, AnzenError::Expired);
    verify_p256(ixs, qx, qy, preimage, auth)
}

/// Takes a seat, or (when full) a place on the waitlist with the commitment held.
fn place(e: &mut Event, m: &mut Member, p: &mut Participation, key: [u8; 32]) -> Result<()> {
    require!(!e.cancelled && Clock::get()?.unix_timestamp <= e.commit_deadline, AnzenError::WrongPhase);
    require!(p.status == status::NONE, AnzenError::AlreadyCommitted);
    // ponytail: a host can't hold a place at their own event (their balance account would be loaded twice).
    require!(key != e.organizer, AnzenError::OwnEvent);
    require!(m.balance >= e.amount, AnzenError::InsufficientBalance);
    p.event_id = e.id;
    p.key = key;
    if e.committed >= e.capacity {
        e.waitlist.retain(|w| w.waiting);
        require!(e.waitlist.len() < MAX_WAITLIST && (e.waitlist.len() as u32) < e.capacity, AnzenError::EventFull);
        e.waitlist.push(WaitEntry { key, waiting: true });
        e.waitlisted += 1;
        p.status = status::WAITLISTED;
    } else {
        e.committed += 1;
        p.status = status::COMMITTED;
    }
    m.balance -= e.amount;
    Ok(())
}

/// Moves the first person still waiting into the freed seat. Their participation account must be passed.
fn promote<'info>(e: &mut Event, remaining: &'info [AccountInfo<'info>]) -> Result<bool> {
    let Some(i) = e.waitlist.iter().position(|w| w.waiting) else { return Ok(false) };
    let info = remaining.first().ok_or(AnzenError::WaitlistAccountMissing)?;
    let mut p: Account<Participation> = Account::try_from(info)?;
    require!(p.event_id == e.id && p.key == e.waitlist[i].key && p.status == status::WAITLISTED, AnzenError::WaitlistAccountMissing);
    p.status = status::COMMITTED;
    p.exit(&crate::ID)?;
    e.waitlist[i].waiting = false;
    e.waitlisted -= 1;
    e.committed += 1;
    e.refilled += 1;
    emit!(Refilled { event_id: e.id, key: p.key });
    Ok(true)
}

fn attend(e: &mut Event, p: &mut Participation, now: i64, tier: u8) -> Result<()> {
    require!(p.status == status::COMMITTED, AnzenError::NotCommitted); // also blocks a second check-in
    p.status = status::CHECKED_IN;
    p.attended = true;
    p.checked_in_at = now;
    p.tier = tier;
    e.attended += 1;
    emit!(CheckedIn { event_id: e.id, key: p.key, tier });
    Ok(())
}

// ---------------------------------------------------------------- state

#[account]
#[derive(InitSpace)]
pub struct Config {
    pub admin: Pubkey,
    pub mint: Pubkey,
    pub vault: Pubkey,
    pub issuer: Pubkey, // ed25519 key that signs venue codes
    pub chain_id: u64,  // the domain number in every signed preimage (103 = devnet)
    pub event_count: u64,
    pub passport_count: u64,
    pub bump: u8,
}

#[derive(AnchorSerialize, AnchorDeserialize, Clone, Default, InitSpace)]
pub struct Record {
    pub attended: u32,
    pub missed: u32,
    pub released_in_time: u32,
    pub streak: u32,
    pub best_streak: u32,
    pub shields: u32, // earned by giving a place back in time; a miss spends one instead of breaking the streak
}

/// Agent Pass rules. Zero means "no limit", except max_late_fee_bps: 0 allows only free-release events.
#[derive(AnchorSerialize, AnchorDeserialize, Clone, Default, InitSpace)]
pub struct PassRules {
    pub categories: u32,
    pub max_late_fee_bps: u16,
    pub max_active: u8,
    pub max_lead_days: u16,
}

#[derive(AnchorSerialize, AnchorDeserialize, Clone, Default, InitSpace)]
pub struct Pass {
    pub agent: Pubkey,
    pub cap: u64,
    pub spent: u64,
    pub expiry: i64,
    pub rules: PassRules,
    pub active: u8,
}

/// One per P-256 key: balance, replay nonce, show-up record and Agent Pass.
#[account]
#[derive(InitSpace)]
pub struct Member {
    pub key: [u8; 32],
    pub balance: u64,
    pub nonce: u64,
    pub record: Record,
    pub pass: Pass,
}

#[derive(AnchorSerialize, AnchorDeserialize, Clone, InitSpace)]
pub struct WaitEntry {
    pub key: [u8; 32],
    pub waiting: bool,
}

#[account]
#[derive(InitSpace)]
pub struct Event {
    pub id: u64,
    pub organizer: [u8; 32],
    pub policy_hash: [u8; 32],
    pub amount: u64,
    pub capacity: u32,
    pub commit_deadline: i64,
    pub check_in_start: i64,
    pub check_in_end: i64,
    pub no_show: u8, // 0 to organizer, 1 split among attendees
    pub category: u8,
    pub late_fee_bps: u16,
    pub committed: u32,
    pub attended: u32,
    pub cancelled: bool,
    pub finalized: bool,
    pub attendee_payout: u64,
    pub waitlisted: u32,
    pub refilled: u32,
    pub released: u32,
    pub recovered: u64,
    #[max_len(32)]
    pub waitlist: Vec<WaitEntry>,
}

#[account]
#[derive(InitSpace)]
pub struct Participation {
    pub event_id: u64,
    pub key: [u8; 32],
    pub status: u8,
    pub attended: bool,
    pub checked_in_at: i64,
    pub settled_amount: u64,
    pub passport_id: u64,
    pub tier: u8, // 1 venue code, 2 staff scan
    pub by_agent: bool,
    pub agent: Pubkey,
}

/// An agent's replay nonce and its show-up record, written only by settle.
#[account]
#[derive(InitSpace)]
pub struct AgentRecord {
    pub agent: Pubkey,
    pub nonce: u64,
    pub reservations: u32,
    pub settled: u32,
    pub showed_up: u32,
}

#[derive(AnchorSerialize, AnchorDeserialize, Clone)]
pub struct CreateArgs {
    pub event_id: u64,
    pub qx: [u8; 32],
    pub qy: [u8; 32],
    pub policy_hash: [u8; 32],
    pub amount: u64,
    pub capacity: u32,
    pub commit_deadline: i64,
    pub check_in_start: i64,
    pub check_in_end: i64,
    pub no_show: u8,
    pub category: u8,
    pub late_fee_bps: u16,
    pub deadline: i64,
    pub auth: Auth,
}

// ---------------------------------------------------------------- accounts

#[derive(Accounts)]
pub struct Initialize<'info> {
    #[account(mut)]
    pub admin: Signer<'info>,
    #[account(init, payer = admin, space = 8 + Config::INIT_SPACE, seeds = [b"config"], bump)]
    pub config: Account<'info, Config>,
    pub mint: Account<'info, Mint>,
    #[account(init, payer = admin, seeds = [b"vault"], bump, token::mint = mint, token::authority = config)]
    pub vault: Account<'info, TokenAccount>,
    pub token_program: Program<'info, Token>,
    pub system_program: Program<'info, System>,
}

#[derive(Accounts)]
#[instruction(key: [u8; 32])]
pub struct DepositFor<'info> {
    #[account(mut)]
    pub payer: Signer<'info>,
    #[account(seeds = [b"config"], bump = config.bump)]
    pub config: Account<'info, Config>,
    #[account(init_if_needed, payer = payer, space = 8 + Member::INIT_SPACE, seeds = [b"member", key.as_ref()], bump)]
    pub member: Account<'info, Member>,
    #[account(mut, token::mint = config.mint)]
    pub from: Account<'info, TokenAccount>,
    #[account(mut, address = config.vault)]
    pub vault: Account<'info, TokenAccount>,
    pub token_program: Program<'info, Token>,
    pub system_program: Program<'info, System>,
}

#[derive(Accounts)]
#[instruction(qx: [u8; 32], qy: [u8; 32])]
pub struct Withdraw<'info> {
    #[account(mut)]
    pub payer: Signer<'info>,
    #[account(seeds = [b"config"], bump = config.bump)]
    pub config: Account<'info, Config>,
    #[account(mut, seeds = [b"member", key_id(&qx, &qy).as_ref()], bump)]
    pub member: Account<'info, Member>,
    #[account(mut, address = config.vault)]
    pub vault: Account<'info, TokenAccount>,
    #[account(mut, token::mint = config.mint)]
    pub to: Account<'info, TokenAccount>,
    /// CHECK: the instructions sysvar, for signature introspection
    #[account(address = ix_sysvar::ID)]
    pub ixs: UncheckedAccount<'info>,
    pub token_program: Program<'info, Token>,
}

#[derive(Accounts)]
#[instruction(a: CreateArgs)]
pub struct CreateEvent<'info> {
    #[account(mut)]
    pub payer: Signer<'info>,
    #[account(mut, seeds = [b"config"], bump = config.bump)]
    pub config: Account<'info, Config>,
    #[account(init_if_needed, payer = payer, space = 8 + Member::INIT_SPACE, seeds = [b"member", key_id(&a.qx, &a.qy).as_ref()], bump)]
    pub organizer: Account<'info, Member>,
    #[account(init, payer = payer, space = 8 + Event::INIT_SPACE, seeds = [b"event", a.event_id.to_le_bytes().as_ref()], bump)]
    pub event: Account<'info, Event>,
    /// CHECK: the instructions sysvar
    #[account(address = ix_sysvar::ID)]
    pub ixs: UncheckedAccount<'info>,
    pub system_program: Program<'info, System>,
}

#[derive(Accounts)]
#[instruction(event_id: u64, key: [u8; 32])]
pub struct Commit<'info> {
    #[account(mut)]
    pub payer: Signer<'info>,
    #[account(seeds = [b"config"], bump = config.bump)]
    pub config: Account<'info, Config>,
    #[account(mut, seeds = [b"event", event_id.to_le_bytes().as_ref()], bump)]
    pub event: Account<'info, Event>,
    #[account(mut, seeds = [b"member", key.as_ref()], bump)]
    pub member: Account<'info, Member>,
    #[account(init_if_needed, payer = payer, space = 8 + Participation::INIT_SPACE,
              seeds = [b"part", event_id.to_le_bytes().as_ref(), key.as_ref()], bump)]
    pub participation: Account<'info, Participation>,
    /// CHECK: the instructions sysvar
    #[account(address = ix_sysvar::ID)]
    pub ixs: UncheckedAccount<'info>,
    pub system_program: Program<'info, System>,
}

#[derive(Accounts)]
#[instruction(key: [u8; 32], qx: [u8; 32], qy: [u8; 32], agent: Pubkey)]
pub struct GrantPass<'info> {
    #[account(mut)]
    pub payer: Signer<'info>,
    #[account(seeds = [b"config"], bump = config.bump)]
    pub config: Account<'info, Config>,
    #[account(init_if_needed, payer = payer, space = 8 + Member::INIT_SPACE, seeds = [b"member", key.as_ref()], bump)]
    pub member: Account<'info, Member>,
    #[account(init_if_needed, payer = payer, space = 8 + AgentRecord::INIT_SPACE, seeds = [b"agent", agent.as_ref()], bump)]
    pub agent_record: Account<'info, AgentRecord>,
    /// CHECK: the instructions sysvar
    #[account(address = ix_sysvar::ID)]
    pub ixs: UncheckedAccount<'info>,
    pub system_program: Program<'info, System>,
}

#[derive(Accounts)]
#[instruction(event_id: u64, key: [u8; 32])]
pub struct CommitByAgent<'info> {
    #[account(mut)]
    pub payer: Signer<'info>,
    #[account(seeds = [b"config"], bump = config.bump)]
    pub config: Account<'info, Config>,
    #[account(mut, seeds = [b"event", event_id.to_le_bytes().as_ref()], bump)]
    pub event: Account<'info, Event>,
    #[account(mut, seeds = [b"member", key.as_ref()], bump)]
    pub member: Account<'info, Member>,
    #[account(mut, seeds = [b"agent", member.pass.agent.as_ref()], bump)]
    pub agent_record: Account<'info, AgentRecord>,
    #[account(init_if_needed, payer = payer, space = 8 + Participation::INIT_SPACE,
              seeds = [b"part", event_id.to_le_bytes().as_ref(), key.as_ref()], bump)]
    pub participation: Account<'info, Participation>,
    /// CHECK: the instructions sysvar
    #[account(address = ix_sysvar::ID)]
    pub ixs: UncheckedAccount<'info>,
    pub system_program: Program<'info, System>,
}

#[derive(Accounts)]
#[instruction(event_id: u64, key: [u8; 32])]
pub struct Release<'info> {
    pub payer: Signer<'info>,
    #[account(seeds = [b"config"], bump = config.bump)]
    pub config: Account<'info, Config>,
    #[account(mut, seeds = [b"event", event_id.to_le_bytes().as_ref()], bump)]
    pub event: Account<'info, Event>,
    #[account(mut, seeds = [b"member", key.as_ref()], bump)]
    pub member: Account<'info, Member>,
    #[account(mut, seeds = [b"part", event_id.to_le_bytes().as_ref(), key.as_ref()], bump)]
    pub participation: Account<'info, Participation>,
    #[account(mut, seeds = [b"member", event.organizer.as_ref()], bump)]
    pub organizer: Account<'info, Member>,
    /// CHECK: the instructions sysvar
    #[account(address = ix_sysvar::ID)]
    pub ixs: UncheckedAccount<'info>,
}

#[derive(Accounts)]
#[instruction(event_id: u64, key: [u8; 32])]
pub struct CheckIn<'info> {
    pub payer: Signer<'info>,
    #[account(seeds = [b"config"], bump = config.bump)]
    pub config: Account<'info, Config>,
    #[account(mut, seeds = [b"event", event_id.to_le_bytes().as_ref()], bump)]
    pub event: Account<'info, Event>,
    #[account(mut, seeds = [b"part", event_id.to_le_bytes().as_ref(), key.as_ref()], bump)]
    pub participation: Account<'info, Participation>,
    /// CHECK: the instructions sysvar
    #[account(address = ix_sysvar::ID)]
    pub ixs: UncheckedAccount<'info>,
}

#[derive(Accounts)]
#[instruction(event_id: u64)]
pub struct CancelEvent<'info> {
    pub payer: Signer<'info>,
    #[account(seeds = [b"config"], bump = config.bump)]
    pub config: Account<'info, Config>,
    #[account(mut, seeds = [b"event", event_id.to_le_bytes().as_ref()], bump)]
    pub event: Account<'info, Event>,
    #[account(mut, seeds = [b"member", event.organizer.as_ref()], bump)]
    pub organizer: Account<'info, Member>,
    /// CHECK: the instructions sysvar
    #[account(address = ix_sysvar::ID)]
    pub ixs: UncheckedAccount<'info>,
}

#[derive(Accounts)]
#[instruction(event_id: u64)]
pub struct Finalize<'info> {
    #[account(mut, seeds = [b"event", event_id.to_le_bytes().as_ref()], bump)]
    pub event: Account<'info, Event>,
    #[account(mut, seeds = [b"member", event.organizer.as_ref()], bump)]
    pub organizer: Account<'info, Member>,
}

#[derive(Accounts)]
#[instruction(event_id: u64, key: [u8; 32])]
pub struct Settle<'info> {
    #[account(mut, seeds = [b"config"], bump = config.bump)]
    pub config: Account<'info, Config>,
    #[account(seeds = [b"event", event_id.to_le_bytes().as_ref()], bump)]
    pub event: Account<'info, Event>,
    #[account(mut, seeds = [b"member", key.as_ref()], bump)]
    pub member: Account<'info, Member>,
    #[account(mut, seeds = [b"part", event_id.to_le_bytes().as_ref(), key.as_ref()], bump)]
    pub participation: Account<'info, Participation>,
    #[account(mut, seeds = [b"agent", participation.agent.as_ref()], bump)]
    pub agent_record: Option<Account<'info, AgentRecord>>,
}

// ---------------------------------------------------------------- events and errors

#[event]
pub struct EventCreated {
    pub event_id: u64,
    pub organizer: [u8; 32],
    pub policy_hash: [u8; 32],
}

#[event]
pub struct PassGranted {
    pub key: [u8; 32],
    pub agent: Pubkey,
    pub cap: u64,
    pub expiry: i64,
}

#[event]
pub struct Released {
    pub event_id: u64,
    pub key: [u8; 32],
    pub refunded: u64,
    pub fee: u64,
}

#[event]
pub struct Refilled {
    pub event_id: u64,
    pub key: [u8; 32],
}

#[event]
pub struct CheckedIn {
    pub event_id: u64,
    pub key: [u8; 32],
    pub tier: u8,
}

#[event]
pub struct Settled {
    pub event_id: u64,
    pub key: [u8; 32],
    pub attended: bool,
    pub amount: u64,
}

#[event]
pub struct ShowUpReported {
    pub agent: Pubkey,
    pub event_id: u64,
    pub key: [u8; 32],
    pub attended: bool,
}

/// Same names as the Monad contract's errors, so the service and apps map them to the same copy.
#[error_code]
pub enum AnzenError {
    InvalidConfig,
    InvalidSignature,
    Expired,
    UnknownEvent,
    WrongPhase,
    EventFull,
    AlreadyCommitted,
    NotCommitted,
    InsufficientBalance,
    NotOrganizer,
    AlreadySettled,
    NotDelegated,
    BudgetExceeded,
    CategoryNotAllowed,
    FeeAboveLimit,
    TooManyActive,
    TooFarAhead,
    WaitlistAccountMissing,
    OwnEvent,
}
