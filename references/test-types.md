# Choosing the right test type

Decide by **what is under test and how much of the system it needs**, then open
that type's reference file.

1. Testing one class's logic in isolation, no Spring, no DB
   → **unit** (`src/test`, `*Spec`) → `test.md`. This is the default.
2. Testing a repository/service/mapper that needs real Spring beans and/or the
   database → **core** (`src/coreTest`, `*CoreSpec`, `@CoreTest`) → `coreTest.md`.
3. Testing an HTTP endpoint end-to-end through the client, including auth/permissions
   → **component** (`src/componentTest`, `*ComponentSpec`, `@ComponentTest`) → `componentTest.md`.

Prefer the narrowest type that still covers the behavior. Mirror where similar
existing tests for the same area live — the source set a class's neighbors use is
usually the right one. If genuinely ambiguous, ask.

## CoreSpec vs unit Spec — decision checklist

Derived from analyzing the actual receipt-manager test trees (129 unit `*Spec`
vs 104 `*CoreSpec` files). The dominant signal is **whether correctness depends
on Spring wiring or persistence**:

- Of classes covered by CoreSpecs, ~82% have `@Autowired` collaborators and
  ~31% inject a `*Repository` directly.
- Of classes covered by unit Specs, ~45% have **no Spring annotations at all**
  (pure logic), and only ~4% touch a repository.

Walk this checklist top-down; the first match wins.

### → CoreSpec (`src/coreTest`) when the class…
1. **Is a Spring Data repository** (`*Repository`, extends `JpaRepository` /
   `CosmosRepository`). Query derivation and persistence only prove themselves
   against a real DB.
2. **Injects a repository** (`@Autowired *Repository` field) — services,
   purgers, supportal/transactional services whose behavior is read/write of
   real rows.
3. **Depends on Spring wiring to be correct**: `@Qualifier`-selected beans,
   `ServiceLocatorFactoryBean` factories, `@Component("<TYPE>")` strategy
   selection, selector classes (`*Selector`), processor factories, or
   `@ConfigurationProperties`-driven behavior.
4. **Is a Service Bus handler/processor** whose end-to-end routing matters
   (`*MessageHandler`, `*Processor` resolved by a factory) — use
   `JsonMessageTestPublisher.sendAndWaitUntilProcessed(...)` +
   `ResettingMockInjector` for external collaborators only.
5. **Uses entity auditing, converters, or transactions** (`@Transactional`,
   `AuditingEntityListener`, persistence converters) — these only fire inside
   a real context.
6. **Has many `@Autowired` internal collaborators** such that a unit test would
   mostly be mocking your own classes — the team rule is *don't mock your own
   classes*, so wire it for real in a CoreSpec instead.

### → unit Spec (`src/test`) when the class…
1. **Is pure logic with no Spring annotations**: converters (`*Converter`),
   formatters (`*Formatter`), creators (`*Creator`), utilities, enums with
   behavior, domain models/entities with methods (`totalBenefitAmount()`-style).
2. **Is a MapStruct mapper** (`@Mapper`) — instantiate via
   `Mappers.getMapper(...)`; field-by-field mapping needs no context.
3. **Is a validator / guard** (`*Validator`, `accepts()` methods on handlers) —
   input → exception/boolean, no wiring involved.
4. **Only collaborates with external dependencies** (third-party clients,
   framework interfaces) that would be mocked in either layer anyway — a Spring
   context adds nothing.
5. **Has trivially injectable collaborators** (1–2 mocks of *external* deps,
   set via Groovy property injection in `setup()`), e.g. `EmailErrorServiceSpec`.

### Both layers for the same class
It is legitimate (12 classes in the repo do this) to have **both**: a unit Spec
for pure guards/branching (e.g. `PdfJobCompletedMessageHandlerSpec` testing
`accepts()`) *and* a CoreSpec for the wired end-to-end behavior
(`PdfJobCompletedMessageHandlerCoreSpec` publishing real messages). Split by
concern, don't duplicate cases.

### Usually no spec at all
DTOs/models with no behavior (Lombok-only), custom exceptions, `@Configuration`
classes, interfaces, and constants classes. Their behavior is covered through
the classes that use them.

### Quick suffix heuristics (from the real tree — a prior, not a rule)
| Class kind | Usual layer |
|---|---|
| `*Repository` | Core |
| `*Service` with repo/DB writes | Core |
| `*Selector`, `*Updater`, `*Upserter`, `*Fetcher`, `*Scheduler`, `*Publisher` | Core |
| `*MessageHandler` / `*Processor` (wired routing) | Core (+ unit for `accepts()`) |
| `*Converter`, `*Formatter`, `*Creator` | Unit |
| `*Mapper` (MapStruct) | Unit |
| `*Validator`, entities/domain models with logic | Unit |
| `*Resource` (`@RestController`) | Component |

Always read the class's actual dependencies before deciding — the injected
fields, not the name, determine the layer.
