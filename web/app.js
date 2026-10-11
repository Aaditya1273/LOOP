// Shonko web: the ANZEN consumer app in a browser, with passkeys. Hash routes, no build step.
import { Messages, NO_AGENT_ID, account, authorize, createAccount, forget, hex, policyHash, unhex } from "./anzen.js";

const API = (new URLSearchParams(location.search).get("api") || window.ANZEN_API || "").replace(/\/$/, "");
const $ = (s, el = document) => el.querySelector(s);
const view = $("#view");
let health = null;

// ---------------------------------------------------------------- api

async function api(path, body) {
  let r;
  try {
    r = await fetch(API + path, body ? { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body, (k, v) => typeof v === "bigint" ? v.toString() : v) } : {});
  } catch { throw Object.assign(new Error("offline"), { reason: "Offline" }); }
  const j = await r.json().catch(() => ({}));
  if (!r.ok) throw Object.assign(new Error(j.error || `HTTP ${r.status}`), { reason: j.error || `Http${r.status}` });
  return j;
}
const now = () => Math.max(health?.now || 0, Math.floor(Date.now() / 1000)); // display only
/** Chain time, fetched fresh before anything is signed: deadlines must not depend on this device's clock. */
const chainNow = async () => { health = await api("/health"); return health.now; };

const COPY = {
  Offline: "Can't reach ANZEN. Check your connection and try again.",
  ChainUnavailable: "The network isn't responding. Nothing was submitted.",
  InvalidSignature: "The signature didn't verify, so nothing was submitted.",
  Expired: "This expired before it was submitted. Nothing changed; try again.",
  WrongPhase: "That isn't possible at this stage of the event.",
  EventFull: "This event is full.", AlreadyCommitted: "You've already committed to this event.",
  NotCommitted: "This account has no open commitment for this event, or it's already checked in.",
  InsufficientBalance: "Not enough test dollars. Get some from your account first.",
  NotOrganizer: "Only the organizer's passkey can do this.", AlreadySettled: "This outcome was already applied.",
  FaucetUsed: "This account already received its test dollars.", InvalidDesk: "This desk closed. Open it again.",
  NotDelegated: "Your agent has no active budget.", BudgetExceeded: "That would go over your agent's budget.",
  AgentIdentityMismatch: "That ERC-8004 identity doesn't belong to this agent.",
  Cancelled: "You cancelled the passkey prompt. Nothing was signed or sent.",
  KeyMissing: "Create your passkey account first.", AuthUnavailable: "Passkeys aren't available in this browser.",
  PolicyMismatch: "The event terms are inconsistent and weren't published.", TxFailed: "The transaction failed onchain. Nothing changed.",
};
const errText = (e) => COPY[e.reason] || `Something went wrong (${e.reason || e.message}).`;

// ---------------------------------------------------------------- formatting

const tusd = (base) => { const w = Math.floor(base / 1e6), f = (base % 1e6).toString().padStart(6, "0").replace(/0+$/, ""); return `${w.toLocaleString()}${f ? "." + f : ""}`; };
const when = (t) => new Date(t * 1000).toLocaleString([], { dateStyle: "medium", timeStyle: "short" });
const time = (t) => new Date(t * 1000).toLocaleTimeString([], { timeStyle: "short" });
const rel = (t) => { const d = t - now(), a = Math.abs(d); const s = a < 60 ? `${a}s` : a < 3600 ? `${Math.round(a / 60)} min` : a < 86400 ? `${Math.round(a / 3600)} h` : `${Math.round(a / 86400)} days`; return d >= 0 ? `in ${s}` : `${s} ago`; };
const short = (h) => h ? `${h.slice(0, 6)}…${h.slice(-4)}` : "";
const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const txLink = (tx) => health?.explorer ? `<a href="${health.explorer}${tx}" target="_blank" rel="noopener" class="mono">${short(tx)} ↗</a>` : `<span class="mono">${short(tx)}</span>`;
const pill = (text, tone = "neutral") => `<span class="pill ${tone}"><i></i>${esc(text)}</span>`;

function toast(msg) { const t = $("#toast"); t.textContent = msg; t.classList.add("on"); clearTimeout(toast.t); toast.t = setTimeout(() => t.classList.remove("on"), 2600); }

