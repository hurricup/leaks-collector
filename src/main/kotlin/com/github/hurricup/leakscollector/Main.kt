package com.github.hurricup.leakscollector

import io.github.oshai.kotlinlogging.KotlinLogging
import shark.HeapGraph
import shark.HeapObject.HeapInstance
import shark.HprofHeader
import shark.HprofHeapGraph.Companion.openHeapGraph
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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

private fun printReportHeader(hprofFile: File, header: HprofHeader, graph: HeapGraph) {
    val fileSizeMb = "%.1f".format(hprofFile.length() / (1024.0 * 1024.0))
    val timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(header.heapDumpTimestamp))
    val pointerSize = header.identifierByteSize * 8

    println("# leaks-collector $version")
    println("# File: ${hprofFile.absolutePath}")
    println("# Size: $fileSizeMb MB")
    println("# Heap dump timestamp: $timestamp")
    println("# Hprof version: ${header.version.versionString}")
    println("# JVM pointer size: $pointerSize-bit")
    println("# Objects: ${graph.objectCount} (${graph.classCount} classes, ${graph.instanceCount} instances, ${graph.objectArrayCount} object arrays, ${graph.primitiveArrayCount} primitive arrays)")
    println("# GC roots: ${graph.gcRoots.size}")
    println()
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
        val header = HprofHeader.parseHeaderOf(hprofFile)
        hprofFile.openHeapGraph().use { graph ->
            logger.info { "Heap graph opened" }

            printReportHeader(hprofFile, header, graph)

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

            var first = true
            findPaths(graph, hprofFile, predicate, onGroup = { group ->
                if (!first) println()
                first = false
                val ids = group.targetIds
                val className = (group.examplePath.last() as PathStep.Target).className
                if (ids.size == 1) {
                    println("# $className@${ids[0]}")
                } else {
                    println("# $className (${ids.size} instances)")
                }
                println(formatPath(group.examplePath))
            }, onDependentTargets = { dep ->
                if (!first) println()
                first = false
                if (dep.targetIds.size == 1) {
                    println("# ${dep.className}@${dep.targetIds[0]} — held by a path above")
                } else {
                    println("# ${dep.className} (${dep.targetIds.size} instances) — held by a path above")
                }
            })
        }
    }.also { logger.info { "Total time: $it" } }
}
