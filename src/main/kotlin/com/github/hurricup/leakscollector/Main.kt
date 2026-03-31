package com.github.hurricup.leakscollector

import io.github.oshai.kotlinlogging.KotlinLogging
import shark.HeapGraph
import shark.HeapObject.HeapClass
import shark.HeapObject.HeapInstance
import shark.HeapObject.HeapObjectArray
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

/**
 * Finds a singleton instance of the given class name in the heap.
 */
private fun findSingleton(graph: HeapGraph, className: String): HeapInstance? {
    val clazz = graph.findClassByName(className) ?: return null
    return clazz.instances.firstOrNull() as? HeapInstance
}

/**
 * Reads a String field from a HeapInstance by field name.
 */
private fun HeapInstance.readStringField(className: String, fieldName: String): String? {
    return readField(className, fieldName)?.value?.readAsJavaString()
}

/**
 * Extracts IDE product name, version, and build number from the heap.
 */
private fun extractIdeInfo(graph: HeapGraph): String? {
    val namesInfo = findSingleton(graph, "com.intellij.openapi.application.ApplicationNamesInfo")
    val appInfo = findSingleton(graph, "com.intellij.openapi.application.impl.ApplicationInfoImpl")

    val productName = namesInfo?.readStringField("com.intellij.openapi.application.ApplicationNamesInfo", "myFullProductName")
    val edition = namesInfo?.readStringField("com.intellij.openapi.application.ApplicationNamesInfo", "myEditionName")

    val major = appInfo?.readStringField("com.intellij.openapi.application.impl.ApplicationInfoImpl", "myMajorVersion")
    val minor = appInfo?.readStringField("com.intellij.openapi.application.impl.ApplicationInfoImpl", "myMinorVersion")
    val buildNumber = appInfo?.readStringField("com.intellij.openapi.application.impl.ApplicationInfoImpl", "myBuildNumber")

    if (productName == null) return null

    val parts = mutableListOf<String>()
    parts.add(productName)
    if (edition != null) parts[0] = "$productName ($edition)"
    if (major != null && minor != null) parts.add("$major.$minor")
    if (buildNumber != null) parts.add("Build #$buildNumber")

    return parts.joinToString(" ")
}

/**
 * Extracts JVM runtime version from system properties in the heap.
 */
private fun extractJvmInfo(graph: HeapGraph): String? {
    // java.lang.System has a static field "props" of type Properties (or a Map in newer JDKs)
    val systemClass = graph.findClassByName("java.lang.System") ?: return null

    // Try reading via the props static field
    val propsId = systemClass.readStaticFields().firstOrNull { it.name == "props" }
        ?.value?.asNonNullObjectId ?: return null
    val propsObj = graph.findObjectById(propsId) as? HeapInstance ?: return null

    val runtimeVersion = readSystemProperty(graph, propsObj, "java.runtime.version")
    val arch = readSystemProperty(graph, propsObj, "os.arch")

    if (runtimeVersion == null) return null
    return if (arch != null) "$runtimeVersion $arch" else runtimeVersion
}

/**
 * Reads a property value from a Properties/Map object in the heap.
 */
private fun readSystemProperty(graph: HeapGraph, propsObj: HeapInstance, key: String): String? {
    // Properties extends Hashtable, which has a "table" field (Entry[])
    // But in modern JDKs, Properties may use a ConcurrentHashMap internally
    // Try both: "map" field (newer) and "table" field (older Hashtable)

    val mapId = propsObj.readField("java.util.Properties", "map")?.value?.asNonNullObjectId
    val mapObj = mapId?.let { graph.findObjectById(it) as? HeapInstance }

    val tableId = (mapObj ?: propsObj).let { obj ->
        obj.readField(obj.instanceClassName, "table")?.value?.asNonNullObjectId
    } ?: return null

    val tableObj = graph.findObjectById(tableId)
    if (tableObj is HeapObjectArray) {
        val elementIds = tableObj.readRecord().elementIds
        for (elementId in elementIds) {
            if (elementId == 0L) continue
            val entry = graph.findObjectById(elementId) as? HeapInstance ?: continue
            val entryKey = entry.readField(entry.instanceClassName, "key")?.value?.readAsJavaString()
            if (entryKey == key) {
                return entry.readField(entry.instanceClassName, "val")?.value?.readAsJavaString()
                    ?: entry.readField(entry.instanceClassName, "value")?.value?.readAsJavaString()
            }
            // Check linked entries (next pointer for hash collision chains)
            var nextId = entry.readField(entry.instanceClassName, "next")?.value?.asNonNullObjectId
            while (nextId != null && nextId != 0L) {
                val nextEntry = graph.findObjectById(nextId) as? HeapInstance ?: break
                val nextKey = nextEntry.readField(nextEntry.instanceClassName, "key")?.value?.readAsJavaString()
                if (nextKey == key) {
                    return nextEntry.readField(nextEntry.instanceClassName, "val")?.value?.readAsJavaString()
                        ?: nextEntry.readField(nextEntry.instanceClassName, "value")?.value?.readAsJavaString()
                }
                nextId = nextEntry.readField(nextEntry.instanceClassName, "next")?.value?.asNonNullObjectId
            }
        }
    }
    return null
}

