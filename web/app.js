// ANZEN web: the consumer app in a browser, with passkeys. Hash routes, no build step.
import { Messages, NO_AGENT_ID, account, authorize, createAccount, forget, hex, keyIdOf, policyHash, unhex } from "./anzen.js";

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
  PolicyMismatch: "The event terms are inconsistent and weren't published.",
  UnknownEvent: "This event doesn't exist on this network. It may be from another deployment.", InvalidConfig: "Those event settings aren't valid. Check the amounts and schedule.",
  OwnEvent: "Hosts can't commit to their own event.",
  CategoryNotAllowed: "Your Agent Pass doesn't allow this category.", FeeAboveLimit: "This event's late fee is above your Agent Pass limit.",
  TooManyActive: "Your agent already has as many active bookings as your pass allows.", TooFarAhead: "This event is further ahead than your Agent Pass allows.",
  FaucetDisabled: "Test dollars aren't available on this network.", TxNotFound: "That transaction isn't known to this service.", Reverted: "The contract rejected this action. Nothing changed.", TxFailed: "The transaction failed onchain. Nothing changed.",
};
const errText = (e) => COPY[e.reason] || `Something went wrong (${e.reason || e.message}).`;

// ---------------------------------------------------------------- formatting

const tusd = (base) => { const w = Math.floor(base / 1e6), f = (base % 1e6).toString().padStart(6, "0").replace(/0+$/, ""); return `${w.toLocaleString()}${f ? "." + f : ""}`; };
const when = (t) => { const d = new Date(t * 1000); return d.toLocaleString([], { weekday: "short", month: "short", day: "numeric", hour: "numeric", minute: "2-digit", ...(d.getFullYear() !== new Date().getFullYear() && { year: "numeric" }) }); };
const time = (t) => new Date(t * 1000).toLocaleTimeString([], { timeStyle: "short" });
const rel = (t) => { const d = t - now(), a = Math.abs(d); const s = a < 60 ? `${a}s` : a < 3600 ? `${Math.round(a / 60)} min` : a < 86400 ? `${Math.round(a / 3600)} h` : `${Math.round(a / 86400)} days`; return d >= 0 ? `in ${s}` : `${s} ago`; };
const short = (h) => h ? `${h.slice(0, 6)}…${h.slice(-4)}` : "";
const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
/** Chain-specific words, set from /health: the Solana program or the Monad treasury contract. */
let C = {};
const setChain = () => {
  const svm = health.vm === "svm";
  C = { svm, chain: svm ? "Solana" : "Monad", the: svm ? "the ANZEN program" : "the treasury contract", short: svm ? "the program" : "the treasury",
    Short: svm ? "The program" : "The treasury", label: svm ? "ANZEN program" : "Treasury contract", addr: svm ? health.programId : health.treasury };
};
const B58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
/** 0x + 64 hex (a Solana key) → base58, the way Solana tools show it. EVM addresses stay hex. */
const key58 = (h) => { if (!h || h.length !== 66) return h; let n = BigInt(h), s = ""; while (n > 0n) { s = B58[Number(n % 58n)] + s; n /= 58n; } for (const b of unhex(h)) { if (b) break; s = "1" + s; } return s; };
const shortKey = (h) => { const k = key58(h); return k.startsWith("0x") ? short(k) : `${k.slice(0, 4)}…${k.slice(-4)}`; };
const explorerUrl = (tx) => health.explorer.includes("{tx}") ? health.explorer.replace("{tx}", tx) : health.explorer + tx;
const txLink = (tx) => health?.explorer ? `<a href="${explorerUrl(tx)}" target="_blank" rel="noopener" class="mono">${short(tx)} ↗</a>` : `<span class="mono">${short(tx)}</span>`;
/** The panda, used sparingly: onboarding, empty states and real successes. Decorative unless given alt text. */
const panda = (mood, alt = "") => `<img src="brand/panda-${mood}.webp" alt="${esc(alt)}" ${alt ? "" : 'aria-hidden="true"'} loading="lazy" decoding="async">`;
const empty = (title, body, action = "") => `<div class="empty">${panda("curious")}<b>${esc(title)}</b><p>${body}</p>${action}</div>`;
const CATEGORIES = ["Other", "Fitness", "Dining", "Meetups", "Health", "Learning"];
const pct = (bps) => `${bps / 100}%`;
/** Plain words for an Agent Pass's rules (0 = no limit, except the fee: 0 = free release only, ≥10000 = any). */
const rulesText = (r) => [
  r.categories ? `Only ${CATEGORIES.filter((_, i) => r.categories & (1 << i)).join(", ")}` : "Any category",
  r.maxLateFeeBps >= 10000 ? "Any late give-back fee" : r.maxLateFeeBps ? `Late give-back fee at most ${pct(r.maxLateFeeBps)}` : "Only events that are free to give back",
  r.maxActive ? `At most ${r.maxActive} active booking${r.maxActive > 1 ? "s" : ""} at once` : "No limit on active bookings",
  r.maxLeadDays ? `Up to ${r.maxLeadDays} day${r.maxLeadDays > 1 ? "s" : ""} ahead` : "Any date"];
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
  const trigger = document.activeElement?.tagName === "BUTTON" ? document.activeElement : null;
  if (trigger) { if (trigger.disabled) return; trigger.disabled = true; trigger.setAttribute("aria-busy", "true"); }
  const release = () => { if (trigger) { trigger.disabled = false; trigger.removeAttribute("aria-busy"); } };
  try {
    set("Waiting for your passkey", "Confirm with Face ID, Touch ID, Windows Hello or your phone.");
    tx = await submit((s) => set("Submitting", `Sending your signed action to ${C.chain}.`));
    set("Pending", "Submitted. Waiting for the network.", "pending", tx);
    let status = "submitted";
    for (let i = 0; i < 60 && status === "submitted"; i++) { await new Promise((r) => setTimeout(r, 700)); status = (await api(`/tx/${tx}`)).status; }
    if (status === "failed") { release(); return set("Not completed", COPY.TxFailed, "bad", tx); }
    if (status !== "confirmed") return set("Still pending", "Not confirmed yet. It may still go through. Check Account › Activity before trying again.", "pending", tx);
    set("Confirmed onchain", `Reading ${C.short} to verify the result.`, "pending", tx);
    for (let i = 0; i < 20; i++) { if (await verified()) { set(typeof label === "function" ? label() : label, `Verified against ${C.the}.`, "ok", tx); onDone?.(tx); return; } await new Promise((r) => setTimeout(r, 500)); }
    set("Still pending", `Included, but ${C.short} doesn't show it yet.`, "pending", tx);
  } catch (e) {
    release();
    if (e.reason === "Cancelled") set("Cancelled", COPY.Cancelled, "neutral");
    else set("Not completed", errText(e), "bad", tx);
  }
}

// ---------------------------------------------------------------- views