// ---------------------------------------------------------------- transactions

/** Runs one action through its lifecycle; success appears only once the contract state is re-read and matches. */
async function run(box, label, submit, verified, onDone) {
  const set = (state, body, tone = "pending", tx) => {
    box.innerHTML = `<div class="tx ${tone}">${tone === "pending" ? '<span class="spin"></span>' : pill(tone === "ok" ? "Verified" : tone === "bad" ? "Not completed" : "Cancelled", tone === "ok" ? "verified" : tone === "bad" ? "failed" : "neutral")}
      <b>${esc(state)}</b><p>${esc(body)}</p>${tx ? `<p>Transaction ${txLink(tx)}</p>` : ""}</div>`;
  };
  let tx;
  try {
    set("Waiting for your passkey", "Confirm with Face ID, Touch ID, Windows Hello or your phone.");
    tx = await submit((s) => set("Submitting", "Sending your signed action to Monad."));
    set("Pending", "Submitted. Waiting for the network.", "pending", tx);
    let status = "submitted";
    for (let i = 0; i < 60 && status === "submitted"; i++) { await new Promise((r) => setTimeout(r, 700)); status = (await api(`/tx/${tx}`)).status; }
    if (status === "failed") return set("Not completed", COPY.TxFailed, "bad", tx);
    if (status !== "confirmed") return set("Still pending", "Not confirmed yet. It may still go through.", "pending", tx);
    set("Confirmed onchain", "Reading the treasury to verify the result.", "pending", tx);
    for (let i = 0; i < 20; i++) { if (await verified()) { set(label, "Verified against the treasury contract.", "ok", tx); onDone?.(tx); return; } await new Promise((r) => setTimeout(r, 500)); }
    set("Still pending", "Included, but the treasury doesn't show it yet.", "pending", tx);
  } catch (e) {
    if (e.reason === "Cancelled") set("Cancelled", COPY.Cancelled, "neutral");
    else set("Not completed", errText(e), "bad", tx);
  }
}

// ---------------------------------------------------------------- views

