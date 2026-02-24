# Implementation Plan: Leak Path Collector

## Architecture

```
Main.kt                  - CLI entry point (args: hprof path)
HeapObjectContext.kt     - Wrapper around HeapObject with extra context
PathFinder.kt            - Finds paths from GC roots to matching objects
PathFormatter.kt         - Formats a path into the output string
```

## Current State

Working tool that finds 100 diverse shortest paths from GC roots to target objects.

### What works
- Reverse index with leaf-type filtering (String, primitives, wrappers, Class, String[])
- BFS backward from target with forward edge reconstruction
- Path deduplication via signature (array indices normalized to `[*]`)
- Single GC root entry per root object (no duplicate root paths)
- Structured logging with timings (kotlin-logging + Logback)

### Known problems
- BFS stores ALL edges at first-visit depth — collection internals (HashMap buckets, array slots) create massive forward edge maps
- DFS reconstruction enumerates all same-depth alternatives, wastes time on near-identical paths
- Signature dedup catches duplicates at output time, but all the traversal work is already done

## Next: Redesign Path Finding

### Core idea
Diversity comes from different **direct parents of the target**, not from combinatorial branching midway. One walk per direct parent is sufficient.

### Algorithm
1. Build reverse index (same as now)
2. For each target, get direct parents from reverse index
3. For each direct parent, walk backward to a GC root (one path per walk)
4. On hitting an already-visited node:
   - Compare prefix lengths (target → node)
   - If new prefix shorter: update node's best prefix, drop old path from results
   - Stop walk either way, start next one
5. Merge threshold: don't merge within first N steps from root (N=5–7)
   - "Interesting" objects (the ones to blame) are near the root
   - Merging near root would collapse genuinely different paths

### Why this is better
- Each walk stops at first known node — very cheap for redundant branches
- No BFS explosion through collection internals
- Number of walks = number of direct references to target (bounded)
- Natural dedup: similar branches merge early

### Open questions
- Exact merge threshold value (start with fixed N, tune from results)
- Walk strategy: DFS? Shortest-first? (DFS is simplest, merging handles bias)

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
