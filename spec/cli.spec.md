---
name: Command Line
description: Requirements for the leaks-collector command and the capture and analyze helper scripts.
style: plain-1
targets:
  - ../src/main/kotlin/com/github/hurricup/leakscollector/Main.kt
  - ../capture
  - ../analyze
---

# Command Line

Status: Draft
Date: 2026-10-13

## Summary

The tool is a command that takes a heap dump file and prints a report. Two helper scripts wrap the usual
workflow: `capture` takes the data from a running IDE, and `analyze` runs the tool on a dump.

## Requirements

### leaks-collector

- `leaks-collector [--pretty] <path-to-hprof>` must analyze the dump and print the report to stdout.
- `--pretty` must select the multi-line path form. See the [report format spec](./report-format.spec.md).
- With no path, the command must print the usage to stderr and exit.
- With a path to a missing file, the command must print `File not found: <path>` to stderr and exit.
- The command must log the total time to stderr at the end.

### capture

- `capture [--analyze] [--shutdown] [--help] [<pid>]` must take diagnostic data from a running IDE.
- Flags and the pid may come in any order.
- With no pid, the script must find the one running IDE process.
- When it finds no IDE process, the script must list the Java processes and fail.
- When it finds more than one IDE process, the script must list them and fail.
- The script must run GC 5 times, with a pause of 3 seconds after each one.
- Then it must write a thread dump to `tmp/jstack_<DDMM_HHMM>.txt`.
- Then it must write a heap dump to `tmp/closedprojects_<DDMM_HHMM>.hprof`.
- With `--shutdown`, the script must then send SIGTERM to the IDE and wait up to 60 seconds for it to exit.
- Then it must zip the IDE log folder to `tmp/logs_<DDMM_HHMM>.zip`.
- When it cannot find the log folder, the script must say so and go on.
- With `--analyze`, the script must run `analyze` on the new heap dump at the end.
- `--help` must print the usage and exit.

### analyze

- `analyze <dump>` must write the report to `<dump>.out.txt` and the log to `<dump>.err.txt`.
- `analyze` must use the pretty path form.
- `analyze` must keep the dump on disk.
- With no argument or a missing file, `analyze` must print a message and fail.

## User Experience

- `--shutdown` does not ask to save open editors. Unsaved changes may be lost.

## Testing / Local Run

- The tool needs a JDK 21 or later. `capture` needs `jcmd`, `jps`, `jstack` and `jmap` on the PATH.
