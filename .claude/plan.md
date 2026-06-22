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
- Thread name resolution for thread-aware GC roots (JavaFrame, JniLocal, NativeStack, ThreadBlock) via ThreadObject → java.lang.Thread.name
- YAML-based test suite covering path-finding, grouping, formatting, and schema validation

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

### 1. Deprioritize trivial root paths
Paths of length 1 (Root → Target directly) are technically correct but uninformative — they just say "it's on the stack" without showing what code holds the reference. These should be deprioritized: shown last or suppressed when more informative paths exist for the same or other targets.

### 2. Disposer hierarchy annotation in paths
When a path goes through Disposer's `ObjectTree.myObject2ParentNode`, the map has parallel `key` (Disposable child) and `value` (ObjectNode wrapping the parent) arrays — same-index entries are linked. When we traverse through one side (e.g., `key[619]`), annotate the path with the corresponding disposal pair from the other side. This would show the actual disposal parent-child relationship in the output, making Disposer paths much more informative (e.g., which Disposable is the parent of the leaked object in the disposal hierarchy).

### 3. Additional merge depth anchors
Disposer anchor is implemented. More anchors can be added to `computeMergeDepth` for other infrastructure classes. See `.claude/notes.md` for the `myRootNode` variant caveat.

### 4. User-friendly CLI workflow
Provide interactive CLI that:
- Runs a command to find IDE processes (or shows all JVM processes)
- Lets user pick a process
- Runs `jmap` to capture heap dump
- Analyzes the snapshot automatically

### 5. Submit reports to JetBrains exception analyzer
Represent each leak chain as a synthetic stacktrace and submit it to JetBrains exception analyzer (ea.jetbrains.com). This would allow leak reports to be tracked alongside regular exception reports. For implementation details, study how IntelliJ IDEA's built-in error reporting mechanism formats and submits reports.

### 6. Per-class object annotations in paths
For specific classes, attach diagnostic info (field values) as comment lines between path steps in `--pretty` output. Initial implementation: string-only handling with a fallback to "Object of {className}" for non-strings. Per-class custom annotators added as we encounter useful cases (e.g., `LightVirtualFile` → name + content snippet). Annotations must be display-only — must NOT affect `pathSignature` or grouping. Truncate string values to ~100 chars with ellipsis; replace newlines with `\n` literal.

Needs a general `CharSequence` reader helper (IntelliJ uses CharSequence widely: String, StringBuilder, ImmutableCharSequence, CharArrayCharSequence, etc.) — reusable both for annotations and other field reads (e.g., IDE info extraction).

Example:
```
com.intellij.openapi.command.impl.EditorAndState.virtualFile ->
    # myName = "Dummy.txt"
    # myContent = "blablablba..."
com.intellij.testFramework.LightVirtualFile.value ->
```

### 7. Thread stack traces from the heap dump itself
HotSpot `jmap` heap dumps DO contain full thread call stacks (verified: opening a dump in YourKit shows per-thread stacks with method/line info). The data is in `HprofRecord.StackTraceRecord` (threadSerialNumber + ordered stackFrameIds) and `HprofRecord.StackFrameRecord` (methodNameStringId, methodSignatureStringId, sourceFileNameStringId, classSerialNumber, lineNumber). `GcRoot.ThreadObject` links a thread to its `stackTraceSerialNumber`; `GcRoot.JavaFrame` carries `threadSerialNumber` + `frameNumber`.

The high-level `HeapGraph` API does NOT expose these — they're only reachable via the lower-level `StreamingHprofReader` with record-tag listeners (read once, build the maps).

Resolving frame IDs to readable `class.method(File:line)` (same as YourKit): stream four record types in one pass (all live in the small top section before the heap-dump body) and join:
- `STRING_IN_UTF8` → `Map<Long,String>` (stringId → string)
- `LOAD_CLASS` → `Map<Int,Long>` (classSerialNumber → classNameStringId)
- `STACK_FRAME` → frameId → (methodNameStringId, methodSignatureStringId, sourceFileNameStringId, classSerialNumber, lineNumber)
- `STACK_TRACE` → traceSerial → (threadSerial, ordered frameIds)

Gotcha: the frame's `classSerialNumber` is NOT in Shark's index (Shark skips the serial and keys class names by object id). But the raw `LoadClassRecord` carries both `classSerialNumber` and `classNameStringId`, so streaming `LOAD_CLASS` ourselves builds the serial→name map Shark doesn't keep. JVM dumps use `/` package separators — replace with `.`.

Two payoffs:
- Print the actual frame (`method (File:line)`) on `JavaFrame` root annotations instead of just the thread name.
- Largely removes the need for the separate `jstack` step in `capture` — though `jstack -l` lock/monitor info and `-e` native frames are still NOT in the dump, so jstack isn't fully redundant.

### 8. MCP server for snapshot navigation
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
17. Add thread name to GC root display in path output
