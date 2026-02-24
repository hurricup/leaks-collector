package com.github.hurricup.leakscollector

/**
 * Formats a path of [PathStep]s into a human-readable string.
 *
 * Example output:
 * `Root -> SomeObject.field -> OtherObject.array[0] -> TargetClass`
 */
fun formatPath(path: List<PathStep>): String = path.joinToString(" -> ") { step ->
    when (step) {
        is PathStep.Root -> "Root[${gcRootTypeName(step.gcRoot)}, ${step.heapObject.objectId}]"
        is PathStep.FieldReference -> "${step.ownerClassName}.${step.fieldName}"
        is PathStep.ArrayReference -> "${step.arrayClassName}[${step.index}]"
        is PathStep.Target -> "${step.className}@${step.objectId}"
    }
}
