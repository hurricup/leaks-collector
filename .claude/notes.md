# Development Notes

## Key files
- `PathFinder.kt` — core algorithm (~580 lines), `findPathsForTarget` is `internal` for testing
- `PathFormatter.kt` — output formatting
- `ReverseIndexCache.kt` — gzip binary cache with hprof fingerprint (v3)
- `Main.kt` — CLI entry, target filtering (disposed ProjectImpl + released EditorImpl), report header
- `PathFinderTest.kt` — YAML-based test harness with schema validation (19 tests)
- `GroupingTest.kt` — tests for path signature grouping (4 tests)
- `src/test/resources/graphs/*.yaml` — 19 test graph definitions
- `src/test/resources/test-graph-schema.json` — JSON Schema for test graphs
- `.claude/plan.md` — design doc with future ideas

## Architecture notes
- Reverse index is `Map<Long, LongArray>` (child -> parent IDs), no field names stored
- Edge resolution (field names, array indices) done from HeapGraph only for final output paths
- `findPathsForTarget` accepts `Set<Long>` for root IDs (not `GcRoot`) — enables testing without Shark
- `findPathsForTarget` accepts `defaultMergeDepth` (default 3) and `classNameOf` function for dynamic per-path merge depth
- Disposer anchor detection: if path contains `Disposer` class, merge depth = Disposer step from root + 4
- Tests pass `defaultMergeDepth=3` and a `classNameOf` lambda wrapping the YAML model
- Test harness mirrors production: maintains `claimedNodes` across targets, claims nodes far from root after each target
- Test graphs use YAML object definition order to control reverse index parent ordering (important for backtracking/cycle tests)
- Cache format v3: magic + version + hprof file size + SHA-256 of first 64KB + entries
- `idsFromTarget` in PathRecord includes the root object as last element — important for claiming calculation (exclude root from step count)

## Review flow
- Codex writes reviews in `codex_review.md`
- Claude answers in `claude_review_answer.md`

## Conventions
- "Store" / "save" means save to repo files (e.g. `.claude/notes.md`), NOT machine-local memory

## Running the tool
- Always redirect stdout and stderr to separate files, then read them
- Test snapshots live in `tmp/` (gitignored)

## Open design questions

### Additional merge depth anchors
Dynamic per-path merge depth is implemented with Disposer as the first anchor (offset +4). More anchors can be added to `computeMergeDepth` as needed — just match on class name and return appropriate depth. The `myRootNode` variant of Disposer paths has deeper infrastructure (5 steps after Disposer vs 3) so the +4 offset is slightly short for that case.
