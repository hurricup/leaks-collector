# Implementation Plan: Leak Path Collector

## Architecture

```
Main.kt                  - CLI entry point (args: hprof path, target filtering)
HeapObjectContext.kt     - Wrapper around HeapObject with extra context
PathFinder.kt            - Finds paths from GC roots to matching objects
PathFormatter.kt         - Formats a path into the output string
ReverseIndexCache.kt     - Binary cache for reverse index (gzip-compressed)
```

## Current State

Working tool that finds diverse retention paths from GC roots to leaked objects.

### What works
- Targets: disposed `ProjectImpl` (containerState = DISPOSE_COMPLETED) + released `EditorImpl` (isReleased = true)
- Reverse index (`Map<Long, LongArray>`) with leaf-type filtering (String, primitives, wrappers, Class, String[], primitive arrays)
- Per-parent greedy walks with merge/displacement logic and bounded backtracking (10 retries)
- Cross-target filtering: paths through other targets treated as dead ends
- Global node claiming: nodes far from root (>= SHARED_PREFIX_DEPTH=8) are claimed after finding paths for a target; future targets treat claimed nodes as dead ends
- Dependent targets: targets with no independent path reported as "held by a path above"
- Shared prefix depth (8 steps from root) — single unified constant for both per-target merge and global claiming
- Binary reverse index cache (gzip-compressed `.ri` file) — loads in ~5s vs ~2.5min build
- Report header with snapshot metadata (file, size, timestamp, hprof version, pointer size, object counts, GC roots)
- Path signature deduplication (array indices replaced with `[*]`)
- Groups sorted by target count descending
- Alive instances logged and skipped
- Structured logging with timings (kotlin-logging + Logback)
- Edge resolution from HeapGraph at output time (no field names stored in index)
- 22 tests (18 path-finding + 4 grouping)

### Algorithm (implemented)
1. Scan all instances to find target objects (disposed ProjectImpl + released EditorImpl)
2. Build reverse reference index (child -> parentIds as LongArray), skipping leaf types and weak references
3. Cache reverse index to `.ri` file (or load from cache on subsequent runs)
4. For each target (in order), get direct parents from reverse index
5. Skip direct parents that are other targets or globally claimed nodes
6. For each remaining direct parent, greedy walk backward toward GC root (first unvisited parent at each step)
7. On hitting claimed or target node: backtrack (treat as dead end)
8. On hitting already-known node (per-target merge):
   - If < SHARED_PREFIX_DEPTH steps from root: create new path (genuine diversity)
   - If new prefix shorter: displace old path with shorter prefix
   - Otherwise: skip (redundant path)
9. After finding paths for a target, claim nodes far from root globally
10. Targets with 0 paths become "dependent" (held by a path above)
11. Resolve field names and array indices from HeapGraph only for final output paths
12. Deduplicate by path signature, group targets, sort by group size descending

## Future Ideas

### 1. Forward DFS from roots (algorithm redesign)

Fundamental shift: instead of building a reverse index and walking backward from targets, do a single forward DFS from GC roots and discover leak causes as you go.

**Core idea:** The goal is to find **leak causes**, not enumerate every leaked target. A leak cause is a retention path from a GC root to a target. Walking forward means you discover the cause first (the retention chain) and the target second (at the end).

**Algorithm (hybrid DFS + BFS):**
1. Pre-scan to identify target object IDs (same as now)
2. DFS from GC roots following strong references down to the infrastructure boundary (merge point)
3. At the merge point, switch to BFS — explore breadth-first from each branch
4. Blacklist (mark visited) every node as you enter it
5. Skip leaf types, weak references, already-visited nodes (same filtering as now)
6. When you hit a target:
   - Store the current path (root → ... → target) as a result
   - Do NOT descend into the target (everything below it is likely the same cause)
   - Backtrack/continue from the merge point with the next sibling
7. The next Disposer array entry / CefServer map entry / etc. may lead to the same or different target via a different cause — each is a separate finding

**Why hybrid DFS + BFS:**
- DFS handles the tree-like shared prefix from roots efficiently (no queue overhead)
- BFS after the merge point **guarantees the shortest path from merge point to target** — the most direct explanation of why the object is retained
- BFS handles fan-out cleanly: when 10k array entries point to the same object, it's visited once at the first level and skipped on subsequent enqueues
- DFS alone finds arbitrary-length paths depending on field ordering; BFS finds the most useful path

