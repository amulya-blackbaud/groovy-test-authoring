# Groovy test conventions — receipt-manager (project source of truth)

Cross-cutting rules for every `.groovy` test, derived from the real codebase and
from resolved PR review comments. Rules tagged `[PR NNNNNN]` trace to a resolved
comment. Update this file via pull request whenever a review establishes a new rule.

## Framework & tooling
- Test framework: **Spock** — every spec extends `spock.lang.Specification`.
- Language: Groovy, under `src/<sourceSet>/groovy/...`.
- Assertions: use blocks (`given/when/then/expect/where`) and **always write
  explicit `assert` statements** in `then:`/`expect:` — never rely on Spock's
  implicit last-expression assertion. Exceptions via `thrown SomeException`.
- Never construct domain objects with `new` — always build them with `aRandom.*`
  builders. Add new builders to `CoreARandom` (via `@Delegate`) when needed.
- Test data: `aRandom` builders, static-imported from
  `com.blackbaud.receiptmanager.core.CoreARandom`. Reusable random builders live
  in `src/sharedTest` (e.g. `Random*Builder.groovy`). Prefer `aRandom` over
  hand-rolled data.
- When **writing or reviewing a `Random*Builder`** (creating one, adding `with` methods,
  wiring persistence, or registering in a support class), read
  `references/random-builders.md` first — it is the single source of truth for
  builder structure, naming, registration, and available `aRandom` primitives.
- Spock's default (Byte Buddy) mock maker cannot mock final classes or methods. To mock
  a final type (e.g. Azure `ServiceBusException`), construct a real instance or use
  `Mock(mockMaker: MockMakers.mockito, TheFinalClass)` (requires `mockito-core` 4.11+
  on the test classpath). Don't `Mock()` a final class with the default mock maker.
  [PR 559655]

## The three test types (see per-type files)
| Source set | Suffix | Spring? | DB? | Use for | File |
|---|---|---|---|---|---|
| `src/test` | `*Spec` | no | no | isolated unit tests (default) | `test.md` |
| `src/coreTest` | `*CoreSpec` | yes (`@CoreTest`) | yes | repository/service/mapper against real beans + DB | `coreTest.md` |
| `src/componentTest` | `*ComponentSpec` | yes (`@ComponentTest`, real port) | yes | endpoints end-to-end via client classes | `componentTest.md` |

Helper (non-spec) classes use a `*Matcher` / `*Support` / `*Helper` / `*Assertor`
/ `*Builder` suffix, never `*Spec`.

## Naming
- Test class = `<ClassUnderTest>` + the suffix for its source set.
- Feature methods are `"should ..."` sentences describing the behavior.
- Parametrized features use `@Unroll` and a `where:` table, with a `#placeholder`
  in the name.

## Base class & test context (what to extend / implement)
- Every Groovy test class extends Spock's `Specification` — for unit (`*Spec`),
  core (`*CoreSpec`), and component (`*ComponentSpec`) alike.
