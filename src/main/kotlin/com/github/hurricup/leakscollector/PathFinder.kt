package com.github.hurricup.leakscollector

import shark.GcRoot
import shark.HeapGraph
import shark.HeapObject
import shark.HeapObject.*

/**
 * A step in a reference path from a GC root to a target object.
 */
sealed class PathStep {
    data class Root(val gcRoot: GcRoot, val heapObject: HeapObject) : PathStep()
    data class FieldReference(val ownerClassName: String, val fieldName: String) : PathStep()
    data class ArrayReference(val arrayClassName: String, val index: Int) : PathStep()
    data class Target(val className: String) : PathStep()
}

private val WEAK_REFERENCE_CLASSES = setOf(
    "java.lang.ref.WeakReference",
    "java.lang.ref.SoftReference",
    "java.lang.ref.PhantomReference",
    "java.lang.ref.FinalizerReference",
    "sun.misc.Cleaner",
)

private const val MAX_PATH_DEPTH = 50
private const val MAX_PATHS_PER_TARGET = 100

/**
 * An incoming reference: "objectId is referenced by parentId via some reference".
 */
private sealed class IncomingRef {
    abstract val parentId: Long

    data class Field(override val parentId: Long, val ownerClassName: String, val fieldName: String) : IncomingRef()
    data class Array(override val parentId: Long, val arrayClassName: String, val index: Int) : IncomingRef()
}

/**
 * Finds all paths from GC roots to objects matching the given predicate.
 *
 * Algorithm:
 * 1. Find all target object IDs by scanning instances
 * 2. Build a reverse reference index (child -> list of parents)
 * 3. For each target, BFS backwards to GC roots (shortest paths first, diverse branches)
 *
 * Paths are streamed to [onPath] as they are found.
 * Traversal follows only strong references (skips weak/soft/phantom).
 */
fun findPaths(
    graph: HeapGraph,
    predicate: (HeapObjectContext) -> Boolean,
    onPath: (List<PathStep>) -> Unit,
) {
    System.err.println("Scanning for target objects...")
    val targetIds = findTargetIds(graph, predicate)
    System.err.println("Found ${targetIds.size} target objects")
    if (targetIds.isEmpty()) return

    System.err.println("Building reverse reference index...")
    val reverseIndex = buildReverseIndex(graph)
    System.err.println("Reverse index built: ${reverseIndex.size} entries")

    val gcRootIds = graph.gcRoots
        .filter { graph.objectExists(it.id) }
        .groupBy { it.id }

    System.err.println("Reconstructing paths...")
    var totalPaths = 0
    for (targetId in targetIds) {
        val targetObj = graph.findObjectById(targetId)
        val targetStep = PathStep.Target(classNameOf(targetObj))
        var pathCount = 0

        val seenSignatures = HashSet<String>()
        bfsBackward(graph, targetId, reverseIndex, gcRootIds) { pathFromRoot ->
            val fullPath = pathFromRoot + targetStep
            val signature = pathSignature(fullPath)
            if (signature !in seenSignatures) {
                seenSignatures.add(signature)
                onPath(fullPath)
                pathCount++
                totalPaths++
            }
            pathCount < MAX_PATHS_PER_TARGET
        }

        if (pathCount >= MAX_PATHS_PER_TARGET) {
            System.err.println("  Hit $MAX_PATHS_PER_TARGET path limit for ${classNameOf(targetObj)}")
        }
    }
    System.err.println("Found $totalPaths total paths")
}

/**
 * BFS backward from [targetId] toward GC roots.
 * For each object visited, stores all incoming edges at the same (first-visit) BFS depth.
 * When a GC root is reached, reconstructs all shortest paths back to it.
 * Calls [onPathFound] for each complete path (root-to-target order, excluding target step).
 * Return false from [onPathFound] to stop.
 */
