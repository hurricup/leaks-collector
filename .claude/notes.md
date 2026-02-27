# Development Notes

## Key files
- `PathFinder.kt` — core algorithm (~580 lines), `findPathsForTarget` is `internal` for testing
- `PathFormatter.kt` — output formatting
- `ReverseIndexCache.kt` — gzip binary cache with hprof fingerprint (v3)
- `Main.kt` — CLI entry, target filtering (disposed ProjectImpl + released EditorImpl), report header
- `PathFinderTest.kt` — YAML-based test harness with schema validation (18 tests)
- `GroupingTest.kt` — tests for path signature grouping (4 tests)
- `src/test/resources/graphs/*.yaml` — 18 test graph definitions
- `src/test/resources/test-graph-schema.json` — JSON Schema for test graphs
- `.claude/plan.md` — design doc with future ideas

## Architecture notes
- Reverse index is `Map<Long, LongArray>` (child -> parent IDs), no field names stored
- Edge resolution (field names, array indices) done from HeapGraph only for final output paths
- `findPathsForTarget` accepts `Set<Long>` for root IDs (not `GcRoot`) — enables testing without Shark
- `findPathsForTarget` also accepts `claimedNodes` and `sharedPrefixDepth` params; tests use `sharedPrefixDepth=3` for compact test graphs
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

### Dynamic merge threshold
Fixed `SHARED_PREFIX_DEPTH=8` doesn't work for all cases. Example from `closedprojects_2502`:

All 5 paths share prefix `CefServer.bid2Browser -> ConcurrentHashMap -> Node[] -> Node.val -> RemoteBrowser.myComponent -> JBCefOsrComponent`, then diverge by 2-3 steps to reach the same ProjectImpl. With threshold 8, step 6 is "near root" so all are kept as separate paths — but they're the same root cause.

Meanwhile Disposer paths share infrastructure at steps 4-6 (`Disposer.ourTree -> ObjectTree -> Reference2ObjectOpenHashMap.key`) and diverge into genuinely different subsystems at step 7+. Lowering the threshold would incorrectly merge those.

**Idea: anchor-based dynamic threshold.** Known infrastructure classes shift the merge boundary:
- Registry of `(className, offset)` pairs
- Pre-scan heap for anchor class instance IDs before walking
- During merge decision, if shared prefix contains an anchor, boundary = anchor position + offset
- Paths through Disposer get one boundary, paths through CefServer get another, paths through neither get the default

**Challenges:**
- `walkToRoot` operates on bare object IDs, no HeapGraph access — need to pre-build anchor ID set and pass it through
- Anchor position varies per path (not fixed step from root)
- Need to determine good offset values per anchor class

No good design yet — needs more thought.