const routes = [
  [/^#?\/?$/, home], [/^#\/events\/(\d+)$/, event], [/^#\/checkin/, checkin], [/^#\/desk\/(\d+)$/, desk],
  [/^#\/create$/, create], [/^#\/delegate/, delegate], [/^#\/proof\/(\d+)\/(0x[0-9a-fA-F]{64})$/, proof],
  [/^#\/account$/, accountView], [/^#\/agent$/, agentView],
];

async function render() {
  const h = location.hash.split("?")[0] || "#/";
  window.scrollTo(0, 0);
  for (const [re, fn] of routes) { const m = h.match(re); if (m) { view.innerHTML = '<div class="loading"><span class="spin"></span> Loading</div>'; try { await fn(...m.slice(1)); } catch (e) { view.innerHTML = `<div class="note bad">${esc(errText(e))}</div>`; } return; } }
  location.hash = "#/";
}
const params = () => new URLSearchParams(location.hash.split("?")[1] || "");

function signup(next) {
  return `<section class="card sealed signup"><div class="mark"></div><h2>Your account is a passkey</h2>
    <p>No app, no seed phrase. Your device's Face ID, Touch ID, Windows Hello or phone holds the key; every commitment shows exactly what you sign.</p>
    <input id="name" placeholder="Your name" maxlength="40" autocomplete="nickname"><button class="accent" id="mk">Create passkey account</button><div id="mkbox"></div></section>`;
}
function bindSignup(after) {
  $("#mk")?.addEventListener("click", async () => {
    const name = $("#name").value.trim() || "Shonko user";
    try { await createAccount(name); toast("Passkey created"); after?.(); render(); }
    catch (e) { $("#mkbox").innerHTML = `<div class="note bad">${esc(e.name === "NotAllowedError" ? COPY.Cancelled : e.message)}</div>`; }
  });
}

async function home() {
  const acct = account();
  const [events, me] = await Promise.all([api(`/events${acct ? `?key=${acct.keyId}` : ""}`), acct ? api(`/accounts/${acct.keyId}`) : null]);
  const open = events.filter((e) => e.phase === "open"), live = events.filter((e) => e.phase === "check_in");
  const checkedIn = events.reduce((n, e) => n + e.attended, 0), committed = events.reduce((n, e) => n + e.committed, 0);
  view.innerHTML = `
    <section class="hero"><h1>Your agent can book it.<br><span>Only you can show up.</span></h1>
      <p>Reserve creator events with a refundable commitment. Prove you came with a code that dies in 45 seconds. Get it back, plus a passport. Built on Monad's native P-256 verification and ERC-8004.</p>
      <div class="stats"><div><b>${events.length}</b><span>events</span></div><div><b>${committed}</b><span>commitments</span></div><div><b>${checkedIn}</b><span>verified check-ins</span></div></div></section>
    ${acct ? `<section class="card balance"><div><span class="label">Available</span><b class="amount">${tusd(me.balance)} <small>tUSD</small></b><span class="hint">Test dollars, no value · ${esc(acct.name)}</span></div>
      ${me.faucetUsed ? "" : '<button id="faucet">Get 100 test dollars</button>'}<div id="fbox"></div></section>
      ${me.delegation?.active ? `<a class="card row" href="#/agent"><div><b>Your AI agent</b><span>${tusd(me.delegation.remaining)} of ${tusd(me.delegation.cap)} tUSD left${me.delegation.reputation?.showUpRate != null ? ` · show-up ${me.delegation.reputation.showUpRate}%` : ""}</span></div>${pill("Active", "accent")}</a>` : ""}` : signup()}
    ${live.length ? `<h3>Check-in open now</h3>${live.map(card).join("")}` : ""}
    <h3>Open for commitments</h3>${open.length ? open.map(card).join("") : '<p class="empty">No open events right now. Create one.</p>'}
    <a class="card row" href="#/create"><div><b>Host an event</b><span>Fix the terms, run a check-in desk, let the treasury settle.</span></div><span class="chev">›</span></a>`;
  bindSignup();
  $("#faucet")?.addEventListener("click", () => run($("#fbox"), "100 test dollars added",
    async () => (await api("/faucet", { keyId: acct.keyId })).tx,
    async () => (await api(`/accounts/${acct.keyId}`)).balance > me.balance, () => setTimeout(render, 900)));
}

function status(e) {
  const me = e.me?.status || "none";
  if (me === "settled") return pill("Settled", "verified");
  if (e.cancelled) return pill("Cancelled", "failed");
  if (me === "checked_in") return pill("Checked in", "verified");
  if (me === "committed") return pill(e.phase === "check_in" ? "Check in now" : "Committed", e.phase === "check_in" ? "pending" : "accent");
  return pill({ open: "Open", closed: "Closed", check_in: "Check-in open", awaiting_settlement: "Settling", finalized: "Settled" }[e.phase] || e.phase);
}
const art = (e) => `<div class="art a${e.artwork % 6}"></div>`;
const card = (e) => `<a class="card ev" href="#/events/${e.id}">${art(e)}<div><b>${esc(e.title)}</b><span>${esc(e.creator)} · ${when(e.startsAt)}</span><div class="meta">${status(e)}<b>${tusd(e.amount)} tUSD</b></div></div></a>`;

async function event(id) {
  const acct = account();
  const e = await api(`/events/${id}${acct ? `?key=${acct.keyId}` : ""}`);
  const me = e.me?.status || "none", mine = acct && e.organizer === acct.keyId;
  view.innerHTML = `<a class="back" href="#/">‹ Events</a>${art(e).replace("art", "art big")}
    ${status(e)}<h1>${esc(e.title)}</h1><p class="sub">by ${esc(e.creator)} · ${esc(e.venue)}</p>
    <section class="card sealed"><span class="label">Commitment per person</span><b class="amount">${tusd(e.amount)} <small>tUSD</small></b>
      <p>${e.noShow === "split" ? "Check in and it comes back, plus an equal share of no-shows' commitments." : "Check in and it comes back. If you don't, it goes to the organizer."}</p>${pill(`${health.network} · test dollars`, "info")}</section>
    <section class="card"><dl>
      <dt>Starts</dt><dd>${when(e.startsAt)}</dd><dt>Commitments close</dt><dd>${when(e.commitDeadline)} (${rel(e.commitDeadline)})</dd>
      <dt>Check-in</dt><dd>${when(e.checkInStart)} – ${time(e.checkInEnd)}</dd><dt>Seats</dt><dd>${e.committed} of ${e.capacity} taken</dd>
      <dt>Terms hash</dt><dd class="mono">${short(e.policyHash)} ${e.policyVerified ? pill("matches onchain", "verified") : pill("mismatch", "failed")}</dd>
      <dt>Published in</dt><dd>${txLink(e.createTx)}</dd></dl></section>
    <section class="card"><b>How check-in works</b><p class="muted">During the window, scan the code at the venue. It rotates every 35 s, expires in 45 s and is signed by the event's issuer; your passkey signs it too. This shows your device signed a fresh code. It doesn't prove where you were. Disputes: ${esc(e.disputeContact)}.</p></section>
    <div id="act"></div><div id="box"></div>`;
  const act = $("#act");
  if (!acct) { act.innerHTML = signup(); bindSignup(); return; }
  const btn = (text, cls, fn) => { const b = document.createElement("button"); b.className = cls; b.textContent = text; b.onclick = fn; act.appendChild(b); };
  if (me === "none" && e.phase === "open" && e.committed < e.capacity && e.policyVerified)
    btn(`Commit ${tusd(e.amount)} tUSD with passkey`, "accent", () => run($("#box"), "Commitment recorded",
      async (submitting) => { const a = await api(`/accounts/${acct.keyId}`); const dl = (await chainNow()) + 600;
        const sig = await authorize(Messages.commit(health.chainId, health.treasury, e.id, e.amount, a.nonce, dl)); submitting();
        return (await api(`/events/${e.id}/commit`, { deadline: dl, ...sig })).tx; },
      async () => (await api(`/events/${e.id}?key=${acct.keyId}`)).me.status === "committed", () => setTimeout(render, 1200)));
  if (me === "committed" && e.phase === "check_in") btn("Scan venue code", "accent", () => scan(e.id));
  if (["committed", "checked_in"].includes(me) && (e.phase === "awaiting_settlement" || e.phase === "finalized" || e.cancelled))
    btn(e.cancelled ? "Apply refund" : "Apply outcome", "", () => run($("#box"), "Outcome applied",
      async (s) => { s(); return (await api(`/events/${e.id}/settle`, { keyId: acct.keyId })).tx; },
      async () => (await api(`/events/${e.id}?key=${acct.keyId}`)).me.status === "settled", () => setTimeout(render, 1200)));
  if (me === "settled") act.innerHTML += outcome(e);
  if (me !== "none") btn("View proof", "quiet", () => location.hash = `#/proof/${e.id}/${acct.keyId}`);
  if (mine && !e.cancelled && ["open", "closed", "check_in"].includes(e.phase)) btn("Open check-in desk", "", () => location.hash = `#/desk/${e.id}`);
}

function outcome(e) {
  const m = e.me; if (!m.attended) return `<section class="card"><b>No-show rule applied</b><p class="muted">There's no check-in from this account, so your commitment ${e.noShow === "split" ? "was shared among attendees" : "went to the organizer"}.</p></section>`;
  return `<section class="card sealed passport"><div class="row"><div class="mark small"></div><span class="label">SUPPORTER PASSPORT</span></div>
    <h2>${esc(e.title)}</h2><p>${esc(e.creator)} · ${when(e.startsAt)}</p><b class="accent-text">No. ${m.passportId} · issued onchain</b>
    <p>${tusd(m.settledAmount)} tUSD returned to your balance${m.settledAmount > e.amount ? ` (your ${tusd(e.amount)} + ${tusd(m.settledAmount - e.amount)} from no-shows)` : ""}.</p></section>`;
}

// ---------------------------------------------------------------- check-in

function parseCode(text) {
  try {
    const u = new URL(text.trim().replace(/^anzen:\/\/checkin\?/, "https://x/#/checkin?"));
    const q = new URLSearchParams(u.hash.split("?")[1] || u.search);
    const c = { e: +q.get("e"), c: q.get("c"), x: +q.get("x"), s: q.get("s") };
    return /^0x[0-9a-f]{64}$/i.test(c.c) && /^0x[0-9a-f]{130}$/i.test(c.s) && c.e > 0 ? c : null;
  } catch { return null; }
}

async function scan(eventId) {
  view.insertAdjacentHTML("beforeend", `<div class="scanner" id="scanner"><video playsinline muted></video><div class="frame"></div><p>Point at the code on the check-in desk</p><button class="quiet" id="close">Cancel</button></div>`);
  const sc = $("#scanner"), video = $("video", sc);
  let stream;
  const stop = () => { stream?.getTracks().forEach((t) => t.stop()); sc.remove(); };
  $("#close").onclick = stop;
  try { stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: "environment" } }); }
  catch { stop(); toast("Camera unavailable. Open the code's link on this device instead."); return; }
  video.srcObject = stream; await video.play();
  const canvas = document.createElement("canvas"), ctx = canvas.getContext("2d", { willReadFrequently: true });
  const detector = "BarcodeDetector" in window ? new BarcodeDetector({ formats: ["qr_code"] }) : null;
  const tick = async () => {
    if (!document.body.contains(sc)) return;
    let text = null;
    if (detector) text = (await detector.detect(video).catch(() => []))[0]?.rawValue;
    else if (window.jsQR && video.videoWidth) { canvas.width = video.videoWidth; canvas.height = video.videoHeight; ctx.drawImage(video, 0, 0); text = jsQR(ctx.getImageData(0, 0, canvas.width, canvas.height).data, canvas.width, canvas.height)?.data; }
    const code = text && parseCode(text);
    if (code) { stop(); navigator.vibrate?.(30); location.hash = `#/checkin?e=${code.e}&c=${code.c}&x=${code.x}&s=${code.s}`; return; }
    if (text) toast("That isn't an ANZEN check-in code.");
    requestAnimationFrame(tick);
  };
  tick();
}

