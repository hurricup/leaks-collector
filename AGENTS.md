# leaks-collector

## Where each kind of text goes

| Kind of text | Home |
| --- | --- |
| Behavior that a caller or a user can observe | a spec in `spec/` |
| A decision and its reason | a decision record (ADR) in `adr/` |
| How the code works | KDoc on the symbol |
| A rule for future changes | this file |

A sentence is moved to its home, never dropped.

## Before a change

- Read the specs whose `targets` cover the files you change. Read the ADRs that they link.
- The specs are the source of truth for behavior. An updated test does not replace an updated spec.
- A behavior change updates the owning spec in the same commit. If no spec owns the behavior, add one.
- A refactoring with no behavior change updates no spec. Say so in the summary.
- A decision that the work agrees on gets a new ADR.

## Specs

- A spec is `spec/<feature>.spec.md`. Keep the folder flat until it needs subfolders by area.
- A spec starts with YAML frontmatter: `name`, `description`, `style: plain-1`, and `targets` (at least one
  file path or glob).
- Then an H1 title equal to `name`, and a `Status:` line (`Draft`, `Active`) and a `Date:` line (ISO-8601).
- Sections, in this order, each only when it has content: `Summary`, `Goals`, `Non-goals`, `Requirements`,
  `User Experience`, `Data & Backend`, `Error Handling`, `Testing / Local Run`, `Open Questions / Risks`. A spec
  that needs another section may add one, but does not rename these.
- A sentence in a spec describes behavior that a caller or a user can observe. Mechanics go to KDoc, rationale
  goes to an ADR, policy goes to this file.
- One requirement per bullet. Use must/should/may for its strength. A requirement sentence stays at or under 20
  words, other prose at or under 25.
- Put a `[@test]` link on the line after the requirement it verifies. The links are the test inventory. A YAML
  graph under `src/test/resources/graphs/` is a valid `[@test]` target.
- `## Summary` opens with what the feature is, in words for a reader who has never opened the code.
- Name a class in prose only when the sentence is about that class. Otherwise name the behavior.
- Paths in `targets` and `[@test]` are relative to the spec file: `../src/main/kotlin/...`,
  `../src/test/...`.
- A main Kotlin target carries a backlink after its `package` line, relative to that file. From
  `src/main/kotlin/com/github/hurricup/leakscollector/` it is
  `// @spec ../../../../../../../spec/<feature>.spec.md`. A script or a resource target has none.
- No test checks the spec references. When you move, rename or delete a file, find its old path in `spec/`
  and update it in the same commit.

## Decision records

- An ADR is `adr/NNNN-<slug>.md`. Numbers are sequential and never reused.
- The title states the decision as a sentence, not a topic.
- Sections: `Status`, `Context`, `Decision`, `Consequences`, and `Rejected alternatives` when there were real
  alternatives. A `Date:` line follows the title.
- Status values: `Proposed`, `Accepted`, `Accepted. Amended by NNNN`, `Superseded by NNNN`, `Deprecated`. Add the
  issue link when the ADR belongs to an issue.
- An agent writes a new ADR as `Proposed`. Only the user accepts it. The agent sets `Accepted` only after the user
  says so, in the conversation. Code and specs may follow a `Proposed` ADR.
- A `Proposed` ADR that amends or supersedes another ADR does not change the status of that ADR. The old status
  changes when the new ADR is accepted.
- An accepted ADR is not rewritten. A change to the decision is a new ADR that amends or supersedes it, and only
  the status of the old ADR changes.
- One decision per ADR. An ADR links the spec that holds the resulting behavior.
- KDoc may cite a decision as `ADR NNNN` when the code follows from it.
- In `adr/` and `spec/`, every mention of an ADR or a spec is a relative link, not plain text. This applies to
  each mention, not only to the first one.

## Writing

- Simple English: short sentences, one idea per sentence, active voice, the same word for the same thing.
