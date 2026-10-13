---
name: Leak Targets
description: Requirements for choosing which heap objects the tool reports as leaked.
style: plain-1
targets:
  - ../src/main/kotlin/com/github/hurricup/leakscollector/Main.kt
  - ../src/main/kotlin/com/github/hurricup/leakscollector/HeapObjectContext.kt
---

# Leak Targets

Status: Draft
Date: 2026-10-13

## Summary

A target is a heap object that should be gone but is still in memory. The tool looks for retention paths only
to targets. Today the targets are closed projects and released editors of an IntelliJ-based IDE.

## Goals

- Report only objects that are really leaked, not objects that are still in use.

## Non-goals

- Any class other than the ones listed below. A new target class is a spec change.

## Requirements

- A `ProjectImpl` instance must be a target when its container state is `DISPOSE_COMPLETED`.
- A `ProjectImpl` instance in any other container state must not be a target.
- An `EditorImpl` instance must be a target when its `isReleased` field is `true`.
- An `EditorImpl` instance that is not released must not be a target.
- A skipped alive instance must be logged to stderr with its id and state.
- When there are no targets, the report must hold only the header.

## Data & Backend

- The filter is a predicate on a heap object together with its heap graph. A new target kind is a new branch of
  this predicate.
