package com.github.hurricup.leakscollector

// @spec ../../../../../../../spec/path-annotations.spec.md

import shark.HeapGraph
import shark.HeapObject
import shark.HeapObject.HeapInstance
import shark.HeapObject.HeapObjectArray

/**
 * Computes diagnostic annotation lines for an instance. Each returned line is rendered as a
 * `# ...` comment line in the pretty path output, placed before the step that references the
 * annotated object. Annotations are display-only and must not affect path signatures.
 */
typealias Annotator = (HeapInstance, HeapGraph) -> List<String>

/**
 * Registry of per-class annotators, keyed by the owning instance's class name.
 */
private val ANNOTATORS: Map<String, Annotator> = mapOf(
    "com.intellij.testFramework.LightVirtualFile" to ::annotateLightVirtualFile,
    "com.intellij.openapi.editor.impl.DocumentImpl" to ::annotateDocumentImpl,
    "com.intellij.ui.tabs.impl.JBTabsImpl" to ::annotateJBTabsImpl,
)

fun annotationsFor(instance: HeapInstance, graph: HeapGraph): List<String> {
    val annotator = ANNOTATORS[instance.instanceClassName] ?: return emptyList()
    return annotator(instance, graph)
}

private fun annotateLightVirtualFile(obj: HeapInstance, graph: HeapGraph): List<String> {
    val lines = mutableListOf<String>()
    formatFieldValue(obj, graph, "myName")?.let { lines.add("myName = $it") }
    formatFieldValue(obj, graph, "myContent")?.let { lines.add("myContent = $it") }
    return lines
}

/**
 * Field path from `DocumentImpl` to its text in builds where the text moved behind `DocumentCoreImpl`:
 * `DocumentImpl.impl` -> `DocumentCoreImpl.snapshot` -> `DocumentSnapshotImpl.text` -> `DocumentTextImpl.chars`,
 * all in IntelliJ `platform/core-impl/src/com/intellij/openapi/editor/impl/`.
 */
private val DOCUMENT_CORE_TEXT_PATH = arrayOf("impl", "snapshot", "text", "chars")

private fun annotateDocumentImpl(obj: HeapInstance, graph: HeapGraph): List<String> {
    formatFieldValue(obj, graph, "myText")?.let { return listOf("myText = $it") }
    formatFieldValue(obj, graph, *DOCUMENT_CORE_TEXT_PATH)?.let { return listOf("text = $it") }
    return emptyList()
}

private fun annotateJBTabsImpl(obj: HeapInstance, graph: HeapGraph): List<String> {
    val lines = mutableListOf<String>()
    formatFieldValue(obj, graph, "parentDisposable")?.let { lines.add("parentDisposable = $it") }
    return lines
}

/**
 * Follows a chain of fields from [owner] and formats the value of the last one.
 *
 * Each field is searched across the class hierarchy of its owner. The value is formatted as:
 * - null/missing field -> null (caller skips)
 * - String value -> "..." (truncated to 100 chars, newlines escaped)
 * - Other object -> "<className>"
 * - Primitive -> string representation
 *
 * @param fieldPath field names to follow; every field but the last must hold an instance.
 * @return the formatted value, or `null` when a field is missing or an intermediate value is not an instance.
 */
private fun formatFieldValue(
    owner: HeapInstance,
    graph: HeapGraph,
    vararg fieldPath: String,
): String? {
    var current = owner
    for (fieldName in fieldPath.dropLast(1)) {
        val id = readFieldInHierarchy(current, fieldName)?.value?.asNonNullObjectId ?: return null
        current = graph.findObjectById(id) as? HeapInstance ?: return null
    }
    val field = readFieldInHierarchy(current, fieldPath.last()) ?: return null
    val value = field.value

    if (value.isNullReference) return "null"

    val id = value.asNonNullObjectId
    if (id != null) {
        return formatObjectValue(graph.findObjectById(id), graph)
    }

    return value.holder.toString()
}

/**
 * Returns the field named [fieldName] of [owner], searching from its own class up the hierarchy, or `null` if
 * no class in the hierarchy declares it.
 */
private fun readFieldInHierarchy(owner: HeapInstance, fieldName: String): shark.HeapField? {
    for (cls in owner.instanceClass.classHierarchy) {
        owner.readField(cls.name, fieldName)?.let { return it }
    }
    return null
}

private fun formatObjectValue(obj: HeapObject, graph: HeapGraph): String {
    if (obj is HeapInstance) {
        readAsString(obj, graph)?.let { return formatString(it) }
        // Named disposables from Disposer.newDisposable(debugName) capture the name as val$debugName.
        obj.readField(obj.instanceClassName, "val\$debugName")?.value?.asNonNullObjectId?.let { nameId ->
            (graph.findObjectById(nameId) as? HeapInstance)?.readAsJavaString()?.let { name ->
                return "<${obj.instanceClassName}: ${formatString(name)}>"
            }
        }
    }
    val className = when (obj) {
        is HeapInstance -> obj.instanceClassName
        is HeapObject.HeapClass -> obj.name
        is HeapObject.HeapObjectArray -> obj.arrayClassName
        is HeapObject.HeapPrimitiveArray -> obj.arrayClassName
    }
    return "<$className>"
}

/**
 * Attempts to extract a string from a CharSequence-like instance. Handles common
 * IntelliJ/JDK CharSequence implementations by unwrapping to the underlying String.
 * Returns null when the instance isn't a recognized string-like type.
 */
