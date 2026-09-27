# Bulk Transfer Service

Scala 2 implementation of a backend engineering exercise. The solution grew from an explicit behavioural
contract into a narrow HTTP-to-SQLite slice, then added retry and concurrency correctness before
moving the verified persistence boundary to PostgreSQL.

## Run

The local demo workflow requires Docker Desktop, or another Docker-compatible engine with Compose.
Colima is not required. Build and start PostgreSQL and the service, then create the explicit demo
account. The commands use current Docker Compose (`docker compose`); installations exposing the
standalone command can substitute `docker-compose`.

```sh
docker compose up --build -d
docker compose --profile tools run --rm seed
```

Submit a transfer:

```sh
curl -i http://localhost:8080/transfers/bulk \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: review-1' \
  --data '{
    "organization_bic": "DEMOBIC",
    "organization_iban": "FR761234",
    "credit_transfers": [{
      "amount": "14.50",
      "currency": "EUR",
      "counterparty_bic": "DEUTDEFF",
      "counterparty_iban": "DE893704",
      "counterparty_name": "Supplier A",
      "description": "Invoice A"
    }]
  }'
```

The response is `201 Created`. Repeating the same command with the same idempotency key also returns
`201` without applying the debit again. The persisted result can be inspected directly:

```sh
docker compose exec postgres psql -U challenge -d bulk_transfers -c \
  "SELECT balance_cents, (SELECT COUNT(*) FROM transactions) FROM bank_accounts WHERE bic = 'DEMOBIC';"
```

Stop the environment and delete its demo data with:

```sh
docker compose down -v
```

### Development and tests

The project uses JDK 21, Scala 2.13, and sbt through `mise`. A Docker-compatible engine must be
running because the integration suite provisions its own disposable PostgreSQL database with
Testcontainers; the Compose services do not need to be running:

```sh
mise install
mise exec -- sbt test
```

The command above works without additional configuration with Docker Desktop and standard Linux
Docker installations.

#### If using Colima on macOS (optional)

This section applies only to developers who already use Colima instead of Docker Desktop. Colima
uses a non-standard socket that must be exposed to Testcontainers:

```sh
DOCKER_HOST="unix://$HOME/.colima/default/docker.sock" \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
mise exec -- sbt test
```

The same [`db/schema.sql`](db/schema.sql) initializes both Compose and the test database. The app
uses `CHALLENGE_DATABASE_URL`, `CHALLENGE_DATABASE_USER`, `CHALLENGE_DATABASE_PASSWORD`, and
`CHALLENGE_PORT`. Defaults support a PostgreSQL instance on `localhost:5432`; Compose supplies its
internal database hostname. The schema is deliberately not applied by application startup.

## Architecture

The service uses http4s/Circe for HTTP and JSON, Cats Effect for resource lifecycle, and doobie with
a resource-managed Hikari pool and PostgreSQL for persistence. These are established Scala
libraries and keep the implementation on Scala 2 without introducing an application framework.

The flow is deliberately small:

```text
HTTP/JSON -> pure normalization and validation -> one transactional batch operation -> HTTP result
```

The database operation binds the idempotency key, conditionally debits the account, inserts every
negative ledger entry, and records successful completion in one transaction. Integration tests
cover duplicate retries, competing spends, and rollback after a mid-batch failure. HTTP and
persistence are separated because they change for different reasons; additional layers or
per-class interfaces would not yet earn their complexity. Operational limits remain an open
production decision.

## Method

Work is split into outcome-oriented sprints recorded in [`docs/worklog.md`](docs/worklog.md).
Commits represent reviewable outcomes rather than clock boundaries. The guiding constraint is to
keep the solution proportional to the problem while making financial correctness explicit.

## Contract

`POST /transfers/bulk` receives a batch of outgoing transfers for one account, identified by BIC
and IBAN. The implemented HTTP contract is also available as [`openapi.yaml`](openapi.yaml).

- A request is accepted only when the account can fund the entire batch.
- Acceptance persists every debit and deducts the exact total atomically.
- Insufficient funds returns `422` and changes nothing.
- Success returns `201`.
- Errors return a stable machine-readable `code`.
- Correctness must hold across concurrent, load-balanced instances and unexpected process, client,
  network, or database failures.
