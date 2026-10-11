"""SQLite: published policy documents and the record of what the relayer submitted.
Onchain state is never cached here; the contract is the source of truth for money and status."""

import sqlite3
from pathlib import Path

SCHEMA = """
CREATE TABLE IF NOT EXISTS events (
  id INTEGER PRIMARY KEY,            -- onchain event id
  policy TEXT NOT NULL,              -- the exact published document; sha256(policy) = onchain policyHash
  organizer TEXT NOT NULL,           -- keyId hex
  create_tx TEXT NOT NULL,
  created_at REAL NOT NULL
);
-- One row per relayed transaction. idem makes a resubmitted signature return the same tx.
CREATE TABLE IF NOT EXISTS relays (
  tx TEXT PRIMARY KEY,
  idem TEXT NOT NULL UNIQUE,
  action TEXT NOT NULL,              -- create | commit | checkin | cancel | finalize | settle | faucet
  event_id INTEGER,
  key_id TEXT,
  payload TEXT,                      -- create: the policy document, until the event id is known
  status TEXT NOT NULL,              -- submitted | confirmed | failed
  block INTEGER,
  created_at REAL NOT NULL
);
CREATE INDEX IF NOT EXISTS relays_by_key ON relays(key_id, created_at);
CREATE TABLE IF NOT EXISTS faucet (key_id TEXT PRIMARY KEY, created_at REAL NOT NULL);
CREATE TABLE IF NOT EXISTS desks (token TEXT PRIMARY KEY, event_id INTEGER NOT NULL, expires_at REAL NOT NULL);
"""


def connect(path: str | Path) -> sqlite3.Connection:
    db = sqlite3.connect(path, check_same_thread=False)
    db.row_factory = sqlite3.Row
    db.execute("PRAGMA journal_mode=WAL")
    db.executescript(SCHEMA)
    return db
