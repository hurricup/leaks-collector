# 0002. The analysis runs on one thread

Date: 2026-10-13

## Status

Proposed.

## Context

The walks for many targets are independent, so they could run in parallel. But Shark's
`RandomAccessHprofReader` keeps shared IO state, an okio `Buffer`. Concurrent reads through one heap graph are
not safe.

## Decision

**The index build and the path search run on one thread, over one heap graph.**

## Consequences

- No locks and no races on the reader.
- A large dump takes longer than it would with parallel walks.
- The behavior that this protects is in the [retention paths spec](../spec/retention-paths.spec.md). The order
  of targets matters there, because of claiming.

## Rejected alternatives

### Parallel walks over one heap graph

Not safe: the reader state is shared.

### Parallel walks with one heap graph per thread

Possible, but each thread then needs its own heap graph instance.
