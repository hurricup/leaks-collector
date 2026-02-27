# Codex Context Snapshot

Date: 2026-02-25
Repository: leaks-collector

## Scope completed
- Performed code review focused on actionable leak-path behavior.
- Verified recent fixes around reverse-index cache validation and corrupt-cache fallback.
- Reviewed newly added YAML-based tests for pathfinding behavior quality.

## Current conclusions
- Cache stale-data issue is fixed:
  - Cache now includes hprof size + fingerprint.
  - Loader rebuilds when mismatch is detected.
- Corrupt/incompatible cache hard-fail issue is fixed:
  - Loader returns null on bad cache and caller rebuilds.
- Test coverage improved significantly (many YAML scenarios + schema validation).

## Remaining review findings (active)
1. `dead-end-with-backtrack` test does not strictly force backtracking; it may pass even if backtracking regresses.
2. `cycle-avoidance` test does not strictly force traversal to confront the cycle branch; it may pass with cycle-handling regressions.

These two findings are recorded in:
- `codex_review.md`

## Important context about review intent
- User clarified goal is actionable leaks (not full GC-root completeness).
- Excluding non-actionable root categories like `StickyClass` is treated as intentional scope, not a bug.
 - Review workflow: I record findings in `codex_review.md`, then Claude responds in `claude_review_answer.md`.

## Practical constraints acknowledged
- Real heap snapshots are too large for repo tests.
- Team is using synthetic YAML graph fixtures as phase-1 test strategy.
- Suggested long-term direction: optional graph abstraction layer for stronger integration testing without real dumps.