**What we gain:**
- No reverse index needed — no 50s build time, no 83MB cache file, no `.ri` format
- Single pass through the graph
- Cross-target filtering is free (don't descend into targets)
- Claiming/merging logic disappears — the blacklist + backtrack-to-merge-point does the same job
- Focus on causes, not targets — 50 editors leaking through the same Disposer entry produce one finding, not 50
- Aligns better with Shark's API (forward references are native)

**What changes:**
- No need for `findPathsForTarget`, `walkToRoot`, reverse index builder, cache
- The infrastructure boundary concept (currently `SHARED_PREFIX_DEPTH`) becomes "how far to backtrack after finding a target" instead of "when to merge paths"
- Output shifts from per-target grouping to per-cause listing

**Potential problems to think through:**

1. **DFS stack depth.** Heap object graphs can be very deep (thousands of levels via linked lists, trees, etc.). DFS stack could overflow or be very large. May need iterative DFS with explicit stack, and possibly a depth limit.

2. **Infrastructure boundary detection during forward walk.** Currently SHARED_PREFIX_DEPTH is a fixed step count from root. In forward DFS, we need to know "where is the merge point" to decide how far to backtrack. The anchor-based approach (known infrastructure classes) is still needed — same open question as before, but now it controls backtrack depth instead of merge threshold.

3. **Missed targets.** If target A and target B are both reachable through the same intermediate node (between infrastructure boundary and target), and we find A first and backtrack past that node, B becomes unreachable (blacklisted). This is acceptable if they share the same cause, but not if they have genuinely different causes that happen to share a common intermediate.

4. **Path quality.** Solved by the hybrid approach: BFS after the merge point guarantees the shortest path from merge point to target. The shared prefix (root to merge point) is fixed infrastructure, so overall path quality is good.

5. **Re-traversal after backtrack.** If we backtrack to the merge point and un-blacklist nodes between merge point and target (to allow finding them via other routes), we might re-traverse large subtrees. If we DON'T un-blacklist, we miss targets reachable only through those intermediate nodes.

6. **Shark API constraints.** `HeapObject.readRecord()` and field traversal require random access to the hprof file. DFS visits nodes in an unpredictable order, so IO will be random (not sequential). The reverse index approach reads the file sequentially once (during build) and then works from memory. Forward DFS may be slower due to random IO patterns.

7. **Multiple paths per target.** Current approach finds 12+ paths for a single ProjectImpl through different subsystems. Forward DFS would find each one naturally IF the subsystems are in different branches of the DFS tree (e.g., different Disposer entries). But if two paths to the same target share a node that gets blacklisted during the first traversal, the second path is lost.

8. **Testing.** Current YAML test harness builds synthetic reverse indexes. Forward DFS would need a different test approach — synthetic forward graphs or mocking Shark's HeapGraph API.

### 2. Dynamic merge threshold (anchor-based)
Fixed `SHARED_PREFIX_DEPTH=8` doesn't work for all cases — see `.claude/notes.md` for details. Needs an anchor-based approach where known infrastructure classes shift the merge boundary dynamically. Relevant to both current algorithm and forward DFS (where it controls backtrack depth).

### 3. User-friendly CLI workflow
Provide interactive CLI that:
- Runs a command to find IDE processes (or shows all JVM processes)
- Lets user pick a process
- Runs `jmap` to capture heap dump
- Analyzes the snapshot automatically

### 3. MCP server for snapshot navigation
Separate tool that loads a heap snapshot and exposes MCP tools for navigating, searching, and inspecting the object graph interactively. This feels like a separate project rather than an extension of leaks-collector.

## Completed commits
1. Scaffold Gradle project
2. Add shark-graph dependency
3. Add HeapObjectContext wrapper
4. Add PathFinder (initial forward BFS)
5. Add PathFormatter
6. Wire up Main with CLI args
7. Rewrite to reverse index + DFS walkBack
8. Filter `<`-prefixed JVM internal fields
9. BFS backward from target with forward edge reconstruction
10. Single GC root entry per root object
11. Deduplicate paths differing only in array indices
12. Skip leaf-type objects in reverse index
13. Structured logging with timings
14. Redesign path finding: per-parent greedy walks with merge
15. Add binary reverse index cache
16. Filter targets to only disposed ProjectImpl instances
