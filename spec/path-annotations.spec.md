---
name: Path Annotations
description: Requirements for the diagnostic values that the pretty report shows next to path steps.
style: plain-1
targets:
  - ../src/main/kotlin/com/github/hurricup/leakscollector/Annotators.kt
  - ../src/main/kotlin/com/github/hurricup/leakscollector/PathFormatter.kt
---

# Path Annotations

Status: Draft
Date: 2026-10-13

## Summary

An annotation is a `# ...` line in the pretty report that shows a value from an object on the path. It helps
to tell which file, document or disposable a step is about. Annotations exist for a short list of known
classes. They are for display only.

## Goals

- Tell instances of one class apart on a path, with no need to open the dump.

## Non-goals

- Annotations in the compact report.
- Annotations on the root step or the target step.

## Requirements

### Display

- Annotations must show only with `--pretty`.
  [@test] ../src/test/kotlin/com/github/hurricup/leakscollector/PathFormatterTest.kt
- An annotation line must show as a tab, `# `, and the text, before the step it belongs to.
  [@test] ../src/test/kotlin/com/github/hurricup/leakscollector/PathFormatterTest.kt
- A step with no annotations must add no lines.
  [@test] ../src/test/kotlin/com/github/hurricup/leakscollector/PathFormatterTest.kt
- Annotations must not change a path signature or a group. See the
  [retention paths spec](./retention-paths.spec.md).

### Field steps

- A field step must get the annotations of the object that owns the field.
- A field annotation must show as `<field> = <value>`.
- A missing field must give no line.
- `LightVirtualFile` must show `myName` and `myContent`.
- `DocumentImpl` must show `myText` when it has this field.
- Otherwise `DocumentImpl` must show its text as `text = <value>`. Newer builds keep it in `DocumentCoreImpl`.
- `JBTabsImpl` must show `parentDisposable`.

### Array steps

- A step through the key array of the `Disposer` tree map must show `parent = <value>`. The value is the entry
  at the same index in the value array.
- A step through the value array of that map must show `child = <value>`. The value is the entry at the same
  index in the key array.
- An `ObjectNode` value must show as `ObjectNode of <value of its object>`.

### Values

- A null reference must show as `null`.
- A primitive must show its value.
- Text must show in double quotes. It may come from a `String` or an `ImmutableText`.
- Text must escape `\`, newline, carriage return and tab as `\\`, `\n`, `\r` and `\t`.
- Text longer than 100 characters must be cut to 100 and end with `…`.
- A named disposable from `Disposer.newDisposable(debugName)` must show as `<<class>: "<debugName>">`.
- Any other object must show as `<<class>>`.

Example:

```
kotlin.Pair.first ->
	# myName = "Dummy.txt"
	# myContent = "master"
	com.intellij.testFramework.LightVirtualFile.value ->
```
