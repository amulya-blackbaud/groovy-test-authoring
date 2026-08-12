# test — conventions (unit specs, `*Spec.groovy`)

Derived from the real `src/test` tree (126 `*Spec` files) and from resolved PR
review comments. Rules tagged `[PR NNNNNN]` came from a resolved comment in that PR.

## Purpose
Fast, isolated unit tests. **No** Spring context and **no** database. Exercise one
class directly and mock its collaborators. This is the default test type — reach
for coreTest/componentTest only when you actually need Spring or the DB.

## Location & naming
- Path: `src/test/groovy/com/blackbaud/receiptmanager/...`
- Class name: `<ClassUnderTest>Spec`.
- Extends `spock.lang.Specification`; **no** `@CoreTest` / `@ComponentTest`.

## Structure (canonical shape)
```groovy
package com.blackbaud.receiptmanager.core.api.converters

import spock.lang.Specification
import spock.lang.Unroll

import static com.blackbaud.receiptmanager.core.CoreARandom.aRandom

class InternalBenefitsConverterSpec extends Specification {

    def "should return null if source is null"() {
        expect:
        assert InternalBenefitsConverter.convertToInternalBenefit(null) == null
    }

    @Unroll
    def "should default when provided with #desc type"() {
        expect:
        assert BulkTaskFormat.bulkTaskFormatOrDefaultToEmail(type).email

        where:
        desc      | type
        "unknown" | aRandom.text()
        "null"    | null
    }
}
```

## Rules
- Call the class under test directly; mock/stub **external** collaborators with
  Spock `Mock()` / `Stub()`. Do not mock the class under test or other internal
  classes. [copilot-instructions]
- Always write explicit `assert` statements (also in `expect:`); never rely on
  Spock's implicit last-expression assertion. [copilot-instructions]
- Mock interaction cardinalities (`N * mock.method(...)`) must match exactly how
  many times the implementation actually calls them. Don't assert an interaction
  the code never makes, and don't under/over-count. [PR 549889]
- When a change adds a new mapped/returned field, assert that field — do not add
  it to `BeanCompare.excludeFields(...)` to make the test pass. [PR 549889]
- Use valid `BigDecimal` values: only `ZERO`, `ONE`, `TEN` exist as constants;
  for anything else use `BigDecimal.valueOf(n)` (there is no `BigDecimal.FIVE`).
  [PR 549889]
- Use `@Unroll` with a `where:` data table for parametrized cases; use `expect:`
  for one-line assertions.
- Build data with `aRandom`; factor repeated assertions into a static helper class
  named `*Matcher` / `*Support` (e.g. `InternalBenefitsMatcher.assertBenefitMatch`).
- For flag-driven logic, cover both the true and false paths.
- When a field's type changes (e.g. String → structured object), update assertions to compare like types — apply the same formatter/converter the production code uses to the expected value, rather than asserting a formatted String equals the raw object.

## Canonical examples in the repo

Read the one closest to what you're testing before writing. All paths are under
`examples/`.

- `TransactionReceiptRequestValidatorSpec.groovy` —
  **validator / error paths.** 14 cases, each asserting a specific domain exception
  via `thrown()` plus explicit `assert` on the exception message. Follow this when
  testing guard clauses or request validation.
- `PaymentSectionCreatorSpec.groovy` —
  **data-driven logic.** 12 cases with extensive `@Unroll` + `where:` tables and
  `aRandom` builders. Follow this for formatting/branching logic with many input
  permutations; note how each column of the `where:` table is actually used.
- `EmailErrorServiceSpec.groovy` —
  **service with mocked collaborators.** 10 cases wiring the class under test with
  Spock `Mock()`s via property injection in `setup()`, asserting exact interaction
  cardinalities (`1 * mock.method(...)`). Follow this when a unit-testable service
  delegates to repositories/clients.
- `ReceiptEntitySpec.groovy` —
  **domain model behavior.** 7 cases covering entity methods, boolean checks, and
  an error path — no Spring, no mocks, just `aRandom` entities and explicit asserts.
- `ReceiptSeriesMapperSpec.groovy` —
  **MapStruct mapper.** 6 cases asserting every mapped field explicitly (35 asserts),
  including null-source and null-field handling. Follow this instead of using
  `BeanCompare.excludeFields(...)` to dodge new fields.
- `core/api/converters/InternalBenefitsConverterSpec.groovy` + its
  `InternalBenefitsMatcher.groovy` helper — factoring shared assertions into a
  `*Matcher` helper.
- `core/domain/bulktask/BulkTaskFormatSpec.groovy` — minimal `@Unroll` + `where:` table.

## Static analysis — CodeNarc, PMD & Spotless must pass
A unit spec is not "done" until static analysis is green; violations fail the build.

- **CodeNarc** checks the Groovy spec. After writing/editing, run
  `./gradlew clean codenarcTest` and fix every violation; the ruleset and the
  most common violations are in `references/conventions.md`.
- **PMD** checks Java only (Groovy specs and `*Matcher`/`*Support` helpers are skipped).
  If you add or edit any Java test-support class, run `./gradlew clean pmdTest`; if you
  also change production Java, run `./gradlew clean pmdMainTest` (both run as part of
  `./gradlew staticAnalysis`).
- **Spotless** formats the Java in this source set. If you add or edit any Java support
  class, run `./gradlew clean spotlessJavaApply` before finishing; verify with
  `./gradlew clean spotlessJavaCheck` (also runs as part of `./gradlew staticAnalysis`).
