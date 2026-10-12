// node web/vectors.test.mjs — the web client's preimages must equal the contract's (same golden vectors as
// backend/tests/test_messages.py and app/src/test/.../MessagesTest.kt).
import assert from "node:assert/strict";
import { Messages, sha256, hex, keyIdOf } from "./anzen.js";

const T = "0x5FbDB2315678afecb367f032d93F642f64180aa3", ISSUER = "0x70997970C51812dc3A010C7d01b50e0d17dc79C8";
const qx = hex(Uint8Array.from({ length: 32 }, (_, i) => i + 1)), qy = hex(Uint8Array.from({ length: 32 }, (_, i) => i + 33));
const h = async (b) => hex(await sha256(b)).slice(2);
const org = await keyIdOf(qx, qy);
assert.equal(org, "0x20a7ec84684f7fe124cb3727d049734ab0b7da2f52fcafbcef989ecfd91e870b");
const terms = { organizer: org, policyHash: hex(await sha256(new TextEncoder().encode("policy"))), issuer: ISSUER, amount: 10_000_000,
  capacity: 20, commitDeadline: 1_800_086_400, checkInStart: 1_800_172_800, checkInEnd: 1_800_183_600, noShow: 1 };
assert.equal(await h(Messages.create(10143, T, 3, 1_800_000_600, terms)), "18c0fcdd83456a3a8cbc13e0d31c678f844edfb511bcf20d15541c85ee05e3e4");
assert.equal(await h(Messages.commit(10143, T, 7, 10_000_000, 4, 1_800_000_600)), "d7a0febc16a64dd5461c4d7bdb9804bde3796ce86bcec11ad8780a54c03c27c5");
assert.equal(await h(Messages.checkIn(10143, T, 7, hex(new Uint8Array(32).fill(0xab)), 1_800_172_845)), "3ec77fc75c2c93950e193badf20080c19bee8f33706087c4df501058cd5707e1");
assert.equal(await h(Messages.cancel(10143, T, 7, 5, 1_800_000_600)), "4d1419ebdaf75c90bdf278e1a891c8dea667214e9df00fb351211aa760ac5224");
assert.equal(await h(Messages.desk(10143, T, 7, 1_800_000_300)), "ae130c5e88daa4d2c6aac044edeb9e72ece35b4a6cfec8bf8511d82a266430ab");
assert.equal(await h(Messages.delegate(10143, T, ISSUER, 7, 20_000_000, 1_800_604_800, 6, 1_800_000_600)), "1b93458a85a0df0e491a02d9b363f53722a038ff5d9b02ba1d1e4ce081e98049");
// v4
assert.equal(await h(Messages.createV2(10143, T, 3, 1_800_000_600, terms, 1, 2000)), "532166810d76b6c89fc99c75c68a1f1d615f0e8c359d54ad0da98e9dabd68d5e");
assert.equal(await h(Messages.grantPass(10143, T, ISSUER, 7, 20_000_000, 1_800_604_800, { categories: 6, maxLateFeeBps: 1000, maxActive: 2, maxLeadDays: 7 }, 6, 1_800_000_600)), "b64e4b0a1e07c8e3dd202df1fa5e6aaae09556ce69195e1440e34025877cd888");
assert.equal(await h(Messages.release(10143, T, 7, 5, 1_800_000_600)), "cd7cb7cae83abcf6e043551f107b92354780c8a7f166d981884f8072f4df3bd6");
assert.equal(await h(Messages.present(10143, T, 7, 1_800_172_845)), "437f254f2caaf77565fb8b1d3ac2ff942a6b1e0f50518c955cc564b966b9d590");
assert.equal(await h(Messages.staff(10143, T, 7, org, 1_800_172_845)), "a9f55ee9ff83aacad83b8e254b20fa2bacdbe1be9a2475139db4b2aa44c2632e");
// Solana: a 32-byte key in an address slot is encoded as its first 20 bytes (same preimages on both chains).
const sol = ISSUER + "00112233445566778899aabb";
assert.deepEqual(Messages.delegate(103, T, sol, 7, 1, 2, 3, 4), Messages.delegate(103, T, ISSUER, 7, 1, 2, 3, 4));
console.log("web vectors: 12/12 match the contract; 32-byte keys use their first 20 bytes");

import { abiWebAuthn, uint } from "./anzen.js";
const ad = new Uint8Array([...await sha256(new TextEncoder().encode("anzen.app")), 5, 0, 0, 0, 1]);
const cd = new TextEncoder().encode('{"type":"webauthn.get","challenge":"abc","origin":"https://anzen.app"}');
assert.equal(await h(abiWebAuthn(new Uint8Array(32).fill(0x11), new Uint8Array(32).fill(0x22), 23, 1, ad, cd)), "bade41bb3e0d695c5d65754819770dbc87cc39505a8d5f07f3545ac23ced3647");
console.log("web WebAuthn encoding matches abi.encode(WebAuthnAuth)");
