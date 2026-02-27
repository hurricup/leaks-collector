package com.github.hurricup.leakscollector

import io.github.oshai.kotlinlogging.KotlinLogging
import shark.GcRoot
import shark.HeapGraph
import shark.HeapObject
import shark.HeapObject.*
import java.io.File
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
/** Default merge depth: nodes within this many steps from root are considered shared infrastructure. */
private const val DEFAULT_MERGE_DEPTH = 3
private const val DISPOSER_CLASS = "com.intellij.openapi.util.Disposer"

/**
 * A recorded path as a list of object IDs from target toward root.
 * idsFromTarget[0] is the direct parent of the target,
 * idsFromTarget[last] is the GC root object (same as rootObjectId).
 */
internal class PathRecord(
    val idsFromTarget: List<Long>,
    val rootObjectId: Long,
    val mergeDepth: Int,
)

/**
 * Finds all paths from GC roots to objects matching the given predicate.
 *
 * Algorithm:
 * 1. Find all target object IDs by scanning instances
 * 2. Build a reverse reference index (child -> parent IDs)
 * 3. For each target, walk backward from each direct parent to a GC root
 *    - Walks merge on already-visited nodes (with displacement if shorter)
 *    - Per-walk cycle detection
 *    - No depth limit (all strong refs lead to a root)
 *
 * Paths are emitted to [onPath] after all walks for a target complete.
 * Edge metadata (field names, array indices) is resolved from the HeapGraph only for final paths.
 */
/**
 * A group of targets sharing the same path signature.
 */
class PathGroup<T>(
    val signature: String,
    val examplePath: T,
    val targetIds: List<Long>,
)

/**
 * Groups paths by signature. Each entry is (targetId, signature, path).
 * Paths with the same signature across different targets are collapsed into one group.
 */
internal fun <T> groupPathsBySignature(
    entries: List<Triple<Long, String, T>>,
): List<PathGroup<T>> {
    val groups = LinkedHashMap<String, Pair<T, ArrayList<Long>>>()
    for ((targetId, signature, path) in entries) {
        val group = groups.getOrPut(signature) { path to ArrayList() }
        group.second.add(targetId)
    }
    return groups.map { (sig, pair) -> PathGroup(sig, pair.first, pair.second) }
}

data class DependentTargets(
    val className: String,
    val targetIds: List<Long>,
)

fun findPaths(
    graph: HeapGraph,
    hprofFile: File,
    predicate: (HeapObjectContext) -> Boolean,
    onGroup: (PathGroup<List<PathStep>>) -> Unit,
    onDependentTargets: (DependentTargets) -> Unit = {},
) {
    logger.info { "Scanning for target objects..." }
    var targetIds: List<Long> = emptyList()
    measureTime { targetIds = findTargetIds(graph, predicate) }
        .also { logger.info { "Found ${targetIds.size} target objects in $it" } }
    if (targetIds.isEmpty()) return

    val cacheFile = File(hprofFile.parentFile, "${hprofFile.name}.ri")
    val reverseIndex: Map<Long, LongArray> = run {
        if (cacheFile.exists()) {
            logger.info { "Loading reverse index from cache: ${cacheFile.name}" }
            var cached: Map<Long, LongArray>? = null
            measureTime { cached = loadReverseIndex(cacheFile, hprofFile) }
                .also { if (cached != null) logger.info { "Reverse index loaded: ${cached!!.size} entries in $it" } }
            cached?.let { return@run it }
        }
        var built: Map<Long, LongArray>? = null
        logger.info { "Building reverse reference index..." }
        measureTime { built = buildReverseIndex(graph) }
            .also { logger.info { "Reverse index built: ${built!!.size} entries in $it" } }
        logger.info { "Saving reverse index cache..." }
        measureTime { saveReverseIndex(built!!, cacheFile, hprofFile) }
            .also { logger.info { "Cache saved in $it" } }
        built!!
    }

    val gcRootIds = graph.gcRoots
        .filter { isStrongRoot(it) && graph.objectExists(it.id) }
        .groupBy { it.id }

    logger.info { "Finding paths..." }

    val targetIdSet = targetIds.toHashSet()
    val claimedNodes = HashSet<Long>()
    val allEntries = ArrayList<Triple<Long, String, List<PathStep>>>()
    val dependentTargetIds = ArrayList<Pair<Long, String>>() // targetId to className
    val classNameResolver: (Long) -> String? = { id -> classNameOf(graph.findObjectById(id)) }

    for (targetId in targetIds) {
        val targetObj = graph.findObjectById(targetId)
        val targetClassName = classNameOf(targetObj)
        val directParents = reverseIndex[targetId]?.size ?: 0
        logger.info { "Target: $targetClassName@$targetId, direct parents: $directParents" }
        val paths = findPathsForTarget(targetId, reverseIndex, gcRootIds.keys, targetIdSet, claimedNodes, classNameOf = classNameResolver)
        logger.info { "  Found ${paths.size} raw paths" }

        if (paths.isEmpty()) {
            dependentTargetIds.add(targetId to targetClassName)
            continue
        }

        // Claim nodes far from root for future targets.
        // idsFromTarget includes the root object as last element — exclude it from counting.
        for (record in paths) {
            val ids = record.idsFromTarget
            val stepsExcludingRoot = ids.size - 1
            val farFromRootCount = maxOf(0, stepsExcludingRoot - record.mergeDepth + 1)
            for (i in 0 until farFromRootCount) {
                claimedNodes.add(ids[i])
            }
        }

        val seenSignatures = HashSet<String>()
        for (record in paths) {
            val fullPath = buildPathSteps(graph, record, targetClassName, targetId, gcRootIds)
                ?: continue
            val signature = pathSignature(fullPath)
            if (signature !in seenSignatures) {
                seenSignatures.add(signature)
                allEntries.add(Triple(targetId, signature, fullPath))
            }
        }
        if (seenSignatures.size >= MAX_PATHS_PER_TARGET) {
            logger.info { "Hit $MAX_PATHS_PER_TARGET path limit for $targetClassName" }
        }
    }

    val groups = groupPathsBySignature(allEntries).sortedByDescending { it.targetIds.size }
    logger.info { "Found ${groups.size} unique path groups for ${targetIds.size} targets" }
    for (group in groups) {
        onGroup(group)
    }

    if (dependentTargetIds.isNotEmpty()) {
        // Group dependent targets by class name
        val byClass = dependentTargetIds.groupBy({ it.second }, { it.first })
        logger.info { "Found ${dependentTargetIds.size} dependent targets (held by paths above)" }
        for ((className, ids) in byClass) {
            onDependentTargets(DependentTargets(className, ids))
        }
    }
}

