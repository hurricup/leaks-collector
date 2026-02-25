package com.github.hurricup.leakscollector

import io.github.oshai.kotlinlogging.KotlinLogging
import shark.GcRoot
import shark.HeapGraph
import shark.HeapObject
import shark.HeapObject.*
import kotlin.time.measureTime

private val logger = KotlinLogging.logger {}

/**
 * A step in a reference path from a GC root to a target object.
 */
sealed class PathStep {
    data class Root(val gcRoot: GcRoot, val heapObject: HeapObject) : PathStep()
    data class FieldReference(val ownerClassName: String, val fieldName: String) : PathStep()
    data class ArrayReference(val arrayClassName: String, val index: Int) : PathStep()
    data class Target(val className: String, val objectId: Long) : PathStep()
}

private val WEAK_REFERENCE_CLASSES = setOf(
    "java.lang.ref.WeakReference",
    "java.lang.ref.SoftReference",
    "java.lang.ref.PhantomReference",
    "java.lang.ref.FinalizerReference",
    "sun.misc.Cleaner",
)

private val LEAF_INSTANCE_CLASSES = setOf(
    "java.lang.String",
    "java.lang.Byte",
    "java.lang.Short",
    "java.lang.Integer",
    "java.lang.Long",
    "java.lang.Float",
    "java.lang.Double",
    "java.lang.Boolean",
    "java.lang.Character",
)

private val LEAF_ARRAY_CLASSES = setOf(
    "java.lang.String[]",
)

private const val MAX_PATHS_PER_TARGET = 100
private const val MERGE_THRESHOLD = 5

/**
 * An incoming reference: "objectId is referenced by parentId via some reference".
 */
private sealed class IncomingRef {
    abstract val parentId: Long

    data class Field(override val parentId: Long, val ownerClassName: String, val fieldName: String) : IncomingRef()
    data class Array(override val parentId: Long, val arrayClassName: String, val index: Int) : IncomingRef()
}

/**
 * A recorded path from a GC root to a target, stored as a list of refs from target toward root.
 * refsFromTarget[0] is the ref from target's direct parent to target,
 * refsFromTarget[last] is the ref from the GC root's child to the next node.
 * rootObjectId is the GC root object.
 */
private class PathRecord(
    val refsFromTarget: List<IncomingRef>,
    val rootObjectId: Long,
)

/**
 * Finds all paths from GC roots to objects matching the given predicate.
 *
 * Algorithm:
 * 1. Find all target object IDs by scanning instances
 * 2. Build a reverse reference index (child -> list of parents)
 * 3. For each target, walk backward from each direct parent to a GC root
 *    - Walks merge on already-visited nodes (with displacement if shorter)
 *    - Per-walk cycle detection
 *    - No depth limit (all strong refs lead to a root)
 *
 * Paths are emitted to [onPath] after all walks for a target complete.
 */
fun findPaths(
    graph: HeapGraph,
    predicate: (HeapObjectContext) -> Boolean,
    onPath: (List<PathStep>) -> Unit,
) {
    logger.info { "Scanning for target objects..." }
    val targetIds: List<Long>
    measureTime { targetIds = findTargetIds(graph, predicate) }
        .also { logger.info { "Found ${targetIds.size} target objects in $it" } }
    if (targetIds.isEmpty()) return

    logger.info { "Building reverse reference index..." }
    val reverseIndex: Map<Long, List<IncomingRef>>
    measureTime { reverseIndex = buildReverseIndex(graph) }
        .also { logger.info { "Reverse index built: ${reverseIndex.size} entries in $it" } }

    val gcRootIds = graph.gcRoots
        .filter { isStrongRoot(it) && graph.objectExists(it.id) }
        .groupBy { it.id }

    logger.info { "Finding paths..." }
    var totalPaths = 0
    for (targetId in targetIds) {
        val targetObj = graph.findObjectById(targetId)
        val targetClassName = classNameOf(targetObj)
        val directParents = reverseIndex[targetId]?.size ?: 0
        logger.info { "Target: $targetClassName@$targetId, direct parents: $directParents" }
        val paths = findPathsForTarget(graph, targetId, reverseIndex, gcRootIds)
        logger.info { "  Found ${paths.size} raw paths" }
        val seenSignatures = HashSet<String>()
        var pathCount = 0
        for (record in paths) {
            val fullPath = buildPathSteps(graph, record, targetClassName, targetId, gcRootIds)
                ?: continue
            val signature = pathSignature(fullPath)
            if (signature !in seenSignatures) {
                seenSignatures.add(signature)
                onPath(fullPath)
                pathCount++
                totalPaths++
            }
        }
        if (pathCount >= MAX_PATHS_PER_TARGET) {
            logger.info { "Hit $MAX_PATHS_PER_TARGET path limit for $targetClassName" }
        }
    }
    logger.info { "Found $totalPaths total paths" }
}

