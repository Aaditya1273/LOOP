//! Signed preimages and how they are verified.
//!
//! Every preimage is byte-identical to the one the web and Android apps already sign (ABI-style 32-byte words, see
//! backend/anzen/messages.py), so the apps' encoders and golden vectors carry over unchanged. Where a preimage has an
//! EVM `address` slot, a Solana key goes in as its first 20 bytes (`addr20`): the program's id for "treasury", the
//! issuer's ed25519 key for "issuer", the agent's ed25519 key for "agent".
//!
//! Signatures are verified by Solana's precompiles in the same transaction, found here by instruction introspection:
//!   - the owner's P-256 key (passkey or Android Keystore): secp256r1 program (SIMD-0075). A raw key signs the
//!     preimage; a WebAuthn passkey signs authenticatorData ‖ sha256(clientDataJSON), whose challenge must be
//!     base64url(sha256(preimage)) and whose flags must show user presence and verification.
//!   - the check-in issuer and AI agents: ed25519 program, over the preimage itself.

use anchor_lang::prelude::*;
use solana_sha256_hasher::hash as sha256;
use anchor_lang::solana_program::sysvar::instructions::{load_current_index_checked, load_instruction_at_checked};

use crate::AnzenError;

pub const SECP256R1_ID: Pubkey = pubkey!("Secp256r1SigVerify1111111111111111111111111");
pub const ED25519_ID: Pubkey = pubkey!("Ed25519SigVerify111111111111111111111111111");



/// How the owner's P-256 key authorized an action.
#[derive(AnchorSerialize, AnchorDeserialize, Clone, Debug)]
pub enum Auth {
    /// A raw low-s ECDSA signature over sha256(preimage) (Android Keystore).
    Raw,
    /// A WebAuthn assertion (browser passkey). The precompile carries authenticatorData ‖ sha256(clientDataJSON).
    WebAuthn { client_data: Vec<u8>, challenge_index: u16, type_index: u16 },
}

pub fn key_id(qx: &[u8; 32], qy: &[u8; 32]) -> [u8; 32] {
    let mut b = [0u8; 64];
    b[..32].copy_from_slice(qx);
    b[32..].copy_from_slice(qy);
    sha256(&b).to_bytes()
}

pub fn addr20(k: &Pubkey) -> [u8; 20] {
    k.to_bytes()[..20].try_into().unwrap()
}

/// ABI-style encoder: every value is one 32-byte word.
pub struct Pre(pub Vec<u8>);

impl Pre {
    pub fn new(tag: &[u8], chain_id: u64) -> Self {
        let mut p = Pre(Vec::with_capacity(512));
        let mut t = [0u8; 32];
        t[..tag.len()].copy_from_slice(tag);
        p.0.extend_from_slice(&t);
        p.u(chain_id as u128).addr(&addr20(&crate::ID))
    }
    pub fn u(mut self, v: u128) -> Self {
        self.0.extend_from_slice(&[0u8; 16]);
        self.0.extend_from_slice(&v.to_be_bytes());
        self
    }
    pub fn b32(mut self, v: &[u8; 32]) -> Self {
        self.0.extend_from_slice(v);
        self
    }
    pub fn addr(mut self, v: &[u8; 20]) -> Self {
        self.0.extend_from_slice(&[0u8; 12]);
        self.0.extend_from_slice(v);
        self
    }
}

struct Sig<'a> {
    pubkey: &'a [u8],
    message: &'a [u8],
}