- When the test uses **entitlements** (e.g. `withEntitlements([...])`, RENXT, etc.),
  extend `EntitledSpecification` instead of `Specification` (it is a `Specification`,
  so this replaces the base class — don't extend both).
- When the test builds or asserts on any Blackbaud-auth field — `bbAuthId` (UUID),
  `environmentId`, `legalEntityId`, `familyName`, `givenName`, `email`, `personId`
  (String), or `portalUser` (Boolean) — add `implements BBAuthSupport` and use its
  helpers to set up the authenticated user context (don't set these fields by hand).

## Entity comparison
- Compare entities/DTOs with `com.blackbaud.testsupport.BeanCompare`.
- Exclude audit fields from comparison:
  `createdByUserId`, `lastModifiedByUserId`, `createdDate`, `lastModifiedDate`.

## What NOT to do (hard rules from resolved review comments)
- Don't mock collaborators in a CoreSpec when a real `@Autowired` bean will do;
  and keep any `ResettingMockInjector.set(...)` in `setup()`. [PR 547471]
- Don't assert Spock mock interactions the implementation doesn't actually make,
  and don't mis-count them (`N * mock.method(...)` must match reality). [PR 549889]
- Don't hide a newly-added field by adding it to `BeanCompare.excludeFields(...)`
  to make a test pass — assert the new field. [PR 549889]
- Don't use non-existent constants like `BigDecimal.FIVE`; use
  `BigDecimal.valueOf(n)` (only `ZERO`, `ONE`, `TEN` exist). [PR 549889]
- Don't test only the happy path for flag-driven logic — add the inverse case. [PR 547471]
- Don't leave unused imports/variables or silence a CodeNarc/PMD rule with inline
  suppressions to get a green build — fix the underlying issue instead.
- Don't mock your own classes — mocking internal classes couples tests to
  implementation details. Only mock **external** dependencies (third-party clients,
  framework interfaces). [copilot-instructions]
- Don't write meaningless tests — no asserting trivially obvious outcomes, no calling
  a method without asserting behavior, no duplicating what a more integrated test
  already covers. [copilot-instructions]
- Don't add a test that would already pass on master before your change — it gives
  false confidence. If the guarantee under test is enforced by pre-existing code
  (e.g. an existing `@Transactional` boundary), the test isn't validating your change.
  Target the new behavior, or rename/rescope the test to claim only what it actually
  verifies. [PR 555094]
- Prefer a CoreSpec for behavior; write a unit `*Spec` only for pure logic that
  genuinely has no Spring/DB dependency. [copilot-instructions]
- Don't `Mock()` a final class (e.g. Azure's `ServiceBusException`) with Spock's
  default Byte Buddy mock maker — it can't mock final classes/methods. Construct a
  real instance, or use `Mock(mockMaker: MockMakers.mockito, TheFinalClass)` with
  `mockito-core` 4.11+ on the test classpath. [PR 559655]

## Static analysis (CodeNarc, PMD & Spotless) — required gate
Every spec must pass static analysis before it's considered done; violations fail
the build. See each type's reference file for the source-set-specific task and the
rules that most often bite.

Task names in this project (use these exactly):
| Tool | Unit (`src/test`) | Core (`src/coreTest`) | Component (`src/componentTest`) | Main Java |
|---|---|---|---|---|
| CodeNarc (Groovy) | `codenarcTest` | `codenarcCoreTest` | `codenarcComponentTest` | — |
| PMD (Java) | `pmdTest` | *(none)* | `pmdComponentTest` | `pmdMainTest` |
| Spotless (Java) | `spotlessJavaCheck` / `spotlessJavaApply` (all source sets) | | | |

### CodeNarc (Groovy specs)
CodeNarc is the gate for every `.groovy` spec. Ruleset: `config/codenarc/codenarc.groovy`.

- Check: `./gradlew clean codenarc<SourceSet>` per the table above, or the aggregate
  `./gradlew clean codenarc`.
- Don't add inline disables / `@SuppressWarnings` to dodge a rule unless the
  surrounding code already does; fix the violation instead.
- Most common in test edits: `UnusedImport` (static imports of `aRandom`, matchers,
  clients, or `ResettingMockInjector` helpers left behind after removing a case),
  `UnusedVariable` / `UnusedMethodParameter` (no leftover `given:` locals or unused
  `where:` columns), `UnnecessaryGString`, `UnnecessarySemicolon`,
  `TrailingWhitespace`, `ConsecutiveBlankLines`, `LineLength`.

### PMD (Java)
PMD analyzes Java only — Groovy specs are skipped, and there is **no `pmdCoreTest`**.
Ruleset: `config/pmd/*.xml`.

- Check: `./gradlew clean pmdTest` (unit Java support classes),
  `./gradlew clean pmdComponentTest` (e.g. `ComponentTest.java`,
  `ComponentTestConfig.java`), `./gradlew clean pmdMainTest` (production Java).
- `./gradlew staticAnalysis` runs cpd + PMD + Spotless together.
- Never silence a rule with inline suppressions to make it pass; fix the violation.

### Spotless (Java formatting)
Spotless is applied by the `blackbaud-internal` Gradle plugin — there is no `spotless {}`
block in `build.gradle`. It formats **Java only** (test support classes like
`ComponentTest.java`, `ResettingMockInjector`, random builders, and any production Java you
touch). Groovy specs are not covered by Spotless — CodeNarc handles those.

- Check formatting: `./gradlew clean spotlessJavaCheck`
- Fix formatting in-place: `./gradlew clean spotlessJavaApply` (preferred over hand-fixing)
- `./gradlew staticAnalysis` runs cpd + PMD + Spotless together.
- A Git pre-push hook can be installed with `./gradlew spotlessInstallGitPrePushHook`, so
  unformatted Java will fail the push — always run `spotlessJavaApply` after editing Java.
- Never hand-format to satisfy the check and never disable Spotless for a file; run the
  `Apply` task and commit the result.

## Running tests
Team convention: **prefix every `./gradlew` command with `clean`** for a fresh build
state. `./gradlew clean check` compiles everything and runs all tests.
Gradle source sets map to tasks:
- Unit: `./gradlew clean test --tests "*SomeSpec"`
- Core: `./gradlew clean coreTest --tests "*SomeCoreSpec"`
- Component: `./gradlew clean componentTest --tests "*SomeComponentSpec"`
- Static analysis: `codenarcTest` / `codenarcCoreTest` / `codenarcComponentTest`
  (or the aggregate `./gradlew clean codenarc`) and, if Java changed,
  `pmdTest` / `pmdComponentTest` / `pmdMainTest` plus
  `spotlessJavaCheck` (fix with `spotlessJavaApply`).
(Gradle wrapper is 8.14.x. `coreTest`/`componentTest` require their DB/context
prerequisites to be available.)
