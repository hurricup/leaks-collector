# Working Context

Read these files first for project background:
- `CLAUDE.md` — project description, algorithm overview, build/test/run commands
- `.claude/plan.md` — architecture, detailed algorithm, future ideas
- `.claude/notes.md` — key files, architecture notes, open design questions

## What those files don't cover

### Running the tool
- Use `./gradlew run -q --args="--pretty path/to/dump.hprof"` — `-q` suppresses gradle noise, `--pretty` gives one-node-per-line output
- Always redirect: `> tmp/output.txt 2> tmp/stderr.txt`
- Snapshots live in `tmp/` (gitignored), named like `closedprojects_DDMM.hprof`
- Cache files (`.ri`) are generated next to hprof files automatically

### Capturing heap dumps
- `jmap -dump:format=b,file=tmp/closedprojects_DDMM.hprof <pid>`
- Find IDEA process: `jps` or `ps aux | grep idea`

### Shark library
- Sources unpacked in `tmp/shark-hprof/` and `tmp/shark-graph/` for reference
- Filed https://github.com/square/leakcanary/issues/2807 — NegativeArraySizeException on some large dumps (int overflow in record count, not file size dependent)

### Algorithm nuance
- The pathfinding already walks from every direct parent of a target, not just the first one
- Most walks merge/skip because the graph is so interconnected — that's why results are usually ~1 path per target despite trying all parents

### Tests
- 30 total: 19 path-finding + 4 grouping + 5 formatting + 2 schema validation

### Repo state
- Branch: main, clean
- Latest commit: 99ccd39 "Add IDE info extraction to plan"
