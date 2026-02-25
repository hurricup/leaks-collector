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

Working tool that finds diverse retention paths from GC roots to disposed ProjectImpl instances.

### What works
- Reverse index (`Map<Long, LongArray>`) with leaf-type filtering (String, primitives, wrappers, Class, String[], primitive arrays)
- Per-parent greedy walks with merge/displacement logic
- Merge threshold (5 steps from root) preserves genuinely different retention chains
- Binary reverse index cache (gzip-compressed `.ri` file) — loads in ~5s vs ~2.5min build
- Target filtering: only disposed ProjectImpl instances (containerState = DISPOSE_COMPLETED)
- Alive projects logged with their actual state
- Structured logging with timings (kotlin-logging + Logback)
- Edge resolution from HeapGraph at output time (no field names stored in index)

### Algorithm (implemented)
1. Scan all instances to find disposed ProjectImpl targets
2. Build reverse reference index (child -> parentIds as LongArray), skipping leaf types and weak references
3. Cache reverse index to `.ri` file (or load from cache on subsequent runs)
4. For each target, get direct parents from reverse index
5. For each direct parent, greedy walk backward toward GC root (first unvisited parent at each step)
6. On hitting already-known node:
   - If < MERGE_THRESHOLD steps from root: create new path (genuine diversity)
   - If new prefix shorter: displace old path with shorter prefix
   - Otherwise: skip (redundant path)
7. Resolve field names and array indices from HeapGraph only for final output paths

## Future Ideas

### 1. Configurable merge threshold
The merge threshold (currently hardcoded at 5) could be a CLI argument. Works perfectly for current use cases, but worth keeping an eye on — different heap patterns might benefit from tuning.

### 2. User-friendly CLI workflow
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
