# Worklog

This log records active project work at meaningful checkpoints. It is intentionally concise: the
README contains the curated final explanation, while Git history contains the reviewable changes.

Breaks, unrelated interruptions, and unattended download/build time are excluded from active time.

## Sprint 1 — Contract and risk discovery

- **Active time:** Approximately 1h 10m
- **Goal:** Extract the contract, discrepancies, assumptions, risks, and acceptance criteria without
  selecting an implementation architecture.
- **Inputs reviewed:** PDF brief, supplementary exercise instructions, supplied README/OpenAPI, both JSON samples, SQLite
  schema and contents, and a card-processor engineering article from the domain source.
- **Evidence gathered:**
  - Scala is explicitly approved in the supplementary exercise instructions.
  - The account starts with `10,000,000` cents.
  - The existing transactions sum to the current balance.
  - The sample database has no declared indexes beyond row IDs, foreign keys, `NOT NULL`
    constraints, or financial check constraints; it is illustrative rather than canonical.
  - The supplied sources conflict on the successful HTTP status. Positive request amounts and
    negative persisted debits are consistent with the described ledger convention.
- **Outcome:** Complete. The behavioural contract, assumptions, acceptance criteria, and primary
  risks are recorded in the README.
- **Architecture decisions:** None; intentionally deferred.

### Review checkpoint

The first draft was reviewed rather than accepted unchanged. The documentation was shortened;
personal names were removed; supplied assets were reclassified as illustrative inputs; debit signs
were clarified as consistent with outgoing transfers; and response and normalization questions were
narrowed. Canonical amount syntax and a required client idempotency key were accepted. Limits and
response bodies remain open. Time awaiting review is not counted as active project time.

## Sprint 2 — Minimal architecture and walking slice

- **Active time:** Approximately 1h 10m
- **Goal:** Choose a proportionate Scala stack, define only the boundaries needed for correctness,
  and implement one executable request-to-persistence path.
- **Scope:** Application structure, request decoding, domain validation, controlled database setup,
  and the basic accepted/rejected flow.
- **Deferred to Sprint 3:** Concurrency proof, crash behavior, idempotent replay, operational limits,
  and production hardening.
- **Current checkpoint:** A real HTTP request now passes through contract decoding, pure validation,
  and one SQLite transaction. Integration tests prove both an accepted batch and a rejected
  insufficient-funds batch against isolated state. Unknown-account and missing/blank idempotency-key
  paths are also covered without financial side effects. Idempotent replay is intentionally not yet
  implemented.
- **Outcome:** Complete. The executable walking slice establishes the minimum architecture needed
  to test the contract while leaving concurrency and retry hardening for Sprint 3.

## Sprint 3 — Retry and concurrency correctness

- **Active time:** Approximately 30m
- **Goal:** Make ambiguous retries safe and prove the financial invariants under concurrency and
  mid-transaction failure.
- **Scope:** Persistent idempotency-key binding, normalized request fingerprints, successful-result
  replay, state-dependent retry behavior, concurrent spending, and rollback verification.
- **Current checkpoint:** The key reservation and successful result now share the financial
  transaction. Integration tests cover replay without duplicate effects, key conflicts, changing
  account state between retries, concurrent spending, and rollback after a forced insertion error.
- **Outcome:** Complete. Successful effects are replayable without duplication; state-dependent
  failures are re-evaluated; and the concurrency and rollback invariants are covered by executable
  tests.

### Decisions and future improvements

- Do not add `ETag`/`If-Match`. A version precondition could detect stale account state, but the bulk
  endpoint is a command and the client has no account representation to condition it on. It also
  cannot determine whether an earlier request committed after its response was lost. The
  `Idempotency-Key` and conditional database update address those separate concerns more directly.
- Do not permanently replay state-dependent `404` or `422` outcomes. A same-key, same-request retry
  should re-evaluate them because an account may be created or credited between attempts. A
  successful financial effect remains permanently replayable for the key's retention period.
- For a production database, replace the direct DriverManager transactor with a managed, pool-backed
  transactor; this was subsequently addressed in Sprint 5.

## Sprint 4 — Submission polish

- **Active time:** Approximately 30–40m
- **Goal:** Close the highest-value contract and usability and documentation gaps without broadening the
  architecture.
- **Scope:** Exact-balance, malformed-JSON, and empty-batch HTTP tests; clearer run instructions;
  an OpenAPI contract; and concise documentation of the remaining production limitations.
- **Outcome:** Complete. The submission now has focused boundary coverage, a reproducible manual
  demo, an OpenAPI contract with examples, and an explicit record of deliberately deferred
  production concerns.

## Sprint 5 — Containerized PostgreSQL

- **Active time:** Approximately 1h 40m
- **Goal:** Make the service runnable with one standard toolchain and verify persistence semantics
  against the production-shaped database engine.
- **Scope:** PostgreSQL, a resource-managed Hikari pool, Testcontainers integration tests, a
  multi-stage application image, Compose orchestration, and an explicit demo seed command.
- **Schema decision:** Compose and tests share one bootstrap schema for fresh databases. Versioned
  migrations are deferred until there is a second schema version to migrate to.
- **Outcome:** PostgreSQL fully replaces SQLite; Docker is the only reviewer prerequisite; the
  financial concurrency, idempotency, and rollback tests execute against PostgreSQL.