/**
 * Computes the merge depth for a newly found path.
 * Searches for known infrastructure anchors (e.g., Disposer) and adjusts the merge boundary accordingly.
 */
private fun computeMergeDepth(
    idsFromTarget: List<Long>,
    defaultMergeDepth: Int,
    classNameOf: ((Long) -> String?)?,
): Int {
    if (classNameOf == null) return defaultMergeDepth
    for ((idx, id) in idsFromTarget.withIndex()) {
        val className = classNameOf(id) ?: continue
        if (className == DISPOSER_CLASS) {
            val stepsFromRoot = (idsFromTarget.size - 1) - idx
            return stepsFromRoot + 4
        }
    }
    return defaultMergeDepth
}

/**
 * For a single target, walks backward from each direct parent to find diverse paths to GC roots.
 */
internal fun findPathsForTarget(
    targetId: Long,
    reverseIndex: Map<Long, LongArray>,
    rootObjectIds: Set<Long>,
    allTargetIds: Set<Long> = emptySet(),
    claimedNodes: Set<Long> = emptySet(),
    defaultMergeDepth: Int = DEFAULT_MERGE_DEPTH,
    classNameOf: ((Long) -> String?)? = null,
): List<PathRecord> {
    if (targetId in rootObjectIds) {
        return listOf(PathRecord(emptyList(), targetId, defaultMergeDepth))
    }

    val directParents = reverseIndex[targetId] ?: return emptyList()

    // nodeId -> (pathIndex, stepsFromTarget)
    val nodeOwner = HashMap<Long, Pair<Int, Int>>()
    val paths = ArrayList<PathRecord>()

    for (parentId in directParents) {
        if (paths.size >= MAX_PATHS_PER_TARGET) break
        if (parentId in allTargetIds) continue
        if (parentId in claimedNodes) continue

        val walkResult = walkToRoot(parentId, targetId, reverseIndex, rootObjectIds, nodeOwner, allTargetIds, claimedNodes)

        when (walkResult) {
            is WalkResult.FoundRoot -> {
                val pathIndex = paths.size
                val mergeDepth = computeMergeDepth(walkResult.idsFromTarget, defaultMergeDepth, classNameOf)
                val record = PathRecord(walkResult.idsFromTarget, walkResult.rootObjectId, mergeDepth)
                paths.add(record)
                for ((i, nodeId) in record.idsFromTarget.withIndex()) {
                    nodeOwner[nodeId] = pathIndex to (i + 1)
                }
            }
            is WalkResult.Merged -> {
                val newPrefix = walkResult.idsFromTarget
                val sharedNodeId = walkResult.sharedNodeId
                val (existingPathIndex, existingStepsFromTarget) = nodeOwner.getValue(sharedNodeId)
                val existingRecord = paths[existingPathIndex]

                if (existingStepsFromTarget > existingRecord.idsFromTarget.size) continue

                val existingStepsFromRoot = existingRecord.idsFromTarget.size - existingStepsFromTarget

                if (existingStepsFromRoot < existingRecord.mergeDepth) {
                    // Shared node is near root — genuine diversity, create new path
                    val oldSuffix = existingRecord.idsFromTarget.subList(
                        existingStepsFromTarget, existingRecord.idsFromTarget.size
                    )
                    val pathIndex = paths.size
                    paths.add(PathRecord(newPrefix + oldSuffix, existingRecord.rootObjectId, existingRecord.mergeDepth))
                    for ((i, nodeId) in newPrefix.withIndex()) {
                        nodeOwner[nodeId] = pathIndex to (i + 1)
                    }
                } else if (newPrefix.size < existingStepsFromTarget) {
                    // Shared node is far from root, new prefix is shorter — displace
                    val oldSuffix = existingRecord.idsFromTarget.subList(
                        existingStepsFromTarget, existingRecord.idsFromTarget.size
                    )
                    val newIds = newPrefix + oldSuffix
                    val newRecord = PathRecord(newIds, existingRecord.rootObjectId, existingRecord.mergeDepth)

                    // Remove old prefix nodes from nodeOwner
                    for (i in 0 until existingStepsFromTarget) {
                        nodeOwner.remove(existingRecord.idsFromTarget[i])
                    }

                    paths[existingPathIndex] = newRecord
                    for ((i, nodeId) in newPrefix.withIndex()) {
                        nodeOwner[nodeId] = existingPathIndex to (i + 1)
                    }
                    for (i in oldSuffix.indices) {
                        nodeOwner[oldSuffix[i]] = existingPathIndex to (newPrefix.size + i + 1)
                    }
                }
                // else: shared node far from root, prefix not shorter — skip
            }
            is WalkResult.DeadEnd -> { /* cycle or no parents — skip */ }
        }
    }

    return paths
}

