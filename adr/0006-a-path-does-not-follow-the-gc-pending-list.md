# 0006. A path does not follow the GC pending list

Date: 2026-10-23

## Status

Proposed.

## Context

A reference has four fields: `referent`, `queue`, `next` and `discovered`. The tool skipped only `referent`. In
snapshot IJPL-259450, the only path to a leaked editor went through `discovered` links of weak references. The
chain was a strong one at the moment of the dump. Nothing else held the editor.

`discovered` links the pending list of the GC: references whose referent the GC has just cleared. The GC of the
heap dump fills this list. The Reference Handler thread empties it soon after, and then the chain is gone. The
thread dump taken just before the heap dump showed this thread idle, so the list was empty before the dump.

## Decision

**A path does not go through the `discovered` field of a reference.** It still goes through `next` and `queue`.

## Consequences

- A target that is held only through the pending list shows as not strongly reachable.
- A reference that waits in a queue that nobody polls still makes a path through `next`. That hold is real.
- The resulting behavior is in the [retention paths spec](../spec/retention-paths.spec.md).

## Rejected alternatives

### Follow `discovered` as a strong field

It is strong at the moment of the dump. But the chain is made by the dump itself, and no code change can break
it.

### Skip `next` too

A queue that nobody polls holds its references and everything that their fields hold. That is a leak that code
can fix.
