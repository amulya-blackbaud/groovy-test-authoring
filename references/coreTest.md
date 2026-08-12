# coreTest — conventions (`*CoreSpec.groovy`)

Derived from the real `src/coreTest` tree of `receipt-manager` (107 `*CoreSpec`
files) and from resolved PR review comments. Rules tagged `[PR NNNNNN]` came from
a resolved comment in that pull request.

## Purpose
Integration-style tests that boot the Spring context and exercise a single bean
(repository, service, mapper) against its **real** collaborators and the database.
Slower than unit tests; use them when persistence or Spring wiring is the thing
under test.

## Canonical examples in the repo
- `examples/AbstractGiftReceiptEmailTemplateHistoryCoreSpec.groovy`
- `examples/BaseReceiptFetcherCoreSpec.groovy`
- `examples/ConsolidatedReceiptingServiceCoreSpec.groovy`
- `examples/GftGiftFetcherCoreSpec.groovy`
- `examples/InternalContributorServiceSelectorCoreSpec.groovy`
The following core test files in our codebase serve as excellent references for writing effective and consistent `*CoreSpec.groovy` tests:

- `AbstractGiftReceiptEmailTemplateHistoryCoreSpec.groovy`
  - Demonstrates testing abstract classes and their interactions with subclasses
  - Showcases the use of mocks and stubs for dependent components
  - Verifies the behavior of template rendering and history management

- `BaseReceiptFetcherCoreSpec.groovy`
  - Provides an example of testing base classes and their common functionality
  - Illustrates the use of test data builders or factories for creating test instances
  - Checks the correctness of receipt fetching logic under various scenarios

- `ConsolidatedReceiptingServiceCoreSpec.groovy`
  - Highlights the testing of service-level functionality and integration points
  - Demonstrates the use of `@Autowired` for injecting real dependencies
  - Verifies the consolidated receipting process, including edge cases and error handling

- `GftGiftFetcherCoreSpec.groovy`
  - Showcases the testing of a specific gift fetcher implementation
  - Mocks external services or repositories to focus on the fetcher's logic
  - Covers various gift scenarios and ensures the correct gifts are fetched

- `InternalContributorServiceSelectorCoreSpec.groovy`
  - Exemplifies the testing of service selection logic based on contributor types
  - Uses data-driven tests with `where:` blocks to cover multiple selection scenarios
  - Verifies the correct service is selected for each contributor type

These examples cover a range of testing scenarios, from abstract and base classes to service-level functionality and specific implementations. They demonstrate the effective use of mocking, data-driven tests, and the `@Autowired` annotation for dependency injection.

When writing new core tests or updating existing ones, refer to these examples for guidance on test structure, naming conventions, and the use of Spock features. Strive to maintain consistency with the patterns and best practices showcased in these canonical tests.


## Location & naming
- Path: `src/coreTest/groovy/com/blackbaud/receiptmanager/...`
- Class name: `<ClassUnderTest>CoreSpec` (e.g. `BulkTaskRepositoryCoreSpec`).
- Extends `spock.lang.Specification`; annotate the class with `@CoreTest`
  (`com.blackbaud.receiptmanager.CoreTest`).

## Structure (canonical shape)
```groovy
package com.blackbaud.receiptmanager.core.domain.bulktask

import com.blackbaud.receiptmanager.CoreTest
import com.blackbaud.testsupport.BeanCompare
import com.blackbaud.testsupport.RequiresSasContext
import org.springframework.beans.factory.annotation.Autowired
import spock.lang.Specification

import static com.blackbaud.receiptmanager.core.CoreARandom.aRandom

@CoreTest
@RequiresSasContext
class BulkTaskRepositoryCoreSpec extends Specification {

    @Autowired
    private BulkTaskRepository bulkTaskRepository

    private BeanCompare beanCompare = new BeanCompare()
            .excludeFields("createdByUserId", "lastModifiedByUserId", "createdDate", "lastModifiedDate")

    def "should be able to save and retrieve bulk tasks"() {
        given:
        BulkTaskEntity entity = aRandom.bulkTaskEntity().build()

        when:
        bulkTaskRepository.save(entity)

        then:
        beanCompare.assertEquals(entity, bulkTaskRepository.findById(entity.id).get())
    }
}
```

## Rules
- Obtain the bean under test with `@Autowired`. Do **not** mock collaborators in a
  CoreSpec unless there is no alternative — the point of a CoreSpec is to run
  against real beans. [PR 547471]
- If you genuinely must substitute a collaborator, use
  `ResettingMockInjector.set(bean, "fieldName", mockOrValue)` and place it in a
  `setup()` method, not inside an individual feature method. [PR 547471]
- Add `@RequiresSasContext` when the test touches persistence; add
  `@RequiresBbAuthContext(...)` when it needs an auth context.
- Build test data with `aRandom` (static import from
  `com.blackbaud.receiptmanager.core.CoreARandom`): `.build()` for in-memory,
  `.save()` when the row must exist in the DB.
- Compare entities with `BeanCompare`, excluding the audit fields
  `createdByUserId`, `lastModifiedByUserId`, `createdDate`, `lastModifiedDate`.
- For behavior gated by a boolean/flag, cover **both** the true path and the
  false (inverse) path. [PR 547471]
- When a query or behavior orders by a JPA-audited timestamp (e.g. `lastModifiedDate`),
  set explicit, distinct timestamps in the fixtures via the SQL-based mechanism in
  `RandomReceiptEntityBuilder` — don't rely on the order of consecutive `.save()` calls.
  Auditing may assign equal timestamps, so the DB can return either row and the test
  flakes. Assert strict descending order, not `>=`. [PR 559975]
- Name features as `"should ..."` sentences; use `given/when/then`.

## Canonical examples in the repo
- `core/domain/bulktask/BulkTaskRepositoryCoreSpec.groovy` — minimal repository spec.
- `core/domain/mappers/EmailTemplateInfoRequestMapperCoreSpec.groovy` — mapper spec
  with a private `assert*` helper for shared assertions.

## Static analysis — CodeNarc, PMD & Spotless must pass
A CoreSpec is not "done" until static analysis is green; violations fail the build.

- **CodeNarc** checks the Groovy spec. After writing/editing, run
  `./gradlew clean codenarcCoreTest` and fix every violation; the ruleset and the
  most common violations are in `references/conventions.md`.
- **PMD** checks Java only — there is **no `pmdCoreTest`** task, so CodeNarc is the gate
  for this source set. If you also change production Java, run
  `./gradlew clean pmdMainTest` (also runs as part of `./gradlew staticAnalysis`).
- **Spotless** formats the Java in this source set. If you add or edit any Java support
  class, run `./gradlew clean spotlessJavaApply` before finishing; verify with
  `./gradlew clean spotlessJavaCheck` (also runs as part of `./gradlew staticAnalysis`).
