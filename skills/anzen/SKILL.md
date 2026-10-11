---
name: anzen
description: Find creator events on ANZEN and reserve a place for the user with a refundable commitment, spending only inside the budget the user approved on their phone. Use when the user wants to find, book, or reserve a spot at an event, meetup, launch, listening session or workshop, or asks what their event budget is.
metadata:
  {
    "openclaw":
      {
        "requires": { "bins": ["uv"], "env": ["ANZEN_OWNER"] },
        "emoji": "🎟️"
      }
  }
---

# ANZEN event reservations

ANZEN events ask each attendee for a small **refundable commitment** (test dollars, tUSD). Attendees who check
in at the venue get it back; no-shows lose it under the event's published rule. You can reserve places with the
user's money, but **only inside a budget the user signed on their phone**, and enforced by the ANZEN contract.

## What you can and cannot do

- You CAN list open events, check the remaining budget, and reserve a place.
- You CANNOT check in, cancel, withdraw, or change your own budget. Check-in needs the user physically at the
  venue with their phone. Never claim to have checked them in, and never ask for their PIN, fingerprint or keys.
- Every reservation spends real (test) funds from the user's balance. If they don't show up, they lose it.

## Tool

Run `{baseDir}/scripts/anzen-agent <command>` with the shell tool. It prints one JSON line on stdout.

| Command | What it does |
|---|---|
| `init` | Creates your agent key once and prints `approvalLink`. |
| `budget` | `{active, remaining, cap, expiry}` in base units (1 tUSD = 1,000,000). |
| `events [query]` | Open events: `id, title, creator, venue, startsAt, commitDeadline, amount, seatsLeft, alreadyReserved`. Times are unix seconds. |
| `reserve <id>` | Commits the user's funds to that event. Returns `{ok, tx, status}`. |

## How to work

1. If `budget` says no active budget, run `init` and send the user the `approvalLink`. Tell them: "Open this on
   your phone and approve a budget in Shonko. I can only spend inside it." Stop until they confirm.
2. To find events, run `events`, filter by what the user asked for (topic, date, city, price), and skip any with
   `alreadyReserved: true` or `seatsLeft: 0`.
3. Before reserving, tell the user the event, when and where, the commitment amount, and that they get it back
   only if they check in at the venue. Reserve only after they say yes, unless they already told you to book
   without asking (e.g. "book me one thing this weekend under 10").
4. Never reserve more than the remaining budget allows; if `reserve` returns `BudgetExceeded` or `NotDelegated`,
   explain and ask them to raise or renew the budget on their phone.
5. After a reservation, confirm with the event title, amount and check-in window, and remind them: "You'll need to
   scan the code at the venue with Shonko to get your commitment back."

Amounts: divide base units by 1,000,000 and say "tUSD (test dollars)". Convert times to the user's local time.
