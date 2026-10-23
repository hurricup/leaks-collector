---
name: Retention Paths
description: Requirements for finding the reference chains that keep each target alive.
style: plain-1
targets:
  - ../src/main/kotlin/com/github/hurricup/leakscollector/PathFinder.kt
---

# Retention Paths

Status: Draft
Date: 2026-10-13

## Summary

A retention path is a chain of strong references from a GC root to a target. The tool finds a small set of
different paths per target, so that each distinct cause of a leak shows once. It does not try to list every
path. A target that is held only through the path of another target is reported as dependent on it.

## Goals

- Show each distinct retention chain once, even when a target has thousands of parents through one collection.
- Show the chains that a developer can act on. See [ADR 0001](../adr/0001-only-actionable-gc-roots-start-a-path.md).
- Show a target that is held by another target's chain as dependent, not as a copy of that chain.

## Non-goals

- A complete list of all paths to a target.
- The shortest path to a target.

## Requirements

### References and roots

- A path must follow only strong references.
- A path must not go through the `referent` of any subclass of `java.lang.ref.Reference`.
  [@test] ../src/test/resources/graphs/weak-reference-referent.yaml
  [@test] ../src/test/resources/graphs/finalizer-referent.yaml
- A path must start at a GC root of one of these types: `JniGlobal`, `JniLocal`, `JavaFrame`, `NativeStack`,
  `ThreadBlock`, `MonitorUsed`, `ThreadObject`, `JniMonitor`, `ReferenceCleanup`, `VmInternal`.
  See [ADR 0001](../adr/0001-only-actionable-gc-roots-start-a-path.md).
  [@test] ../src/test/resources/graphs/multiple-roots.yaml
- A path must not start at a `StickyClass`, `Finalizing`, `Debugger`, `Unreachable`, `InternedString` or
  `Unknown` root.
- A target that is a GC root itself must get a path with the root only.
  [@test] ../src/test/resources/graphs/target-is-root.yaml
- A path must not go through a `String`, a boxed primitive, a primitive array or a `String[]`.

### Walk

- The tool must try a walk from each direct parent of a target.
  [@test] ../src/test/resources/graphs/simple-chain.yaml
  [@test] ../src/test/resources/graphs/array-reference.yaml
- A walk that meets a node with no way to a root must step back and try another parent.
  [@test] ../src/test/resources/graphs/dead-end-with-backtrack.yaml
- A walk must step back at most 10 times. After that it gives up.
- A walk must not loop on a reference cycle.
  [@test] ../src/test/resources/graphs/cycle-avoidance.yaml
- A path must not go through another target.
  [@test] ../src/test/resources/graphs/cross-target-dead-end.yaml
  [@test] ../src/test/resources/graphs/cross-target-partial.yaml
- A target must get at most 100 paths.

### Merge of paths of one target

- Each path has a merge depth: a number of steps from its root.
- When a walk meets a node of an earlier path closer to the root than the merge depth, both paths must stay.
  [@test] ../src/test/resources/graphs/merge-near-root.yaml
- When a walk meets a node of an earlier path at or beyond the merge depth, only one path must stay.
- In that case the path with the shorter part between the target and the shared node must stay.
  [@test] ../src/test/resources/graphs/merge-far-displacement.yaml
- When the part of the new walk is not shorter, the earlier path must stay.
  [@test] ../src/test/resources/graphs/merge-far-from-root-skip.yaml

### Merge depth

- The default merge depth must be 3. See [ADR 0004](../adr/0004-known-infrastructure-classes-move-the-merge-boundary.md).
- A path through `Disposer` must get a merge depth of the `Disposer` step from root plus 4.
  [@test] ../src/test/resources/graphs/disposer-merge-depth.yaml
- A path through `PathClassLoader` must get a merge depth of the `PathClassLoader` step from root plus 3.
  [@test] ../src/test/resources/graphs/pathclassloader-merge-depth.yaml
- When several rules apply, the largest merge depth must win.
  [@test] ../src/test/resources/graphs/disposer-overrides-pathclassloader.yaml
- A path made by a merge must keep the merge depth of the earlier path.

### Claiming across targets

- The tool must process targets in heap scan order.
- After a target, each node of its paths at or beyond the merge depth must become claimed.
- A later target's walk must not go through a claimed node.
  [@test] ../src/test/resources/graphs/two-pairs-shared-paths.yaml
  [@test] ../src/test/resources/graphs/cascading-fallback.yaml
- A target with direct parents but no path must be reported as dependent: held by a path above.
  [@test] ../src/test/resources/graphs/shared-prefix-four-targets.yaml
  [@test] ../src/test/resources/graphs/cascading-with-dependent.yaml
- A target with no strong direct parent must be reported as not strongly reachable.
  [@test] ../src/test/resources/graphs/no-path.yaml

### Grouping

- The signature of a path is its steps with array indices as `[*]` and the root without its id.
- Paths of one target with the same signature must show once.
- Targets whose paths have the same signature must form one group.
  [@test] ../src/test/kotlin/com/github/hurricup/leakscollector/GroupingTest.kt
- Groups must be sorted by number of targets, largest first.
- Annotations must not change a signature. See the [path annotations spec](./path-annotations.spec.md).

## Error Handling

- A reference that the tool cannot name must show as the field `?`, with a warning in the log.

## Testing / Local Run

- Algorithm tests run on synthetic YAML graphs, not on real heap dumps. See
  [ADR 0005](../adr/0005-algorithm-tests-run-on-synthetic-yaml-graphs.md). The format is in
  `src/test/resources/test-graph-schema.md`.
  [@test] ../src/test/kotlin/com/github/hurricup/leakscollector/PathFinderTest.kt

## Open Questions / Risks

- The merge boundary is a fixed offset from an anchor class. It may not fit every case, and there is no better
  design yet.
- The `myRootNode` variant of `Disposer` paths has 5 infrastructure steps after `Disposer`, not 3. The offset of 4
  is a bit short for it.
- The `dead-end-with-backtrack` graph does not force a step back. It may pass when stepping back is broken.
- The `cycle-avoidance` graph does not force the walk into the cycle. It may pass when cycle handling is broken.