/**
 * For a single target, walks backward from each direct parent to find diverse paths to GC roots.
 *
 * Each walk follows the first available parent at each step (greedy DFS through reverse index).
 * When a walk reaches a node already visited by a previous walk, it merges:
 * - If the new prefix (target → shared node) is shorter AND the shared node is far enough
 *   from root (beyond MERGE_THRESHOLD), the old path is displaced by the new shorter prefix.
 * - Otherwise, the walk just stops (reuses the existing suffix).
 *
 * Returns a list of [PathRecord]s — one per unique path found.
 */
private fun findPathsForTarget(
    graph: HeapGraph,
    targetId: Long,
    reverseIndex: Map<Long, List<IncomingRef>>,
    gcRootIds: Map<Long, List<GcRoot>>,
): List<PathRecord> {
    // Check if target itself is a GC root
    if (targetId in gcRootIds) {
        return listOf(PathRecord(emptyList(), targetId))
    }

    val directRefs = reverseIndex[targetId] ?: return emptyList()

    // nodeId -> (pathIndex, stepsFromTarget) for the path that "owns" this node
    val nodeOwner = HashMap<Long, Pair<Int, Int>>()
    // All collected path records; index matches nodeOwner's pathIndex
    val paths = ArrayList<PathRecord>()

    for (directRef in directRefs) {
        if (paths.size >= MAX_PATHS_PER_TARGET) break

        val walkResult = walkToRoot(
            directRef, targetId, reverseIndex, gcRootIds, nodeOwner, paths
        )

        when (walkResult) {
            is WalkResult.FoundRoot -> {
                val pathIndex = paths.size
                val record = PathRecord(walkResult.refsFromTarget, walkResult.rootObjectId)
                paths.add(record)
                // Register all nodes in this path
                for ((i, ref) in record.refsFromTarget.withIndex()) {
                    val nodeId = ref.parentId
                    val stepsFromTarget = i + 1
                    nodeOwner[nodeId] = pathIndex to stepsFromTarget
                }
            }
            is WalkResult.Merged -> {
                val newPrefix = walkResult.refsFromTarget
                val sharedNodeId = walkResult.sharedNodeId
                val (existingPathIndex, existingStepsFromTarget) = nodeOwner.getValue(sharedNodeId)
                val existingRecord = paths[existingPathIndex]

                // Guard against stale nodeOwner entries from previous displacements
                if (existingStepsFromTarget > existingRecord.refsFromTarget.size) continue

                val existingStepsFromRoot = existingRecord.refsFromTarget.size - existingStepsFromTarget

                if (existingStepsFromRoot < MERGE_THRESHOLD) {
                    // Shared node is near root — genuine diversity, create new path
                    val oldSuffix = existingRecord.refsFromTarget.subList(
                        existingStepsFromTarget, existingRecord.refsFromTarget.size
                    )
                    val newRefs = newPrefix + oldSuffix
                    val pathIndex = paths.size
                    val newRecord = PathRecord(newRefs, existingRecord.rootObjectId)
                    paths.add(newRecord)
                    // Register only the new prefix nodes
                    for ((i, ref) in newPrefix.withIndex()) {
                        val nodeId = ref.parentId
                        val stepsFromTarget = i + 1
                        nodeOwner[nodeId] = pathIndex to stepsFromTarget
                    }
                } else if (newPrefix.size < existingStepsFromTarget) {
                    // Shared node is far from root, new prefix is shorter — displace
                    val oldSuffix = existingRecord.refsFromTarget.subList(
                        existingStepsFromTarget, existingRecord.refsFromTarget.size
                    )
                    val newRefs = newPrefix + oldSuffix
                    val newRecord = PathRecord(newRefs, existingRecord.rootObjectId)

                    logDisplacement(graph, existingRecord, newPrefix, existingStepsFromTarget, gcRootIds, targetId)

                    // Remove old prefix nodes from nodeOwner (they're no longer in this path)
                    for (i in 0 until existingStepsFromTarget) {
                        nodeOwner.remove(existingRecord.refsFromTarget[i].parentId)
                    }

                    paths[existingPathIndex] = newRecord
                    // Update nodeOwner for the new prefix nodes
                    for ((i, ref) in newPrefix.withIndex()) {
                        val nodeId = ref.parentId
                        val stepsFromTarget = i + 1
                        nodeOwner[nodeId] = existingPathIndex to stepsFromTarget
                    }
                    // Update stepsFromTarget for suffix nodes (they shifted)
                    for (i in oldSuffix.indices) {
                        val nodeId = oldSuffix[i].parentId
                        nodeOwner[nodeId] = existingPathIndex to (newPrefix.size + i + 1)
                    }
                }
                // else: shared node far from root, prefix not shorter — skip (redundant path)
            }
            is WalkResult.DeadEnd -> { /* cycle or no parents — skip */ }
        }
    }

    return paths
}

