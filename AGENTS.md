# Repository working agreement

This repository is a portfolio and reference project demonstrating reliable backend engineering.
Changes should strengthen a specific, reviewable engineering claim rather than accumulate unrelated
features.

## Working method

Before substantial work, define:

- **Claim:** what the checkpoint demonstrates.
- **Scope:** what is included and deliberately excluded.
- **Evidence:** how the claim will be verified.
- **Completion:** what must be reviewable at the end.

Keep the solution proportional to the problem. Prefer the smallest change that produces credible
evidence. Maintain one production-quality implementation; document meaningful alternatives and
their trade-offs, and implement an alternative only when the comparison itself produces useful
evidence.

Preserve existing behaviour unless a checkpoint explicitly changes the contract. Do not combine a
behavioural change with a toolchain or language migration unless the checkpoint requires both.

## Verification

Use `mise exec -- sbt test` for the full test suite. A Docker-compatible engine must be running
because the integration tests provision PostgreSQL through Testcontainers. See `README.md` for the
optional Colima environment variables.

Run affected tests after material changes. Before completing a checkpoint, run the full test suite
and verify every command or behaviour used as evidence. Do not claim compatibility or performance
without executable tests or reproducible measurements.

## Documentation

- `README.md` is the public, curated explanation of the project and its evidence.
- `docs/worklog.md` records completed, outcome-oriented work without becoming a diary.
- `docs/decisions/` is reserved for consequential, non-obvious decisions that need more space than
  the README.
- `docs/release-template.md` defines the structure for portfolio checkpoint releases.
- GitHub Releases identify permanent, reviewable checkpoints.

Keep temporary planning, personal learning goals, and career strategy outside the repository.

## AI-assisted work

AI suggestions are proposals, not decisions. The repository owner remains responsible for every
committed change. Generated or suggested code must be understood, reviewed, and verified before it
is committed.

Do not rely on conversation memory for durable project requirements or decisions. Record lasting
context in the appropriate repository document, but avoid adding process documentation that does
not help a contributor understand, change, verify, or review the software.
