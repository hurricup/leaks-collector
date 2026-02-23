package com.github.hurricup.leakscollector

import shark.GcRoot
import shark.HeapGraph
import shark.HeapObject
import shark.HeapObject.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

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

private data class QueueEntry(val heapObject: HeapObject, val path: List<PathStep>)

/**
 * Finds all paths from GC roots to objects matching the given predicate.
 * Traversal follows only strong references (skips weak/soft/phantom).
 * GC roots are processed in parallel across available CPU cores.
 */
fun findPaths(
    graph: HeapGraph,
    predicate: (HeapObjectContext) -> Boolean,
): List<List<PathStep>> {
    val visited = ConcurrentHashMap.newKeySet<Long>()
    val results = CopyOnWriteArrayList<List<PathStep>>()
    val threadCount = Runtime.getRuntime().availableProcessors()
    val executor = Executors.newFixedThreadPool(threadCount)

    val roots = graph.gcRoots.filter { graph.objectExists(it.id) }
    val chunks = roots.chunked((roots.size / threadCount).coerceAtLeast(1))

    val futures = chunks.map { chunk ->
        executor.submit {
            for (root in chunk) {
                if (!visited.add(root.id)) continue
                val rootObject = graph.findObjectById(root.id)
                val rootStep = PathStep.Root(root, rootObject)
                val initialPath = listOf<PathStep>(rootStep)

                if (predicate(HeapObjectContext(rootObject, graph))) {
                    results.add(initialPath + PathStep.Target(classNameOf(rootObject)))
                } else {
                    bfs(graph, rootObject, initialPath, visited, predicate, results)
                }
            }
        }
    }

    futures.forEach { it.get() }
    executor.shutdown()

    return results
}

private fun bfs(
    graph: HeapGraph,
    startObject: HeapObject,
    initialPath: List<PathStep>,
    visited: MutableSet<Long>,
    predicate: (HeapObjectContext) -> Boolean,
    results: MutableList<List<PathStep>>,
) {
    val queue = ArrayDeque<QueueEntry>()
    enqueueReferences(graph, startObject, initialPath, visited, queue)

    while (queue.isNotEmpty()) {
        val (obj, path) = queue.removeFirst()

        if (predicate(HeapObjectContext(obj, graph))) {
            results.add(path + PathStep.Target(classNameOf(obj)))
            continue
        }

        enqueueReferences(graph, obj, path, visited, queue)
    }
}

private fun enqueueReferences(
    graph: HeapGraph,
    obj: HeapObject,
    currentPath: List<PathStep>,
    visited: MutableSet<Long>,
    queue: ArrayDeque<QueueEntry>,
) {
    when (obj) {
        is HeapInstance -> {
            if (isWeakReferenceType(obj)) return

            obj.readFields().forEach { field ->
                val refId = field.value.asNonNullObjectId ?: return@forEach
                if (!visited.add(refId)) return@forEach
                val refObject = graph.findObjectById(refId)
                val step = PathStep.FieldReference(obj.instanceClassName, field.name)
                queue.addLast(QueueEntry(refObject, currentPath + step))
            }
        }
        is HeapObjectArray -> {
            obj.readRecord().elementIds.forEachIndexed { index, elementId ->
                if (elementId == 0L) return@forEachIndexed
                if (!visited.add(elementId)) return@forEachIndexed
                if (!graph.objectExists(elementId)) return@forEachIndexed
                val refObject = graph.findObjectById(elementId)
                val step = PathStep.ArrayReference(obj.arrayClassName, index)
                queue.addLast(QueueEntry(refObject, currentPath + step))
            }
        }
        is HeapClass -> {
            obj.readStaticFields().forEach { field ->
                val refId = field.value.asNonNullObjectId ?: return@forEach
                if (!visited.add(refId)) return@forEach
                val refObject = graph.findObjectById(refId)
                val step = PathStep.FieldReference(obj.name, field.name)
                queue.addLast(QueueEntry(refObject, currentPath + step))
            }
        }
        is HeapPrimitiveArray -> { /* no references to follow */ }
    }
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
