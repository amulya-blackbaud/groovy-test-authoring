# groovy-test-authoring (team skill for receipt-manager)

A Claude skill that captures how the receipt-manager team writes Spock tests, so
new/updated tests match our conventions and past PR-review lessons the first time.

## Layout
```
groovy-test-authoring/
├── SKILL.md                     # instructions Claude loads (the "brain")
├── references/
│   ├── copilot-instructions.md  # team repo conventions (read first, authoritative)
│   ├── conventions.md           # cross-cutting rules (source of truth)
│   ├── test-types.md            # pick unit vs core vs component
│   ├── test.md                  # unit tests  (src/test, *Spec)
│   ├── coreTest.md              # core tests  (src/coreTest, *CoreSpec)
│   ├── componentTest.md         # component tests (src/componentTest, *ComponentSpec)
│   └── pr-comment-log.md        # mined PR comments + curation staging
├── scripts/
│   └── ado_sync.py              # mine resolved .groovy PR comments from ADO
└── examples/                    # (optional) drop real exemplar specs here
```
The reference files are already seeded from the real codebase and from resolved
PR comments (each PR-sourced rule is tagged `[PR NNNNNN]`). Keep growing them.

## Use it in IntelliJ IDEA
The skill is loaded by Claude Code, which plugs into IntelliJ:
1. Install Claude Code (`npm install -g @anthropic-ai/claude-code`; needs Node 18+).
2. In IntelliJ: Settings → Plugins → Marketplace → install **Claude Code** (or
   **Claude Code [Beta]**), then restart. A Claude panel appears in the IDE.
3. Put this folder in the repo at `.claude/skills/groovy-test-authoring/` and
   commit it. Because it lives in the repo, everyone who opens receipt-manager in
   IntelliJ gets the skill automatically — no per-person setup.
4. Sign in to Claude Code in the panel. Then just ask, e.g.
   "ReceiptRepository changed — update its coreTest" or "write a unit test for
   BulkTaskFormatConverter". Claude loads the skill by its description and follows
   these conventions.

submodule at `.claude/skills/`, or package it as a Claude Code plugin.

## Keep it consistent & auto-updated as new PRs land
The rules stay current by turning resolved PR comments into rules. Two layers:

1. **Automated mining** — `scripts/ado_sync.py` scans completed PRs, keeps resolved
   comments on `.groovy` files, classifies each as unit/core/component, and appends
   them to `references/pr-comment-log.md`. It's incremental, so run it whenever a PR
   is raised/completed:
   - locally/cron: `ADO_PAT=… python3 scripts/ado_sync.py --org blackbaud --project Products --repo receipt-manager`
   - in CI/webhook: add an Azure Pipeline triggered on PR completion (or a scheduled
     nightly) that runs the script and opens a PR if `pr-comment-log.md` changed.
2. **Human curation** — a maintainer reviews the appended candidates and folds the
   durable ones into `conventions.md` / the per-type file (deleting noise), via a PR.
   This keeps the rules deliberate and consistent rather than a raw comment dump.

You can also just ask Claude in the IDE: "run ado_sync, then curate any new
candidates into the right reference file and show me the diff."

## Optional: delegated agent
`groovy-test-writer.md` (see the bundle) is a Claude Code subagent that preloads this
skill; drop it in `.claude/agents/` if you want to hand off the whole task.