private fun readAsString(obj: HeapInstance, graph: HeapGraph, budget: Int = MAX_STRING_LENGTH + 1): String? {
    if (budget <= 0) return ""
    return when (obj.instanceClassName) {
        "java.lang.String" -> obj.readAsJavaString()?.take(budget)
        "com.intellij.util.text.ImmutableText" -> {
            readReferencedString(obj, graph, "com.intellij.util.text.ImmutableText", "myNode", budget)
        }
        "com.intellij.util.text.ImmutableText\$CompositeNode" -> {
            val cls = "com.intellij.util.text.ImmutableText\$CompositeNode"
            val head = readReferencedString(obj, graph, cls, "head", budget) ?: return null
            if (head.length >= budget) return head
            val tail = readReferencedString(obj, graph, cls, "tail", budget - head.length) ?: return head
            head + tail
        }
        else -> null
    }
}

private fun readReferencedString(
    owner: HeapInstance,
    graph: HeapGraph,
    declaringClassName: String,
    fieldName: String,
    budget: Int,
): String? {
    val id = owner.readField(declaringClassName, fieldName)?.value?.asNonNullObjectId ?: return null
    val target = graph.findObjectById(id) as? HeapInstance ?: return null
    return readAsString(target, graph, budget)
}

private const val MAX_STRING_LENGTH = 100

private fun formatString(s: String): String {
    val escaped = s.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
    return if (escaped.length > MAX_STRING_LENGTH) {
        "\"${escaped.take(MAX_STRING_LENGTH)}…\""
    } else {
        "\"$escaped\""
    }
}

// --- Array-step annotations: parallel key/value arrays of fastutil maps -----

/** Field of a fastutil open hash map that holds its keys, parallel to [FASTUTIL_VALUE_ARRAY]. */
private const val FASTUTIL_KEY_ARRAY = "key"

/** Field of a fastutil open hash map that holds its values, parallel to [FASTUTIL_KEY_ARRAY]. */
private const val FASTUTIL_VALUE_ARRAY = "value"

/**
 * Holds a value that is computed once per [HeapGraph], and computed again when another graph is passed.
 */
private class PerGraphCache<T>(private val compute: (HeapGraph) -> T) {
    @Volatile
    private var cached: Pair<HeapGraph, T>? = null

    /**
     * Returns the value for [graph], computing it on the first call for this graph.
     */
    operator fun get(graph: HeapGraph): T {
        cached?.let { (cachedGraph, value) -> if (cachedGraph === graph) return value }
        return compute(graph).also { cached = graph to it }
    }
}

private val disposerMapId = PerGraphCache(::findDisposerMapId)

/**
 * Returns the id of `Disposer.ourTree.myObject2ParentNode`, the map from each disposable to the node of its
 * parent, or `null` when the heap has no Disposer tree.
 */
private fun findDisposerMapId(graph: HeapGraph): Long? {
    val disposerClass = graph.findClassByName("com.intellij.openapi.util.Disposer") ?: return null
    val treeId = disposerClass.readStaticField("ourTree")?.value?.asNonNullObjectId ?: return null
    val tree = graph.findObjectById(treeId) as? HeapInstance ?: return null
    return tree.readField("com.intellij.openapi.util.ObjectTree", "myObject2ParentNode")?.value?.asNonNullObjectId
}

/**
 * Computes annotation lines for an ArrayReference step. Currently used to surface the corresponding disposal
 * pair when a path traverses the Disposer's key/value arrays.
 *
 * @param holderIds ids of the objects that hold the array on the path, nearest first.
 * @param arrayId the array that the step goes through.
 * @param index the index of the step in the array.
 */
fun annotateArrayStep(holderIds: List<Long>, arrayId: Long, index: Int, graph: HeapGraph): List<String> {
    val holderId = holderIds.firstOrNull() ?: return emptyList()
    if (holderId != disposerMapId[graph]) return emptyList()
    val map = graph.findObjectById(holderId) as? HeapInstance ?: return emptyList()
    val keyArrayId = map.readReferenceField(FASTUTIL_KEY_ARRAY)
    val valueArrayId = map.readReferenceField(FASTUTIL_VALUE_ARRAY)
    return when (arrayId) {
        keyArrayId -> disposerSibling(valueArrayId, index, graph, "parent")
        valueArrayId -> disposerSibling(keyArrayId, index, graph, "child")
        else -> emptyList()
    }
}

/**
 * Returns the object id held by the field named [fieldName], searched across the class hierarchy, or `null` when
 * the field is missing or holds null.
 */
private fun HeapInstance.readReferenceField(fieldName: String): Long? =
    readFieldInHierarchy(this, fieldName)?.value?.asNonNullObjectId

private fun disposerSibling(siblingArrayId: Long?, index: Int, graph: HeapGraph, role: String): List<String> {
    if (siblingArrayId == null) return emptyList()
    val arr = graph.findObjectById(siblingArrayId) as? HeapObjectArray ?: return emptyList()
    val elementIds = arr.readRecord().elementIds
    val siblingId = elementIds.getOrNull(index) ?: return emptyList()
    if (siblingId == 0L) return emptyList()
    val siblingObj = graph.findObjectById(siblingId)
    return listOf("$role = ${formatDisposerSibling(siblingObj, graph)}")
}

/**
 * Renders a Disposer disposal-pair sibling for inline display. ObjectNode wrappers are
 * unwrapped to their myObject for clarity; other objects are formatted via [formatObjectValue].
 */
private fun formatDisposerSibling(obj: HeapObject, graph: HeapGraph): String {
    if (obj is HeapInstance && obj.instanceClassName == "com.intellij.openapi.util.ObjectNode") {
        val targetId = obj.readField("com.intellij.openapi.util.ObjectNode", "myObject")
            ?.value?.asNonNullObjectId
        if (targetId != null) {
            val target = graph.findObjectById(targetId)
            return "ObjectNode of ${formatObjectValue(target, graph)}"
        }
    }
    return formatObjectValue(obj, graph)
}
