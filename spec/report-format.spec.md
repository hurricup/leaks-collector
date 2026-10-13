---
name: Report Format
description: Requirements for the text report that the tool prints to stdout.
style: plain-1
targets:
  - ../src/main/kotlin/com/github/hurricup/leakscollector/Main.kt
  - ../src/main/kotlin/com/github/hurricup/leakscollector/PathFormatter.kt
---

# Report Format

Status: Draft
Date: 2026-10-13

## Summary

The report is plain text on stdout. It starts with a header about the heap dump. Then it shows one block per
group of targets that share a retention path, and then the dependent and the unreachable targets. Logs go to
stderr, so the report stays clean.

## Requirements

### Header

- Every header line must start with `# `.
- The header must show the tool version, the file path, the file size in MB, and the dump timestamp.
- The header must show the hprof version, the pointer size, object counts by kind, and the GC root count.
- The header must show the IDE name, version and build when the dump has them.
- The header must show the JVM version and architecture when the dump has them.
- The header must show the JVM uptime when the dump has the JVM start time.
- The header must show the VM options when the dump has them.
- The header must show the non-bundled plugins, sorted, with their versions, when there are any.
- An empty line must follow the header.

### Blocks

- An empty line must separate blocks.
- A group block must start with `# <class>@<id>` for one target, or `# <class> (<N> instances)` for more.
- Then the block must show the path of the group.
- Dependent targets of a class must show as one line ending with `— held by a path above`.
- Unreachable targets of a class must show as one line ending with `— not strongly reachable (GC-eligible)`.
- Group blocks must come first, then dependent lines, then unreachable lines.

Example:

```
# leaks-collector 2026.3
# File: /path/to/dump.hprof
# Size: 1686.5 MB
# ...

# com.intellij.openapi.project.impl.ProjectImpl (3 instances)
Root[JniGlobal, 17179869184] -> PathClassLoader.classes -> java.lang.Object[][12] -> ProjectImpl@18131688384

# com.intellij.openapi.editor.impl.EditorImpl (41 instances) — held by a path above
```

### Path

- A root step must show as `Root[<type>, <id>]`.
  [@test] ../src/test/kotlin/com/github/hurricup/leakscollector/PathFormatterTest.kt
- A root step of a thread root must also show the thread name: `Root[<type>, <id>, "<name>"]`.
- A field step must show as `<owner class>.<field>`. A static field shows the class as owner.
  [@test] ../src/test/kotlin/com/github/hurricup/leakscollector/PathFormatterTest.kt
- An array step must show as `<array class>[<index>]`.
  [@test] ../src/test/kotlin/com/github/hurricup/leakscollector/PathFormatterTest.kt
- The last step must show the target as `<class>@<id>`.
- By default, a path must show on one line, with ` -> ` between steps.
  [@test] ../src/test/kotlin/com/github/hurricup/leakscollector/PathFormatterTest.kt
- With `--pretty`, each step after the first must show on its own line, indented with a tab.
  [@test] ../src/test/kotlin/com/github/hurricup/leakscollector/PathFormatterTest.kt
- With `--pretty`, the annotations of a step must show before it. See the
  [path annotations spec](./path-annotations.spec.md).

### Log

- Timings, target info and path counts must go to stderr.
