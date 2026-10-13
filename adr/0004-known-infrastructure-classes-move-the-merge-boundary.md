# 0004. Known infrastructure classes move the merge boundary

Date: 2026-10-13

## Status

Proposed.

## Context

Two paths of one target that share a node are merged only when the node is far from the root. Near the root
they stay separate, because there they are different causes. The boundary was a fixed depth of 8 steps. It did
not fit all cases. A path through IDE infrastructure, such as `Disposer` or `PathClassLoader`, has several
steps of shared plumbing first, and the real cause comes after them.

## Decision

**The merge boundary of a path is a small default, moved further by known infrastructure classes on the
path.** The default is 3 steps. `Disposer` moves it to its own step from root plus 4. `PathClassLoader` moves it
to its own step from root plus 3. When several apply, the largest wins.

## Consequences

- Paths that split right after the plumbing stay separate.
- A new kind of plumbing needs a new anchor class.
- A fixed offset after an anchor may still not fit every case.
- The resulting behavior is in the [retention paths spec](../spec/retention-paths.spec.md).

## Rejected alternatives

### A fixed depth of 8

It did not fit all cases. For example, it kept apart the `CefServer` paths, which split at step 6 or 7 but
have one cause.
