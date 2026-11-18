# 0006GM. A new ADR takes a time-based base 36 id

Date: 2026-11-18T07:39:59Z

## Status

Accepted.

## Context

An ADR had a sequential four-digit number. A sequential number has no allocator. Two branches take the same next
number, and git reports nothing at the merge. The collision shows only later, as two records with one number.
Every link and every mention of that number is then ambiguous.

## Decision

**A new ADR takes an id that is the time of its creation, written in base 36.**

- The id is the number of seconds from the base to the creation of the record.
- The base is `2026-11-18T05:20:25Z`, the unix timestamp `1794979225`.
- The id is written in base 36 with the alphabet `0-9`, then `A-Z`, uppercase only. It is padded with zeros to
  six characters: `00K3F9`.
- The file is `adr/<ID>-<slug>.md`, the title is `# <ID>. <Title>`, and a citation is `ADR <ID>`.
- The `Date:` line holds the creation time in UTC with seconds. The id equals that time minus the base.
- When the id is taken in the folder, it advances by one second until it is free. The `Date:` line advances
  with it.
- A numbered record `NNNN` keeps its file name, its title and its `Date:` line. It holds the id `00NNNN`.
- No new record takes a number. The last number is 0006.

The rules for future changes are in [AGENTS.md](../AGENTS.md).

## Consequences

- Two branches can add a record without a shared counter.
- Every existing link and `ADR NNNN` mention stays valid.
- No new id can equal an old number. The old numbers read as the first six seconds after the base, and that time
  has passed.
- Six characters hold about 69 years of seconds from the base.
- An id is not readable as a count. The number of records is no longer visible from the last id.
- The file names do not sort by time across the two shapes. A list in time order sorts by the id, with `NNNN`
  as `00NNNN`.
- Two branches that create a record in the same second still get one id. The repair moves the younger record
  one second forward: the file name, the title, the `Date:` line, and every link and mention of it.

## Rejected alternatives

### Keep the sequential numbers and repair a collision at the merge

Nothing runs at the merge, so nobody sees the collision in time. The repair renames a record that other branches
and commits already cite.

### Rename the existing records to six characters

It breaks the mentions in the code and in the commit messages.
