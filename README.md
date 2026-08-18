# Groovy Test Authoring

A self-maintaining **Claude skill** that writes and updates the `receipt-manager`
project's Groovy (Spock) tests so they follow the team's established conventions —
on the first try, without re-litigating the same points in code review.

The skill's rules are distilled from our own resolved pull-request comments, and a
scheduled job keeps them growing: every weekday it scans Azure DevOps for new
review lessons and opens a pull request proposing them as rules.

---

## What it does

- **Writes new tests** in the correct location, category, and style when a class is added.
- **Updates existing tests** with a minimal, review-friendly diff when a class changes.
- **Enforces our standards** — explicit assertions, `aRandom` builders, no self-mocking,
  correct base classes/auth — and respects the **CodeNarc** and **PMD** gates before a
  test is considered done.
- **Knows our three test categories** and picks the right one automatically:
  - **Unit** — `src/test`, `*Spec` — fast, isolated logic; no Spring/DB.
  - **Core** — `src/coreTest`, `*CoreSpec` — real Spring beans + database.
  - **Component** — `src/componentTest`, `*ComponentSpec` — full service, via endpoints.
- **Stays current automatically** — new conventions are mined from PR review comments
  and proposed as rules for a human to approve (see [The self-updating loop](#the-self-updating-loop)).

Every rule is traceable to the PR it came from (tagged `[PR NNNNNN]`).

---

## Repository layout

```
groovy-test-authoring/
├── SKILL.md                       # entry point: when to use + the workflow Claude follows
├── references/                    # the rules (loaded on demand)
│   ├── copilot-instructions.md    # team repo conventions (authoritative, read first)
│   ├── conventions.md             # cross-cutting do / don't rules
│   ├── test-types.md              # how to pick unit vs core vs component
│   ├── test.md                    # unit-test conventions
│   ├── coreTest.md                # core-test conventions
│   ├── componentTest.md           # component-test conventions
│   ├── random-builders.md         # aRandom test-data patterns
│   └── pr-comment-log.md          # provenance: mined PR comments + curation staging
├── examples/                      # real exemplar specs to mirror
├── scripts/
│   └── harvest_fetch.py           # deterministic, read-only ADO scan → harvest/candidates.json
├── harvest/
│   └── state.json                 # cursor: the last ADO PR already analyzed
└── .github/
    ├── harvest-prompt.md          # instructions the harvest job gives Claude
    └── workflows/
        └── harvest.yml            # weekday GitHub Action that proposes new rules
```

---

## Using the skill (GitHub Copilot)

The skill uses the open **`SKILL.md`** *Agent Skills* standard, which GitHub Copilot supports
in **VS Code agent mode**, the **Copilot CLI**, and the **Copilot coding agent**. (The same
folder also works in Claude Code, Cowork, and claude.ai.)

**1. Make the skill available to Copilot** — choose one:

- **Per repo (recommended for the team):** add it inside the `receipt-manager` repo at
  `.github/skills/groovy-test-authoring/` (Copilot also discovers `.claude/skills/` and
  `.agents/skills/`). Adding it as a submodule keeps it updatable:
  ```bash
  git submodule add -b develop \
    https://github.com/amulya-blackbaud/groovy-test-authoring.git \
    .github/skills/groovy-test-authoring
  ```
- **Across all your repos:** clone it anywhere, then register that folder in VS Code Copilot
  Chat with `/skills add`:
  ```bash
  git clone -b develop https://github.com/amulya-blackbaud/groovy-test-authoring.git
  ```

**2. Use it** — open `receipt-manager` in VS Code with GitHub Copilot in **agent mode**:

1. In Copilot Chat, type `/skills` to confirm **groovy-test-authoring** is listed.
2. Ask in plain language:
   - `Update ReceiptRepositoryCoreSpec.groovy — ReceiptRepository changed`
   - `Write a unit test for BulkTaskFormatConverter`

The **Copilot CLI** (`/skills`) and the **Copilot coding agent** on github.com read the same
`SKILL.md`. Keep your copy current with `git pull` (or `git submodule update --remote`).

> Requires a recent VS Code with GitHub Copilot agent mode — Copilot added `SKILL.md`
> support in April 2026.

---

## The self-updating loop

The rules grow the same way the codebase does — through pull requests.

```
ADO PRs ──▶ Weekday scan ──▶ New rule? one PR per rule ──▶ You review & merge ──▶ Everyone pulls
(review    (harvest_fetch    (Claude drafts the rule       (the approval gate)     (git pull develop)
 comments)  finds new ones)   into references/*.md)
```

1. **Scan** — `scripts/harvest_fetch.py` lists completed PRs in `receipt-manager` newer than
   the saved cursor and keeps resolved, human review comments on `.groovy` files.
2. **Analyze** — the GitHub Action runs Claude, which judges which comments are genuinely
   new conventions (not already in `references/*.md`) and drafts them into the right file.
3. **Propose** — new rules are opened as a pull request against **`develop`**, one commit per
   changed file, with each rule linking back to its source PR.
4. **Approve** — a maintainer reviews and merges. Merging is the only way a rule becomes active.
5. **Distribute** — everyone's local skill picks it up on the next `git pull`.

Runs **every weekday** (`cron: "0 13 * * 1-5"`, ~9am US Eastern) and can also be triggered
manually from the **Actions** tab.

---

## Branch model

- **`develop`** — the active line of rules. Local skills track this branch; harvest PRs target it.
- **`main`** — the default branch. Holds the workflow so GitHub can schedule it.

---

## Configuration

The harvest workflow needs two repository secrets
(**Settings → Secrets and variables → Actions**):

| Secret | What it is |
|---|---|
| `ADO_PAT` | Azure DevOps PAT with **Code (Read)** — used only to read PRs and review threads. |
| `CLAUDE_CODE_OAUTH_TOKEN` | A Claude subscription token from `claude setup-token` — authenticates the analysis step. No pay-as-you-go API key required. |

`GITHUB_TOKEN` (built in) opens the pull request, so no personal Git credentials are stored.
The scan target (`blackbaud` / `Products` / `receipt-manager`) and schedule are set in
`.github/workflows/harvest.yml` and `scripts/harvest_fetch.py`.

**Requirements:** Settings → Actions → General → Workflow permissions set to *Read and write*,
with *Allow GitHub Actions to create and approve pull requests* enabled.

---

## Contributing

- **Adding a rule by hand:** edit the relevant `references/*.md` file and open a PR to `develop`.
  Keep entries short and imperative, and tag the source `[PR NNNNNN]` when there is one.
- **Curating harvested rules:** review the PR the harvest opens; merge the durable rules,
  edit or drop the noise. The reference files — not the connector — are the source of truth.
- Prefer narrow, specific rules over broad ones, and never add a rule that duplicates an
  existing one.
