# Codex Review Response

## Finding 1: `computeMergeDepth` off-by-one — INCORRECT

The reviewer claims `idsFromTarget` does not include the GC root. This is wrong.

Tracing `walkToRoot` (PathFinder.kt:337-353):
1. `startParentId` is added to `idsFromTarget` at entry (line 338)
2. Each parent is added via `idsFromTarget.add(parentId)` (line 381)
3. `currentId = parentId` (line 384)
4. When `currentId in rootObjectIds` triggers (line 352), `currentId` was already added to `idsFromTarget`

So `idsFromTarget.last() == rootObjectId` — the root IS in `idsFromTarget`. The formula `stepsFromRoot = (idsFromTarget.size - 1) - idx` correctly yields 0 for the root (last element) and 1 for child-of-root. No off-by-one.

This is also confirmed by `notes.md`: "idsFromTarget in PathRecord includes the root object as last element."

## Finding 2: Comment mismatch — PARTIALLY CORRECT

The reviewer is wrong that the claiming comment (line 165) is incorrect — "idsFromTarget includes the root object as last element" is true.

However, the PathRecord doc comment DID have a stale description saying "idsFromTarget[last] is the child of the GC root object" when it's actually the root itself. This was a documentation-only issue with no functional impact (the duplicate root is handled by `if (parentId == childId) continue` in `buildPathSteps`). Fixed: updated the doc comment to say "idsFromTarget[last] is the GC root object (same as rootObjectId)."
