# leaks-collector

A CLI tool that reads a JVM heap dump (hprof) and finds strong retention paths from GC roots to leaked objects of
an IntelliJ-based IDE. User docs are in `README.md`. Behavior is in `spec/`, decisions are in `adr/`.

| Folder | Content |
| --- | --- |
| `src/main/kotlin/com/github/hurricup/leakscollector/` | the tool |
| `src/test/kotlin/com/github/hurricup/leakscollector/` | tests |
| `src/test/resources/graphs/` | YAML test graphs, format in `src/test/resources/test-graph-schema.md` |
| `capture`, `analyze` | helper scripts |
| `libs/` | Shark jars |
| `tmp/` | heap dumps, outputs, Shark sources; gitignored |

## Build and run

- Build: `./gradlew build`. Test: `./gradlew test`. JDK 21 or later.
- Run: `./gradlew run -q --args="--pretty tmp/<dump>.hprof" > tmp/<dump>.out.txt 2> tmp/<dump>.err.txt`, or
  `./analyze tmp/<dump>.hprof`, which does the same.
- Always send stdout and stderr to separate files, then read them.
- Heap dumps live in `tmp/`, named `closedprojects_<DDMM_HHMM>.hprof`. `./capture` makes them.

## Rules

- Kotlin, Gradle with the Kotlin DSL.
- One logical change per commit.
- Keep the analysis on one thread. See [ADR 0002](adr/0002-the-analysis-runs-on-one-thread.md).
- Test the path search with YAML graphs. See
  [ADR 0005](adr/0005-algorithm-tests-run-on-synthetic-yaml-graphs.md).
- "Store" or "save" means a file in the repository, never machine-local memory. Put each text in its home, see
  below.
- Review flow: Codex writes a review in `codex_review.md`, Claude answers in `claude_review_answer.md`. Both
  files are gitignored.

## Shark

- The heap parser is Shark (`shark-graph`, `shark-hprof`) from Square's LeakCanary. The jars are in `libs/`,
  version 3.0-alpha-9. It reads standard JVM dumps, though LeakCanary targets Android.
- Shark sources are unpacked in `tmp/shark-hprof/` and `tmp/shark-graph/` for reference.
- Known upstream bug: [leakcanary#2807](https://github.com/square/leakcanary/issues/2807), a
  `NegativeArraySizeException` on some large dumps. It is an int overflow in the record count.

## Where each kind of text goes

| Kind of text | Home |
| --- | --- |
| Behavior that a caller or a user can observe | a spec in `spec/` |
| A decision and its reason | a decision record (ADR) in `adr/` |
| How the code works | KDoc on the symbol |
| A rule for future changes | this file |
| An idea for later | `PLANS.md` |

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