/**
 * Extracts JVM startup time from RuntimeImpl.vmStartupTime (epoch millis).
 */
private fun extractJvmStartupTime(graph: HeapGraph): Long? {
    val runtimeClass = graph.findClassByName("sun.management.RuntimeImpl") ?: return null
    val runtime = runtimeClass.instances.firstOrNull() as? HeapInstance ?: return null
    return runtime.readField("sun.management.RuntimeImpl", "vmStartupTime")?.value?.asLong
}

/**
 * Extracts VM options from the heap via VMManagementImpl.vmArgs (cached List<String>).
 * This field is lazily populated — only present if getVmArguments() was called during the JVM lifetime.
 */
private fun extractVmOptions(graph: HeapGraph): List<String>? {
    val vmMgmtClass = graph.findClassByName("sun.management.VMManagementImpl") ?: return null
    val vmMgmt = vmMgmtClass.instances.firstOrNull() as? HeapInstance ?: return null
    val vmArgsId = vmMgmt.readField("sun.management.VMManagementImpl", "vmArgs")
        ?.value?.asNonNullObjectId ?: return null
    val vmArgsList = graph.findObjectById(vmArgsId) as? HeapInstance ?: return null

    val innerListId = vmArgsList.readField(vmArgsList.instanceClassName, "list")
        ?.value?.asNonNullObjectId ?: vmArgsId
    val innerList = if (innerListId != vmArgsId) graph.findObjectById(innerListId) as? HeapInstance else vmArgsList

    val arrayId = innerList?.let { list ->
        list.readField(list.instanceClassName, "elementData")?.value?.asNonNullObjectId
            ?: list.readField(list.instanceClassName, "a")?.value?.asNonNullObjectId
    } ?: return null

    val arrayObj = graph.findObjectById(arrayId) as? HeapObjectArray ?: return null
    val result = mutableListOf<String>()
    for (elementId in arrayObj.readRecord().elementIds) {
        if (elementId == 0L) continue
        val str = (graph.findObjectById(elementId) as? HeapInstance)?.readAsJavaString() ?: continue
        result.add(str)
    }
    return result.ifEmpty { null }
}

/**
 * Extracts non-bundled plugins list from the heap.
 */
