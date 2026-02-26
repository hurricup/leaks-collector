# leaks-collector

CLI tool that analyzes JVM heap dumps (`.hprof` files) and finds retention paths from GC roots to leaked objects. Useful for diagnosing memory leaks in JVM applications — particularly IntelliJ-based IDEs.

## How it works

1. Opens the heap dump and scans for target objects:
   - `ProjectImpl` instances that have been fully disposed (`containerState` = `DISPOSE_COMPLETED`)
   - `EditorImpl` instances that have been released (`isReleased` = `true`)

   Alive instances are skipped and logged.
2. Builds a **reverse reference index** — for each object, which other objects reference it. Only strong references are followed; weak/soft/phantom references and leaf types (strings, primitive wrappers, primitive arrays) are skipped. The index is cached to a `.ri` file next to the heap dump for fast reruns. The cache is validated against the hprof file size and a SHA-256 fingerprint of its first 64KB.
3. For each target object, walks backward from its **direct parents** toward GC roots. Paths through other targets and globally claimed nodes are skipped. Each walk follows a greedy path (first available parent at each step) with **bounded backtracking** (up to 10 retries per walk) to recover from dead ends. When a walk reaches a node already seen by a previous walk, it merges — paths that share the same retention chain are collapsed into one, keeping only the shortest route to the shared point. This avoids the combinatorial explosion that happens when thousands of objects reference the target through the same collection internals (e.g., HashMap buckets).
4. A **shared prefix depth** (8 steps from root) prevents merging near the root, preserving genuinely different retention chains. Paths that diverge only deep in the object graph (far from root, close to target) are considered redundant.
5. After finding paths for a target, nodes far from root are **claimed globally**. Future targets treat claimed nodes as dead ends, forcing discovery of independent retention paths. Targets that find no independent path are reported as **dependent** ("held by a path above").
6. The result is a small number of **diverse retention paths** — typically one per distinct retention chain. If a target has 3000 direct parents but they all flow through the same `Disposer.ourTree` chain, you get one path, not 3000.

## Output

Output starts with a metadata header, followed by targets sharing the same retention path grouped together and sorted by group size (largest first). Targets with no independent path are reported as dependent:

```
# leaks-collector 2026.2
# File: /path/to/heap.hprof
# Size: 1686.5 MB
# Heap dump timestamp: 2026-02-25 17:19:25 AMT
# Hprof version: JAVA PROFILE 1.0.2
# JVM pointer size: 64-bit
# Objects: 18787340 (187258 classes, 12374340 instances, ...)
# GC roots: 11396

# com.intellij.openapi.project.impl.ProjectImpl@18131688384
Root[JniGlobal, 17179869184] -> PathClassLoader.classes -> ... -> ProjectImpl@18131688384

# com.intellij.openapi.project.impl.ProjectImpl@18141078080
Root[JniGlobal, 17179869184] -> PathClassLoader.classes -> ... -> ProjectImpl@18141078080

# com.intellij.openapi.editor.impl.EditorImpl (41 instances) — held by a path above
```

Each path reads left to right: GC root type and ID, then each reference step (class.field or class[index]), ending with the target class. Single-instance groups include the object ID in the header. Multi-instance groups show the count.

Logs (timings, target info, path counts) go to stderr.

## Running

### From Gradle

```bash
./gradlew run --args="/path/to/heap.hprof"
```

### Standalone

Build a distribution:

```bash
./gradlew installDist
```

Then run:

```bash
./build/install/leaks-collector/bin/leaks-collector /path/to/heap.hprof
```

### Saving output

```bash
./gradlew run --args="/path/to/heap.hprof" > paths.txt 2> log.txt
```

## Caching

On first run, the reverse index is built from the heap dump (takes ~2-3 minutes for large dumps) and saved as a compressed `.ri` file next to the hprof. Subsequent runs load the cache in ~5 seconds. Delete the `.ri` file to force a rebuild.

## Building

```bash
./gradlew build
```

Requires JDK 21+.
