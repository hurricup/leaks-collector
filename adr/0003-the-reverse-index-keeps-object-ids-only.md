# 0003. The reverse index keeps object ids only

Date: 2026-10-13

## Status

Proposed.

## Context

The path search walks from a target to the roots, so it needs the parents of each object. A large dump has
millions of objects and more references. The index must fit in memory and in a cache file. Only the few final
paths need field names and array indices for the report.

## Decision

**The reverse index maps each object id to the ids of its parents, with no field names.** The tool names a step
from the heap graph only when it builds a final path.

## Consequences

- The index and its cache stay compact.
- To name a step, the tool reads the fields of the parent again. When it finds no match, the step shows as `?`.
- The resulting behavior is in the [retention paths spec](../spec/retention-paths.spec.md) and the
  [reverse index cache spec](../spec/reverse-index-cache.spec.md).

## Rejected alternatives

### Keep the field name on each edge

It makes the index and the cache much larger, for data that only a few paths use.
