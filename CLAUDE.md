# leaks-collector

## Project

CLI tool that reads JVM heap dumps (hprof) and finds all hard-referenced paths from GC roots to objects matching a filter. Currently targets disposed `ProjectImpl` instances in IntelliJ-based IDEs.

### Output Format

Grouped by target with markdown headers:
```
# com.example.TargetClass@objectId (N paths)
Root[RootType, rootId] -> ClassName.field -> OtherClass.array[index] -> TargetClass@objectId

# com.example.TargetClass@objectId2 (N paths)
Root[RootType, rootId] -> ...
```

### Filtering

Filter is a `(HeapObjectContext) -> Boolean` predicate. `HeapObjectContext` wraps `HeapObject` + `HeapGraph` for extensibility. Current filter: `ProjectImpl` instances with `containerState = DISPOSE_COMPLETED` (leaked projects). Alive projects are logged and skipped.

### Algorithm

1. Scan all instances to find target object IDs
2. Build reverse reference index (`Map<Long, LongArray>`, child -> parent IDs), skipping leaf types (String, primitives, wrappers, Class objects) and weak references
3. Cache reverse index as `.ri` file (gzip-compressed binary with hprof fingerprint validation)
4. For each target, walk backward from each direct parent toward GC roots:
   - Greedy walk: pick first unvisited parent at each step
   - Bounded backtracking: up to 10 backtracks per walk to escape dead ends
   - Merge/displacement when hitting already-known nodes
   - Merge threshold (5 steps from root): paths sharing a node near root are kept as separate (genuine diversity); paths sharing a node far from root are merged (shorter prefix displaces, same/longer prefix skipped)
5. Resolve field names and array indices from HeapGraph only for final output paths
6. Deduplicate paths differing only in array indices (signature with `[*]`)
7. Limit: 100 paths per target

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