private fun bfsBackward(
    graph: HeapGraph,
    targetId: Long,
    reverseIndex: Map<Long, List<IncomingRef>>,
    gcRootIds: Map<Long, List<GcRoot>>,
    onPathFound: (List<PathStep>) -> Boolean,
): Boolean {
    // objectId -> BFS depth
    val depth = HashMap<Long, Int>()
    // childId -> all incoming edges at first-visit depth
    // Each IncomingRef says: "IncomingRef.parentId references childId" in the original graph
    val incomingEdges = HashMap<Long, MutableList<IncomingRef>>()
    val foundRoots = mutableListOf<Long>()
    val queue = ArrayDeque<Long>()

    depth[targetId] = 0
    queue.addLast(targetId)

    // Phase 1: BFS from target toward GC roots
    while (queue.isNotEmpty()) {
        val objectId = queue.removeFirst()
        val currentDepth = depth.getValue(objectId)

        if (currentDepth >= MAX_PATH_DEPTH) continue

        if (objectId in gcRootIds) {
            foundRoots.add(objectId)
            continue
        }

        val refs = reverseIndex[objectId] ?: continue
        val nextDepth = currentDepth + 1

        for (ref in refs) {
            val existingDepth = depth[ref.parentId]
            if (existingDepth == null) {
                depth[ref.parentId] = nextDepth
                incomingEdges.getOrPut(objectId) { mutableListOf() }.add(ref)
                queue.addLast(ref.parentId)
            } else if (existingDepth == nextDepth) {
                incomingEdges.getOrPut(objectId) { mutableListOf() }.add(ref)
            }
        }
    }

    if (foundRoots.isEmpty()) return true

    // Phase 2: Build forward edge map from the BFS tree
    // forwardEdges[parentId] = list of (childId, ref) — edges going root-toward-target
    val forwardEdges = HashMap<Long, MutableList<Pair<Long, IncomingRef>>>()
    for ((childId, refs) in incomingEdges) {
        for (ref in refs) {
            forwardEdges.getOrPut(ref.parentId) { mutableListOf() }.add(childId to ref)
        }
    }

    // Phase 3: Reconstruct paths from each found root to target
    var keepGoing = true
    for (rootObjectId in foundRoots) {
        if (!keepGoing) break
        val root = gcRootIds[rootObjectId]?.firstOrNull() ?: continue
        val rootObj = graph.findObjectById(rootObjectId)
        val rootStep = PathStep.Root(root, rootObj)

        if (rootObjectId == targetId) {
            keepGoing = onPathFound(listOf(rootStep))
            continue
        }

        keepGoing = reconstructForward(
            rootObjectId, targetId, forwardEdges, listOf(rootStep), onPathFound
        )
    }

    return keepGoing
}

/**
 * DFS through the BFS-tree forward edges to enumerate all shortest paths from root to target.
 */
private fun reconstructForward(
    currentId: Long,
    targetId: Long,
    forwardEdges: Map<Long, List<Pair<Long, IncomingRef>>>,
    pathSoFar: List<PathStep>,
    onPathFound: (List<PathStep>) -> Boolean,
): Boolean {
    if (currentId == targetId) {
        return onPathFound(pathSoFar)
    }

    val edges = forwardEdges[currentId] ?: return true
    for ((childId, ref) in edges) {
        val step = when (ref) {
            is IncomingRef.Field -> PathStep.FieldReference(ref.ownerClassName, ref.fieldName)
            is IncomingRef.Array -> PathStep.ArrayReference(ref.arrayClassName, ref.index)
        }
        if (!reconstructForward(childId, targetId, forwardEdges, pathSoFar + step, onPathFound)) {
            return false
        }
    }
    return true
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
 * Builds a reverse reference index: for each object, which objects reference it and how.
 * Only strong references are indexed.
 */
private fun buildReverseIndex(graph: HeapGraph): Map<Long, List<IncomingRef>> {
    val index = HashMap<Long, MutableList<IncomingRef>>()

    for (instance in graph.instances) {
        if (isWeakReferenceType(instance)) continue
        val parentId = instance.objectId
        val className = instance.instanceClassName
        instance.readFields().forEach { field ->
            if (field.name.startsWith('<')) return@forEach
            val childId = field.value.asNonNullObjectId ?: return@forEach
            index.getOrPut(childId) { mutableListOf() }
                .add(IncomingRef.Field(parentId, className, field.name))
        }
    }

    for (array in graph.objectArrays) {
        val parentId = array.objectId
        val className = array.arrayClassName
        array.readRecord().elementIds.forEachIndexed { idx, elementId ->
            if (elementId != 0L) {
                index.getOrPut(elementId) { mutableListOf() }
                    .add(IncomingRef.Array(parentId, className, idx))
            }
        }
    }

    for (clazz in graph.classes) {
        val parentId = clazz.objectId
        val className = clazz.name
        clazz.readStaticFields().forEach { field ->
            if (field.name.startsWith('<')) return@forEach
            val childId = field.value.asNonNullObjectId ?: return@forEach
            index.getOrPut(childId) { mutableListOf() }
                .add(IncomingRef.Field(parentId, className, field.name))
        }
    }

    return index
}

private fun isWeakReferenceType(instance: HeapInstance): Boolean {
    return instance.instanceClass.classHierarchy.any { it.name in WEAK_REFERENCE_CLASSES }
}

private fun classNameOf(obj: HeapObject): String = when (obj) {
    is HeapInstance -> obj.instanceClassName
    is HeapClass -> obj.name
    is HeapObjectArray -> obj.arrayClassName
    is HeapPrimitiveArray -> obj.arrayClassName
}

private fun pathSignature(path: List<PathStep>): String = path.joinToString(" -> ") { step ->
    when (step) {
        is PathStep.Root -> "Root"
        is PathStep.FieldReference -> "${step.ownerClassName}.${step.fieldName}"
        is PathStep.ArrayReference -> "${step.arrayClassName}[*]"
        is PathStep.Target -> step.className
    }
}
