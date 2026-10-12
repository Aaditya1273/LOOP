// Browser end to end with a REAL WebAuthn ceremony (Chromium's virtual platform authenticator: P-256, user
// verification), against the local devnet (scripts/dev_stack.sh, which serves this web app on :8790).
//   cd web && npm install && node passkey.e2e.mjs        (SHOTS=/some/dir also saves screenshots, light and dark)
// v1: publish → commit → organizer desk → the QR is decoded from its pixels (as a phone camera would) → check-in →
// outcome → passport → proof. v4: published terms, Agent Pass rules, waitlist + give-back refill, staff-scanned
// (silver) check-in with a second passkey, Show-Up Score, venue dashboard. Three browser contexts = three passkeys.
// Chain time: on the EVM devnet it's fast-forwarded with scripts/warp.sh; on local Solana (chain 104, no warp) the
// events use the 30 s/35–90 s "Local test" schedule and the run waits on the chain clock (/health.now).
import { chromium } from "playwright";
import { execFileSync } from "node:child_process";
import assert from "node:assert/strict";

const BASE = process.env.ANZEN_WEB || "http://localhost:8790"; // WebAuthn needs a domain name, not an IP
const SHOTS = process.env.SHOTS;
const warp = (s) => execFileSync(new URL("../scripts/warp.sh", import.meta.url).pathname, [String(s)], { stdio: "pipe" });
const step = (s) => console.log("✓", s);
const browser = await chromium.launch();

async function shot(page, name) {
  if (!SHOTS) return;
  for (const scheme of ["light", "dark"]) {
    await page.emulateMedia({ colorScheme: scheme, reducedMotion: "reduce" });
    await page.screenshot({ path: `${SHOTS}/${name}-${scheme}.png`, fullPage: true });
  }
  await page.emulateMedia({ colorScheme: "light", reducedMotion: "no-preference" });
}

/** A browser with its own passkey (virtual authenticator) and a funded ANZEN account. */
async function user(name) {
  const ctx = await browser.newContext({ viewport: { width: 430, height: 900 } });
  const page = await ctx.newPage();
  page.on("pageerror", (e) => console.error(`[${name}] page error:`, e.message));
  page.on("console", (m) => ["error", "warning"].includes(m.type()) && console.error(`[${name}] console ${m.type()}:`, m.text()));
  const cdp = await ctx.newCDPSession(page);
  await cdp.send("WebAuthn.enable");
  await cdp.send("WebAuthn.addVirtualAuthenticator", { options: {
    protocol: "ctap2", transport: "internal", hasResidentKey: true, hasUserVerification: true,
    isUserVerified: true, automaticPresenceSimulation: true } });
  await page.goto(`${BASE}/#/`);
  await page.fill("#name", name);
  await page.click("#mk");
  await page.click("#faucet");
  await until(page, /100 test dollars added|Not completed/, "#fbox");
  assert.match(await page.locator("#fbox").innerText(), /100 test dollars added/);
  page.keyId = await page.evaluate(() => JSON.parse(localStorage.getItem("anzen.passkey.v1")).keyId);
  return page;
}

async function until(page, re, sel = "body", timeout = 120000) {
  try {
    await page.waitForFunction(([r, s]) => new RegExp(r).test(document.querySelector(s)?.innerText || ""), [re.source, sel], { timeout });
  } catch (e) {
    console.error(`waiting for ${re} in ${sel} — it had: ${JSON.stringify(await page.evaluate((s) => document.querySelector(s)?.outerHTML?.slice(0, 600), sel))}\nscreen:\n${(await page.locator("main").innerText()).slice(0, 800)}`);
    throw e;
  }
}

const api = async (path) => (await fetch(BASE + path)).json();
const HEALTH = await api("/health");
const SOLANA = HEALTH.vm === "svm", SCHEDULE = HEALTH.chainId === 104 ? "local" : "demo";
/** Moves the chain to `ts`: a warp on the EVM devnet, a wait on Solana. */
async function advanceTo(ts) {
  const now = (await api("/health")).now;
  if (now >= ts) return;
  if (SCHEDULE !== "local") return warp(ts - now);
  while ((await api("/health")).now < ts) await new Promise((r) => setTimeout(r, 1000));
}
const click = (page, text, scope = "#act") => page.locator(`${scope} button`, { hasText: text }).click();

/** Decodes the QR on screen from its pixels, as a phone camera would. */
const decodeQR = (page) => page.evaluate(async () => {
  const xml = new XMLSerializer().serializeToString(document.querySelector(".qr svg"));
  const img = new Image(); img.src = "data:image/svg+xml;base64," + btoa(xml); await img.decode();
  const c = document.createElement("canvas"); c.width = c.height = 900;
  const ctx = c.getContext("2d"); ctx.fillStyle = "#fff"; ctx.fillRect(0, 0, 900, 900); ctx.drawImage(img, 20, 20, 860, 860);
  return jsQR(ctx.getImageData(0, 0, 900, 900).data, 900, 900)?.data;
});