private sealed class WalkResult {
    /** Walk reached a GC root. */
    data class FoundRoot(
        val refsFromTarget: List<IncomingRef>,
        val rootObjectId: Long,
    ) : WalkResult()

    /** Walk hit a node already owned by a previous path. */
    data class Merged(
        val refsFromTarget: List<IncomingRef>,
        val sharedNodeId: Long,
    ) : WalkResult()

    /** Walk hit a cycle or dead end. */
    data object DeadEnd : WalkResult()
}

/**
 * Greedy walk backward from [startRef]'s parentId toward a GC root.
 * At each step, picks the first unvisited parent. No backtracking —
 * if all parents are visited (cycle), the walk is a dead end.
 */
private fun walkToRoot(
    startRef: IncomingRef,
    targetId: Long,
    reverseIndex: Map<Long, List<IncomingRef>>,
    gcRootIds: Map<Long, List<GcRoot>>,
    nodeOwner: Map<Long, Pair<Int, Int>>,
    @Suppress("UNUSED_PARAMETER") paths: List<PathRecord>,
): WalkResult {
    val refsFromTarget = ArrayList<IncomingRef>()
    refsFromTarget.add(startRef)

    val visitedInWalk = HashSet<Long>()
    visitedInWalk.add(targetId)
    visitedInWalk.add(startRef.parentId)

    var currentId = startRef.parentId

    while (true) {
        // Check if current node is a GC root
        if (currentId in gcRootIds) {
            return WalkResult.FoundRoot(refsFromTarget.toList(), currentId)
        }

        // Check if current node is already owned by another path
        if (currentId in nodeOwner) {
            return WalkResult.Merged(refsFromTarget.toList(), currentId)
        }

        // Pick first unvisited parent (greedy, no backtracking)
        val refs = reverseIndex[currentId]
        val nextRef = refs?.firstOrNull { it.parentId !in visitedInWalk }

        if (nextRef != null) {
            refsFromTarget.add(nextRef)
            visitedInWalk.add(nextRef.parentId)
            currentId = nextRef.parentId
        } else {
            // All parents visited (cycle) or no parents — dead end
            return WalkResult.DeadEnd
        }
    }
}

