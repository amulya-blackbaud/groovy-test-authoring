# componentTest — conventions (`*ComponentSpec.groovy`)

Derived from the real `src/componentTest` tree (30 `*ComponentSpec` files) and the
`@ComponentTest` annotation.

## Purpose
Full-stack tests that boot the whole application on a real port and exercise HTTP
endpoints through the generated **client** classes. Use them to verify a
resource/endpoint end-to-end, including auth and permissions.

## Location & naming
- Path: `src/componentTest/groovy/com/blackbaud/receiptmanager/...`
- Class name: `<Resource>ComponentSpec` (e.g. `AdminEmailTemplateResourceComponentSpec`).
- Extends `spock.lang.Specification`; annotate the class with `@ComponentTest`.

`@ComponentTest` runs `@SpringBootTest` with a DEFINED_PORT web environment
(server on 10000), active profiles `local, test, componentTest, sharedTest`, and
runs `db/test_cleanup.sql` before every test method — so each feature starts from
a clean database.

## Structure (canonical shape)
```groovy
package com.blackbaud.receiptmanager.resources

import com.blackbaud.boot.exception.ForbiddenException
import com.blackbaud.receiptmanager.ComponentTest
import com.blackbaud.receiptmanager.client.AdminEmailTemplateClient
import com.blackbaud.testsupport.RequiresBbAuthContext
import org.springframework.beans.factory.annotation.Autowired
import spock.lang.Specification

import static com.blackbaud.receiptmanager.core.CoreARandom.aRandom
import static com.blackbaud.receiptmanager.resources.GeneratedPermissions.RECEIPTING_VIEW

@ComponentTest
class AdminEmailTemplateResourceComponentSpec extends Specification {

    @Autowired
    private AdminEmailTemplateClient adminEmailTemplateClient

    @RequiresBbAuthContext(supportal = true, includedPermissions = [RECEIPTING_VIEW])
    def "should return Forbidden for GET emailtemplates when user lacks permission"() {
        when:
        adminEmailTemplateClient.findEmailTemplatesForEnvironment(aRandom.environmentId())

        then:
        thrown ForbiddenException
    }
}
```

## Rules
- Drive the endpoint through the injected `*Client` bean (`@Autowired`), not by
  calling the resource class directly — this is what makes it a component test.

- Set up auth/permission context with
  `@RequiresBbAuthContext(supportal = ..., includedPermissions = [...])` on the
  feature method.
- Assert failure/permission paths with Spock's `thrown SomeException`
  (e.g. `thrown ForbiddenException`). Include both a forbidden/negative case and a
  success case for a new endpoint.
- Substitute external collaborators (not the endpoint itself) with
  `ResettingMockInjector` when a real downstream call isn't desirable.
- Reuse `aRandom` for request data; don't hand-roll payloads.

## Canonical examples in the repo
All paths relative to `examples/`.

- `AdminReceiptResourceComponentSpec.groovy` (19 tests) — the largest resource spec.
  Class-level `@RequiresBbAuthContext(environmentId = SUPPORTAL_ENVIRONMENT_ID, supportal = true,
  includedPermissions = RECEIPTING_VIEW)` overridden per-method for 403/forbidden cases; implements
  `BBAuthSupport` + `StubTracerSupport`; swaps supportal services with `ResettingMockInjector`.
  Reference for: supportal/admin endpoints, permission matrices, mixed success + forbidden flows.

- `AdminResourceComponentSpec.groovy` (14 tests) — extends `EntitledSpecification`;
  uses `@Unroll` data-driven cases; persists real entities with `aRandom.*().save()` and
  `aRandom.nonEmptyList { ... }` to verify query/filter endpoints (e.g. bulk tasks by
  environment/date range). Reference for: seeding the real DB and asserting what the endpoint returns.

- `PortalResourceComponentSpec.groovy` (8 tests) — portal-user auth scenarios via
  `aRandom.personContext().portalUser(true)`; implements `BlobCleanupSupport`; `@Unroll` tables
  covering invalid-user edge cases; mocks own bulk-task services on the resource via
  `ResettingMockInjector`. Reference for: portal endpoints, blob cleanup, negative auth tables.

- `GivingStatementConstituentServiceComponentSpec.groovy`
  (7 tests) — a **service-layer** component spec (not a resource): drives a service that calls
  external SAS clients (`RenxtGiftSasClient`, `RenxtListsClient`) with stubbed external responses
  (`aRandom.renxtGiftsResponse()`), including pagination. Reference for: component-testing a service
  whose behavior spans external HTTP clients.

- `ConsolidatedReceiptingResourceComponentSpec.groovy` (6 tests) — end-to-end
  consolidated receipting flow: resource → processors → external gift/user/list clients; mocks only
  the external `RenxtContributorService` boundary via `ResettingMockInjector`. Reference for:
  multi-collaborator end-to-end flows through a single endpoint.

## Static analysis — CodeNarc, PMD & Spotless must pass
A ComponentSpec is not "done" until static analysis is green; violations fail the build.

- **CodeNarc** checks the Groovy spec. After writing/editing, run
  `./gradlew clean codenarcComponentTest` and fix every violation; the ruleset and the
  most common violations are in `references/conventions.md`.
- **PMD** checks the Java support classes in this source set (`ComponentTest.java`,
  `ComponentTestConfig.java`, and any Java helpers you add or change). If you touch any,
  run `./gradlew clean pmdComponentTest`; if you also change production Java, run
  `./gradlew clean pmdMainTest` (both run as part of `./gradlew staticAnalysis`).
- **Spotless** formats the Java in this source set. If you add or edit any Java support
  class, run `./gradlew clean spotlessJavaApply` before finishing; verify with
  `./gradlew clean spotlessJavaCheck` (also runs as part of `./gradlew staticAnalysis`).