async function publish(page, { title, capacity, schedule = SCHEDULE, category, lateFee }) {
  await page.goto(`${BASE}/#/create`);
  await page.click("#next"); // step 1 refuses to continue without a title
  assert.equal(await page.locator("fieldset[data-step='0']").isVisible(), true, "validation keeps step 1 open");
  await page.fill("input[name=title]", title);
  await page.fill("input[name=venue]", "Headless Chromium");
  await page.click("#next");
  if (capacity) await page.fill("input[name=capacity]", String(capacity));
  await page.selectOption("select[name=schedule]", schedule);
  if (category != null) await page.selectOption("select[name=category]", String(category));
  if (lateFee != null) await page.selectOption("select[name=lateFee]", String(lateFee));
  await page.fill("input[name=dispute]", "e2e@example.com");
  await page.click("#next");
  const rv = await page.locator("#rv").innerText();
  assert.match(rv, /People who don't/);
  await page.click("#next"); // Publish with passkey
  await page.waitForURL(/#\/events\/\d+/, { timeout: 120000 });
  await until(page, new RegExp(title.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")), "main h1");
  return { id: +page.url().match(/events\/(\d+)/)[1], review: rv };
}

// ---------------------------------------------------------------- accounts (three passkeys) and an Agent Pass

console.log(`against ${HEALTH.network} (chain ${HEALTH.chainId}, ${HEALTH.vm || "evm"}), schedule ${SCHEDULE}`);
const judge = await user("E2E judge"), bea = await user("Guest Bea"), cal = await user("Guest Cal");
step("three passkey accounts created, test dollars verified onchain");

const AGENT = SOLANA ? "0x" + "ab".repeat(32) : "0x000000000000000000000000000000000000dEaD"; // Solana agents are ed25519 keys
await judge.goto(`${BASE}/#/delegate?agent=${AGENT}&cap=20&days=7`);
await judge.locator("input[name=cat][value='2']").uncheck(); // no dining
await judge.selectOption("select[name=active]", "1");
await click(judge, "Review Agent Pass");
const passReview = await judge.locator("#act").innerText();
assert.match(passReview, /Only Other, Fitness, Meetups, Health, Learning/);
assert.match(passReview, /At most 1 active booking/);
await click(judge, "Approve with passkey");
await until(judge, /Agent Pass approved|Not completed/, "#box");
assert.match(await judge.locator("#box").innerText(), /Agent Pass approved/);
const pass = (await api(`/accounts/${judge.keyId}`)).delegation;
assert.equal(pass.agent.toLowerCase(), AGENT.toLowerCase());
assert.deepEqual(pass.rules, { categories: 0b111011, maxLateFeeBps: 2000, maxActive: 1, maxLeadDays: 7 });
step("Agent Pass approved: categories, fee ceiling, active bookings and lead time in one passkey signature");

// ---------------------------------------------------------------- publish and commit

const { id: eventId } = await publish(judge, { title: "Browser E2E: passkey check-in" });
step(`event #${eventId} published from the 3-step flow (terms hash signed with the passkey)`);
assert.match(await judge.locator("#act").innerText(), /You're the host/); // hosts don't commit to their own event
await bea.goto(`${BASE}/#/events/${eventId}`);
await click(bea, "Review commitment");
assert.match(await bea.locator("#act").innerText(), /If you don't/);
await shot(bea, "review-commit");
await click(bea, "with passkey");
await until(bea, /Commitment recorded|Not completed/, "#box");
assert.match(await bea.locator("#box").innerText(), /Commitment recorded/);
step("reviewed the rules, then committed 5 tUSD with a WebAuthn assertion");

const wl = await publish(judge, { title: "Waitlist E2E: spin class", capacity: 1, category: 1, lateFee: 2000 });
assert.match(wl.review, /Fitness/);
assert.match(wl.review, /20% goes to you/);
const e2 = await api(`/events/${wl.id}`);
assert.deepEqual([e2.category, e2.lateFeeBps, e2.policyVerified], [1, 2000, true]);
step(`event #${wl.id} published with terms (Fitness, 20% late give-back fee)`);

// ---------------------------------------------------------------- waitlist and give-back refill

await bea.goto(`${BASE}/#/events/${wl.id}`);
await click(bea, "Review commitment");
await click(bea, "with passkey");
await until(bea, /Commitment recorded|Not completed/, "#box");
assert.match(await bea.locator("#box").innerText(), /Commitment recorded/);

await cal.goto(`${BASE}/#/events/${wl.id}`);
await click(cal, "join the waitlist");
assert.match(await cal.locator("#act").innerText(), /If none does/);
await shot(cal, "review-waitlist");
await click(cal, "with passkey");
await until(cal, /You're on the waitlist|Not completed/, "#box");
assert.match(await cal.locator("#box").innerText(), /You're on the waitlist/);
step("event full: the second guest's commitment is held on the waitlist");

await bea.goto(`${BASE}/#/events/${wl.id}`);
await bea.reload();
await click(bea, "Give my place back");
const rel = await bea.locator("#act").innerText();
assert.match(rel, /Goes straight to the first person on the waitlist/);
assert.match(rel, /Streak shield\s*Yes/);
await shot(bea, "review-give-back");
await click(bea, "with passkey");
await until(bea, /Place given back|Not completed/, "#box");
assert.match(await bea.locator("#box").innerText(), /Place given back/);
const after = await api(`/events/${wl.id}?key=${cal.keyId}`);
assert.deepEqual([after.me.status, after.refilled, after.waitlisted], ["committed", 1, 0]);
await cal.goto(`${BASE}/#/events/${wl.id}`);
await cal.reload();
await until(cal, /You're in/, "#act");
await bea.goto(`${BASE}/#/account`);
await until(bea, /🛡 1/, ".score");
step("first guest gave the place back in time: full refund, a streak shield, and the waitlist got the seat in the same transaction");

// ---------------------------------------------------------------- venue code check-in (bronze)

const e1 = await api(`/events/${eventId}`);
await advanceTo(e1.checkInStart + 1);
await judge.goto(`${BASE}/#/desk/${eventId}`);
await judge.click("#act button");
await judge.waitForSelector(".qr svg", { timeout: 60000 });
const link = await decodeQR(judge);
assert.ok(link?.includes("#/checkin?e="), "QR decodes to a check-in link");
step("desk QR decoded from pixels");

await bea.goto(link);
await bea.locator("#act button").click();
await until(bea, /You're checked in|Not completed/, "#box");
assert.match(await bea.locator("#box").innerText(), /You're checked in/);
step("checked in with the passkey and the fresh venue code");

// ---------------------------------------------------------------- staff-scanned check-in (silver)

await advanceTo(e2.checkInStart + 1);
await cal.goto(`${BASE}/#/events/${wl.id}`);
await cal.reload();
await click(cal, "Show my code to staff");
await cal.click("#cdnew");
await cal.waitForSelector(".qr svg", { timeout: 60000 });
const present = await decodeQR(cal);
assert.ok(present?.startsWith(`anzen://present?e=${wl.id}&`), `guest QR decodes to a present code: ${present?.slice(0, 40)}`);
await shot(cal, "guest-code");
step("guest's own 45-second code signed with their passkey, decoded from pixels");

await judge.goto(`${BASE}/#/desk/${wl.id}`);
await judge.locator("summary").click();
await judge.fill("#guestcode", present);
await judge.click("#admit");
await until(judge, /checked in|Not completed/, "#guests");
assert.match(await judge.locator("#guests").innerText(), /Guest .* checked in/);
await until(cal, /Staff checked you in/, "#act", 30000);
assert.equal((await api(`/events/${wl.id}?key=${cal.keyId}`)).me.tier, 2);
await shot(judge, "desk-scan-guests");
step("organizer countersigned the guest's code: silver-tier check-in, and the guest's screen saw it");

// ---------------------------------------------------------------- outcomes, proof, score, dashboard

await advanceTo(e1.checkInEnd + 2);
await bea.goto(`${BASE}/#/events/${eventId}`);
await bea.reload();
await click(bea, "Apply outcome");
await until(bea, /SUPPORTER PASSPORT|Not completed/, "main");
assert.match(await bea.locator("main").innerText(), /issued onchain/);
step("outcome applied, supporter passport issued");

await click(bea, "View proof");
await until(bea, /Verified onchain|Commitment recorded/, "main");
assert.match(await bea.locator("main").innerText(), /Check-in signed with a fresh venue code\s*Yes/);
await shot(bea, "proof");
step("proof shows every check passed");

await advanceTo(e2.checkInEnd + 2);
await cal.goto(`${BASE}/#/events/${wl.id}`);
await cal.reload();
await click(cal, "Apply outcome");
await until(cal, /SUPPORTER PASSPORT|Not completed/, "main");
await cal.goto(`${BASE}/#/account`);
await until(cal, /\b80\b/, ".score .amount");
assert.match(await cal.locator(".score").innerText(), /🔥 1/);
await shot(cal, "account-score");
step("Show-Up Score 80 with a 1-event streak, read from the onchain record");

await judge.goto(`${BASE}/#/venue`);
await until(judge, /Money recovered/, "main");
const dash = await judge.locator("main").innerText();
assert.match(dash, /Waitlist E2E: spin class/);
assert.match(dash, /refilled from waitlists/);
await shot(judge, "venue-dashboard");
await judge.goto(`${BASE}/#/`);
await until(judge, /Hi, E2E/, "main");
await shot(judge, "home");
await judge.goto(`${BASE}/#/agents`);
await until(judge, /Agent show-up board/, "main");
await shot(judge, "agents");
step("venue dashboard shows seats, refills and money recovered");

await browser.close();
console.log("BROWSER PASSKEY E2E PASSED");