const routes = [
  [/^#?\/?$/, home], [/^#\/events\/(\d+)$/, event], [/^#\/checkin/, checkin], [/^#\/desk\/(\d+)$/, desk],
  [/^#\/create$/, create], [/^#\/delegate/, delegate], [/^#\/proof\/(\d+)\/(0x[0-9a-fA-F]{64})$/, proof],
  [/^#\/account$/, accountView], [/^#\/agent$/, agentView], [/^#\/agents$/, agentsBoard], [/^#\/venue$/, venue],
];

const SKELETON = '<div class="skel" aria-label="Loading"><i></i><i></i><i></i></div>';
const TAB = { create: "create", agents: "agents", agent: "agents", delegate: "agents", account: "account", venue: "account" };

/** Refreshes the current page after a success, unless the user has already moved on (it would rebuild that page mid-action). */
const refreshSoon = (ms) => { const h = location.hash; setTimeout(() => location.hash === h && render(), ms); };
async function render() {
  const h = location.hash.split("?")[0] || "#/";
  const tab = TAB[h.split("/")[1]] || "home";
  document.querySelectorAll("nav.tabs a").forEach((a) => a.dataset.tab === tab ? a.setAttribute("aria-current", "page") : a.removeAttribute("aria-current"));
  window.scrollTo(0, 0);
  for (const [re, fn] of routes) {
    const m = h.match(re); if (!m) continue;
    view.innerHTML = SKELETON;
    try {
      await fn(...m.slice(1));
      // A slow page that finished after the user moved on just overwrote the newer one: draw the current page again.
      if ((location.hash.split("?")[0] || "#/") !== h) return render();
    }
    catch (e) {
      view.innerHTML = `<div class="note bad" role="alert">${esc(errText(e))}</div><div class="btns"><button onclick="location.hash='#/'">All events</button><button class="accent" id="retry">Try again</button></div>`;
      $("#retry").onclick = render;
    }
    return;
  }
  location.hash = "#/";
}
const params = () => new URLSearchParams(location.hash.split("?")[1] || "");

function signup(next) {
  return `<section class="card sealed signup"><div class="mark"></div><h2>Your account is a passkey</h2>
    <p>No wallet, no seed phrase. Face ID, Touch ID, Windows Hello or your phone holds the key, and every action shows exactly what you sign.</p>
    <label class="label" for="name">Your name</label><input id="name" placeholder="How organizers will see you" maxlength="40" autocomplete="nickname">
    <button class="accent" id="mk">Create passkey account</button><div id="mkbox"></div></section>`;
}
function bindSignup(after) {
  $("#mk")?.addEventListener("click", async () => {
    const name = $("#name").value.trim() || "ANZEN member";
    try { await createAccount(name); toast("Passkey created"); after?.(); render(); }
    catch (e) { $("#mkbox").innerHTML = `<div class="note bad">${esc(e.name === "NotAllowedError" ? COPY.Cancelled : e.message)}</div>`; }
  });
}

async function home() {
  const acct = account();
  const [events, me] = await Promise.all([api(`/events${acct ? `?key=${acct.keyId}` : ""}`), acct ? api(`/accounts/${acct.keyId}`) : null]);
  const checkedIn = events.reduce((n, e) => n + e.attended, 0), committed = events.reduce((n, e) => n + e.committed, 0);
  const plans = acct ? events.filter((e) => ["committed", "checked_in", "waitlisted"].includes(e.me?.status)) : [];
  const top = acct ? `
    <section class="greet">${panda("confident")}<div><h1>Hi, ${esc(acct.name.split(" ")[0])}</h1><p>Your agent can book it. Only you can show up.</p></div></section>
    <section class="card balance"><div><span class="label">Available</span><b class="amount">${tusd(me.balance)} <small>tUSD</small></b><span class="hint">Test dollars on ${esc(health.network)}. They have no value.</span></div>
      ${me.faucetUsed ? "" : '<button id="faucet" class="primary">Get 100 test dollars</button>'}<div id="fbox"></div></section>
    <a class="card row" href="#/account"><div><b>Show-Up Score <span class="score-mini num">${me.score}</span></b><span>${me.scoreBasis ? `🔥 ${me.record.streak} streak · ${me.record.attended} check-ins, ${me.record.missed} missed` : "Everyone starts at 75. Showing up moves it."}</span></div><span class="chev">›</span></a>
    ${me.delegation?.active ? `<a class="card row" href="#/agent"><div><b>Your AI agent</b><span>${tusd(me.delegation.remaining)} of ${tusd(me.delegation.cap)} tUSD left${me.delegation.reputation?.showUpRate != null ? ` · show-up ${me.delegation.reputation.showUpRate}%` : ""}</span></div>${pill("Active", "accent")}</a>` : ""}
    ${plans.length ? `<h3>Your plans</h3>${plans.map(card).join("")}` : ""}`
  : `
    <section class="hero"><h1>Show up.<br><span>Make it count.</span></h1>${panda("happy", "The ANZEN panda waving")}
      <p class="sub">Reserve real events with a small refundable commitment. Your AI agent can book; only you can check in.</p></section>
    <ol class="steps">
      <li><div><b>Find an event worth attending</b><span>Every event publishes its rules before anyone commits.</span></div></li>
      <li><div><b>Commit under those rules</b><span>A small amount, signed with your passkey. You see exactly what you sign.</span></div></li>
      <li><div><b>Check in at the venue</b><span>Scan a code that changes every 35 seconds and sign it with your passkey.</span></div></li>
      <li><div><b>${C.Short} applies the rules</b><span>Attend and your commitment comes back, with a supporter passport. Miss it and the organizer's no-show rule applies.</span></div></li>
    </ol>
    ${signup()}
    <div class="stats"><div><b>${events.length}</b><span>events</span></div><div><b>${committed}</b><span>commitments</span></div><div><b>${checkedIn}</b><span>verified check-ins</span></div></div>`;
  view.innerHTML = `${top}
    <div class="search"><svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="11" cy="11" r="6.5"/><path d="m20 20-4.2-4.2"/></svg>
      <input id="q" type="search" placeholder="Search events, hosts, venues" aria-label="Search events" autocomplete="off"></div>
    <div id="list"></div>
    <a class="card row" href="#/agents"><div><b>Agent show-up board</b><span>AI agents ranked by how often their humans actually came. ${C.svm ? "Written only by the ANZEN program's settle instruction." : "Written by the treasury on ERC-8004."}</span></div><span class="chev">›</span></a>
    <a class="card row" href="#/create"><div><b>Host an event</b><span>Fix the terms, run a check-in desk, let ${C.short} settle.</span></div><span class="chev">›</span></a>`;
  const draw = (q = "") => {
    const hit = (e) => !q || [e.title, e.creator, e.venue].some((t) => t.toLowerCase().includes(q));
    const open = events.filter((e) => e.phase === "open" && hit(e)), live = events.filter((e) => e.phase === "check_in" && hit(e));
    $("#list").innerHTML = (live.length ? `<h3>Check-in open now</h3>${live.map(card).join("")}` : "") + `<h3>Open for commitments</h3>` +
      (open.length ? open.map(card).join("") : q ? empty("No matches", `Nothing open matches “${esc(q)}”. Try a host or a city.`)
        : empty("No open events right now", "Host one: it takes a minute, and the rules are fixed onchain once published.", '<button class="primary" onclick="location.hash=\'#/create\'">Host an event</button>'));
  };
  draw();
  $("#q").addEventListener("input", (ev) => draw(ev.target.value.trim().toLowerCase()));
  bindSignup();
  $("#faucet")?.addEventListener("click", () => run($("#fbox"), "100 test dollars added",
    async () => (await api("/faucet", { keyId: acct.keyId })).tx,
    async () => (await api(`/accounts/${acct.keyId}`)).balance > me.balance, () => refreshSoon(900)));
}

function status(e) {
  const me = e.me?.status || "none";
  if (me === "settled") return pill("Settled", "verified");
  if (me === "released") return pill("Given back");
  if (me === "waitlisted") return pill(e.cancelled || e.finalized ? "Refund due" : "Waitlisted", "pending");
  if (e.cancelled) return pill("Cancelled", "failed");
  if (me === "checked_in") return pill("Checked in", "verified");
  if (me === "committed") return pill(e.phase === "check_in" ? "Check in now" : "Committed", e.phase === "check_in" ? "pending" : "accent");
  if (e.phase === "open" && e.committed >= e.capacity) return pill(e.waitlisted < e.capacity ? "Full · waitlist open" : "Full", "pending");
  return pill({ open: "Open", closed: "Closed", check_in: "Check-in open", awaiting_settlement: "Settling", finalized: "Settled" }[e.phase] || e.phase);
}
const art = (e) => `<div class="art a${e.artwork % 6}"></div>`;
const card = (e) => `<a class="card ev" href="#/events/${e.id}">${art(e)}<div><b>${esc(e.title)}</b><span>${esc(e.creator)} · ${when(e.startsAt)}</span><div class="meta">${status(e)}<b>${tusd(e.amount)} tUSD</b></div></div></a>`;

async function event(id) {
  const acct = account();
  const e = await api(`/events/${id}${acct ? `?key=${acct.keyId}` : ""}`);
  const me = e.me?.status || "none", mine = acct && e.organizer === acct.keyId;
  view.innerHTML = `<a class="back" href="#/">‹ Events</a>${art(e).replace("art", "art big")}
    <div class="pills">${status(e)}${pill(e.categoryName)}</div><h1>${esc(e.title)}</h1><p class="sub">by ${esc(e.creator)} · ${esc(e.venue)}</p>
    <section class="card sealed"><span class="label">Commitment per person</span><b class="amount">${tusd(e.amount)} <small>tUSD</small></b>
      <p>${e.noShow === "split" ? "Check in and it comes back, plus an equal share of no-shows' commitments." : "Check in and it comes back. If you don't, it goes to the organizer."}</p>${pill(`${health.network} · test dollars`, "info")}</section>
    <section class="card"><dl>
      <dt>Starts</dt><dd>${when(e.startsAt)}</dd><dt>Commitments close</dt><dd>${when(e.commitDeadline)} (${rel(e.commitDeadline)})</dd>
      <dt>Check-in</dt><dd>${when(e.checkInStart)} – ${time(e.checkInEnd)}</dd><dt>Seats</dt><dd>${e.committed} of ${e.capacity} taken${e.waitlisted ? ` · ${e.waitlisted} on the waitlist` : ""}</dd>
      <dt>Give your place back</dt><dd>${e.lateFeeBps ? `Free until ${when(e.commitDeadline)}, then ${pct(e.lateFeeBps)} fee unless refilled` : "Free until check-in opens"}</dd>
      <dt>Terms hash</dt><dd class="mono">${short(e.policyHash)} ${e.policyVerified ? pill("matches onchain", "verified") : pill("mismatch", "failed")}</dd>
      <dt>Published in</dt><dd>${txLink(e.createTx)}</dd></dl></section>
    <section class="card"><b>How check-in works</b><p class="muted">During the window, scan the code at the venue. It rotates every 35 s, expires in 45 s and is signed by the event's issuer; your passkey signs it too. This shows your device signed a fresh code. It doesn't prove where you were. Disputes: ${esc(e.disputeContact)}.</p></section>
    <div id="act"></div><div id="box"></div>`;
  const act = $("#act");
  if (!acct) { act.innerHTML = signup(); bindSignup(); return; }
  const btn = (text, cls, fn) => { const b = document.createElement("button"); b.className = cls; b.textContent = text; b.onclick = fn; act.appendChild(b); };
  const full = e.committed >= e.capacity, beforeCheckIn = ["open", "closed"].includes(e.phase) && !e.cancelled;
  if (me === "none" && mine && e.phase === "open") act.insertAdjacentHTML("beforeend", `<div class="note">You're the host. Guests commit here; you run the check-in desk.</div>`);
  if (me === "none" && !mine && e.phase === "open" && e.policyVerified && !full) btn("Review commitment", "accent", () => review(e, acct));
  if (me === "none" && !mine && e.phase === "open" && e.policyVerified && full && e.waitlisted < e.capacity) {
    act.insertAdjacentHTML("beforeend", `<div class="note">Every seat is taken. Join the waitlist: your commitment is held, and if someone gives their place back, it's yours automatically. If no seat frees up, it all comes back.</div>`);
    btn("Review: join the waitlist", "accent", () => review(e, acct));
  }
  if (me === "none" && e.phase === "open" && full && e.waitlisted >= e.capacity) act.innerHTML = `<div class="note">${COPY.EventFull} The waitlist is full too.</div>`;
  if (me === "committed" && e.phase === "check_in") { btn("Scan venue code", "accent", () => scan(e.id)); btn("Show my code to staff", "", () => showCode(e)); }
  if (me === "committed" && beforeCheckIn) act.insertAdjacentHTML("beforeend", `<div class="card sage"><b>You're in</b><p class="muted">Check-in opens ${rel(e.checkInStart)} (${when(e.checkInStart)}). Bring the phone with your passkey.</p></div>`);
  if (me === "waitlisted" && !e.cancelled && !e.finalized && e.phase !== "awaiting_settlement") act.insertAdjacentHTML("beforeend", `<div class="card sage"><b>You're on the waitlist</b><p class="muted">${tusd(e.amount)} tUSD is held. If someone gives their place back before check-in opens, the seat is yours and we'll show it here. If not, it all comes back when the event settles.</p></div>`);
  if (["committed", "waitlisted"].includes(me) && beforeCheckIn) btn(me === "waitlisted" ? "Leave the waitlist" : "Give my place back", "quiet", () => releaseReview(e));
  if (me === "released") act.insertAdjacentHTML("beforeend", `<div class="card"><b>You gave this place back</b><p class="muted">Your refund went to your balance. Check Account › Activity for the transaction.</p></div>`);
  if (["committed", "checked_in", "waitlisted"].includes(me) && (e.phase === "awaiting_settlement" || e.phase === "finalized" || e.cancelled))
    btn(e.cancelled ? "Apply refund" : "Apply outcome", "accent", () => run($("#box"), "Outcome applied",
      async (s) => { s(); return (await api(`/events/${e.id}/settle`, { keyId: acct.keyId })).tx; },
      async () => (await api(`/events/${e.id}?key=${acct.keyId}`)).me.status === "settled", () => refreshSoon(1200)));
  if (me === "checked_in" && !(e.phase === "awaiting_settlement" || e.phase === "finalized")) act.insertAdjacentHTML("beforeend", `<div class="card sage celebrate">${panda("success")}<div><b>You're checked in</b><p class="muted">Your outcome can be applied when check-in closes ${rel(e.checkInEnd)}.</p></div></div>`);
  if (me === "settled") act.insertAdjacentHTML("beforeend", outcome(e));
  if (!["none", "released"].includes(me)) btn("View proof", "quiet", () => location.hash = `#/proof/${e.id}/${acct.keyId}`);
  if (mine && !e.cancelled && ["open", "closed", "check_in"].includes(e.phase)) btn("Open check-in desk", "", () => location.hash = `#/desk/${e.id}`);
}

/** The published give-back rule, in one sentence. */
const giveBack = (e) => e.lateFeeBps
  ? `Free until ${when(e.commitDeadline)}. After that, ${pct(e.lateFeeBps)} goes to the host unless someone on the waitlist takes your seat. Not possible once check-in opens.`
  : "Free until check-in opens.";

/** Give the place back (or leave the waitlist): what comes back, what the host keeps, whether it earns a shield. */
function releaseReview(e) {
  const acct = account(), waiting = e.me.status === "waitlisted", inTime = now() <= e.commitDeadline;
  const refill = !waiting && e.waitlisted > 0, fee = waiting || inTime || refill ? 0 : Math.floor(e.amount * e.lateFeeBps / 10000);
  $("#act").innerHTML = `<section class="card"><h2>${waiting ? "Leave the waitlist" : "Give your place back"}</h2><dl class="stack">
      <dt>Comes back to your balance</dt><dd><b class="num">${tusd(e.amount - fee)} tUSD</b></dd>
      <dt>Kept by the host</dt><dd>${fee ? `${tusd(fee)} tUSD, the published ${pct(e.lateFeeBps)} late give-back fee` : "Nothing"}</dd>
      ${waiting ? "" : `<dt>Your seat</dt><dd>${refill ? "Goes straight to the first person on the waitlist" : "Opens up for someone else"}</dd>
      <dt>Streak shield</dt><dd>${inTime ? "Yes: giving back before commitments close earns one (you can hold two). It saves your streak if you miss an event later." : "No: shields are earned only before commitments close"}</dd>`}
    </dl><p class="hint">${C.Short} applies the published rule at the moment this runs.${!waiting && !inTime && e.lateFeeBps ? " If the waitlist empties first, the fee applies." : ""}</p></section>
    <div class="btns"><button id="rlback">Back</button><button class="accent" id="rlgo">${waiting ? "Leave waitlist" : "Give back"} with passkey</button></div>`;
  $("#rlback").onclick = render;
  $("#rlgo").onclick = () => run($("#box"), waiting ? "You left the waitlist" : "Place given back",
    async (s) => { const a = await api(`/accounts/${acct.keyId}`); const dl = (await chainNow()) + 600;
      const sig = await authorize(Messages.release(health.chainId, health.treasury, e.id, a.nonce, dl)); s();
      return (await api(`/events/${e.id}/release`, { deadline: dl, ...sig })).tx; },
    async () => (await api(`/events/${e.id}?key=${acct.keyId}`)).me.status === "released", () => refreshSoon(1500));
}

/** Review → confirm → passkey → verified. Every rule that touches the money is shown before anything is signed. */
function review(e, acct) {
  const wait = e.committed >= e.capacity;
  $("#act").innerHTML = `<section class="card" aria-labelledby="rv"><h2 id="rv">${wait ? "Review: join the waitlist" : "Review your commitment"}</h2><dl class="stack">
      <dt>${wait ? "Held while you wait" : "You commit"}</dt><dd><b class="num">${tusd(e.amount)} tUSD</b></dd>
      ${wait ? `<dt>If a seat frees up</dt><dd>It's yours automatically, and the same rules below apply</dd><dt>If none does</dt><dd>All of it comes back when the event settles</dd>` : ""}<dt>Network</dt><dd>${esc(health.network)} · test dollars, no value</dd>
      <dt>If you check in</dt><dd>${e.noShow === "split" ? "It comes back, plus an equal share of no-shows' commitments" : "It comes back in full"}</dd>
      <dt>If you don't</dt><dd>${e.noShow === "split" ? "It's shared among people who checked in" : "It goes to the organizer"}</dd>
      <dt>Check-in window</dt><dd>${when(e.checkInStart)} – ${time(e.checkInEnd)}</dd><dt>Changed your mind</dt><dd>${giveBack(e)}</dd><dt>Network fee</dt><dd>Paid by ANZEN's relayer</dd>
      <dt>${C.label}</dt><dd class="mono">${shortKey(C.addr)}</dd></dl>
      <p class="hint">Your passkey signs these exact terms for this event only. It can't be reused for anything else.</p></section>
    <div class="btns"><button id="rvback">Back</button><button class="accent" id="rvgo">${wait ? `Hold ${tusd(e.amount)} tUSD on the waitlist` : `Commit ${tusd(e.amount)} tUSD`} with passkey</button></div>`;
  $("#rvback").onclick = render;
  let got;
  $("#rvgo").onclick = () => run($("#box"), () => got === "waitlisted" ? "You're on the waitlist" : "Commitment recorded",
      async (submitting) => { const a = await api(`/accounts/${acct.keyId}`); const dl = (await chainNow()) + 600;
        const sig = await authorize(Messages.commit(health.chainId, health.treasury, e.id, e.amount, a.nonce, dl)); submitting();
        return (await api(`/events/${e.id}/commit`, { deadline: dl, ...sig })).tx; },
      async () => ["committed", "waitlisted"].includes(got = (await api(`/events/${e.id}?key=${acct.keyId}`)).me.status), () => refreshSoon(1200));
}

function outcome(e) {
  const m = e.me;
  if (!m.attended && m.settledAmount > 0) return `<section class="card"><b>Refunded</b><p class="muted">${tusd(m.settledAmount)} tUSD went back to your balance: ${e.cancelled ? "the organizer cancelled the event" : "no seat freed up from the waitlist"}.</p></section>`;
  if (!m.attended) return `<section class="card"><b>No-show rule applied</b><p class="muted">There's no check-in from this account, so your commitment ${e.noShow === "split" ? "was shared among attendees" : "went to the organizer"}.</p></section>`;
  return `<section class="card sealed passport">${panda("excited")}<span class="label">SUPPORTER PASSPORT</span>
    <h2>${esc(e.title)}</h2><p>${esc(e.creator)} · ${when(e.startsAt)}</p><b class="accent-text">No. ${m.passportId} · issued onchain</b>
    <p>${tusd(m.settledAmount)} tUSD returned to your balance${m.settledAmount > e.amount ? ` (your ${tusd(e.amount)} + ${tusd(m.settledAmount - e.amount)} from no-shows)` : ""}.</p></section>`;
}

// ---------------------------------------------------------------- check-in

function parseCode(text) {
  try {
    const u = new URL(text.trim().replace(/^anzen:\/\/checkin\?/, "https://x/#/checkin?"));
    const q = new URLSearchParams(u.hash.split("?")[1] || u.search);
    const c = { e: +q.get("e"), c: q.get("c"), x: +q.get("x"), s: q.get("s") };
    return /^0x[0-9a-f]{64}$/i.test(c.c) && /^0x[0-9a-f]{128}([0-9a-f]{2})?$/i.test(c.s) && c.e > 0 ? c : null;
  } catch { return null; }
}

const scan = () => scanner("Point at the code on the check-in desk", parseCode,
  (code) => location.hash = `#/checkin?e=${code.e}&c=${code.c}&x=${code.x}&s=${code.s}`, "That isn't an ANZEN check-in code.");

/** Camera → QR → parse; calls onCode once with the first valid code. */
async function scanner(prompt, parse, onCode, wrong) {
  view.insertAdjacentHTML("beforeend", `<div class="scanner" id="scanner"><video playsinline muted></video><div class="frame"></div><p>${esc(prompt)}</p><button class="quiet" id="close">Cancel</button></div>`);
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
    const code = text && parse(text);
    if (code) { stop(); navigator.vibrate?.(30); onCode(code); return; }
    if (text) toast(wrong);
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
    async () => (await api(`/events/${e.id}?key=${acct.keyId}`)).me.attended, () => {
      b.remove(); navigator.vibrate?.([20, 40, 20]);
      $("#act").innerHTML = `<div class="card sage celebrate">${panda("success", "The ANZEN panda celebrating")}<div><b>See you inside</b><p class="muted">Your outcome can be applied when check-in closes ${rel(e.checkInEnd)}.</p></div></div>`;
    });
}

// ---------------------------------------------------------------- staff-scanned check-in (silver)

const PRESENT_WINDOW = 45;
function parsePresent(text) {
  const t = text.trim(); if (!t.startsWith("anzen://present?")) return null;
  const q = new URLSearchParams(t.slice("anzen://present?".length));
  const p = { e: +q.get("e"), x: q.get("x"), y: q.get("y"), t: +q.get("t"), a: q.get("a") };
  return p.e > 0 && p.t > 0 && /^0x[0-9a-f]{64}$/i.test(p.x) && /^0x[0-9a-f]{64}$/i.test(p.y) && /^0x[0-9a-f]{128,8192}$/i.test(p.a) ? p : null;
}

/** The guest's own code: their passkey signs (event, time); venue staff scan it and countersign. Valid 45 s. */
async function showCode(e) {
  const acct = account();
  $("#act").innerHTML = `<section class="card"><h2>Your check-in code</h2><p class="muted">Show this to the host's staff. They scan it and approve with their passkey. Each code works for 45 seconds; it proves your passkey signed it just now, not where you are.</p>
    <div id="code"></div><div class="btns"><button id="cdback">Back</button><button class="accent" id="cdnew">Create code with passkey</button></div></section>`;
  $("#cdback").onclick = render;
  const make = async () => {
    try {
      const t = await chainNow(); const sig = await authorize(Messages.present(health.chainId, health.treasury, e.id, t));
      const uri = `anzen://present?e=${e.id}&x=${sig.qx}&y=${sig.qy}&t=${t}&a=${sig.auth}`;
      const q = qrcode(0, "L"); q.addData(uri); q.make();
      $("#code").innerHTML = `<div class="qr" data-uri="${esc(uri)}">${q.createSvgTag({ cellSize: 4, margin: 2, scalable: true })}</div><div id="cdleft"></div>`;
      $("#cdnew").textContent = "New code with passkey";
      const born = Date.now(); let ticks = 0; // wall time since signing: the local devnet's chain clock can be warped
      const left = () => { const l = Math.round(PRESENT_WINDOW - (Date.now() - born) / 1000); $("#cdleft") && ($("#cdleft").innerHTML = l > 0 ? pill(`Valid for ${l}s`, l > 10 ? "info" : "pending") : pill("Expired · create a new code", "failed")); };
      left(); const iv = setInterval(async () => {
        if (!$("#cdleft")) return clearInterval(iv);
        left();
        if (ticks++ % 3 === 0 && (await api(`/events/${e.id}?key=${acct.keyId}`).catch(() => null))?.me?.attended) {
          clearInterval(iv); navigator.vibrate?.([20, 40, 20]);
          $("#act").innerHTML = `<div class="card sage celebrate">${panda("success", "The ANZEN panda celebrating")}<div><b>Staff checked you in</b><p class="muted">Your outcome can be applied when check-in closes ${rel(e.checkInEnd)}.</p></div></div>`;
        }
      }, 1000);
    } catch (err) { $("#code").innerHTML = `<div class="note bad">${esc(errText(err))}</div>`; }
  };
  $("#cdnew").onclick = make;
}

// ---------------------------------------------------------------- organizer desk

async function desk(id) {
  const acct = account(); const e = await api(`/events/${id}`);
  view.innerHTML = `<a class="back" href="#/events/${id}">‹ ${esc(e.title)}</a><h1>Check-in desk</h1><p class="sub" id="count">${e.attended} of ${e.committed} checked in</p>
    <p class="muted">Opening the desk asks for your passkey once. Then a new code appears every 35 seconds; each works for 45, so a photo of it goes stale fast.</p>
    <div id="act"></div><div id="qr"></div><div id="box"></div>
    <h3>Scan guests instead</h3><section class="card"><p class="muted">Guests can show their own 45-second code. You scan it and approve each one with your passkey, so a code sent from somewhere else still needs your eyes on a person.</p>
      <button id="scanguest">Scan a guest's code</button>
      <details><summary>Camera not available? Paste a guest's code</summary><label class="label" for="guestcode">Guest code</label><input id="guestcode" placeholder="anzen://present?…" autocomplete="off"><button id="admit">Check in this guest</button></details></section>
    <div id="guests"></div>`;
  if (!acct || e.organizer !== acct.keyId) { $("#act").innerHTML = `<div class="note bad">${COPY.NotOrganizer}</div>`; view.querySelectorAll("#scanguest, details").forEach((x) => x.remove()); return; }
  const admit = async (p) => {
    if (!p || p.e !== e.id) { toast(p ? "That code is for another event." : "That isn't an ANZEN guest code."); return; }
    const guestId = await keyIdOf(p.x, p.y), box = document.createElement("div");
    $("#guests").prepend(box);
    return run(box, `Guest ${short(guestId)} checked in`, async (s) => {
      const sig = await authorize(Messages.staff(health.chainId, health.treasury, e.id, guestId, p.t)); s();
      return (await api(`/events/${e.id}/staff-checkin`, { guest: { qx: p.x, qy: p.y, auth: p.a }, issuedAt: p.t, staff: sig })).tx;
    }, async () => (await api(`/events/${e.id}?key=${guestId}`)).me.attended, async () => {
      const ev = await api(`/events/${e.id}`); $("#count") && ($("#count").textContent = `${ev.attended} of ${ev.committed} checked in`);
    });
  };
  $("#scanguest").onclick = () => scanner("Point at the guest's code", parsePresent, admit, "That isn't an ANZEN guest code.");
  // The desk admits guest after guest: re-enable the button once each admission has finished.
  $("#admit").onclick = async (ev) => { const b = ev.currentTarget; await admit(parsePresent($("#guestcode").value)); $("#guestcode").value = ""; b.disabled = false; b.removeAttribute("aria-busy"); };
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
          $("#qr").innerHTML = `<div class="qr">${q.createSvgTag({ cellSize: 6, margin: 2, scalable: true })}</div>${pill("New code every 35 s · each valid 45 s", "info")}<p class="muted">Attendees scan this with their phone camera. It opens ANZEN and asks for their passkey.</p>`;
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

const SCHEDULES = { local: [30, 35, 90, "Local test: commitments close in 30 s, check-in 35 s–90 s"], demo: [240, 300, 900, "Demo: commitments close in 4 min, check-in 5–15 min from now"],
  tonight: [9000, 10800, 18000, "Tonight: check-in opens in 3 h, for 2 h"], week: [600000, 604800, 615600, "Next week: check-in opens in 7 days, for 3 h"] };

async function create() {
  const acct = account();
  if (!acct) { view.innerHTML = `<h1>Host an event</h1><p class="sub">Create your passkey account first. It signs the terms you publish.</p>${signup()}`; bindSignup(); return; }
  view.innerHTML = `<h1>Host an event</h1><p class="sub">Terms are fixed once published: their sha256 goes onchain.</p>
    <div class="progress" aria-hidden="true"><i class="on"></i><i></i><i></i></div>
    <form class="card form" id="f" novalidate>
      <fieldset data-step="0"><legend>1 · The event</legend>
        <label>Title<input name="title" required maxlength="80" placeholder="Rooftop listening session"></label>
        <label>Host name<input name="creator" required maxlength="60" value="${esc(acct.name)}"></label>
        <label>Venue<input name="venue" required maxlength="200" placeholder="Studio 4, Bandra, Mumbai"></label>
        <label>Description <small>(optional)</small><textarea name="description" maxlength="2000" rows="3" placeholder="What people get by showing up"></textarea></label>
      </fieldset>
      <fieldset data-step="1" hidden><legend>2 · Commitment and rules</legend>
        <div class="two"><label>Commitment <small>(tUSD)</small><input name="amount" type="number" inputmode="numeric" min="1" max="1000" step="1" value="5" required></label>
        <label>Capacity<input name="capacity" type="number" inputmode="numeric" min="1" max="10000" value="20" required></label></div>
        <label>Schedule<select name="schedule">${Object.entries(SCHEDULES).filter(([k]) => k !== "local" || health.chainId === 104).map(([k, v]) => `<option value="${k}">${v[3]}</option>`).join("")}</select></label>
        <div class="two"><label>Category<select name="category">${CATEGORIES.map((c, i) => `<option value="${i}"${i === 3 ? " selected" : ""}>${c}</option>`).join("")}</select></label>
        <label>Late give-back fee<select name="lateFee"><option value="0">None</option><option value="1000">10%</option><option value="2000">20%</option><option value="5000">50%</option></select></label></div>
        <p class="hint">People can give their place back free until commitments close. After that, this share stays with you, unless someone on the waitlist takes the seat.</p>
        <label>If someone doesn't show<select name="noShow"><option value="1">Share it among people who checked in</option><option value="0">It goes to me, the host</option></select></label>
        <label>Dispute contact<input name="dispute" type="text" required maxlength="200" placeholder="you@example.com"></label>
      </fieldset>
      <fieldset data-step="2" hidden><legend>3 · Review</legend><div id="rv"></div></fieldset>
      <div class="btns"><button type="button" id="prev" hidden>Back</button><button type="button" class="accent" id="next">Continue</button></div>
    </form><div id="box"></div>`;
  const form = $("#f"), steps = [...form.querySelectorAll("fieldset")];
  let at = 0;
  const go = (i) => {
    at = i; steps.forEach((f, j) => f.hidden = j !== i);
    view.querySelectorAll(".progress i").forEach((p, j) => p.classList.toggle("on", j <= i));
    $("#prev").hidden = i === 0; $("#next").textContent = i === 2 ? "Publish with passkey" : "Continue";
    if (i === 2) {
      const f = Object.fromEntries(new FormData(form)), [, cs] = SCHEDULES[f.schedule];
      $("#rv").innerHTML = `<h2 class="wrap">${esc(f.title)}</h2><p class="sub wrap">${esc(f.creator)} · ${esc(f.venue)}</p><dl class="stack">
        <dt>Each person commits</dt><dd><b class="num">${tusd(Math.round(+f.amount * 1e6))} tUSD</b></dd><dt>Places</dt><dd class="num">${+f.capacity} (plus a waitlist of the same size)</dd>
        <dt>Category</dt><dd>${CATEGORIES[+f.category]}</dd>
        <dt>Giving a place back</dt><dd>${+f.lateFee ? `Free until commitments close, then ${pct(+f.lateFee)} goes to you unless the waitlist refills the seat` : "Free until check-in opens"}</dd>
        <dt>Check-in opens</dt><dd>${when(now() + cs)} (approx.)</dd>
        <dt>People who check in</dt><dd>Get their commitment back${f.noShow === "1" ? ", plus an equal share of no-shows'" : ""}, and a supporter passport</dd>
        <dt>People who don't</dt><dd>${f.noShow === "1" ? "Their commitment is shared among attendees" : "Their commitment goes to you"}</dd>
        <dt>Disputes</dt><dd class="wrap">${esc(f.dispute)}</dd></dl>
        <p class="hint">Publishing asks for your passkey. These terms can't be edited afterwards; you can cancel the event, which refunds everyone.</p>`;
    }
    steps[i].querySelector("input, select")?.focus({ preventScroll: true });
  };
  $("#prev").onclick = () => go(at - 1);
  $("#next").onclick = () => {
    if (at < 2) { const bad = [...steps[at].querySelectorAll("input, select, textarea")].find((el) => !el.checkValidity()); if (bad) { bad.reportValidity(); bad.focus(); return; } go(at + 1); return; }
    const f = Object.fromEntries(new FormData(form));
    const [cd, cs, ce] = SCHEDULES[f.schedule];
    let created;
    run($("#box"), "Event published", async (s) => {
      const t = await chainNow(), dl = t + 600; // the schedule is anchored to chain time, not this device's clock
      const terms = { amount: Math.round(+f.amount * 1e6), capacity: +f.capacity, commitDeadline: t + cd, checkInStart: t + cs, checkInEnd: t + ce, noShow: f.noShow === "1" ? "split" : "organizer", issuer: health.issuer };
      const policy = JSON.stringify({ version: 1, title: f.title.trim(), description: f.description.trim(), creator: f.creator.trim(), venue: f.venue.trim(), startsAt: t + cs + 300, timezone: Intl.DateTimeFormat().resolvedOptions().timeZone, artwork: Math.floor(Math.random() * 6), disputeContact: f.dispute.trim(), ...terms, category: +f.category, lateFeeBps: +f.lateFee });
      const t2 = { organizer: acct.keyId, policyHash: await policyHash(policy), issuer: health.issuer, amount: terms.amount, capacity: terms.capacity, commitDeadline: terms.commitDeadline, checkInStart: terms.checkInStart, checkInEnd: terms.checkInEnd, noShow: +f.noShow };
      const a = await api(`/accounts/${acct.keyId}`);
      const sig = await authorize(Messages.createV2(health.chainId, health.treasury, a.nonce, dl, t2, +f.category, +f.lateFee)); s();
      created = (await api("/events", { policy, config: t2, deadline: dl, ...sig, terms: { category: +f.category, lateFeeBps: +f.lateFee } })).tx; return created;
    }, async () => (await api(`/tx/${created}`)).eventId != null, async () => { const id = (await api(`/tx/${created}`)).eventId; setTimeout(() => location.hash = `#/events/${id}`, 1200); });
  };
}

// ---------------------------------------------------------------- AI agent

async function delegate() {
  const q = params(), acct = account();
  const agent = q.get("agent") || "", agentId = q.get("id"), cap = Math.min(1000, Math.max(1, +(q.get("cap") || 20))), days = Math.min(30, Math.max(1, +(q.get("days") || 7)));
  const info = /^0x([0-9a-fA-F]{40}|[0-9a-fA-F]{64})$/.test(agent) ? await api(`/agents/${agent}${agentId ? `?agentId=${agentId}` : ""}`) : null;
  const rep = info?.reputation;
  view.innerHTML = `<a class="back" href="#/">‹ Home</a><h1>Your agent is asking for a budget</h1><p class="sub">It can reserve places for you. Showing up is always you.</p>
    <section class="card"><dl><dt>Agent</dt><dd class="mono">${shortKey(agent)}</dd>
      ${C.svm ? "" : `<dt>ERC-8004 identity</dt><dd>${agentId ? `#${esc(agentId)} ${pill("registered", "verified")}` : "none"}</dd>`}
      <dt>Show-up reputation</dt><dd>${rep?.showUpRate != null ? `<b>${rep.showUpRate}%</b> of ${rep.count} reservations ended in a verified check-in` : "no settled reservations yet"}</dd></dl>
      <p class="muted">${C.svm ? "Reputation is written only by the ANZEN program's settle instruction into this agent's record account" : "Reputation counts only feedback written by the ANZEN treasury contract itself"}: every point is a real commitment and a verified check-in, so it can't be farmed by fake reviewers.</p></section>
    <section class="card"><dl><dt>Reserve places with your funds</dt><dd>Yes, within the cap and your rules</dd><dt>Check in for you</dt><dd class="no">Never</dd>
      <dt>Give back, cancel or withdraw</dt><dd class="no">Never</dd><dt>Raise its own budget</dt><dd class="no">Never</dd></dl></section>
    <form class="card form" id="rules"><h2>Agent Pass rules</h2><p class="muted">Enforced by ${C.the} on every booking, not by the agent.</p>
      <fieldset><legend>Categories it may book</legend><div class="checks">${CATEGORIES.map((c, i) => `<label><input type="checkbox" name="cat" value="${i}" checked> ${c}</label>`).join("")}</div></fieldset>
      <div class="two"><label>Late give-back fee<select name="fee"><option value="0">Free give-back only</option><option value="1000">Up to 10%</option><option value="2000" selected>Up to 20%</option><option value="10000">Any</option></select></label>
      <label>Active bookings<select name="active"><option value="1">1 at a time</option><option value="2" selected>2 at a time</option><option value="5">5 at a time</option><option value="0">No limit</option></select></label></div>
      <label>How far ahead<select name="lead"><option value="1">1 day</option><option value="7" selected>7 days</option><option value="30">30 days</option><option value="0">Any date</option></select></label></form>
    <div id="act"></div><div id="box"></div>`;
  if (!info) { $("#act").innerHTML = '<div class="note bad">This link has no valid agent address.</div>'; $("#rules").remove(); return; }
  if (!acct) { $("#act").innerHTML = signup(); bindSignup(); return; }
  const rules = () => { const f = new FormData($("#rules")), cats = f.getAll("cat").map(Number);
    return { categories: cats.length === CATEGORIES.length ? 0 : cats.reduce((m, i) => m | (1 << i), 0), maxLateFeeBps: +f.get("fee"), maxActive: +f.get("active"), maxLeadDays: +f.get("lead") }; };
  const b = document.createElement("button"); b.className = "accent"; b.textContent = "Review Agent Pass"; $("#act").appendChild(b);
  b.onclick = () => {
    const r = rules();
    if (!$("#rules").querySelector("input[name=cat]:checked")) { toast("Pick at least one category."); return; }
    $("#rules").hidden = true;
    $("#act").innerHTML = `<section class="card"><h2>Review your Agent Pass</h2><dl class="stack"><dt>Budget</dt><dd><b class="num">${cap} tUSD</b> for ${days} day${days > 1 ? "s" : ""}</dd>
      ${rulesText(r).map((t) => `<dt>Rule</dt><dd>${esc(t)}</dd>`).join("")}<dt>Never</dt><dd>Check in, give back, withdraw or raise its own budget</dd></dl>
      <p class="hint">One passkey signature binds the agent${C.svm ? "" : ", its ERC-8004 identity"}, the budget and every rule. You can replace or revoke it any time.</p></section>
      <div class="btns"><button id="psback">Back</button><button class="accent" id="psgo">Approve with passkey</button></div>`;
    $("#psback").onclick = render;
    $("#psgo").onclick = () => run($("#box"), "Agent Pass approved", async (s) => {
      const a = await api(`/accounts/${acct.keyId}`), cn = await chainNow(), dl = cn + 600, expiry = cn + days * 86400, id = agentId != null ? BigInt(agentId) : NO_AGENT_ID;
      const sig = await authorize(Messages.grantPass(health.chainId, health.treasury, agent, id, cap * 1e6, expiry, r, a.nonce, dl)); s();
      return (await api("/pass", { agent, agentId: id, cap: cap * 1e6, expiry, rules: r, deadline: dl, ...sig })).tx;
    }, async () => { const d = (await api(`/accounts/${acct.keyId}`)).delegation; return d.active && d.agent.toLowerCase() === agent.toLowerCase() && d.rules.maxActive === r.maxActive; },
    () => $("#act .btns")?.remove());
  };
}

async function agentView() {
  const acct = account(); if (!acct) { location.hash = "#/"; return; }
  const me = await api(`/accounts/${acct.keyId}`), d = me.delegation, rep = d.reputation;
  if (!d.active) { view.innerHTML = `<a class="back" href="#/agents">‹ Agents</a><h1>Your AI agent</h1>${empty("No active budget", "Connect an agent such as OpenClaw or Claude with the ANZEN skill or MCP server. It sends you an approval link; you choose the cap and expiry.")}`; return; }
  view.innerHTML = `<a class="back" href="#/">‹ Home</a><h1>Your AI agent</h1>
    <section class="card sealed"><span class="label">Budget left</span><b class="amount">${tusd(d.remaining)} <small>tUSD</small></b><p>of ${tusd(d.cap)} · until ${when(d.expiry)}</p>
      ${rep ? `<p>${d.agentId != null ? `ERC-8004 #${d.agentId}` : `Agent ${shortKey(d.agent)}`} · show-up ${rep.showUpRate != null ? rep.showUpRate + "%" : "—"} over ${rep.count}</p>` : ""}</section>
    <h3>Agent Pass rules</h3><section class="card"><ul class="rules">${rulesText(d.rules).map((t) => `<li>${esc(t)}</li>`).join("")}<li>${d.activeBookings} active booking${d.activeBookings === 1 ? "" : "s"} now</li></ul>
      <p class="hint">Checked by ${C.the} on every booking. Only your passkey can change them.</p></section>
    <h3>Booked by your agent</h3>${me.commitments.filter((e) => e.me.agentId != null).map(card).join("") || empty("Nothing booked yet", "Ask your agent to find something. It can reserve within this budget; checking in is always you.")}`;
}

// ---------------------------------------------------------------- proof & account

async function agentsBoard() {
  const rows = await api("/agents");
  const acct = account();
  view.innerHTML = `<h1>Agent show-up board</h1>
    ${acct ? `<a class="card row" href="#/agent"><div><b>Your AI agent</b><span>Budget, identity and what it booked for you</span></div><span class="chev">›</span></a>` : ""}
    <p class="muted">${C.svm ? "Every AI agent an owner has given an Agent Pass, with the show-up record the ANZEN program keeps for it. Only the program's settle instruction writes to an agent's record account, one point per settled booking: a check-in or a miss. Fake reviewers can't add to it."
      : "Every AI agent an owner has given a budget, with the reputation the ANZEN treasury wrote for it in Monad's official ERC-8004 registry. One point per settled reservation: 100 if the human checked in, 0 if not. Fake reviewers can't add to it."}</p>
    ${rows.length ? rows.map((a) => `<div class="card row"><div><b>${a.agentId != null ? `ERC-8004 #${a.agentId}` : `Agent ${esc(shortKey(a.agent))}`}</b><span class="mono">${esc(a.agentId != null ? shortKey(a.agent) : a.agent.slice(0, 10) + "…")}</span></div>
      <div class="right"><b>${a.count ? `${a.showUpRate}%` : "—"}</b><span>${a.count ? `${a.count} settled` : "no settled reservations yet"}</span></div></div>`).join("")
      : empty("No agents yet", "Run <code>anzen-agent init</code> or add the MCP server, then approve its budget.")}
    <p class="muted">${C.svm ? `Read it yourself: fetch the agent's record account of program <code>${esc(shortKey(health.programId))}</code>.` : 'Read it yourself: <code>getSummary(agentId, [treasury], "showUp", "anzen")</code> on the reputation registry.'}</p>
    <h3>For agent builders</h3><section class="card"><p class="muted">Any HTTP agent can book over x402: <code>GET /x402/events/{id}/reserve</code> answers <b>402 Payment Required</b> with what to sign. Retry with an <code>X-PAYMENT</code> header and the commitment is paid from the owner's Agent Pass, inside its rules. The OpenClaw skill and the MCP server use the same endpoint.</p></section>`;
}

async function proof(eventId, keyId) {
  const p = await api(`/proof/${eventId}/${keyId}`), e = p.event, m = p.participation;
  const yes = (ok) => ok ? pill("Yes", "verified") : pill("No");
  view.innerHTML = `<a class="back" href="#/events/${eventId}">‹ ${esc(e.title)}</a><h1>Proof</h1><p class="sub">Everything here is read from ${C.the} on ${esc(health.network)}.</p>
    <section class="card"><dl><dt>Commitment recorded</dt><dd>${yes(m.status !== "none")}</dd><dt>Reserved by an AI agent</dt><dd>${m.agentId != null ? `ERC-8004 #${m.agentId}` : m.byAgent ? "Yes, inside your Agent Pass" : "No, by the owner"}</dd>
      <dt>Check-in signed with a fresh venue code</dt><dd>${yes(m.attended)}</dd><dt>Outcome applied</dt><dd>${yes(m.status === "settled")}</dd>
      <dt>Supporter passport</dt><dd>${m.passportId ? `No. ${m.passportId}` : "—"}</dd><dt>Terms match onchain hash</dt><dd>${yes(e.policyVerified)}</dd></dl></section>
    <section class="card"><dl><dt>${C.label}</dt><dd class="mono">${shortKey(C.svm ? health.programId : p.treasury)}</dd><dt>Participant key</dt><dd class="mono">${short(p.keyId)}</dd>
      ${p.transactions.map((t) => `<dt>${esc(t.action)}</dt><dd>${txLink(t.tx)}</dd>`).join("")}</dl></section>
    <h3>What this doesn't prove</h3>${p.limitations.map((l) => `<div class="note">${esc(l)}</div>`).join("")}`;
}

const ACTIONS = { faucet: "Test dollars received", create: "Event published", commit: "Commitment", agent_commit: "Reserved by your agent",
  checkin: "Checked in", settle: "Outcome applied", delegate: "Agent Pass changed", release: "Place given back", finalize: "Check-in closed", cancel: "Event cancelled" };
const txPill = (st) => st === "confirmed" ? pill("Confirmed", "verified") : st === "failed" ? pill("Failed", "failed") : pill("Pending", "pending");

async function accountView() {
  const acct = account();
  if (!acct) { view.innerHTML = `<h1>Account</h1><p class="sub">Your account is a passkey on this device. Nothing to remember, nothing to lose in a screenshot.</p>${signup()}`; bindSignup(); return; }
  const [me, act] = await Promise.all([api(`/accounts/${acct.keyId}`), api(`/accounts/${acct.keyId}/activity`)]);
  const r = me.record, perk = me.score >= 90 ? ["90+", "Venues can waive deposits entirely and give you priority on waitlists."]
    : me.score >= 70 ? ["70–89", "Venues can offer you reduced deposits."] : ["Under 70", "Standard deposits. Every check-in moves it back up."];
  view.innerHTML = `<section class="greet">${panda("focused")}<div><h1>${esc(acct.name)}</h1><p>${tusd(me.balance)} tUSD available</p></div></section>
    <h3>Show-Up Score</h3><section class="card sealed score" aria-label="Show-Up Score ${me.score} out of 100">
      <span class="label">Show-Up Score</span><b class="amount num">${me.score}<small> / 100</small></b>
      <p>${me.scoreBasis ? `From ${r.attended} verified check-in${r.attended === 1 ? "" : "s"} and ${r.missed} miss${r.missed === 1 ? "" : "es"}, recorded by ${C.the}.` : "No history yet. Everyone starts at 75, and it moves toward your real show-up rate with every event."}</p>
      <div class="kpis"><div><b class="num">🔥 ${r.streak}</b><span>streak</span></div><div><b class="num">${r.bestStreak}</b><span>best</span></div><div><b class="num">🛡 ${r.shields}</b><span>shields</span></div><div><b class="num">${r.releasedInTime}</b><span>given back in time</span></div></div>
      <p><b>${perk[0]}:</b> ${perk[1]} <span class="hint-sealed">Perks are what venues on ANZEN can offer; the score itself is computed onchain from your passkey's history, so no agent or app can inflate it.</span></p></section>
    ${me.organized?.length ? `<a class="card row" href="#/venue"><div><b>Venue dashboard</b><span>Seats filled, refilled from the waitlist and money recovered</span></div><span class="chev">›</span></a>` : ""}
    <h3>Passkey and security</h3><section class="card"><dl class="stack"><dt>Sign-in</dt><dd>WebAuthn passkey (P-256), user verification required</dd>
      <dt>Account key</dt><dd class="mono">${short(acct.keyId)}</dd><dt>Recovery</dt><dd>Through your passkey provider's sync (iCloud Keychain, Google Password Manager)</dd></dl>
      <p class="hint">ANZEN never sees your private key. Each signature covers one exact action and can't be reused.</p></section>
    <h3>Network</h3><section class="card"><dl class="stack"><dt>Network</dt><dd>${esc(health.network)} · chain ${health.chainId}</dd><dt>Asset</dt><dd>tUSD, a test token with no value</dd>
      <dt>${C.label}</dt><dd class="mono">${shortKey(C.addr)}</dd><dt>Network fees</dt><dd>Paid by the relayer, which can delay but never forge an action</dd></dl></section>
    ${me.delegation?.active ? `<h3>Agent Pass</h3><a class="card row" href="#/agent"><div><b>Your AI agent</b><span>${tusd(me.delegation.remaining)} tUSD budget left · ${esc(rulesText(me.delegation.rules).slice(0, 2).join(" · "))}</span></div><span class="chev">›</span></a>` : ""}
    <h3>Activity</h3>${act.map((a) => `<div class="card row"><div><b>${esc(ACTIONS[a.action] || a.action)}</b><span>${esc(a.eventTitle ? a.eventTitle + " · " : "")}${when(a.at)}</span><span>${txLink(a.tx)}</span></div>${txPill(a.status)}</div>`).join("")
      || empty("No activity yet", "Commitments, check-ins and outcomes will appear here, each with its transaction.")}
    <h3>About</h3><section class="card"><p class="muted">ANZEN holds refundable commitments in ${C.svm ? "a Solana program" : "a smart contract"} on ${esc(health.network)}. Outcomes follow each event's published rules; nothing here is an investment or a guaranteed return. A check-in shows your passkey signed a fresh venue code, not your exact location.</p>
      <p class="muted"><a href="https://github.com/Aaditya1273/LOOP" target="_blank" rel="noopener">Source and documentation ↗</a></p></section>
    <button class="quiet danger" id="forget">Forget this passkey on this browser</button>`;
  $("#forget").onclick = () => { if (confirm("Forget this account on this browser? The passkey stays on your device, but this page won't know which one is yours.")) { forget(); location.hash = "#/"; } };
}

// ---------------------------------------------------------------- venue dashboard

async function venue() {
  const acct = account();
  if (!acct) { view.innerHTML = `<h1>Venue dashboard</h1><p class="sub">Create your passkey account to host events.</p>${signup()}`; bindSignup(); return; }
  const d = await api(`/organizers/${acct.keyId}/dashboard`), t = d.totals;
  const rate = (x) => x == null ? "—" : `${Math.round(x * 100)}%`;
  view.innerHTML = `<a class="back" href="#/account">‹ Account</a><h1>Venue dashboard</h1><p class="sub">Read from ${C.the} for every event your passkey published.</p>
    ${t.events ? `<section class="card sealed"><span class="label">Money recovered</span><b class="amount num">${tusd(t.recovered)} <small>tUSD</small></b><p>Late give-back fees and no-show commitments that reached you under your published rules.</p></section>
    <div class="stats grid3"><div><b>${t.committed}<small>/${t.capacity}</small></b><span>seats filled</span></div><div><b>${t.refilled}</b><span>refilled from waitlists</span></div><div><b>${t.released}</b><span>given back</span></div>
      <div><b>${t.attended}</b><span>verified check-ins</span></div><div><b>${rate(t.showRate)}</b><span>show rate (closed events)</span></div><div><b>${t.events}</b><span>events</span></div></div>
    <h3>Events</h3>${d.events.map((e) => `<a class="card row" href="#/events/${e.id}"><div><b>${esc(e.title)}</b><span>${e.committed}/${e.capacity} seats · ${e.attended} checked in · ${e.refilled} refilled${e.waitlisted ? ` · ${e.waitlisted} waiting` : ""}</span></div>
      <div class="right"><b>${tusd(e.recovered)}</b><span>${rate(e.showRate)}</span></div></a>`).join("")}`
    : empty("You're not hosting yet", "Publish an event and this page shows seats filled, refills from the waitlist and money recovered.", '<button class="primary" onclick="location.hash=\'#/create\'">Host an event</button>')}`;
}

// ---------------------------------------------------------------- boot

(async () => {
  try { health = await api("/health"); setChain(); if (C.svm) $("#foot").textContent = "ANZEN · refundable commitments, passkeys and show-up reputation on Solana"; $("#net").innerHTML = pill(`${health.network} · test dollars`, "info"); }
  catch (e) { view.innerHTML = `<div class="note bad">${esc(errText(e))}</div>`; return; }
  addEventListener("hashchange", render); render();
})();
