# Plans

Ideas for later. A done idea leaves this file: its behavior goes to `spec/`, its reasons to `adr/`.

### Deprioritize trivial root paths
Paths of length 1 (Root → Target directly) are technically correct but uninformative — they just say "it's on the stack" without showing what code holds the reference. These should be deprioritized: shown last or suppressed when more informative paths exist for the same or other targets.

### Additional merge depth anchors
Disposer anchor is implemented. More anchors can be added to `computeMergeDepth` for other infrastructure classes. See the open questions of `spec/retention-paths.spec.md` for the `myRootNode` variant caveat.

### User-friendly CLI workflow
Provide interactive CLI that:
- Runs a command to find IDE processes (or shows all JVM processes)
- Lets user pick a process
- Runs `jmap` to capture heap dump
- Analyzes the snapshot automatically

### Submit reports to JetBrains exception analyzer
Represent each leak chain as a synthetic stacktrace and submit it to JetBrains exception analyzer (ea.jetbrains.com). This would allow leak reports to be tracked alongside regular exception reports. For implementation details, study how IntelliJ IDEA's built-in error reporting mechanism formats and submits reports.

### Wider CharSequence reader for annotations
Annotations read text from `String` and `ImmutableText` only. Add other CharSequence kinds that IntelliJ uses: `StringBuilder`, `CharArrayCharSequence`, other `ImmutableCharSequence` kinds. The same helper could serve other field reads, such as IDE info.

### Thread stack traces from the heap dump itself
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

### MCP server for snapshot navigation
Separate tool that loads a heap snapshot and exposes MCP tools for navigating, searching, and inspecting the object graph interactively. This feels like a separate project rather than an extension of leaks-collector.

### Key names on small user data maps
`OneElementFMap` and `PairElementsFMap` hold the `Key` directly, not in an array. Show it on their field steps. The pair map also needs the field the path went through (`value1` pairs with `key1`).