- Monetary calculations must be exact to the cent.

## Decisions and working assumptions

| Topic | Decision | Reasoning |
|---|---|---|
| Success status | Return `201`. | The supplied OpenAPI says `204`, but the direct brief explicitly requires `201`. `201` also communicates that transfer records were created. |
| Insufficient funds | Return `422`. | This is explicitly required by the brief. The request is syntactically valid but cannot be processed under the account's current state. |
| Invalid request | Return `400`. | Use this for malformed JSON, invalid fields, an empty batch, unsupported currency, or an invalid/out-of-range amount. This keeps `422` specific to insufficient funds. |
| Unknown account | Return `404`. | No account matches the normalized BIC/IBAN identity. Authentication and account-enumeration concerns are outside the supplied scope but matter in production. |
| Debit sign | Request amounts are positive; persisted outgoing transactions are negative. | This matches the supplied schema description: negative for debit, positive for credit. |
| Currency | Accept only `EUR`. | The supplied request contract states that currency is always EUR. |
| Zero amount | Reject it. | “Always positive” means strictly greater than zero, and a zero-value transfer has no customer value. |
| Atomicity | Treat the batch as indivisible. | Either all transfers and the balance change commit, or none do. |
| Exact balance | Allow spending the exact remaining balance. | “Enough funds” includes equality. |
| Account identity | Normalize BIC and IBAN to uppercase and remove permitted presentation spaces before comparison and persistence. | These identifiers have canonical uppercase electronic representations; normalization avoids duplicate textual forms. |
| Retry identity | Require a client-provided `Idempotency-Key`. | Without a request identifier, a retry after a lost response cannot be distinguished from an intentional identical batch. |
| Supplied assets | Treat them as examples and discovery inputs, not canonical test fixtures. | The final tests should express the chosen contract explicitly and run from controlled state. |

## Amount grammar and range

The brief allows a positive decimal string with at most two decimal places. The current proposal is:

- accept surrounding ASCII whitespace only after trimming it;
- accept digits with an optional decimal point followed by one or two digits;
- accept leading zeroes;
- reject `.5`, `1.`, `+1`, exponent notation, negative values, and more than two decimal places;
- reject values outside the exact range supported by persistence.

PostgreSQL `BIGINT` and Scala `Long` are signed 64-bit values. Using non-negative cents gives a
maximum supported individual amount and balance of `9,223,372,036,854,775,807` cents, or
€92,233,720,368,547,758.07. Each amount is range-checked before conversion. The batch total is
evaluated without overflow; a total above this range cannot be covered by a representable balance and
therefore follows the insufficient-funds path. Supporting larger balances would require a database
schema change, not only a different Scala type.

## Idempotency semantics

- Every request requires an `Idempotency-Key` supplied by the client.
- Once a valid request reaches processing, its key remains bound to a fingerprint of the normalized
  request. Reusing that key for different content returns `409 Conflict`.
- A successful outcome is persisted in the same transaction as the debit and transfer rows.
  Retrying the same key and request then replays `201` without applying another debit.
- `404` and `422` are state-dependent, non-mutating outcomes rather than permanently cached
  results. A retry with the same key and request re-evaluates them because the account may have been
  created or credited in the meantime.
- Requests rejected with `400` do not reserve the key because processing has not begun.
- A request fingerprint may detect key misuse, but is never used to deduplicate requests that have
  different keys.

For an accepted batch, one database transaction claims the unique idempotency key, conditionally
updates the account balance, inserts one row per transfer into `transactions`, and marks the key as
successfully completed. Keeping these writes together closes crash windows between recording the
key and applying the financial effect. It also means transaction duration grows with batch size.
PostgreSQL allows unrelated accounts and keys to progress concurrently, while requests against the
same account must still contend on that account's balance row. Batch limits and measured lock time
are therefore important production controls, not correctness substitutes.

## Acceptance criteria