async function checkin() {
  const code = parseCode(location.href), acct = account();
  if (!code) { view.innerHTML = '<div class="note bad">That isn\'t an ANZEN check-in code.</div>'; return; }
  const e = await api(`/events/${code.e}${acct ? `?key=${acct.keyId}` : ""}`);
  view.innerHTML = `<a class="back" href="#/events/${e.id}">‹ ${esc(e.title)}</a><h1>Check in</h1><p class="sub">${esc(e.title)} · ${esc(e.venue)}</p>
    <section class="card"><span class="label">Venue code</span><div id="left"></div><p class="muted">Signing shows your passkey signed this event's code while it was fresh. It doesn't prove your location.</p></section><div id="act"></div><div id="box"></div>`;
  const tickLeft = () => { const l = code.x - now(); $("#left") && ($("#left").innerHTML = l > 0 ? pill(`Valid for ${l}s`, l > 10 ? "info" : "pending") : pill("Expired · scan a fresh code", "failed")); };
  tickLeft(); const iv = setInterval(() => { if (!$("#left")) clearInterval(iv); else tickLeft(); }, 1000);
  if (!acct) { $("#act").innerHTML = signup(); bindSignup(); return; }
  if (e.me?.status !== "committed") { $("#act").innerHTML = `<div class="note">${e.me?.attended ? "You're already checked in." : "This account has no commitment for this event."}</div>`; return; }
  const b = document.createElement("button"); b.className = "accent"; b.textContent = "Sign check-in with passkey"; $("#act").appendChild(b);
  b.onclick = () => run($("#box"), "You're checked in",
    async (s) => { const sig = await authorize(Messages.checkIn(health.chainId, health.treasury, e.id, code.c, code.x)); s();
      return (await api(`/events/${e.id}/checkin`, { challenge: code.c, expiresAt: code.x, issuerSig: code.s, ...sig })).tx; },
    async () => (await api(`/events/${e.id}?key=${acct.keyId}`)).me.attended, () => { b.remove(); navigator.vibrate?.([20, 40, 20]); });
}

