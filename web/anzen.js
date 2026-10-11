// ANZEN web core: passkey accounts (WebAuthn, P-256) and the byte-exact action preimages the treasury verifies.
// Mirrors backend/anzen/messages.py and app/.../core/Messages.kt (golden vectors in web/test.html).

const enc = new TextEncoder();
export const hex = (b) => "0x" + [...new Uint8Array(b)].map((x) => x.toString(16).padStart(2, "0")).join("");
export const unhex = (h) => Uint8Array.from(h.replace(/^0x/, "").match(/../g) || [], (x) => parseInt(x, 16));
const concat = (...a) => { const o = new Uint8Array(a.reduce((n, x) => n + x.length, 0)); let i = 0; for (const x of a) { o.set(x, i); i += x.length; } return o; };
export const sha256 = async (b) => new Uint8Array(await crypto.subtle.digest("SHA-256", b));
const b64url = (b) => btoa(String.fromCharCode(...new Uint8Array(b))).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
const fromB64url = (s) => Uint8Array.from(atob(s.replace(/-/g, "+").replace(/_/g, "/")), (c) => c.charCodeAt(0));

// ---------------------------------------------------------------- ABI words (static types)

const word = (b) => { if (b.length > 32) throw new Error("word overflow"); const w = new Uint8Array(32); w.set(b, 32 - b.length); return w; };
export const uint = (v) => { let n = BigInt(v); if (n < 0n) throw new Error("uint < 0"); const o = []; while (n > 0n) { o.unshift(Number(n & 255n)); n >>= 8n; } return word(Uint8Array.from(o)); };
const address = (a) => { const b = unhex(a); if (b.length !== 20) throw new Error("bad address"); return word(b); };
const b32 = (h) => { const b = typeof h === "string" ? unhex(h) : h; if (b.length !== 32) throw new Error("bad bytes32"); return b; };
const tag = (s) => { const w = new Uint8Array(32); w.set(enc.encode(s)); return w; };

export const Messages = {
  commit: (chainId, treasury, eventId, amount, nonce, deadline) =>
    concat(tag("ANZEN/commit/v1"), uint(chainId), address(treasury), uint(eventId), uint(amount), uint(nonce), uint(deadline)),
  checkIn: (chainId, treasury, eventId, challenge, expiresAt) =>
    concat(tag("ANZEN/checkin/v1"), uint(chainId), address(treasury), uint(eventId), b32(challenge), uint(expiresAt)),
  cancel: (chainId, treasury, eventId, nonce, deadline) =>
    concat(tag("ANZEN/cancel/v1"), uint(chainId), address(treasury), uint(eventId), uint(nonce), uint(deadline)),
  desk: (chainId, treasury, eventId, deadline) =>
    concat(tag("ANZEN/desk/v1"), uint(chainId), address(treasury), uint(eventId), uint(deadline)),
  delegate: (chainId, treasury, agent, agentId, cap, expiry, nonce, deadline) =>
    concat(tag("ANZEN/delegate/v1"), uint(chainId), address(treasury), address(agent), uint(agentId), uint(cap), uint(expiry), uint(nonce), uint(deadline)),
  create: (chainId, treasury, nonce, deadline, t) =>
    concat(tag("ANZEN/create/v1"), uint(chainId), address(treasury), uint(nonce), uint(deadline), b32(t.organizer), b32(t.policyHash),
      address(t.issuer), uint(t.amount), uint(t.capacity), uint(t.commitDeadline), uint(t.checkInStart), uint(t.checkInEnd), uint(t.noShow)),
};
export const NO_AGENT_ID = (1n << 256n) - 1n;

// ---------------------------------------------------------------- passkey account

const STORE = "anzen.passkey.v1";
export const account = () => { try { return JSON.parse(localStorage.getItem(STORE)); } catch { return null; } };
export const forget = () => localStorage.removeItem(STORE);

export async function keyIdOf(qx, qy) { return hex(await sha256(concat(unhex(qx), unhex(qy)))); }