private fun logDisplacement(
    graph: HeapGraph,
    oldRecord: PathRecord,
    newPrefix: List<IncomingRef>,
    oldPrefixLength: Int,
    gcRootIds: Map<Long, List<GcRoot>>,
    targetId: Long,
) {
    val targetClassName = classNameOf(graph.findObjectById(targetId))
    val oldPath = buildPathSteps(graph, oldRecord, targetClassName, targetId, gcRootIds)
    val oldPrefixRefs = oldRecord.refsFromTarget.subList(0, oldPrefixLength)
    logger.debug {
        buildString {
            appendLine("PATH DISPLACEMENT for $targetClassName@$targetId")
            appendLine("  Old path: ${oldPath?.let { formatPathForLog(it) } ?: "?"}")
            appendLine("  Old prefix (${oldPrefixRefs.size} steps): ${refsToString(oldPrefixRefs)}")
            appendLine("  New prefix (${newPrefix.size} steps): ${refsToString(newPrefix)}")
        }
    }
}

private fun refsToString(refs: List<IncomingRef>): String = refs.joinToString(" -> ") { ref ->
    when (ref) {
        is IncomingRef.Field -> "${ref.ownerClassName}.${ref.fieldName}"
        is IncomingRef.Array -> "${ref.arrayClassName}[${ref.index}]"
    }
}

private fun formatPathForLog(path: List<PathStep>): String = path.joinToString(" -> ") { step ->
    when (step) {
        is PathStep.Root -> "Root[${gcRootTypeName(step.gcRoot)}]"
        is PathStep.FieldReference -> "${step.ownerClassName}.${step.fieldName}"
        is PathStep.ArrayReference -> "${step.arrayClassName}[${step.index}]"
        is PathStep.Target -> step.className
    }
}

/**
 * Converts a [PathRecord] to a list of [PathStep]s in root-to-target order.
 */
private fun buildPathSteps(
    graph: HeapGraph,
    record: PathRecord,
    targetClassName: String,
    targetId: Long,
    gcRootIds: Map<Long, List<GcRoot>>,
): List<PathStep>? {
    val root = gcRootIds[record.rootObjectId]?.firstOrNull() ?: return null
    val rootObj = graph.findObjectById(record.rootObjectId)
    val steps = ArrayList<PathStep>(record.refsFromTarget.size + 2)
    steps.add(PathStep.Root(root, rootObj))

    // refsFromTarget is target-to-root order; we need root-to-target
    for (i in record.refsFromTarget.indices.reversed()) {
        val ref = record.refsFromTarget[i]
        steps.add(
            when (ref) {
                is IncomingRef.Field -> PathStep.FieldReference(ref.ownerClassName, ref.fieldName)
                is IncomingRef.Array -> PathStep.ArrayReference(ref.arrayClassName, ref.index)
            }
        )
    }

    steps.add(PathStep.Target(targetClassName, targetId))
    return steps
}

/**
 * Finds all object IDs matching the predicate.
 */
private fun findTargetIds(
    graph: HeapGraph,
    predicate: (HeapObjectContext) -> Boolean,
): List<Long> {
    val targets = mutableListOf<Long>()
    for (instance in graph.instances) {
        if (predicate(HeapObjectContext(instance, graph))) {
            targets.add(instance.objectId)
        }
    }
    return targets
}

/**
 * Builds a reverse reference index by walking forward from strong GC roots.
 * Only objects reachable via strong references are indexed.
 * Leaf-type objects (primitives, Strings, wrappers, Class objects) are skipped
 * both as parents and as children.
 */