// ---------------------------------------------------------------- organizer desk

async function desk(id) {
  const acct = account(); const e = await api(`/events/${id}`);
  view.innerHTML = `<a class="back" href="#/events/${id}">‹ ${esc(e.title)}</a><h1>Check-in desk</h1><p class="sub" id="count">${e.attended} of ${e.committed} checked in</p>
    <p class="muted">Opening the desk asks for your passkey once. Then a new code appears every 35 seconds; each works for 45, so a photo of it goes stale fast.</p>
    <div id="act"></div><div id="qr"></div><div id="box"></div>`;
  if (!acct || e.organizer !== acct.keyId) { $("#act").innerHTML = `<div class="note bad">${COPY.NotOrganizer}</div>`; return; }
  const b = document.createElement("button"); b.className = "accent"; b.textContent = "Open desk"; $("#act").appendChild(b);
  b.onclick = async () => {
    try {
      const dl = (await chainNow()) + 300; const sig = await authorize(Messages.desk(health.chainId, health.treasury, e.id, dl));
      const d = await api(`/events/${e.id}/desk`, { deadline: dl, ...sig }); b.remove();
      const loop = async () => {
        if (!$("#qr")) return;
        try {
          const c = await api(`/events/${e.id}/challenge?token=${d.token}`);
          const url = `${location.origin}${location.pathname}${location.search}#/checkin?e=${c.eventId}&c=${c.challenge}&x=${c.expiresAt}&s=${c.issuerSig}`;
          const q = qrcode(0, "M"); q.addData(url); q.make();
          $("#qr").innerHTML = `<div class="qr">${q.createSvgTag({ cellSize: 6, margin: 2, scalable: true })}</div>${pill("New code every 35 s · each valid 45 s", "info")}<p class="muted">Attendees scan this with their phone camera. It opens Shonko and asks for their passkey.</p>`;
          const ev = await api(`/events/${e.id}`); $("#count").textContent = `${ev.attended} of ${ev.committed} checked in`;
          setTimeout(loop, 35000);
        } catch (err) {
          $("#qr").innerHTML = `<div class="note">${err.reason === "WrongPhase" ? `Codes appear when check-in opens ${rel(e.checkInStart)} (${time(e.checkInStart)}). Keep this page open.` : esc(errText(err))}</div>`;
          setTimeout(loop, 10000);
        }
      };
      navigator.wakeLock?.request("screen").catch(() => {});
      loop();
    } catch (err) { $("#box").innerHTML = `<div class="note bad">${esc(errText(err))}</div>`; }
  };
}

