# leaks-collector

## Project

CLI tool that reads JVM heap dumps (hprof) and finds all hard-referenced paths from GC roots to objects matching a filter.

### Output Format

One line per path to stdout:
```
Root -> SomeObjectField.field -> OtherObject.otherfield -> OtherObject.somearray[index] -> TargetObject
```

### Filtering

Filter is a `(HeapObjectContext) -> Boolean` predicate. `HeapObjectContext` wraps `HeapObject` + `HeapGraph` for extensibility. Current filter: hardcoded class name match. Designed so filters can later introspect object properties, not just names.

### Algorithm (current)

1. Scan all instances to find target object IDs
2. Build reverse reference index (child -> list of parents), skipping leaf types (String, primitives, wrappers, Class objects) and weak references
3. For each target, BFS backward toward GC roots — stores all incoming edges at first-visit depth
4. Build forward edge map from BFS tree, then DFS from each discovered GC root to enumerate paths
5. Deduplicate paths differing only in array indices (signature with `[*]`)
6. Stream paths to stdout; limits: 50 max depth, 100 paths per target

### Algorithm (planned redesign)

Current approach has a problem: BFS stores ALL shortest-path edges, so collection internals (HashMap buckets, array slots) explode the forward edge map and reconstruction wastes time enumerating near-identical paths through different buckets.

New approach — iterative per-parent walks:

1. Scan for targets, build reverse index (same as now)
2. For each target, get its **direct parents** from the reverse index — these are the real entry points, one walk per parent
3. For each direct parent, walk backward to a GC root (single path per walk)
4. When a walk hits a node already on a known path, compare prefix lengths (target → node):
   - If new prefix is shorter, update that node's best prefix and drop the old path from results
   - Either way, stop the current walk and start the next one
5. **Merge threshold**: don't merge within the first N steps from root (e.g., N=5–7). The "interesting" part of the path (object to blame) is usually near the root (static field, thread local, disposer entry). Merging only below that depth preserves meaningful diversity.

Key insight: diversity comes from different **direct parents of the target**, not from combinatorial branching in collection internals. Walks through similar branches merge quickly and cheaply on the first known node.

### Threading

Single-threaded. Shark's `RandomAccessHprofReader` has shared IO state (okio Buffer) that is NOT thread-safe for concurrent reads. Parallel traversal would require multiple HeapGraph instances.

### Key Library

- **Shark** (`shark-graph` + `shark-hprof` v2.14 from Square's LeakCanary) — Kotlin-native hprof parser with object graph traversal API. Works with standard JVM heap dumps despite LeakCanary being Android-focused.
- Shark sources extracted in `tmp/` (gitignored) for reference.

## Build

- Build: `./gradlew build`
- Test: `./gradlew test`
- Run: `./gradlew run`

## Conventions

- Language: Kotlin
- Build system: Gradle (Kotlin DSL)
- Atomic commits: each logical change in a separate commit