1. An affordable batch returns `201`; every debit is persisted and the exact total is deducted.
2. An unaffordable batch returns `422`; account and transaction state remain unchanged.
3. Invalid input or a missing/invalid idempotency key returns `400`; an unknown account returns
   `404`; neither changes financial state.
4. Repeating a key with the same request cannot duplicate effects; reusing it with different content
   returns `409`.
5. Concurrent requests cannot collectively spend more than the account balance or make decisions
   from a stale balance.
6. Failure before commit cannot expose a partial batch or balance change, and success is not returned
   before commit.
7. Amount parsing and aggregation cannot round or overflow.
8. Tests run from isolated, repeatable state rather than mutating the supplied sample database.
9. Build, run, test, assumptions, trade-offs, and known production limitations are documented.

## Production limitations and future improvements

The original correctness risks—stale balance decisions, partial writes, ambiguous retries, and
monetary overflow—are addressed by the transactional update, persistent idempotency record, exact
parsing, and integration tests. The following boundaries were deliberately left outside this
time-boxed implementation:

| Area | Current boundary | Production direction |
|---|---|---|
| Database concurrency | PostgreSQL and Hikari allow independent work to proceed concurrently, but updates to the same account serialize on its balance row and large batches hold that lock longer. | Set pool, statement, and lock timeouts from measured load; bound batch size; monitor pool saturation and lock waits; consider partitioning only when actual access patterns justify it. |
| Execution model | The complete batch is parsed into memory and processed synchronously in one HTTP request and database transaction. Transaction and lock duration grow with batch size. | Bound the synchronous path. If much larger batches are required, introduce a durable asynchronous job and status model while preserving batch atomicity and idempotency. |
| Capacity limits | Request size, transfer count, and string lengths are unbounded. | Configure HTTP-body, batch, and field limits from measured throughput and latency objectives; reject early and test every boundary. |
| Schema evolution | A single bootstrap schema is sufficient for fresh Compose and Testcontainers databases but cannot evolve an existing deployment. | Introduce ordered, versioned migrations in the delivery pipeline before the first production schema change. |
| Idempotency lifecycle | Keys are globally scoped and retained forever. | Scope keys to the authenticated tenant, retain them for at least the client retry window, and remove them through a monitored cleanup policy. |
| Failure handling | Database failures are not classified and there is no explicit retry policy or application readiness endpoint. | Retry only known transient serialization/deadlock failures inside the idempotent boundary; add readiness checks and verify graceful shutdown under load. |
| Observability and audit | Only basic server logging is present. | Add structured logs, traces, and metrics for request latency, result status, batch size, idempotency replay/conflict, transaction duration, lock wait, rollback, and database errors. Add a financial audit trail while excluding sensitive account data from telemetry. |
| API ergonomics | Errors have stable codes but no structured field details or correlation identifier; success has no response body. | Add field paths for actionable validation errors and correlation identifiers. Consider returning a batch resource identifier without exposing a balance that may immediately become stale. |
| Bank identifiers | BIC and IBAN values are normalized and required to be non-empty, but their structure, country-specific length, checksum, and real-world existence are not validated. Account lookup still establishes whether the organization identifiers belong to a known account. | Use a maintained standards-aware validator for request syntax and authoritative reference data where existence checks are required, especially before persisting counterparty identifiers. |
| Security | Authentication, authorization, rate limiting, and account-enumeration policy are outside the supplied scope. | Derive account identity from the authenticated principal and apply tenant authorization, abuse controls, and the platform's non-enumeration policy. |
| Delivery | Docker Compose provides a reproducible local environment, but there is no CI/CD pipeline or production deployment manifest. | Run build, tests, image scanning, migration checks, and contract checks in CI; deploy immutable images with managed secrets and PostgreSQL. |
| Production verification | Integration tests use real PostgreSQL through Testcontainers and deterministic in-process HTTP calls, but do not cover the containerized HTTP boundary under load. | Add OpenAPI contract checks, end-to-end smoke tests, load tests, and fault injection around connection loss, process termination, and ambiguous commits. |
| Monetary scope | The contract supports EUR and signed 64-bit cents only. | Introduce a currency-aware money model and a matching wider database representation only if future product requirements exceed that range or add currencies. |
