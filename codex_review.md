# Codex Review

## Findings

### 1. High: `computeMergeDepth` uses an off-by-one distance from root
- Location: `src/main/kotlin/com/github/hurricup/leakscollector/PathFinder.kt:216-221`
- Problem: `idsFromTarget` does not include the GC root, but `stepsFromRoot` is computed as `(idsFromTarget.size - 1) - idx`, which yields `0` for the child-of-root node. The correct distance from root should be `1` for the child-of-root node.
- Impact: The dynamic merge depth (e.g., Disposer anchor) is systematically one step smaller than intended, which can collapse paths that should remain distinct.

### 2. Medium: Comment mismatch suggests logic drift
- Location: `src/main/kotlin/com/github/hurricup/leakscollector/PathFinder.kt:165-169`
- Problem: Comment claims `idsFromTarget` includes the root object as last element, which is false (it ends at the child of root).
- Impact: Not a functional bug by itself, but it obscures reasoning about merge depth and claimed-node behavior.
pu