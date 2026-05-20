package com.github.hurricup.leakscollector

import shark.HeapGraph
import shark.HeapObject
import shark.HeapObject.HeapInstance

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

private fun annotateDocumentImpl(obj: HeapInstance, graph: HeapGraph): List<String> {
    val lines = mutableListOf<String>()
    formatFieldValue(obj, graph, "myText")?.let { lines.add("myText = $it") }
    return lines
}

/**
 * Reads a field by name (searching across the class hierarchy) and formats its value:
 * - null/missing field -> null (caller skips)
 * - String value -> "..." (truncated to 100 chars, newlines escaped)
 * - Other object -> "<className>"
 * - Primitive -> string representation
 */
private fun formatFieldValue(
    owner: HeapInstance,
    graph: HeapGraph,
    fieldName: String,
): String? {
    var field: shark.HeapField? = null
    for (cls in owner.instanceClass.classHierarchy) {
        field = owner.readField(cls.name, fieldName)
        if (field != null) break
    }
    if (field == null) return null
    val value = field.value

    if (value.isNullReference) return "null"

    val id = value.asNonNullObjectId
    if (id != null) {
        return formatObjectValue(graph.findObjectById(id), graph)
    }

    return value.holder.toString()
}

private fun formatObjectValue(obj: HeapObject, graph: HeapGraph): String {
    if (obj is HeapInstance) {
        readAsString(obj, graph)?.let { return formatString(it) }
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
