---
name: Reverse Index Cache
description: Requirements for caching the reverse reference index of a heap dump between runs.
style: plain-1
targets:
  - ../src/main/kotlin/com/github/hurricup/leakscollector/ReverseIndexCache.kt
  - ../src/main/kotlin/com/github/hurricup/leakscollector/PathFinder.kt
---

# Reverse Index Cache

Status: Draft
Date: 2026-10-13

## Summary

The reverse index tells, for each object, which objects refer to it. To build it takes minutes on a large
dump, so the tool saves it in a cache file next to the dump. A later run on the same dump loads the cache in
seconds.

## Goals

- Make a second run on the same dump fast.
- Never use a cache that belongs to another dump.

## Requirements

- The tool must build the index only when there is at least one target.
- The cache file must be `<dump file name>.ri`, in the folder of the dump.
- When there is no cache file, the tool must build the index and save the cache.
- When the cache file is valid, the tool must load it and not build the index.
- A cache must be valid only when its dump size and its dump fingerprint match the dump.
- The dump fingerprint is the SHA-256 of the first 64 KB of the dump.
- A cache of another format version must be invalid.
- A cache built by a tool that follows other references must be invalid.
- An invalid or corrupt cache must not fail the run. The tool must log a warning, build the index and overwrite
  the cache.
- To delete the cache file must force a rebuild.

## Data & Backend

- The cache is a gzip stream: magic `RIDX`, format version, dump size, fingerprint, and the entries. Each entry is
  a child id and its parent ids. Field names are not stored.