async function create() {
  const acct = account();
  if (!acct) { view.innerHTML = `<a class="back" href="#/">‹ Events</a><h1>Host an event</h1>${signup()}`; bindSignup(); return; }
  view.innerHTML = `<a class="back" href="#/">‹ Events</a><h1>Host an event</h1><p class="sub">Terms are fixed once published. Their sha256 goes onchain.</p>
    <form class="card form" id="f">
      <label>Title<input name="title" required maxlength="80" placeholder="Rooftop listening session"></label>
      <label>Creator<input name="creator" required maxlength="60" value="${esc(acct.name)}"></label>
      <label>Venue<input name="venue" required maxlength="200" placeholder="Studio 4, Bandra"></label>
      <label>Description<textarea name="description" maxlength="2000" rows="3"></textarea></label>
      <div class="two"><label>Commitment (tUSD)<input name="amount" type="number" min="1" max="1000" step="1" value="5" required></label>
      <label>Capacity<input name="capacity" type="number" min="1" max="10000" value="20" required></label></div>
      <label>Schedule<select name="schedule">
        <option value="demo">Demo: commitments 4 min, check-in 5–15 min from now</option>
        <option value="tonight">Tonight: check-in opens in 3 h</option><option value="week">Next week</option></select></label>
      <label>If someone doesn't show<select name="noShow"><option value="1">Share it among attendees</option><option value="0">It goes to me</option></select></label>
      <label>Dispute contact<input name="dispute" required maxlength="200" placeholder="you@example.com"></label>
      <button class="accent">Publish with passkey</button></form><div id="box"></div>`;
  $("#f").onsubmit = (ev) => {
    ev.preventDefault();
    const f = Object.fromEntries(new FormData(ev.target));
    const [cd, cs, ce] = { demo: [240, 300, 900], tonight: [9000, 10800, 18000], week: [600000, 604800, 615600] }[f.schedule];
    let created;
    run($("#box"), "Event published", async (s) => {
      const t = await chainNow(), dl = t + 600; // the schedule is anchored to chain time, not this device's clock
      const terms = { amount: Math.round(+f.amount * 1e6), capacity: +f.capacity, commitDeadline: t + cd, checkInStart: t + cs, checkInEnd: t + ce, noShow: f.noShow === "1" ? "split" : "organizer", issuer: health.issuer };
      const policy = JSON.stringify({ version: 1, title: f.title.trim(), description: f.description.trim(), creator: f.creator.trim(), venue: f.venue.trim(), startsAt: t + cs + 300, timezone: Intl.DateTimeFormat().resolvedOptions().timeZone, artwork: Math.floor(Math.random() * 6), disputeContact: f.dispute.trim(), ...terms });
      const t2 = { organizer: acct.keyId, policyHash: await policyHash(policy), issuer: health.issuer, amount: terms.amount, capacity: terms.capacity, commitDeadline: terms.commitDeadline, checkInStart: terms.checkInStart, checkInEnd: terms.checkInEnd, noShow: +f.noShow };
      const a = await api(`/accounts/${acct.keyId}`);
      const sig = await authorize(Messages.create(health.chainId, health.treasury, a.nonce, dl, t2)); s();
      created = (await api("/events", { policy, config: t2, deadline: dl, ...sig })).tx; return created;
    }, async () => (await api(`/tx/${created}`)).eventId != null, async () => { const id = (await api(`/tx/${created}`)).eventId; setTimeout(() => location.hash = `#/events/${id}`, 1200); });
  };
}

