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

### Algorithm

1. Scan all instances to find target object IDs
2. Build reverse reference index (child -> list of parents) over entire heap
3. For each target, walk backwards to GC roots with per-path cycle detection
4. Stream paths to stdout as found; limits: 50 max depth, 100 paths per target

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