private sealed class WalkResult {
    data class FoundRoot(val idsFromTarget: List<Long>, val rootObjectId: Long) : WalkResult()
    data class Merged(val idsFromTarget: List<Long>, val sharedNodeId: Long) : WalkResult()
    data object DeadEnd : WalkResult()
}

private const val MAX_BACKTRACKS = 10

/**
 * Greedy walk backward from [startParentId] toward a GC root.
 * At each step, picks the first unvisited parent. On dead ends,
 * backtracks up to [MAX_BACKTRACKS] times to try alternative parents.
 */
private fun walkToRoot(
    startParentId: Long,
    targetId: Long,
    reverseIndex: Map<Long, LongArray>,
    rootObjectIds: Set<Long>,
    nodeOwner: Map<Long, Pair<Int, Int>>,
    allTargetIds: Set<Long> = emptySet(),
    claimedNodes: Set<Long> = emptySet(),
): WalkResult {
    val idsFromTarget = ArrayList<Long>()
    idsFromTarget.add(startParentId)

    val visitedInWalk = HashSet<Long>()
    visitedInWalk.add(targetId)
    visitedInWalk.add(startParentId)

    // Track which parent index we've tried at each position in idsFromTarget
    val parentIndices = ArrayList<Int>()
    parentIndices.add(0)

    var currentId = startParentId
    var backtracksRemaining = MAX_BACKTRACKS

    while (true) {
        if (currentId in rootObjectIds) {
            return WalkResult.FoundRoot(idsFromTarget.toList(), currentId)
        }

        if (currentId in nodeOwner) {
            return WalkResult.Merged(idsFromTarget.toList(), currentId)
        }

        if (currentId in claimedNodes) {
            // Node already claimed by a previous target's path — treat as dead end
            if (idsFromTarget.size <= 1 || backtracksRemaining <= 0) {
                return WalkResult.DeadEnd
            }
            backtracksRemaining--
            idsFromTarget.removeLast()
            parentIndices.removeLast()
            currentId = idsFromTarget.last()
            continue
        }

        val parents = reverseIndex[currentId]
        val startIdx = parentIndices.last()
        val nextParent = parents?.let { p ->
            (startIdx until p.size).firstOrNull { p[it] !in visitedInWalk && p[it] !in allTargetIds && p[it] !in claimedNodes }
        }

        if (nextParent != null) {
            val parentId = parents!![nextParent]
            parentIndices[parentIndices.lastIndex] = nextParent + 1
            idsFromTarget.add(parentId)
            parentIndices.add(0)
            visitedInWalk.add(parentId)
            currentId = parentId
        } else {
            // Dead end at current node — backtrack if budget allows
            if (idsFromTarget.size <= 1 || backtracksRemaining <= 0) {
                return WalkResult.DeadEnd
            }
            backtracksRemaining--
            idsFromTarget.removeLast()
            parentIndices.removeLast()
            currentId = idsFromTarget.last()
        }
    }
}

