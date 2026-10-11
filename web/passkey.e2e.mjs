// Browser end to end with a REAL WebAuthn ceremony (Chromium's virtual platform authenticator: P-256, user
// verification), against the local devnet (scripts/dev_stack.sh, which serves this web app on :8790).
//   cd web && npm install && node passkey.e2e.mjs
// Publish → commit → organizer desk → the QR is decoded from its pixels (as a phone camera would) → check-in →
// outcome → passport → proof. Chain time is fast-forwarded with scripts/warp.sh between phases.
import { chromium } from "playwright";
import { execFileSync } from "node:child_process";
import assert from "node:assert/strict";

const BASE = process.env.ANZEN_WEB || "http://localhost:8790"; // WebAuthn needs a domain name, not an IP
const warp = (s) => execFileSync(new URL("../scripts/warp.sh", import.meta.url).pathname, [String(s)], { stdio: "pipe" });
const step = (s) => console.log("✓", s);

const browser = await chromium.launch();
const page = await (await browser.newContext({ viewport: { width: 430, height: 900 } })).newPage();
const cdp = await page.context().newCDPSession(page);
await cdp.send("WebAuthn.enable");
await cdp.send("WebAuthn.addVirtualAuthenticator", { options: {
  protocol: "ctap2", transport: "internal", hasResidentKey: true, hasUserVerification: true,
  isUserVerified: true, automaticPresenceSimulation: true } });
const until = async (re, sel = "body", timeout = 120000) => {
  try {
    await page.waitForFunction(([r, s]) => new RegExp(r).test(document.querySelector(s)?.innerText || ""), [re.source, sel], { timeout });
  } catch (e) {
    console.error(`waiting for ${re} — screen was:\n${(await page.locator("main").innerText()).slice(0, 800)}`);
    throw e;
  }
};

await page.goto(`${BASE}/#/`);
await page.fill("#name", "E2E judge");
await page.click("#mk");
await page.click("#faucet");
await until(/100 test dollars added|Not completed/, "#fbox");
assert.match(await page.locator("#fbox").innerText(), /100 test dollars added/);
step("passkey account created, test dollars verified onchain");

await page.goto(`${BASE}/#/create`);
await page.fill("input[name=title]", "Browser E2E: passkey check-in");
await page.fill("input[name=venue]", "Headless Chromium");
await page.fill("input[name=dispute]", "e2e@example.com");
await page.click("#f button");
await page.waitForURL(/#\/events\/\d+/, { timeout: 120000 });
const eventId = page.url().match(/events\/(\d+)/)[1];
step(`event #${eventId} published (terms hash signed with the passkey)`);

await page.locator("#act button", { hasText: "Commit" }).click();
await until(/Commitment recorded|Not completed/, "#box");
assert.match(await page.locator("#box").innerText(), /Commitment recorded/);
step("committed 5 tUSD with a WebAuthn assertion");

warp(310);
await page.goto(`${BASE}/#/desk/${eventId}`);
await page.click("#act button");
await page.waitForSelector(".qr svg", { timeout: 60000 });
const link = await page.evaluate(async () => {
  const xml = new XMLSerializer().serializeToString(document.querySelector(".qr svg"));
  const img = new Image(); img.src = "data:image/svg+xml;base64," + btoa(xml); await img.decode();
  const c = document.createElement("canvas"); c.width = c.height = 600;
  const ctx = c.getContext("2d"); ctx.fillStyle = "#fff"; ctx.fillRect(0, 0, 600, 600); ctx.drawImage(img, 20, 20, 560, 560);
  return jsQR(ctx.getImageData(0, 0, 600, 600).data, 600, 600)?.data;
});
assert.ok(link?.includes("#/checkin?e="), "QR decodes to a check-in link");
step("desk QR decoded from pixels");

await page.goto(link);
await page.locator("#act button").click();
await until(/You're checked in|Not completed/, "#box");
assert.match(await page.locator("#box").innerText(), /You're checked in/);
step("checked in with the passkey and the fresh venue code");

warp(650);
await page.goto(`${BASE}/#/events/${eventId}`);
await page.reload();
await page.locator("#act button", { hasText: "Apply outcome" }).click();
await until(/SUPPORTER PASSPORT|Not completed/, "main");
assert.match(await page.locator("main").innerText(), /issued onchain/);
step("outcome applied, supporter passport issued");

await page.locator("#act button", { hasText: "View proof" }).click();
await until(/Verified onchain|Commitment recorded/, "main");
assert.match(await page.locator("main").innerText(), /Check-in signed with a fresh venue code\s*Yes/);
step("proof shows every check passed");
await browser.close();
console.log("BROWSER PASSKEY E2E PASSED");
