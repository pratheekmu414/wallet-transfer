# Wallet & P2P Transfer — design write-up

## Data model

Two tables (`src/main/resources/db/migration/V1__init.sql`).

**`wallets`** — `id uuid pk`, `user_id text UNIQUE`, `balance_paise bigint DEFAULT 0
CHECK (balance_paise >= 0)`, `created_at`. One row per user; the unique constraint is the whole
get-or-create story.

**`transfers`** — `id uuid pk`, `idempotency_key text`, `created_by text`, `from_wallet`, `to_wallet`
(both FK → `wallets`), `amount_paise bigint CHECK (> 0)`, `status text CHECK IN
('pending','succeeded','declined')`, `reason text`, `request_hash text`, `reversal_of uuid`
(FK → `transfers`, nullable), `created_at`. Constraints:
`UNIQUE (created_by, idempotency_key)`, a partial `UNIQUE (reversal_of) WHERE reversal_of IS NOT
NULL` (a transfer is reversible at most once), `CHECK (from_wallet <> to_wallet)`.

Money is `bigint` paise everywhere and `long` in Java. Jackson is configured to reject a fractional
`amount_paise` rather than truncate it. The ledger is closed and zero-sum; `POST /admin/credit` is
the one deliberate, separately-authenticated way to mint (used only to seed balances).

## The simplest-correct mechanism for conservation + no-overdraft

Inside **one** `READ COMMITTED` transaction:

1. lock **both** wallet rows with `SELECT … FOR UPDATE`, **in ascending wallet-id order**;
2. `INSERT` the transfer row (`pending`) — this is also the idempotency checkpoint (below);
3. debit: `UPDATE wallets SET balance_paise = balance_paise - :amt WHERE id = :from AND balance_paise
   >= :amt`. **0 rows changed ⇒ decline** (mark the transfer `declined`, commit, `422`); no partial
   apply is possible.
4. credit: `UPDATE wallets SET balance_paise = balance_paise + :amt WHERE id = :to`; mark `succeeded`.

Conservation holds because the two writes are `± :amt` on the database value itself — the app never
reads a balance and writes it back, so there is no lost update and the sum is invariant. No-overdraft
holds because the debit's `WHERE` clause is the check, evaluated atomically by Postgres while we hold
the row lock, with the `CHECK (balance_paise >= 0)` constraint as a backstop.

**Deadlock avoidance.** Both wallet rows are locked up front in a deterministic order (lower id
first), so every transfer *and* every reversal requests the same locks in the same order — the A→B /
B→A cross cannot form a cycle, one transaction simply waits for the other to commit. Locking before
the `INSERT` matters: the transfer's FK to `wallets` would otherwise take `FOR KEY SHARE` locks on
the two wallet rows in row-argument order (unsorted), which *did* deadlock under the cross in an
early version — taking the stronger `FOR UPDATE` locks first, sorted, subsumes them. A bounded retry
(≤4, jittered) on `TransientDataAccessException` mops up any residual serialization/lock-timeout
blip; with the sorted order it essentially never fires.

**Heavier alternatives rejected:**

- **`SERIALIZABLE` everywhere** — correct, but it pushes the cost onto the client as
  `40001` serialization failures that must be caught and retried, and under the "few wallets, hundreds
  of transfers" contention profile the retry rate would be high. We don't need snapshot isolation for
  a single-row-per-side debit; a conditional `UPDATE` under `READ COMMITTED` is already correct.
- **Unsorted `SELECT … FOR UPDATE`** — the classic deadlock storm on the A→B + B→A cross.
- **Advisory locks / a single global mutex / an actor per wallet** — serializes unrelated transfers,
  throws away Postgres's own row-level concurrency, and adds a failure mode (lock not released on
  crash). No benefit here.
- **Read-modify-write in the app** — lost updates; money created/destroyed. Never considered viable.

The conditional `UPDATE` alone (no explicit `FOR UPDATE`) is also correct for a plain transfer, but
the reversal and the FK-lock interaction made an explicit, sorted lock step the clearer invariant to
hold system-wide, so both paths use it.

## Where idempotency lives

In the database, on `transfers (created_by, idempotency_key)`, **committed in the same transaction
as the debit and credit**. Flow: a fast-path `SELECT` by key returns the stored result on the common
retry; otherwise the `INSERT` runs inside the transaction. A concurrent duplicate **blocks on the
unique index** and then either loses the race — gets a `23505`, rolls back (nothing moved), and
re-reads the committed transfer — or, if the winner rolled back, proceeds. Because the key row and
the balance changes commit atomically, there is no TOCTOU window: you cannot observe the key without
also observing its debit/credit.

- **Same key, same body, K concurrent** ⇒ exactly one debit/credit; every response carries the same
  transfer id and the same `200`.
- **Same key, different body** ⇒ `409 idempotency_key_conflict` (we compare a `request_hash` of
  `from|to|amount`), never a second debit and never a silent return of the first result.
- **Reversal** reuses the same primitive: its own `idempotency_key` in the same unique constraint,
  its movement (recipient → sender) through the same sorted-lock conditional-debit path, and the
  partial unique index on `reversal_of` makes "reverse twice" (even with a new key) a `409`. If the
  recipient has already spent the funds the reversal **declines cleanly** (`422`) rather than
  driving them negative.

## Consistency vs availability for a money workload

**Chosen: consistency (CP).** The service is a single stateless app tier in front of one primary
Postgres. Every transfer is a single-primary ACID transaction; there is no multi-region replication,
no eventual-consistency read path, no "accept the write now, reconcile later". If Postgres is
unreachable the API returns `503`/`500` and the client retries with the same idempotency key — we
**refuse the write rather than risk a double-spend or a lost debit**.

**Consciously given up:** write availability during a database outage or failover, and horizontal
write scaling beyond one primary. Both are acceptable for this workload and both have standard
mitigations that don't change the model (managed HA Postgres with fast failover; the app tier scales
horizontally and stays correct because all invariants are enforced in the shared database, not in
app memory). Read-scaling via replicas would be safe for `GET`s but is out of scope.

## AI: directed vs decided

- **Directed** (I chose the approach, AI implemented): stack (Spring Boot + plain `JdbcTemplate`,
  deliberately not JPA, for exact SQL control); the conditional-`UPDATE` + sorted-`FOR UPDATE`
  mechanism and the decision to reject `SERIALIZABLE`; idempotency as a unique constraint committed
  in the ledger transaction; status-code scheme for identical retry responses; the reversal design
  (reuse the primitive, partial unique index, decline-on-insufficient-funds); data model and
  constraints; observability choices (correlation id, which domain events, which counters).
- **AI decided** (I accepted its design): the bearer-token → identity hashing detail; exact package
  layout and DTO shapes; the embedded-Postgres test harness wiring; the burst script's file-per-
  response mechanism to avoid stdout interleaving; Dockerfile base images and the `curl`-based
  healthcheck; `render.yaml` / `fly.toml` specifics.
- **Found by testing, then directed the fix:** the FK-lock-ordering deadlock under A→B + B→A — the
  first version locked implicitly via the transfer `INSERT`'s FK and deadlocked; the fix (explicit
  sorted `FOR UPDATE` before the insert) was my call after reading the failure.

## Free-tier cost note

**₹0.** Render free web service + Render free PostgreSQL (or Fly.io's free allowance + Fly Postgres).
No card required on Render's free tier. Local dev and CI use Docker Compose or the embedded-Postgres
test harness — no managed resources. The image builds on public base images; no paid registry.
