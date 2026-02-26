# leaks-collector

## Project

CLI tool that reads JVM heap dumps (hprof) and finds all hard-referenced paths from GC roots to objects matching a filter. Currently targets disposed `ProjectImpl` and released `EditorImpl` instances in IntelliJ-based IDEs.

### Output Format

Report header with snapshot metadata, then grouped paths sorted by group size (largest first). Targets with no independent path are reported as dependent ("held by a path above"):
```
# leaks-collector 2026.2
# File: /path/to/dump.hprof
# Size: 1686.5 MB
# ...

# com.example.TargetClass (N instances)
Root[RootType, rootId] -> ClassName.field -> OtherClass.array[index] -> TargetClass

# com.example.TargetClass@objectId
Root[RootType, rootId] -> ...

# com.example.DependentClass (M instances) — held by a path above
```

### Filtering

Filter is a `(HeapObjectContext) -> Boolean` predicate. `HeapObjectContext` wraps `HeapObject` + `HeapGraph` for extensibility. Current filter: `ProjectImpl` instances with `containerState = DISPOSE_COMPLETED` (leaked projects) and `EditorImpl` instances with `isReleased = true`. Alive instances are logged and skipped.

### Algorithm

1. Scan all instances to find target object IDs
2. Build reverse reference index (`Map<Long, LongArray>`, child -> parent IDs), skipping leaf types (String, primitives, wrappers, Class objects) and weak references
3. Cache reverse index as `.ri` file (gzip-compressed binary with hprof fingerprint validation)
4. For each target, walk backward from each direct parent toward GC roots:
   - Paths through other targets are treated as dead ends (cross-target filtering)
   - Paths through globally claimed nodes are treated as dead ends
   - Greedy walk: pick first unvisited parent at each step
   - Bounded backtracking: up to 10 backtracks per walk to escape dead ends
   - Merge/displacement when hitting already-known nodes
   - Shared prefix depth (8 steps from root): paths sharing a node near root are kept as separate (genuine diversity); paths sharing a node far from root are merged (shorter prefix displaces, same/longer prefix skipped)
5. After finding paths for a target, claim nodes far from root (>= SHARED_PREFIX_DEPTH) globally. Future targets treat claimed nodes as dead ends, forcing discovery of independent paths. Targets with no independent path become "dependent" (held by a path above).
6. Resolve field names and array indices from HeapGraph only for final output paths
7. Deduplicate paths differing only in array indices (signature with `[*]`)
8. Group targets sharing the same path signature; sort groups by size descending
9. Limit: 100 paths per target

### Threading

Single-threaded. Shark's `RandomAccessHprofReader` has shared IO state (okio Buffer) that is NOT thread-safe for concurrent reads. Parallel traversal would require multiple HeapGraph instances.

### Key Library

- **Shark** (`shark-graph` + `shark-hprof` v2.14 from Square's LeakCanary) — Kotlin-native hprof parser with object graph traversal API. Works with standard JVM heap dumps despite LeakCanary being Android-focused.
- Shark sources extracted in `tmp/` (gitignored) for reference.

## Build

- Build: `./gradlew build`
- Test: `./gradlew test`
- Run: `./gradlew run --args="path/to/dump.hprof"`

## Testing

YAML-based test harness: define object graphs in `src/test/resources/graphs/*.yaml`, validated against JSON Schema (`test-graph-schema.json`). Tests build synthetic reverse indexes from YAML and verify path-finding algorithm output without real heap dumps. See `test-graph-schema.md` for format spec.

## Conventions

- Language: Kotlin
- Build system: Gradle (Kotlin DSL)
- Atomic commits: each logical change in a separate commit
- When running the tool, always redirect stdout and stderr to separate files, then read them
