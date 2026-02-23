package com.github.hurricup.leakscollector

import shark.HprofHeapGraph.Companion.openHeapGraph
import java.io.File

private const val TARGET_CLASS_NAME = "com.example.TargetClass"

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

    hprofFile.openHeapGraph().use { graph ->
        val predicate: (HeapObjectContext) -> Boolean = { ctx ->
            val obj = ctx.heapObject
            obj is shark.HeapObject.HeapInstance && obj.instanceClassName == TARGET_CLASS_NAME
        }

        val paths = findPaths(graph, predicate)

        if (paths.isEmpty()) {
            System.err.println("No paths found to $TARGET_CLASS_NAME")
        } else {
            paths.forEach { path ->
                println(formatPath(path))
            }
        }
    }
}
