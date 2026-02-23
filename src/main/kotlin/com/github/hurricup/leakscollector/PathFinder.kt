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
 * 3. For each target, walk backwards to GC roots with per-path cycle detection
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

        walkBack(
            graph, targetId, reverseIndex, gcRootIds, HashSet(), 0,
        ) { pathFromRoot ->
            if (pathCount < MAX_PATHS_PER_TARGET) {
                onPath(pathFromRoot + targetStep)
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
            val childId = field.value.asNonNullObjectId ?: return@forEach
            index.getOrPut(childId) { mutableListOf() }
                .add(IncomingRef.Field(parentId, className, field.name))
        }
    }

    return index
}

/**
 * Walks backwards from [objectId] toward GC roots.
 * Calls [onPathFound] with each complete path (root to current object's parent chain).
 * Returns false from [onPathFound] to stop searching for more paths.
 * Uses [visited] for per-path cycle detection and [MAX_PATH_DEPTH] limit.
 */
private fun walkBack(
    graph: HeapGraph,
    objectId: Long,
    reverseIndex: Map<Long, List<IncomingRef>>,
    gcRootIds: Map<Long, List<GcRoot>>,
    visited: MutableSet<Long>,
    depth: Int,
    onPathFound: (List<PathStep>) -> Boolean,
): Boolean {
    if (depth > MAX_PATH_DEPTH) return true

    // Check if this object is a GC root
    val roots = gcRootIds[objectId]
    if (roots != null) {
        val obj = graph.findObjectById(objectId)
        for (root in roots) {
            if (!onPathFound(listOf(PathStep.Root(root, obj)))) return false
        }
        return true
    }

    val incomingRefs = reverseIndex[objectId] ?: return true
    visited.add(objectId)

    var keepGoing = true
    for (ref in incomingRefs) {
        if (!keepGoing) break
        if (ref.parentId in visited) continue

        val step = when (ref) {
            is IncomingRef.Field -> PathStep.FieldReference(ref.ownerClassName, ref.fieldName)
            is IncomingRef.Array -> PathStep.ArrayReference(ref.arrayClassName, ref.index)
        }

        keepGoing = walkBack(graph, ref.parentId, reverseIndex, gcRootIds, visited, depth + 1) { parentPath ->
            onPathFound(parentPath + step)
        }
    }

    visited.remove(objectId)
    return keepGoing
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