/** Creates a passkey (platform authenticator: Face ID, Touch ID, Windows Hello, or a phone). The public key is
 *  the account; nothing secret ever leaves the authenticator, and nothing secret is stored here. */
export async function createAccount(name) {
  const cred = await navigator.credentials.create({ publicKey: {
    rp: { name: "Shonko · ANZEN" },
    user: { id: crypto.getRandomValues(new Uint8Array(16)), name, displayName: name },
    challenge: crypto.getRandomValues(new Uint8Array(32)),
    pubKeyCredParams: [{ type: "public-key", alg: -7 }], // ES256 = P-256
    authenticatorSelection: { residentKey: "preferred", userVerification: "required" },
    timeout: 120000,
  } });
  const spki = cred.response.getPublicKey?.();
  if (!spki) throw new Error("This browser can't export the passkey's public key. Try Chrome, Safari or Edge.");
  const key = await crypto.subtle.importKey("spki", spki, { name: "ECDSA", namedCurve: "P-256" }, true, ["verify"]);
  const raw = new Uint8Array(await crypto.subtle.exportKey("raw", key)); // 0x04 ‖ x ‖ y
  const qx = hex(raw.slice(1, 33)), qy = hex(raw.slice(33, 65));
  const acct = { credentialId: b64url(cred.rawId), qx, qy, keyId: await keyIdOf(qx, qy), name, created: Date.now() };
  localStorage.setItem(STORE, JSON.stringify(acct));
  return acct;
}

const N = 0xFFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551n;
const toBig = (b) => b.reduce((n, x) => (n << 8n) | BigInt(x), 0n);
function derToRS(der) { // ECDSA DER -> r, low-s s (the contract, like OpenZeppelin, rejects high-s)
  let i = 2; const int = () => { const len = der[i + 1]; const v = toBig(der.slice(i + 2, i + 2 + len)); i += 2 + len; return v; };
  const r = int(); let s = int(); if (s > N / 2n) s = N - s;
  return [uint(r), uint(s)];
}

export function abiWebAuthn(r, s, challengeIndex, typeIndex, authData, clientData) {
  // abi.encode(WebAuthnAuth{bytes32 r, bytes32 s, uint256 challengeIndex, uint256 typeIndex, bytes authenticatorData, string clientDataJSON})
  const pad = (b) => concat(b, new Uint8Array((32 - (b.length % 32)) % 32));
  const dyn = (b) => concat(uint(b.length), pad(b));
  const a = dyn(authData), c = dyn(clientData);
  const head = concat(r, s, uint(challengeIndex), uint(typeIndex), uint(6 * 32), uint(6 * 32 + a.length));
  return concat(uint(32), head, a, c);
}

/** Asks the passkey to authorize one action: the WebAuthn challenge is sha256(preimage), exactly what the contract
 *  rebuilds and checks, so the signature can't be used for anything else. Returns the `auth` bytes as hex. */
export async function authorize(preimage) {
  const acct = account();
  if (!acct) throw Object.assign(new Error("No passkey on this browser."), { reason: "KeyMissing" });
  const challenge = await sha256(preimage);
  let cred;
  try {
    cred = await navigator.credentials.get({ publicKey: {
      challenge, userVerification: "required", timeout: 120000,
      allowCredentials: [{ type: "public-key", id: fromB64url(acct.credentialId) }],
    } });
  } catch (e) {
    throw Object.assign(new Error("Authorization cancelled."), { reason: e.name === "NotAllowedError" ? "Cancelled" : "AuthUnavailable" });
  }
  const authData = new Uint8Array(cred.response.authenticatorData);
  const clientBytes = new Uint8Array(cred.response.clientDataJSON);
  const clientData = new TextDecoder().decode(clientBytes);
  const [r, s] = derToRS(new Uint8Array(cred.response.signature));
  const auth = abiWebAuthn(r, s, clientData.indexOf('"challenge":"'), clientData.indexOf('"type":"'), authData, clientBytes);
  return { qx: acct.qx, qy: acct.qy, auth: hex(auth) };
}

export const policyHash = async (policy) => hex(await sha256(enc.encode(policy)));
