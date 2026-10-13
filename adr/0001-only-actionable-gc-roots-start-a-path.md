# 0001. Only actionable GC roots start a path

Date: 2026-10-13

## Status

Proposed.

## Context

A heap dump has GC roots of many types. Some of them, such as `StickyClass`, hold objects for the VM itself. A
review asked why the tool skips these roots and called it a bug. The goal of the tool is to find leaks that a
developer can fix. It is not to prove that every object is reachable.

## Decision

**A path starts only at a GC root type that code can control.** The tool skips `StickyClass`, `Finalizing`,
`Debugger`, `Unreachable`, `InternedString` and `Unknown` roots.

## Consequences

- The report shows only chains that a code change can break.
- A target that is held only through a skipped root shows as not strongly reachable.
- The resulting behavior is in the [retention paths spec](../spec/retention-paths.spec.md).

## Rejected alternatives

### Start a path at every root type

It gives complete reachability, but adds chains that no code change can fix.
