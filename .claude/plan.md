# Implementation Plan: Leak Path Collector

## Architecture

```
Main.kt                  - CLI entry point (args: hprof path)
HeapObjectContext.kt     - Wrapper around HeapObject with extra context
PathFinder.kt            - BFS from GC roots, collects paths to matching objects
PathFormatter.kt         - Formats a path into the output string
```

## Components

### 1. HeapObjectContext
Wrapper around `HeapObject` carrying additional context to keep filter signatures clean:
```kotlin
class HeapObjectContext(
    val heapObject: HeapObject,
    val graph: HeapGraph,
    // extensible: add path, depth, etc. later
)
```

### 2. Filter
A `(HeapObjectContext) -> Boolean` predicate. For now, Main creates one that matches by class name. Easily replaceable with any lambda that introspects fields, values, etc.

### 3. PathFinder
- BFS from all GC roots (graph.gcRoots)
- Follows only strong references (skip WeakReference, SoftReference, PhantomReference, FinalizerReference — check class hierarchy)
- Parallel traversal using ExecutorService fixed thread pool (Runtime.availableProcessors() threads)
- Partition GC roots across threads
- Shared concurrent visited set (ConcurrentHashMap) to avoid duplicate work across threads
- When a matching object is found, records the full path
- Path steps track: source object, field name (or array index), target object
- Collect results into a thread-safe list

### 4. PathFormatter
Formats path as: `Root -> ClassName.fieldName -> ClassName.fieldName -> ClassName.array[0] -> TargetClassName`

### 5. Main
- Parse CLI args: hprof file path only
- Open hprof with shark-graph
- Run PathFinder with a hardcoded class name predicate
- Print each path via PathFormatter

## Commits (atomic)
1. Add shark-graph dependency
2. Add HeapObjectContext wrapper
3. Add PathFinder (BFS traversal)
4. Add PathFormatter
5. Wire up Main with CLI args
2