// ---------------------------------------------------------------- AI agent

async function delegate() {
  const q = params(), acct = account();
  const agent = q.get("agent") || "", agentId = q.get("id"), cap = Math.min(1000, Math.max(1, +(q.get("cap") || 20))), days = Math.min(30, Math.max(1, +(q.get("days") || 7)));
  const info = /^0x[0-9a-fA-F]{40}$/.test(agent) ? await api(`/agents/${agent}${agentId ? `?agentId=${agentId}` : ""}`) : null;
  const rep = info?.reputation;
  view.innerHTML = `<a class="back" href="#/">‹ Home</a><h1>Your agent is asking for a budget</h1><p class="sub">It can reserve places for you. Showing up is always you.</p>
    <section class="card"><dl><dt>Agent</dt><dd class="mono">${short(agent)}</dd>
      <dt>ERC-8004 identity</dt><dd>${agentId ? `#${esc(agentId)} ${pill("registered", "verified")}` : "none"}</dd>
      <dt>Show-up reputation</dt><dd>${rep?.showUpRate != null ? `<b>${rep.showUpRate}%</b> of ${rep.count} reservations ended in a verified check-in` : "no settled reservations yet"}</dd></dl>
      <p class="muted">Reputation counts only feedback written by the ANZEN treasury contract itself: every point is a real commitment and a verified check-in, so it can't be farmed by fake reviewers.</p></section>
    <section class="card"><dl><dt>Reserve places with your funds</dt><dd>Yes, within the cap</dd><dt>Check in for you</dt><dd class="no">Never</dd>
      <dt>Cancel or withdraw</dt><dd class="no">Never</dd><dt>Raise its own budget</dt><dd class="no">Never</dd></dl></section>
    <div id="act"></div><div id="box"></div>`;
  if (!info) { $("#act").innerHTML = '<div class="note bad">This link has no valid agent address.</div>'; return; }
  if (!acct) { $("#act").innerHTML = signup(); bindSignup(); return; }
  const b = document.createElement("button"); b.className = "accent"; b.textContent = `Approve ${cap} tUSD for ${days} day${days > 1 ? "s" : ""}`; $("#act").appendChild(b);
  b.onclick = () => run($("#box"), "Budget approved", async (s) => {
    const a = await api(`/accounts/${acct.keyId}`), cn = await chainNow(), dl = cn + 600, expiry = cn + days * 86400, id = agentId != null ? BigInt(agentId) : NO_AGENT_ID;
    const sig = await authorize(Messages.delegate(health.chainId, health.treasury, agent, id, cap * 1e6, expiry, a.nonce, dl)); s();
    return (await api("/delegate", { agent, agentId: id, cap: cap * 1e6, expiry, deadline: dl, ...sig })).tx;
  }, async () => { const d = (await api(`/accounts/${acct.keyId}`)).delegation; return d.active && d.agent.toLowerCase() === agent.toLowerCase(); }, () => b.remove());
}

async function agentView() {
  const acct = account(); if (!acct) { location.hash = "#/"; return; }
  const me = await api(`/accounts/${acct.keyId}`), d = me.delegation, rep = d.reputation;
  view.innerHTML = `<a class="back" href="#/">‹ Home</a><h1>Your AI agent</h1>
    <section class="card sealed"><span class="label">Budget left</span><b class="amount">${tusd(d.remaining)} <small>tUSD</small></b><p>of ${tusd(d.cap)} · until ${when(d.expiry)}</p>
      ${rep ? `<p>ERC-8004 #${d.agentId} · show-up ${rep.showUpRate != null ? rep.showUpRate + "%" : "—"} over ${rep.count}</p>` : ""}</section>
    <h3>Booked by your agent</h3>${me.commitments.filter((e) => e.me.agentId != null).map(card).join("") || '<p class="empty">Nothing yet.</p>'}`;
}

// ---------------------------------------------------------------- proof & account

async function proof(eventId, keyId) {
  const p = await api(`/proof/${eventId}/${keyId}`), e = p.event, m = p.participation;
  const yes = (ok) => ok ? pill("Yes", "verified") : pill("No");
  view.innerHTML = `<a class="back" href="#/events/${eventId}">‹ ${esc(e.title)}</a><h1>Proof</h1><p class="sub">Everything here is read from the treasury contract on ${esc(health.network)}.</p>
    <section class="card"><dl><dt>Commitment recorded</dt><dd>${yes(m.status !== "none")}</dd><dt>Reserved by an AI agent</dt><dd>${m.agentId != null ? `ERC-8004 #${m.agentId}` : "No, by the owner"}</dd>
      <dt>Check-in signed with a fresh venue code</dt><dd>${yes(m.attended)}</dd><dt>Outcome applied</dt><dd>${yes(m.status === "settled")}</dd>
      <dt>Supporter passport</dt><dd>${m.passportId ? `No. ${m.passportId}` : "—"}</dd><dt>Terms match onchain hash</dt><dd>${yes(e.policyVerified)}</dd></dl></section>
    <section class="card"><dl><dt>Treasury</dt><dd class="mono">${short(p.treasury)}</dd><dt>Participant key</dt><dd class="mono">${short(p.keyId)}</dd>
      ${p.transactions.map((t) => `<dt>${esc(t.action)}</dt><dd>${txLink(t.tx)}</dd>`).join("")}</dl></section>
    <h3>What this doesn't prove</h3>${p.limitations.map((l) => `<div class="note">${esc(l)}</div>`).join("")}`;
}

