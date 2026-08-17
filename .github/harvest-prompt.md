# Weekday rule-harvest — instructions for Claude

You are running inside GitHub Actions, on the `develop` branch of
`amulya-blackbaud/groovy-test-authoring`. A previous step has already written
`harvest/candidates.json`. Follow these steps exactly. Only ever modify
`references/*.md` and `harvest/state.json`.

## 0. Guard against pile-up
Run `gh pr list --state open --base develop`. If any open PR's branch starts with
`rules/harvest-`, STOP immediately and do nothing — a previous batch is still
awaiting review.

## 1. Load inputs
Read `harvest/candidates.json`. It contains `scanned_max_pr_id` and a
`candidates` array (each item: `pr`, `threadId`, `file`, `path`, `source_set`,
`author`, `comment`).

If `candidates` is empty:
- Set `last_pr_id` = `scanned_max_pr_id` in `harvest/state.json`.
- Commit only that file directly to `develop` with message
  `chore(harvest): advance cursor to PR <scanned_max_pr_id>`, push, and STOP.
- Do NOT open a pull request.

## 2. Decide what is genuinely new
For each candidate, read the reference file for its source set **and**
`references/conventions.md`, then decide whether the comment expresses a rule
that is NOT already captured anywhere:
- `test` → `references/test.md`
- `coreTest` → `references/coreTest.md`
- `componentTest` → `references/componentTest.md`
- a rule that applies across all test types → `references/conventions.md`

Skip: anything already covered (even if worded differently), one-off nitpicks,
questions, praise, and bot noise. When unsure, leave it out — a human reviews the PR.

## 3. Write the new rules
Append each genuinely new rule as a concise, imperative bullet to the correct
file, matching the style of the existing entries, ending with `(ADO PR <pr>)`.
Never reword or reorder existing content, and never add a rule that duplicates
one already present.

## 4. Advance the cursor
Set `last_pr_id` = `scanned_max_pr_id` in `harvest/state.json`.

## 5. Commit — one commit per changed file
Create a branch `rules/harvest-YYYYMMDD` (today's date) from `develop`. Commit
**each changed file separately** — e.g. `references/coreTest.md`, then
`references/test.md`, then `harvest/state.json` — each with a message naming the
rule, e.g. `coreTest: prefer @Autowired over mocking in a CoreSpec (ADO PR 547471)`.

## 6. Open ONE pull request
Push the branch and open a single PR with `--base develop`:
- Title: `Harvest: <N> new Groovy test rule(s) (ADO PRs <ids>)`
- Body: one line per rule, each linking its source:
  `https://dev.azure.com/blackbaud/Products/_git/receipt-manager/pullrequest/<pr>`

Then stop. The human reviewer merging the PR is the approval gate.
