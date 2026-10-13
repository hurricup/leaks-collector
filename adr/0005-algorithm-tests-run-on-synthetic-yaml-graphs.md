# 0005. Algorithm tests run on synthetic YAML graphs

Date: 2026-10-13

## Status

Proposed.

## Context

The path search needs a heap graph to test on. Real heap dumps are too large to keep in the repository.

## Decision

**A test of the path search defines a small object graph in YAML.** The test builds a reverse index from it and
checks the found paths. A JSON schema validates each graph file.

## Consequences

- Tests are fast and need no heap dump.
- A test graph can set the parent order, so a test can force a step back or a cycle.
- The tests do not cover the reading of a real dump, the naming of steps, or the annotations.
- A graph abstraction over the heap could later give stronger integration tests without real dumps.
- The tests are linked from the [retention paths spec](../spec/retention-paths.spec.md).

## Rejected alternatives

### Real heap dumps in the repository

They are too large.