/// Signature entries of every `program` precompile instruction placed before this one in the transaction. The
/// precompile already verified each of them, or the transaction would have failed before reaching us.
fn precompile_sigs(ixs: &AccountInfo, program: &Pubkey, pubkey_len: usize, f: &mut dyn FnMut(Sig) -> bool) -> Result<bool> {
    let current = load_current_index_checked(ixs)? as usize;
    for i in 0..current {
        let ix = load_instruction_at_checked(i, ixs)?;
        if ix.program_id != *program || ix.data.len() < 2 {
            continue;
        }
        let d = &ix.data;
        let n = d[0] as usize;
        for k in 0..n {
            let o = 2 + k * 14;
            if d.len() < o + 14 {
                return err!(AnzenError::InvalidSignature);
            }
            let r = |j: usize| u16::from_le_bytes([d[o + j], d[o + j + 1]]);
            let (pk_off, pk_ix, msg_off, msg_len, msg_ix, sig_ix) = (r(4) as usize, r(6), r(8) as usize, r(10) as usize, r(12), r(2));
            // Only data inside the precompile instruction itself (u16::MAX or its own index) is trusted here.
            let own = |x: u16| x == u16::MAX || x as usize == i;
            if !own(pk_ix) || !own(msg_ix) || !own(sig_ix) || d.len() < pk_off + pubkey_len || d.len() < msg_off + msg_len {
                continue;
            }
            if f(Sig { pubkey: &d[pk_off..pk_off + pubkey_len], message: &d[msg_off..msg_off + msg_len] }) {
                return Ok(true);
            }
        }
    }
    Ok(false)
}

fn compressed(qx: &[u8; 32], qy: &[u8; 32]) -> [u8; 33] {
    let mut pk = [0u8; 33];
    pk[0] = if qy[31] & 1 == 0 { 0x02 } else { 0x03 };
    pk[1..].copy_from_slice(qx);
    pk
}

const B64URL: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

fn base64url(input: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(input.len() * 4 / 3 + 3);
    for chunk in input.chunks(3) {
        let b = [chunk[0], *chunk.get(1).unwrap_or(&0), *chunk.get(2).unwrap_or(&0)];
        let n = (b[0] as u32) << 16 | (b[1] as u32) << 8 | b[2] as u32;
        for j in 0..=chunk.len() {
            out.push(B64URL[(n >> (18 - 6 * j) & 63) as usize]);
        }
    }
    out
}

/// The owner's P-256 key authorized exactly `preimage`.
pub fn verify_p256(ixs: &AccountInfo, qx: &[u8; 32], qy: &[u8; 32], preimage: &[u8], auth: &Auth) -> Result<()> {
    let pk = compressed(qx, qy);
    let found = match auth {
        Auth::Raw => precompile_sigs(ixs, &SECP256R1_ID, 33, &mut |s| s.pubkey == pk && s.message == preimage)?,
        Auth::WebAuthn { client_data, challenge_index, type_index } => {
            let mut challenge = b"\"challenge\":\"".to_vec();
            challenge.extend(base64url(&sha256(preimage).to_bytes()));
            challenge.push(b'"');
            let at = |i: u16, want: &[u8]| client_data.get(i as usize..).is_some_and(|rest| rest.starts_with(want));
            require!(at(*type_index, b"\"type\":\"webauthn.get\""), AnzenError::InvalidSignature);
            require!(at(*challenge_index, &challenge), AnzenError::InvalidSignature);
            let client_hash = sha256(client_data).to_bytes();
            precompile_sigs(ixs, &SECP256R1_ID, 33, &mut |s| {
                let m = s.message;
                // authenticatorData is at least 37 bytes; flags byte 32 needs UP (0x01) and UV (0x04).
                s.pubkey == pk && m.len() >= 37 + 32 && m[32] & 0x05 == 0x05 && m[m.len() - 32..] == client_hash
            })?
        }
    };
    require!(found, AnzenError::InvalidSignature);
    Ok(())
}

/// `signer` (an ed25519 key: the check-in issuer or an AI agent) signed exactly `preimage`.
pub fn verify_ed25519(ixs: &AccountInfo, signer: &Pubkey, preimage: &[u8]) -> Result<()> {
    let key = signer.to_bytes();
    let found = precompile_sigs(ixs, &ED25519_ID, 32, &mut |s| s.pubkey == key && s.message == preimage)?;
    require!(found, AnzenError::InvalidSignature);
    Ok(())
}
