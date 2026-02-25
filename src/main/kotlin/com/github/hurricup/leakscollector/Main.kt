package com.github.hurricup.leakscollector

import io.github.oshai.kotlinlogging.KotlinLogging
import shark.HeapGraph
import shark.HeapObject.HeapInstance
import shark.HprofHeapGraph.Companion.openHeapGraph
import java.io.File
import java.util.*
import kotlin.time.measureTime

private val logger = KotlinLogging.logger {}
private const val PROJECT_CLASS = "com.intellij.openapi.project.impl.ProjectImpl"
private const val EDITOR_CLASS = "com.intellij.openapi.editor.impl.EditorImpl"

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

/**
 * Reads the isReleased boolean field from an EditorImpl instance.
 */
private fun isEditorReleased(instance: HeapInstance): Boolean {
    return instance.readFields()
        .firstOrNull { it.name == "isReleased" }
        ?.value?.asBoolean ?: false
}

fun main(args: Array<String>) {
    val hprofPath = args.firstOrNull() ?: run {
        System.err.println("""
            leaks-collector $version — find retention paths to leaked objects in JVM heap dumps

            Usage: leaks-collector <path-to-hprof>

            Analyzes the given .hprof heap dump and prints retention paths from GC roots
            to leaked objects (disposed ProjectImpl, released EditorImpl).

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
                if (obj is HeapInstance) {
                    when (obj.instanceClassName) {
                        PROJECT_CLASS -> {
                            val state = getContainerState(obj, ctx.graph)
                            if (state != "DISPOSE_COMPLETED") {
                                logger.info { "Skipping alive $PROJECT_CLASS@${obj.objectId} (state=$state)" }
                                false
                            } else {
                                true
                            }
                        }
                        EDITOR_CLASS -> {
                            if (!isEditorReleased(obj)) {
                                logger.info { "Skipping alive $EDITOR_CLASS@${obj.objectId} (isReleased=false)" }
                                false
                            } else {
                                true
                            }
                        }
                        else -> false
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
