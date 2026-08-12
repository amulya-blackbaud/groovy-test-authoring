---
name: groovy-test-authoring
description: >-
  Author and maintain Groovy (Spock) test files for the receipt-manager project —
  unit tests (src/test, *Spec), core tests (src/coreTest, *CoreSpec), and
  component tests (src/componentTest, *ComponentSpec) — so they follow the
  team's established conventions. Use this skill whenever a .groovy test is
  being written or edited: writing a new test, updating an existing test
  because the class it covers changed, adding tests for a newly created class,
  or reviewing/refactoring .groovy tests — even if the user doesn't explicitly
  say "use the skill." Also use it whenever the user asks what the team's
  Groovy testing conventions are, or references unit/core/component tests.
---

# Groovy Test Authoring

This skill captures how *our team* writes and maintains Groovy tests. It exists
so that anyone on the project — or Claude acting on their behalf — produces
tests that match our conventions on the first try, instead of re-deriving them
or repeating mistakes that have already been called out in code review.

The conventions here are distilled from real pull-request comments. They are the
source of truth. When in doubt, follow this skill over general Groovy/Spock
habits.

## Before you write or change any test — read these first

Do not start editing a `.groovy` file until you have loaded the relevant
context. This is the single most important step; skipping it is how tests drift
from the team's standards.

1. **Always** read `references/copilot-instructions.md` first — the team's own
   repo conventions (architecture, naming, Spring patterns, and a full Testing
   section). It is authoritative: if anything in this skill ever conflicts with it,
   follow `copilot-instructions.md`.
2. **Always** read `references/conventions.md` — the project-wide do / don't
   rules that apply to every test type.
3. Read the reference file for the specific test type you are working on:
   - Unit test (`src/test`, `*Spec`) → `references/test.md`
   - Core test (`src/coreTest`, `*CoreSpec`) → `references/coreTest.md`
   - Component test (`src/componentTest`, `*ComponentSpec`) → `references/componentTest.md`
   - Not sure which type applies → read `references/test-types.md` first to
     decide, then read that type's file.
4. Open a real, current example of that test type in the repo (paths are listed
   in each type's reference file). Mirror its structure, imports, and naming.
   A live example from the codebase beats any template.

Only after doing this should you produce or modify test code.

## Inputs the user may give you

The user will usually name **the class under test** and often **the exact target
spec file**, e.g. "write test cases in `GiftReceiptServiceCoreSpec.groovy` for
`GiftReceiptService`" or "add tests to `InternalBenefitsConverterSpec.groovy`".
When they do:

- Honor the named spec file. Infer the test type from its suffix and put/keep it
  in the matching source set: `*CoreSpec` → `src/coreTest`, `*ComponentSpec` →
  `src/componentTest`, plain `*Spec` → `src/test`.
- If the named file exists, follow Workflow A (edit it in place). If it doesn't,
  follow Workflow B (create it) at the correct path mirroring the class's package.
- If only the class is named (no file), derive the spec name as
  `<Class>` + the suffix for the type you choose, and confirm the type if it's
  ambiguous.
- Always read the class under test (and a sibling example spec) before writing, so
  cases match the real method signatures and behavior.

## Workflow A — an existing class changed, update its test

Use this when a production class was modified (signature change, new/removed
method, changed behavior, renamed field, etc.) and its test needs to keep up.

1. Locate the test that covers the changed class. Naming/location conventions
   are in each type's reference file; if you can't find it, search the repo for
   the class name before assuming no test exists.
2. Read the whole existing test first. Preserve its structure, helper usage, and
   style — you are editing, not rewriting. Do not reformat unrelated code.
3. Map the production change to test changes:
   - New public method / behavior → add cases covering it (happy path + the edge
     cases the conventions call for).
   - Changed signature or behavior → update the affected cases; don't leave dead
     or now-incorrect assertions.
   - Removed method → remove its cases (and any now-unused setup/mocks).
4. Re-check every case against `references/conventions.md` — especially the
   "what NOT to do" section — before finishing.
5. Verify (see "Verify your work" below).
6. Summarize what changed and *why* (tie it back to the production change) so the
   diff is easy to review.

## Workflow B — a new class was created, write its test

1. Decide the correct test type using `references/test-types.md`. Getting this
   right matters: the wrong type in the wrong place is a common review comment.
2. Read that type's reference file and a real example.
3. Create the test in the correct location with the correct name (per the type's
   reference file).
4. Write cases that cover: the primary behavior, the boundary/edge conditions the
   conventions require, and the failure/error paths. Match the depth of similar
   existing tests — not more, not less.
5. Use the project's standard setup, fixtures, mocking approach, and assertion
   style. Do not introduce new patterns or dependencies without a clear reason;
   if you think a new pattern is warranted, flag it for the human rather than
   silently adopting it.
6. Verify, then summarize.

## Choosing the right test type

`unit` (src/test), `core` (src/coreTest), and `component` (src/componentTest) are not interchangeable —
each has a distinct purpose, location, and scope. When it's not obvious which one
a piece of code belongs to, read `references/test-types.md` and pick
deliberately. If it's genuinely ambiguous, ask the user rather than guessing.

## Conventions and guardrails

`references/conventions.md` is the checklist. It is organized into the positive
patterns to follow and an explicit "what NOT to do" list — the latter is built
directly from recurring review comments, so treat those as hard rules, not
suggestions. Run through it before declaring any test done.

## Verify your work

- Run the affected tests if the environment allows it (the exact command lives in
  `references/conventions.md` under "Running tests"). Report pass/fail.
- Run static analysis and fix every violation — a spec is **not** done until it is
  green. CodeNarc checks the Groovy specs; PMD checks any Java you touched. The
  exact tasks and the rules that most often bite are in each type's reference file
  and in `references/conventions.md` under "Static analysis". Do not silence a rule
  with inline suppressions to make it pass.
- If you can't run them, say so explicitly and list what you'd want to verify
  (tests + CodeNarc/PMD), so the human knows what to check before merging.
- Never claim a test passes if you haven't actually run it.

## Keeping this skill current (team maintenance loop)

This skill is only as good as it is up to date. It's meant to grow the same way
your codebase does — through pull requests.

- When a code review surfaces a new "do this / don't do that" for Groovy tests,
  add it to `references/conventions.md` (or the relevant type file) and open a PR
  against this skill. One reviewer's lesson then becomes automatic for everyone.
- Keep the raw, not-yet-distilled notes in `references/pr-comment-log.md`; move
  them into `conventions.md` as clear rules once the pattern is confirmed.
- If your PRs live in a connected system (e.g. Azure DevOps, Bitbucket/Jira,
  GitHub), you can ask Claude to read recent PR review threads on `.groovy` files
  and propose new rules for `conventions.md` — but a human should review and
  merge them like any other change. The file, not the connector, stays the
  source of truth.

Because this skill lives in the repo, everyone who pulls gets the latest
version, and updates go through normal code review.
