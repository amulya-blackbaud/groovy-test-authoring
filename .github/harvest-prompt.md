# Weekday rule-harvest — instructions for Claude

You run inside GitHub Actions on the `develop` branch. A previous step wrote
`harvest/candidates.json`. Your ONLY job is to decide which candidates are genuinely
new rules and record each one in `harvest/decisions.json`.

**Do NOT edit any files under `references/`. Do NOT run git or gh. Do NOT edit
`harvest/state.json`.** A later step reads your decisions and opens the pull requests
(one PR per rule). The only file you write is `harvest/decisions.json`.

## 1. Read the candidates
Read `harvest/candidates.json` — it has `scanned_max_pr_id` and a `candidates` array
(each item: `pr`, `threadId`, `file`, `path`, `source_set`, `author`, `comment`).

## 2. Decide what is genuinely new
For each candidate, read the reference file for its source set AND
`references/conventions.md`, and decide whether the comment states a rule that is NOT
already captured there:

- source_set `test`          -> `references/test.md`
- source_set `coreTest`      -> `references/coreTest.md`
- source_set `componentTest` -> `references/componentTest.md`
- a rule that applies to all test types -> `references/conventions.md`

Skip anything already covered (even if worded differently), one-off nitpicks,
questions, praise, or noise. When unsure, leave it out.

## 3. Write decisions.json (one entry per NEW rule)
Write `harvest/decisions.json` in EXACTLY this shape:

    {
      "scanned_max_pr_id": 560999,
      "accepted": [
        {
          "ref_file": "references/coreTest.md",
          "title": "prefer @Autowired over mocking in a CoreSpec",
          "pr": 547471,
          "bullet": "- In a CoreSpec, use real @Autowired beans instead of mocking collaborators. (ADO PR 547471)"
        }
      ]
    }

Field rules:
- `ref_file` — the reference file the rule belongs in (one of the repo-relative paths above).
- `title` — a short (< 10 word) summary; used for the branch, commit, and PR title.
- `pr` — the source ADO pull-request id.
- `bullet` — the EXACT one-line markdown bullet to add to `ref_file`: imperative,
  matching the style of the existing entries, ending with `(ADO PR <pr>)`.
- **Emit one object per distinct rule** — the next step opens exactly ONE pull request
  per entry, appending that one bullet to its file on its own branch.
- If nothing is new, write `{"scanned_max_pr_id": <number>, "accepted": []}`.
