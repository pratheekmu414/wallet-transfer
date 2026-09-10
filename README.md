# Wallet & P2P Transfer

A small wallet service with peer-to-peer transfers, built for correctness under concurrency and
failure. Spring Boot 3 (Java 21) + PostgreSQL, plain SQL via `JdbcTemplate`, Flyway migrations.

- **Live URL:** _<fill in after deploy>_
- **Public logs:** _<fill in — e.g. Render "Logs" tab or `render logs` stream>_
- **Metrics:** `GET /metrics` (Prometheus) on the live URL
- **Write-up:** [WRITEUP.md](WRITEUP.md)

## Invariants

| # | Property | How it holds |
|---|----------|--------------|
| 1 | **Conservation** — total balance never changes across a transfer | debit and credit are `UPDATE … ± :amount` in one transaction; never read-modify-write |
| 2 | **No overdraft** — a balance never goes negative | debit is `UPDATE … WHERE balance_paise >= :amount`; 0 rows changed ⇒ declined, plus a `CHECK (balance_paise >= 0)` backstop |
| 3 | **Exactly-once transfer** — same `idempotency_key` applies once | `UNIQUE (created_by, idempotency_key)` inserted in the *same transaction* as the ledger movement; retry returns the stored result; same key + different body ⇒ `409` |
| 4 | **Race-free get-or-create** — concurrent `POST /wallets` yield one wallet | `UNIQUE (user_id)`; losing racer catches the duplicate and re-reads the existing row |

Money is always integer **paise** (`BIGINT` / Java `long`). A fractional `amount_paise` fails to
parse (`400`).

## Quickstart (one command)

```bash
docker compose up --build      # app on :8080, Postgres on :5432, migrations auto-run
```

Then:

```bash
# create two wallets (the bearer token *is* the identity — any string works)
ALICE=$(curl -s -XPOST localhost:8080/wallets -H 'Authorization: Bearer alice' | tee /dev/stderr | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])')
BOB=$(curl -s -XPOST localhost:8080/wallets   -H 'Authorization: Bearer bob'   | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])')

# seed Alice with ₹100.00 (admin-only; the only way money enters the system)
curl -s -XPOST localhost:8080/admin/credit -H 'Authorization: Bearer dev-admin-token' \
  -H 'Content-Type: application/json' -d "{\"wallet_id\":\"$ALICE\",\"amount_paise\":10000}"

# transfer ₹30.00, idempotently
curl -s -XPOST localhost:8080/transfers -H 'Authorization: Bearer alice' \
  -H 'Content-Type: application/json' \
  -d "{\"from\":\"$ALICE\",\"to\":\"$BOB\",\"amount_paise\":3000,\"idempotency_key\":\"demo-1\"}"
```

## API

All endpoints except `/health`, `/metrics` and `/admin/**` require `Authorization: Bearer <token>`.
The caller's identity is a stable hash of the token — no user table, no signup.

| Method & path | Body | Result |
|---|---|---|
| `POST /wallets` | – | `200` `{id, balance_paise}` — get-or-create the caller's wallet |
| `GET /wallets/{id}` | – | `200` `{id, balance_paise}` / `404` |
| `POST /transfers` | `{from, to, amount_paise, idempotency_key}` | `200` succeeded / `422` declined / `409` key reused with a different body |
| `GET /transfers/{id}` | – | `200` `{id, status, reason, from, to, amount_paise, reversal_of, created_at}` / `404` |
| `POST /transfers/{id}/reverse` | `{idempotency_key}` | `200` reversed / `422` recipient can't cover it / `409` already reversed / `404` |
| `POST /admin/credit` | `{wallet_id, amount_paise}` | `200` — seed balances; needs `Authorization: Bearer $ADMIN_TOKEN` |

Status codes are chosen so a retry storm returns **identical** responses: a succeeded transfer is
always `200`, a declined one always `422`, whether freshly applied or replayed.

