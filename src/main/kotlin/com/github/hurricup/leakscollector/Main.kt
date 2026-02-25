package com.github.hurricup.leakscollector

import io.github.oshai.kotlinlogging.KotlinLogging
import shark.HprofHeapGraph.Companion.openHeapGraph
import java.io.File
import kotlin.time.measureTime

private val logger = KotlinLogging.logger {}
private const val TARGET_CLASS_NAME = "com.intellij.openapi.project.impl.ProjectImpl"

fun main(args: Array<String>) {
    val hprofPath = args.firstOrNull() ?: run {
        System.err.println("Usage: leaks-collector <path-to-hprof>")
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
                obj is shark.HeapObject.HeapInstance && obj.instanceClassName == TARGET_CLASS_NAME
            }

            findPaths(graph, hprofFile, predicate) { path ->
                println(formatPath(path))
            }
        }
    }.also { logger.info { "Total time: $it" } }
}