private fun buildReverseIndex(graph: HeapGraph): Map<Long, List<IncomingRef>> {
    val skipChildIds = collectLeafObjectIds(graph)
    val index = HashMap<Long, MutableList<IncomingRef>>()
    val visited = HashSet<Long>()
    val queue = ArrayDeque<Long>()

    for (root in graph.gcRoots) {
        if (!isStrongRoot(root) || !graph.objectExists(root.id)) continue
        if (visited.add(root.id)) {
            queue.addLast(root.id)
        }
    }

    while (queue.isNotEmpty()) {
        val objectId = queue.removeFirst()
        val obj = graph.findObjectById(objectId)

        when (obj) {
            is HeapInstance -> {
                if (obj.instanceClassName in LEAF_INSTANCE_CLASSES) continue
                if (isWeakReferenceType(obj)) continue
                val className = obj.instanceClassName
                obj.readFields().forEach { field ->
                    if (field.name.startsWith('<')) return@forEach
                    val childId = field.value.asNonNullObjectId ?: return@forEach
                    if (skipChildIds.binarySearch(childId) >= 0) return@forEach
                    if (!graph.objectExists(childId)) return@forEach
                    index.getOrPut(childId) { mutableListOf() }
                        .add(IncomingRef.Field(objectId, className, field.name))
                    if (visited.add(childId)) {
                        queue.addLast(childId)
                    }
                }
            }
            is HeapObjectArray -> {
                if (obj.arrayClassName in LEAF_ARRAY_CLASSES) continue
                val className = obj.arrayClassName
                obj.readRecord().elementIds.forEachIndexed { idx, elementId ->
                    if (elementId != 0L && skipChildIds.binarySearch(elementId) < 0
                        && graph.objectExists(elementId)) {
                        index.getOrPut(elementId) { mutableListOf() }
                            .add(IncomingRef.Array(objectId, className, idx))
                        if (visited.add(elementId)) {
                            queue.addLast(elementId)
                        }
                    }
                }
            }
            is HeapClass -> {
                val className = obj.name
                obj.readStaticFields().forEach { field ->
                    if (field.name.startsWith('<')) return@forEach
                    val childId = field.value.asNonNullObjectId ?: return@forEach
                    if (skipChildIds.binarySearch(childId) >= 0) return@forEach
                    if (!graph.objectExists(childId)) return@forEach
                    index.getOrPut(childId) { mutableListOf() }
                        .add(IncomingRef.Field(objectId, className, field.name))
                    if (visited.add(childId)) {
                        queue.addLast(childId)
                    }
                }
            }
            is HeapPrimitiveArray -> { /* no object references */ }
        }
    }

    return index
}

/**
 * Collects IDs of leaf-type objects that should be excluded from the reverse index:
 * primitive arrays, class objects, Strings, and primitive wrapper instances.
 */
private fun collectLeafObjectIds(graph: HeapGraph): LongArray {
    val ids = ArrayList<Long>()
    for (arr in graph.primitiveArrays) ids.add(arr.objectId)
    for (instance in graph.instances) {
        if (instance.instanceClassName in LEAF_INSTANCE_CLASSES) {
            ids.add(instance.objectId)
        }
    }
    return ids.toLongArray().also { it.sort() }
}

private fun isWeakReferenceType(instance: HeapInstance): Boolean {
    return instance.instanceClass.classHierarchy.any { it.name in WEAK_REFERENCE_CLASSES }
}

private fun isStrongRoot(root: GcRoot): Boolean = when (root) {
    is GcRoot.JniGlobal -> true
    is GcRoot.JniLocal -> true
    is GcRoot.JavaFrame -> true
    is GcRoot.NativeStack -> true
    is GcRoot.StickyClass -> false
    is GcRoot.ThreadBlock -> true
    is GcRoot.MonitorUsed -> true
    is GcRoot.ThreadObject -> true
    is GcRoot.JniMonitor -> true
    is GcRoot.ReferenceCleanup -> true
    is GcRoot.VmInternal -> true
    is GcRoot.Finalizing -> false
    is GcRoot.Debugger -> false
    is GcRoot.Unreachable -> false
    is GcRoot.InternedString -> false
    is GcRoot.Unknown -> false
}

fun gcRootTypeName(root: GcRoot): String = root::class.simpleName ?: "Unknown"

private fun classNameOf(obj: HeapObject): String = when (obj) {
    is HeapInstance -> obj.instanceClassName
    is HeapClass -> obj.name
    is HeapObjectArray -> obj.arrayClassName
    is HeapPrimitiveArray -> obj.arrayClassName
}

private fun pathSignature(path: List<PathStep>): String = path.joinToString(" -> ") { step ->
    when (step) {
        is PathStep.Root -> "Root[${gcRootTypeName(step.gcRoot)}]"
        is PathStep.FieldReference -> "${step.ownerClassName}.${step.fieldName}"
        is PathStep.ArrayReference -> "${step.arrayClassName}[*]"
        is PathStep.Target -> step.className
    }
}
