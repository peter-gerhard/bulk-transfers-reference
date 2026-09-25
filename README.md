# Bulk Transfer Engineering Exercise

Scala 2 implementation of a company's backend engineering exercise. Sprint 1 established the behavioural
contract before choosing an implementation architecture.

## Method

Work is split into outcome-oriented sprints recorded in [`docs/worklog.md`](docs/worklog.md).
Commits represent reviewable outcomes rather than clock boundaries. The guiding constraint is to
keep the solution proportional to the problem while making financial correctness explicit.

## Contract

`POST /transfers/bulk` receives a batch of outgoing transfers for one account, identified by BIC
and IBAN.

- A request is accepted only when the account can fund the entire batch.
- Acceptance persists every debit and deducts the exact total atomically.
- Insufficient funds returns `422` and changes nothing.
- Success returns `201`.
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

SQLite `INTEGER` and Scala `Long` are signed 64-bit values. Using non-negative cents gives a maximum
supported individual amount and balance of `9,223,372,036,854,775,807` cents, or
€92,233,720,368,547,758.07. Each amount is range-checked before conversion. The batch total is
evaluated without overflow; a total above this range cannot be covered by a representable balance and
therefore follows the insufficient-funds path. Supporting larger balances would require a database
schema change, not only a different Scala type.

## Idempotency semantics

- Every request requires an `Idempotency-Key` supplied by the client.
- The key, request identity, and terminal outcome are persisted as part of processing the batch.
- Retrying the same key with the same request replays the original outcome without applying another
  debit.
- Reusing a key with a different request returns `409 Conflict`.
- A request fingerprint may detect key misuse, but is never used to deduplicate requests that have
  different keys.

## Open decisions

- **Limits:** A finite batch and field-size limit is needed, but the value should be justified by
  transaction duration, lock contention, throughput, latency objectives, and database limits rather
  than guessed. It should be configurable and tested at its boundary.
- **Response body:** No body is required by the supplied contract. A stable machine-readable error
  code would help clients. Returning the available balance is not currently proposed because it is
  sensitive, immediately stale under concurrency, and may encourage race-prone client behaviour.
- **Database invariants:** The supplied SQLite schema has few constraints. The solution may strengthen
  its own schema or migrations where an invariant can be defended; the sample schema is not treated
  as immutable or canonical.

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

## Primary risks

- Race conditions between balance validation and update.
- Partial persistence when a process or database operation fails.
- Duplicate effects after an ambiguous client retry.
- Rounding or overflow in monetary parsing and aggregation.
- Assuming SQLite locking behaviour is equivalent to a production relational database.
- Excessive transaction duration or resource use for an unbounded batch.