async function accountView() {
  const acct = account(); if (!acct) { view.innerHTML = signup(); bindSignup(); return; }
  const [me, act] = await Promise.all([api(`/accounts/${acct.keyId}`), api(`/accounts/${acct.keyId}/activity`)]);
  view.innerHTML = `<h1>${esc(acct.name)}</h1><section class="card"><dl><dt>Account key</dt><dd class="mono">${short(acct.keyId)}</dd><dt>Type</dt><dd>WebAuthn passkey (P-256)</dd>
    <dt>Balance</dt><dd>${tusd(me.balance)} tUSD</dd><dt>Network</dt><dd>${esc(health.network)} · chain ${health.chainId}</dd><dt>Treasury</dt><dd class="mono">${short(health.treasury)}</dd></dl></section>
    <h3>Activity</h3>${act.map((a) => `<div class="card row"><div><b>${esc(a.action)}</b><span>${esc(a.eventTitle || "")} ${when(a.at)}</span></div>${txLink(a.tx)}</div>`).join("") || '<p class="empty">Nothing yet.</p>'}
    <button class="quiet danger" id="forget">Forget this passkey on this browser</button>`;
  $("#forget").onclick = () => { if (confirm("Forget this account on this browser? The passkey stays on your device, but this page won't know which one is yours.")) { forget(); location.hash = "#/"; } };
}

// ---------------------------------------------------------------- boot

(async () => {
  try { health = await api("/health"); $("#net").innerHTML = pill(`${health.network} · test dollars`, "info"); }
  catch (e) { view.innerHTML = `<div class="note bad">${esc(errText(e))}</div>`; return; }
  addEventListener("hashchange", render); render();
})();