/**
 * Converts a [PathRecord] to a list of [PathStep]s in root-to-target order.
 * Resolves edge metadata (field names, array indices) from the HeapGraph.
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
    val steps = ArrayList<PathStep>(record.idsFromTarget.size + 2)
    steps.add(PathStep.Root(root, rootObj))

    // Build the full ID sequence: root, ids (reversed), target
    val ids = ArrayList<Long>(record.idsFromTarget.size + 2)
    ids.add(record.rootObjectId)
    for (i in record.idsFromTarget.indices.reversed()) {
        ids.add(record.idsFromTarget[i])
    }
    ids.add(targetId)

    // Resolve each edge: ids[i] -> ids[i+1], skipping duplicate root
    for (i in 0 until ids.size - 1) {
        val parentId = ids[i]
        val childId = ids[i + 1]
        if (parentId == childId) continue
        steps.add(resolveEdge(graph, parentId, childId))
    }

    steps.add(PathStep.Target(targetClassName, targetId))
    return steps
}

/**
 * Finds the field or array element in [parentId] that references [childId].
 */
private fun resolveEdge(graph: HeapGraph, parentId: Long, childId: Long): PathStep {
    val parent = graph.findObjectById(parentId)
    when (parent) {
        is HeapInstance -> {
            val className = parent.instanceClassName
            parent.readFields().forEach { field ->
                if (field.value.asNonNullObjectId == childId) {
                    return PathStep.FieldReference(className, field.name)
                }
            }
        }
        is HeapObjectArray -> {
            val className = parent.arrayClassName
            parent.readRecord().elementIds.forEachIndexed { idx, elementId ->
                if (elementId == childId) {
                    return PathStep.ArrayReference(className, idx)
                }
            }
        }
        is HeapClass -> {
            val className = parent.name
            parent.readStaticFields().forEach { field ->
                if (field.value.asNonNullObjectId == childId) {
                    return PathStep.FieldReference(className, field.name)
                }
            }
        }
        is HeapPrimitiveArray -> { /* can't reference objects */ }
    }
    logger.warn { "Could not resolve edge: ${classNameOf(parent)}@$parentId -> $childId (${parent::class.simpleName})" }
    return PathStep.FieldReference(classNameOf(parent), "?")
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
 * Returns childId -> array of parentIds.
 */
private fun buildReverseIndex(graph: HeapGraph): Map<Long, LongArray> {
    val skipChildIds = collectLeafObjectIds(graph)
    val index = HashMap<Long, ArrayList<Long>>()
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
                obj.readFields().forEach { field ->
                    if (field.name.startsWith('<')) return@forEach
                    val childId = field.value.asNonNullObjectId ?: return@forEach
                    if (skipChildIds.binarySearch(childId) >= 0) return@forEach
                    if (!graph.objectExists(childId)) return@forEach
                    index.getOrPut(childId) { ArrayList() }.add(objectId)
                    if (visited.add(childId)) {
                        queue.addLast(childId)
                    }
                }
            }
            is HeapObjectArray -> {
                if (obj.arrayClassName in LEAF_ARRAY_CLASSES) continue
                obj.readRecord().elementIds.forEachIndexed { _, elementId ->
                    if (elementId != 0L && skipChildIds.binarySearch(elementId) < 0
                        && graph.objectExists(elementId)) {
                        index.getOrPut(elementId) { ArrayList() }.add(objectId)
                        if (visited.add(elementId)) {
                            queue.addLast(elementId)
                        }
                    }
                }
            }
            is HeapClass -> {
                obj.readStaticFields().forEach { field ->
                    if (field.name.startsWith('<')) return@forEach
                    val childId = field.value.asNonNullObjectId ?: return@forEach
                    if (skipChildIds.binarySearch(childId) >= 0) return@forEach
                    if (!graph.objectExists(childId)) return@forEach
                    index.getOrPut(childId) { ArrayList() }.add(objectId)
                    if (visited.add(childId)) {
                        queue.addLast(childId)
                    }
                }
            }
            is HeapPrimitiveArray -> { /* no object references */ }
        }
    }

    // Convert to LongArray for compact storage
    return index.mapValues { (_, list) -> list.toLongArray() }
}

/**
 * Collects IDs of leaf-type objects that should be excluded from the reverse index.
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
