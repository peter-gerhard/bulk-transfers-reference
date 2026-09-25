# Worklog

This log records active project work at meaningful checkpoints. It is intentionally concise: the
README contains the curated final explanation, while Git history contains the reviewable changes.

Breaks, unrelated interruptions, and unattended download/build time are excluded from active time.

## Sprint 1 — Contract and risk discovery

- **Active time:** Approximately 1h 10m
- **Goal:** Extract the contract, discrepancies, assumptions, risks, and acceptance criteria without
  selecting an implementation architecture.
- **Inputs reviewed:** PDF brief, supplementary exercise instructions, supplied README/OpenAPI, both JSON samples, SQLite
  schema and contents, and a company's card-processor engineering article.
- **Evidence gathered:**
  - Scala is explicitly approved in the supplementary exercise instructions.
  - The account starts with `10,000,000` cents.
  - `sample1.json` totals €62,251.50; `sample2.json` totals €106,482.16.
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
