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
- Global node claiming: nodes far from root (>= merge depth) are claimed after finding paths for a target; future targets treat claimed nodes as dead ends
- Dependent targets: targets with no independent path reported as "held by a path above"
- Dynamic per-path merge depth: default 3, Disposer paths get Disposer position + 4. Merge-derived paths inherit parent's merge depth.
- Binary reverse index cache (gzip-compressed `.ri` file) — loads in ~5s vs ~2.5min build
- Report header with snapshot metadata (file, size, timestamp, hprof version, pointer size, object counts, GC roots)
- Path signature deduplication (array indices replaced with `[*]`)
- Groups sorted by target count descending
- Alive instances logged and skipped
- Structured logging with timings (kotlin-logging + Logback)
- Edge resolution from HeapGraph at output time (no field names stored in index)
- 23 tests (19 path-finding + 4 grouping)

### Algorithm (implemented)
1. Scan all instances to find target objects (disposed ProjectImpl + released EditorImpl)
2. Build reverse reference index (child -> parentIds as LongArray), skipping leaf types and weak references
3. Cache reverse index to `.ri` file (or load from cache on subsequent runs)
4. For each target (in order), get direct parents from reverse index
5. Skip direct parents that are other targets or globally claimed nodes
6. For each remaining direct parent, greedy walk backward toward GC root (first unvisited parent at each step)
7. On hitting claimed or target node: backtrack (treat as dead end)
8. On hitting already-known node (per-target merge):
   - If < existing path's merge depth from root: create new path (genuine diversity, inherit merge depth)
   - If new prefix shorter: displace old path with shorter prefix (inherit merge depth)
   - Otherwise: skip (redundant path)
9. After finding paths for a target, claim nodes far from root (>= path's merge depth) globally
10. Targets with 0 paths become "dependent" (held by a path above)
11. Resolve field names and array indices from HeapGraph only for final output paths
12. Deduplicate by path signature, group targets, sort by group size descending

## Future Ideas

### 1. Additional merge depth anchors
Disposer anchor is implemented. More anchors can be added to `computeMergeDepth` for other infrastructure classes. See `.claude/notes.md` for the `myRootNode` variant caveat.

### 2. User-friendly CLI workflow
Provide interactive CLI that:
- Runs a command to find IDE processes (or shows all JVM processes)
- Lets user pick a process
- Runs `jmap` to capture heap dump
- Analyzes the snapshot automatically

### 3. IDE information in report header
Extract IDE metadata from the heap snapshot and include it in the report header: application name, version, Xmx settings, installed plugins list.

### 4. MCP server for snapshot navigation
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