Errors are `{"code": "...", "message": "..."}` — codes include `unauthorized`, `bad_request`,
`not_found`, `idempotency_key_conflict`, `already_reversed`, `not_reversible`.

## Burst script (reproduces the live probes)

```bash
BASE_URL=https://your-app.example.com ADMIN_TOKEN=... ./scripts/burst.sh
```

Runs, with pass/fail assertions:

1. **Concurrent get-or-create** — 50 simultaneous `POST /wallets` for a fresh user ⇒ 1 wallet.
2. **Idempotent retry storm** — 30 simultaneous identical transfers (same key) ⇒ one debit/credit,
   one transfer id in every response; then same-key/different-body ⇒ `409`.
3. **Conservation under contention** — 240 concurrent transfers among 4 wallets (A→B and B→A at
   once, plus deliberate overdrafts) ⇒ total unchanged, no negative balance, every request `200`/`422`.
4. **Reversal double-fire** — 10 concurrent reversals with one key ⇒ one refund; re-reversing ⇒ `409`.

Needs `bash`, `curl`, `python3` (stdlib only).

## Tests

```bash
./mvnw test
```

Integration tests run against a real PostgreSQL via
[zonky embedded-postgres](https://github.com/zonkyio/embedded-postgres) (a real `postgres` binary,
**no Docker needed**) and hammer the running HTTP server with concurrent bursts —
`GetOrCreateRaceTest`, `IdempotencyStormTest`, `ConservationTest`, `ReversalTest`, `ApiBasicsTest`.

## Deploy (free tier, ₹0)

The image is a standard multi-stage Dockerfile (non-root, `HEALTHCHECK`). Point it at a free managed
Postgres and set **either** `SPRING_DATASOURCE_URL` / `SPRING_DATASOURCE_USERNAME` /
`SPRING_DATASOURCE_PASSWORD`, **or** a single `DATABASE_URL` (`postgres://user:pass@host:5432/db`) —
the app translates the latter on startup.

- **Render** — `render.yaml` blueprint (free web service + free Postgres). Push to GitHub → New +
  → Blueprint.
- **Fly.io** — `fly.toml` + `fly postgres create` / `fly postgres attach` (sets `DATABASE_URL`).

Other env: `PORT` (default 8080), `DB_POOL_MAX` (default 20 — set ~10 for free Postgres),
`ADMIN_TOKEN`.

## Observability

- **Structured logs** — JSON to stdout (one object per line). Every line carries `correlation_id`
  (from an inbound `X-Correlation-Id` / `X-Request-Id`, else generated and echoed back). Domain
  events: `wallet.created`, `wallet.get_or_create.race_lost`, `wallet.minted`, `transfer.created`,
  `transfer.debited`, `transfer.credited`, `transfer.declined`, `transfer.idempotent_replay`,
  `transfer.reversal.*`, plus `http.request` with method/path/status/duration. Run locally with the
  `local` Spring profile for human-readable console logs.
- **Metrics** — `GET /metrics` (Prometheus):
  - `http_server_requests_seconds{...}` — request rate, latency histogram (p50/p95/p99 configured),
    error rate (by `status` / `outcome`).
  - `wallet_transfers_applied_total`, `wallet_transfers_declined_total{reason}`,
    `wallet_idempotent_replays_total`, `wallet_getorcreate_races_total`,
    `wallet_reversals_applied_total`, `wallet_wallets_opened_total`.
- **Health** — `GET /health` (includes a DB check); `HEALTHCHECK` in the image hits it.

## Layout

```
src/main/java/com/paytm/wallet/
  api/         controllers, DTOs, exception handler
  auth/        bearer-token → caller identity
  service/     WalletService, TransferService  ← the concurrency core
  repo/        JdbcTemplate repositories (all SQL lives here)
  obs/         correlation-id filter, domain metrics
  config/      DATABASE_URL parsing
src/main/resources/db/migration/V1__init.sql
src/test/java/…    concurrency / idempotency / conservation / reversal tests
scripts/burst.sh
```
