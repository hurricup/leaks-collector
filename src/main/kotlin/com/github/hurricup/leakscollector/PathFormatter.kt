package com.github.hurricup.leakscollector

/**
 * Formats a path of [PathStep]s into a human-readable string.
 *
 * In `pretty` mode each step is placed on its own line, and per-step diagnostic
 * annotations (e.g. field values for known classes) are emitted as `# ...`
 * comment lines preceding the step they describe. In compact mode annotations
 * are omitted.
 */
fun formatPath(path: List<PathStep>, pretty: Boolean = false): String {
    if (!pretty) {
        return path.joinToString(" -> ") { formatStep(it) }
    }
    val sb = StringBuilder()
    for ((i, step) in path.withIndex()) {
        if (i > 0) {
            sb.append(" ->\n")
            for (line in annotationsOf(step)) {
                sb.append("\t# ").append(line).append('\n')
            }
            sb.append('\t')
        }
        sb.append(formatStep(step))
    }
    return sb.toString()
}

private fun formatStep(step: PathStep): String = when (step) {
    is PathStep.Root -> {
        val threadPart = step.threadName?.let { ", \"$it\"" } ?: ""
        "Root[${gcRootTypeName(step.gcRoot)}, ${step.heapObject.objectId}$threadPart]"
    }
    is PathStep.FieldReference -> "${step.ownerClassName}.${step.fieldName}"
    is PathStep.ArrayReference -> "${step.arrayClassName}[${step.index}]"
    is PathStep.Target -> "${step.className}@${step.objectId}"
}

private fun annotationsOf(step: PathStep): List<String> = when (step) {
    is PathStep.FieldReference -> step.annotations
    else -> emptyList()
}
