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
-- Agents that owners have bound a budget to (their ERC-8004 identity), for the public reputation board.
CREATE TABLE IF NOT EXISTS agents (agent_id TEXT PRIMARY KEY, agent TEXT NOT NULL, first_seen REAL NOT NULL);
-- Next relayer nonce, shared by every instance of the service (see Chain._broadcast).
CREATE TABLE IF NOT EXISTS relayer (id INTEGER PRIMARY KEY, next_nonce INTEGER NOT NULL, updated_at REAL NOT NULL);
"""


def connect(path: str | Path):
    """SQLite file, or Postgres when given a postgres:// URL (see Postgres)."""
    if str(path).startswith(("postgres://", "postgresql://")):
        return Postgres(str(path))
    db = sqlite3.connect(path, check_same_thread=False)
    db.row_factory = sqlite3.Row
    db.execute("PRAGMA journal_mode=WAL")
    db.executescript(SCHEMA)
    return db


class Postgres:
    """The few sqlite3.Connection behaviours the service relies on, over Postgres (Neon), for hosting where the
    disk doesn't persist: `?` placeholders, INSERT OR IGNORE, dict rows, `with db:` transactions, and
    sqlite3.IntegrityError on a unique-key clash. One connection per thread: the web server handles requests on
    a thread pool, and a transaction must never be shared between two requests."""

    def __init__(self, url: str):
        import re
        import threading

        import psycopg
        from psycopg.rows import dict_row
        self._psycopg, self._url, self._row = psycopg, url, dict_row
        self._local = threading.local()
        for stmt in re.sub(r"--[^\n]*", "", SCHEMA).split(";"):
            if stmt.strip():
                self.conn.execute(stmt)

    @property
    def conn(self):
        c = getattr(self._local, "conn", None)
        if c is None or c.closed:
            c = self._local.conn = self._psycopg.connect(self._url, autocommit=True, row_factory=self._row)
        return c

    def execute(self, sql: str, params: tuple = ()):
        ignore = sql.lstrip().upper().startswith("INSERT OR IGNORE")
        sql = sql.replace("INSERT OR IGNORE", "INSERT").replace("?", "%s") + (" ON CONFLICT DO NOTHING" if ignore else "")
        try:
            return self.conn.execute(sql, params)  # inside `with db:` this runs in this thread's open transaction
        except self._psycopg.IntegrityError as e:
            raise sqlite3.IntegrityError(str(e)) from None

    def commit(self) -> None:  # autocommit outside `with`
        pass

    def __enter__(self):
        tx = self.conn.transaction()
        tx.__enter__()
        self._local.tx = tx
        return self

    def __exit__(self, *exc):
        tx, self._local.tx = self._local.tx, None
        try:
            return tx.__exit__(*exc)
        except self._psycopg.IntegrityError as e:
            raise sqlite3.IntegrityError(str(e)) from None
