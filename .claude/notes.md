# Development Notes

## Key files
- `PathFinder.kt` — core algorithm (~500 lines), `findPathsForTarget` is `internal` for testing
- `PathFormatter.kt` — output formatting
- `ReverseIndexCache.kt` — gzip binary cache with hprof fingerprint (v3)
- `Main.kt` — CLI entry, disposed ProjectImpl filter
- `PathFinderTest.kt` — YAML-based test harness with schema validation
- `src/test/resources/graphs/*.yaml` — test graph definitions
- `src/test/resources/test-graph-schema.json` — JSON Schema for test graphs
- `.claude/plan.md` — design doc with future ideas

## Architecture notes
- Reverse index is `Map<Long, LongArray>` (child -> parent IDs), no field names stored
- Edge resolution (field names, array indices) done from HeapGraph only for final output paths
- `findPathsForTarget` accepts `Set<Long>` for root IDs (not `GcRoot`) — enables testing without Shark
- Test graphs use YAML object definition order to control reverse index parent ordering (important for backtracking/cycle tests)
- Cache format v3: magic + version + hprof file size + SHA-256 of first 64KB + entries

## Running the tool
- Always redirect stdout and stderr to separate files, then read them
- Test snapshots live in `tmp/` (gitignored)
