# Weekday rule-harvest — instructions for Claude

You run inside GitHub Actions on the `develop` branch. A previous step wrote
`harvest/candidates.json`. Your ONLY job is to decide which candidates are genuinely
new rules, add them to the correct reference file, and record what you did in
`harvest/decisions.json`.

**Do NOT run git or gh. Do NOT edit `harvest/state.json`.** A later step commits your
changes and opens the pull request. Only edit files under `references/` and write
`harvest/decisions.json`.

## 1. Read the candidates
Read `harvest/candidates.json`. It has `scanned_max_pr_id` and a `candidates` array
(each item: `pr`, `threadId`, `file`, `path`, `source_set`, `author`, `comment`).

## 2. Decide what is genuinely new
For each candidate, open the reference file for its source set **and**
`references/conventions.md`, and decide whether the comment expresses a rule that is
NOT already captured:

- `source_set: test`          -> `references/test.md`
- `source_set: coreTest`      -> `references/coreTest.md`
- `source_set: componentTest` -> `references/componentTest.md`
- a rule that applies to all test types -> `references/conventions.md`

Skip anything already covered (even if worded differently), one-off nitpicks,
questions, praise, or noise. When unsure, leave it out.

## 3. Add the new rules to the right file
Append each genuinely new rule as a concise, imperative bullet to the correct
`references/*.md` file, matching the style of the existing entries, and ending with
`(ADO PR <pr>)`. Never reword or reorder existing content, and never add a rule that
duplicates one already present.

## 4. Record what you did
Write `harvest/decisions.json` in exactly this shape:

    {
      "scanned_max_pr_id": 560999,
      "accepted": [
        { "ref_file": "references/coreTest.md", "title": "prefer @Autowired over mocking in a CoreSpec", "pr": 547471 }
      ]
    }

- `ref_file` must be the repo-relative path you edited.
- `title` is a short (< 10 word) summary of the rule.
- `pr` is the source ADO pull-request id.
- If nothing is new, write `{"scanned_max_pr_id": <number>, "accepted": []}` and edit no rule files.

The next step reads the changed `references/*.md` files and `decisions.json`, commits
one change per file, and opens a single pull request against `develop`.
