# Advancement evidence PR verification

This PR retains the provider's existing Java 21 / Paper 1.21.11 compilation baseline. CI now checks the exact PR head on every target branch, runs clean Maven verification without skipping tests, and preserves test reports. It does not deploy or release artifacts.

Original source change remains in feature/advancement-evidence. Existing player data must be retained. New local/hosted results will be recorded on the PR, not inferred from older test counts.

Historical initial evidence implementation (f0af087, version 1.4.10): 88 Java tests, zero failures/errors/skips. This predates the reset-safe migration fix and is not the verification result for the current 1.4.11 code. Hosted CI remains a separate check on each pushed head.

## Fresh review: world-reset history boundary

The initial follow-up review reproduced a migration bug: retained pre-reset analytics could recreate cleared advancement evidence on the next startup. A durable exclusive epoch-seconds cutoff is now stored in diaries.yml alongside lastWorldUid and applied after world-reset handling. Old and same-second ambiguous events are excluded only from advancement migration; analytics records themselves are retained. Live events still update dedicated evidence normally, including in the reset second. Version 1.4.11 contains this correction.

Regression evidence: diary-reset-red.log failed the missing persisted cutoff and filtered-summary cases on the old code. The final suite also checks startup ordering.

Reset-fix verification at 112426e: 91 Java tests, zero failures/errors/skips; clean verify packaged DiaryKeeper 1.4.11. This is local evidence, not a deployment. This follow-up changes only verification provenance; a new exact-head CI run validates the documentation commit.

Documentation follow-up: round2-diary-verify.log reran clean verification after the provenance correction, with 91 tests and zero failures/errors/skips. No runtime code changed in this follow-up.
