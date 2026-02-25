# leaks-collector

CLI tool that analyzes JVM heap dumps (`.hprof` files) and finds retention paths from GC roots to leaked objects. Useful for diagnosing memory leaks in JVM applications — particularly IntelliJ-based IDEs.

## How it works

1. Opens the heap dump and scans for target objects:
   - `ProjectImpl` instances that have been fully disposed (`containerState` = `DISPOSE_COMPLETED`)
   - `EditorImpl` instances that have been released (`isReleased` = `true`)

   Alive instances are skipped and logged.
2. Builds a **reverse reference index** — for each object, which other objects reference it. Only strong references are followed; weak/soft/phantom references and leaf types (strings, primitive wrappers, primitive arrays) are skipped. The index is cached to a `.ri` file next to the heap dump for fast reruns. The cache is validated against the hprof file size and a SHA-256 fingerprint of its first 64KB.
3. For each target object, walks backward from its **direct parents** toward GC roots. Each walk follows a greedy path (first available parent at each step) with **bounded backtracking** (up to 10 retries per walk) to recover from dead ends. When a walk reaches a node already seen by a previous walk, it merges — paths that share the same retention chain are collapsed into one, keeping only the shortest route to the shared point. This avoids the combinatorial explosion that happens when thousands of objects reference the target through the same collection internals (e.g., HashMap buckets).
4. A **merge threshold** (5 steps from root) prevents merging near the root, preserving genuinely different retention chains. Paths that diverge only deep in the object graph (far from root, close to target) are considered redundant.
5. The result is a small number of **diverse retention paths** — typically one per distinct retention chain. If a target has 3000 direct parents but they all flow through the same `Disposer.ourTree` chain, you get one path, not 3000.

## Output

Targets sharing the same retention path are grouped together, sorted by group size (largest first). Each group has a markdown header with the class name and instance count:

```
# com.intellij.openapi.editor.impl.EditorImpl (28 instances)
Root[JniGlobal, 17179873296] -> PathClassLoader.classes -> ... -> EditorImpl

# com.intellij.openapi.editor.impl.EditorImpl (3 instances)
Root[JniGlobal, 17179873296] -> PathClassLoader.classes -> ... -> EditorImpl

# com.intellij.openapi.project.impl.ProjectImpl@17425364488
Root[JniGlobal, 17179873296] -> PathClassLoader.classes -> ... -> ProjectImpl
```

Each path reads left to right: GC root type and ID, then each reference step (class.field or class[index]), ending with the target class. Single-instance groups include the object ID in the header.

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
