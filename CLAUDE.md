# leaks-collector

## Project

CLI tool that reads JVM heap dumps (hprof) and finds all hard-referenced paths from GC roots to objects matching a filter.

### Output Format

One line per path to stdout:
```
Root -> SomeObjectField.field -> OtherObject.otherfield -> OtherObject.somearray[index] -> TargetObject
```

### Filtering

Object filtering is extendable via filter objects. Current filter: match by class name. Designed so filters can later introspect object properties, not just names.

### Key Library

- **Shark** (`shark-graph` from Square's LeakCanary) — Kotlin-native hprof parser with object graph traversal API. Works with standard JVM heap dumps despite LeakCanary being Android-focused.

## Build

- Build: `./gradlew build`
- Test: `./gradlew test`
- Run: `./gradlew run`

## Conventions

- Language: Kotlin
- Build system: Gradle (Kotlin DSL)
- Atomic commits: each logical change in a separate commit
