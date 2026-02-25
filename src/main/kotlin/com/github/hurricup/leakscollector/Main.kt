package com.github.hurricup.leakscollector

import io.github.oshai.kotlinlogging.KotlinLogging
import shark.HeapGraph
import shark.HeapObject.HeapInstance
import shark.HprofHeapGraph.Companion.openHeapGraph
import java.io.File
import java.util.*
import kotlin.time.measureTime

private val logger = KotlinLogging.logger {}
private const val TARGET_CLASS_NAME = "com.intellij.openapi.project.impl.ProjectImpl"

private val version: String by lazy {
    val props = Properties()
    Main::class.java.getResourceAsStream("/version/version.properties")?.use { props.load(it) }
    props.getProperty("version", "unknown")
}

private object Main

/**
 * Reads the containerState enum name from a ProjectImpl instance:
 * containerState (AtomicReference) -> value (ContainerState enum) -> name
 */
private fun getContainerState(instance: HeapInstance, graph: HeapGraph): String? {
    val atomicRefId = instance.readFields()
        .firstOrNull { it.name == "containerState" }
        ?.value?.asNonNullObjectId ?: return null
    val stateId = (graph.findObjectById(atomicRefId) as? HeapInstance)
        ?.readFields()?.firstOrNull { it.name == "value" }
        ?.value?.asNonNullObjectId ?: return null
    return (graph.findObjectById(stateId) as? HeapInstance)
        ?.readFields()?.firstOrNull { it.name == "name" }
        ?.value?.asNonNullObjectId
        ?.let { (graph.findObjectById(it) as? HeapInstance)?.readAsJavaString() }
}

fun main(args: Array<String>) {
    val hprofPath = args.firstOrNull() ?: run {
        System.err.println("""
            leaks-collector $version — find retention paths to leaked objects in JVM heap dumps

            Usage: leaks-collector <path-to-hprof>

            Analyzes the given .hprof heap dump and prints retention paths from GC roots
            to disposed ProjectImpl instances that are still held in memory.

            On first run, a reverse index is built and cached as <file>.ri next to the
            heap dump. Subsequent runs load the cache for faster analysis.

            Output: one line per retention path to stdout. Logs go to stderr.
        """.trimIndent())
        return
    }

    val hprofFile = File(hprofPath)
    if (!hprofFile.exists()) {
        System.err.println("File not found: $hprofPath")
        return
    }

    logger.info { "Opening heap graph: $hprofPath" }
    measureTime {
        hprofFile.openHeapGraph().use { graph ->
            logger.info { "Heap graph opened" }
            val predicate: (HeapObjectContext) -> Boolean = { ctx ->
                val obj = ctx.heapObject
                if (obj is HeapInstance && obj.instanceClassName == TARGET_CLASS_NAME) {
                    val state = getContainerState(obj, ctx.graph)
                    if (state != "DISPOSE_COMPLETED") {
                        logger.info { "Skipping alive $TARGET_CLASS_NAME@${obj.objectId} (state=$state)" }
                        false
                    } else {
                        true
                    }
                } else {
                    false
                }
            }

            var firstTarget = true
            findPaths(graph, hprofFile, predicate,
                onTarget = { className, objectId, pathCount ->
                    if (!firstTarget) println()
                    firstTarget = false
                    println("# $className@$objectId ($pathCount paths)")
                },
                onPath = { path ->
                    println(formatPath(path))
                },
            )
        }
    }.also { logger.info { "Total time: $it" } }
}