private fun extractNonBundledPlugins(graph: HeapGraph): List<String> {
    val pluginManagerClass = graph.findClassByName("com.intellij.ide.plugins.PluginManagerCore") ?: return emptyList()

    // Kotlin object: INSTANCE field, then pluginsStateLazy -> _value -> nullablePluginSet -> enabledPlugins
    val pluginsStateLazyId = pluginManagerClass.readStaticFields()
        .firstOrNull { it.name == "pluginsStateLazy" }?.value?.asNonNullObjectId ?: return emptyList()
    val lazyObj = graph.findObjectById(pluginsStateLazyId) as? HeapInstance ?: return emptyList()
    val stateId = lazyObj.readField(lazyObj.instanceClassName, "_value")?.value?.asNonNullObjectId ?: return emptyList()
    val stateObj = graph.findObjectById(stateId) as? HeapInstance ?: return emptyList()
    val pluginSetId = stateObj.readField(stateObj.instanceClassName, "nullablePluginSet")?.value?.asNonNullObjectId ?: return emptyList()
    val pluginSetObj = graph.findObjectById(pluginSetId) as? HeapInstance ?: return emptyList()

    // enabledPlugins is a List — find the backing array
    val enabledPluginsId = pluginSetObj.readField("com.intellij.ide.plugins.PluginSet", "enabledPlugins")?.value?.asNonNullObjectId ?: return emptyList()
    val enabledPluginsObj = graph.findObjectById(enabledPluginsId) as? HeapInstance ?: return emptyList()

    // It's likely an ImmutableList or ArrayList — find the array
    val arrayId = enabledPluginsObj.readField(enabledPluginsObj.instanceClassName, "elements")?.value?.asNonNullObjectId
        ?: enabledPluginsObj.readField(enabledPluginsObj.instanceClassName, "elementData")?.value?.asNonNullObjectId
        ?: return emptyList()
    val arrayObj = graph.findObjectById(arrayId) as? HeapObjectArray ?: return emptyList()

    val result = mutableListOf<String>()
    for (elementId in arrayObj.readRecord().elementIds) {
        if (elementId == 0L) continue
        val plugin = graph.findObjectById(elementId) as? HeapInstance ?: continue
        val className = plugin.instanceClassName

        // Only PluginMainDescriptor has isBundled — ContentModuleDescriptor and DependsSubDescriptor delegate to parent
        if (className != "com.intellij.ide.plugins.PluginMainDescriptor") continue

        val isBundled = plugin.readField("com.intellij.ide.plugins.PluginMainDescriptor", "isBundled")?.value?.asBoolean ?: true
        if (isBundled) continue

        val idObj = plugin.readField("com.intellij.ide.plugins.PluginMainDescriptor", "id")?.value?.asNonNullObjectId?.let {
            graph.findObjectById(it) as? HeapInstance
        }
        val idString = idObj?.readStringField("com.intellij.openapi.extensions.PluginId", "idString") ?: continue
        val pluginVersion = plugin.readStringField("com.intellij.ide.plugins.PluginMainDescriptor", "version") ?: "?"

        result.add("$idString ($pluginVersion)")
    }
    return result
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

    extractIdeInfo(graph)?.let { println("# IDE: $it") }
    extractJvmInfo(graph)?.let { println("# JVM: $it") }
    extractJvmStartupTime(graph)?.let { startupTime ->
        val uptimeMs = header.heapDumpTimestamp - startupTime
        if (uptimeMs > 0) {
            val hours = uptimeMs / 3_600_000
            val minutes = (uptimeMs % 3_600_000) / 60_000
            println("# Uptime: ${hours}h ${minutes}m")
        }
    }
    extractVmOptions(graph)?.let { options ->
        println("# VM options: ${options.joinToString(" ")}")
    }
    val plugins = extractNonBundledPlugins(graph)
    if (plugins.isNotEmpty()) {
        println("# Non-bundled plugins (${plugins.size}): ${plugins.sorted().joinToString(", ")}")
    }

    println()
}

fun main(args: Array<String>) {
    val argList = args.toMutableList()
    val pretty = argList.remove("--pretty")

    val hprofPath = argList.firstOrNull() ?: run {
        System.err.println("""
            leaks-collector $version — find retention paths to leaked objects in JVM heap dumps

            Usage: leaks-collector [--pretty] <path-to-hprof>

            Analyzes the given .hprof heap dump and prints retention paths from GC roots
            to leaked objects (disposed ProjectImpl, released EditorImpl).

            On first run, a reverse index is built and cached as <file>.ri next to the
            heap dump. Subsequent runs load the cache for faster analysis.

            Options:
              --pretty    Multi-line output with one node per line

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
                println(formatPath(group.examplePath, pretty))
            }, onDependentTargets = { dep ->
                if (!first) println()
                first = false
                if (dep.targetIds.size == 1) {
                    println("# ${dep.className}@${dep.targetIds[0]} — held by a path above")
                } else {
                    println("# ${dep.className} (${dep.targetIds.size} instances) — held by a path above")
                }
            }, onUnreachableTargets = { dep ->
                if (!first) println()
                first = false
                if (dep.targetIds.size == 1) {
                    println("# ${dep.className}@${dep.targetIds[0]} — not strongly reachable (GC-eligible)")
                } else {
                    println("# ${dep.className} (${dep.targetIds.size} instances) — not strongly reachable (GC-eligible)")
                }
            })
        }
    }.also { logger.info { "Total time: $it" } }
}
