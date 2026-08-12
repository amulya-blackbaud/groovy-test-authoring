# Copilot Instructions — receipt-manager

## Project Overview
`receipt-manager` is a **Spring Boot microservice** responsible for generating, managing, and delivering donation receipts and giving statements. It is part of the Blackbaud RE NXT platform and integrates with Azure Service Bus, Azure Blob Storage, MS SQL Server, and Azure Cosmos SQL.

---

## Architecture

The project follows a **layered architecture** with strict separation of concerns:

```
resources/          → REST controllers (HTTP entry points)
core/service/       → Business logic services
core/domain/        → JPA entities, Spring Data repositories, MapStruct mappers
core/api/           → Internal data models (DTOs between layers)
external/           → Clients for external Blackbaud services
servicebus/         → Azure Service Bus message handlers & processors
```

Multi-module Gradle project:
- `receipt-manager` — main service
- `rest-client` — published REST API contracts
- `service-bus-client` — Service Bus message definitions
- `performance-tests` — Gatling load tests

---

## Naming Conventions

| Type | Convention | Example |
|------|-----------|---------|
| REST API DTOs | `*Request` / `*Response` | `ReceiptSettingsRequest` |
| Internal models | `Internal*` | `InternalGift`, `InternalContributor` |
| JPA Entities | `*Entity` | `GivingStatementEntity` |
| Repositories | `*Repository` | `GivingStatementRepository` |
| Admin repos | `*SupportalRepository` | `GiftReceiptSupportalRepository` |
| Services | behavior-driven name | `BulkTaskService`, `GftGiftReceiptStatusUpdater`, `RenxtReceiptHistoryUpdater` |
| Service Bus handlers | `*MessageHandler` | `PdfJobCompletedMessageHandler` |
| Processors | `*Processor` / `*ProcessorFactory` | `GivingStatementProcessorFactory` |
| MapStruct mappers | `*Mapper` / `*ResponseMapper` | `ConsolidatedReceiptResponseMapper` |
| Custom exceptions | `*Exception` | `MergeFieldFormatException` |
| Packages | `com.blackbaud.receiptmanager.{layer}.{domain}` | `core.service.consolidatedreceipting` |

**Service naming:** Names are behavior-driven and do not need to end with `Service`. Use a suffix that describes what the class does: `*Updater`, `*Fetcher`, `*Upserter`, `*Validator`, `*Scheduler`, `*Processor`, `*Service` (only when truly a general orchestrator). Examples: `GftGiftReceiptStatusUpdater`, `RenxtReceiptHistoryUpdater`, `RenxtReceiptUpserter`, `BulkTaskService`.

**Method naming:**
- `getXxx()` — only for true property accessors (Lombok-generated or equivalent). Do not use for queries or lookups.
- `findXxx()` — retrieve data that may or may not exist (returns `Optional` or nullable)
- `fetchXxx()` — retrieve data that is expected to exist, fetching it from an external source
- `retrieveXxx()` — retrieve data from the current system or context, typically by key
- `formattedXxx()` — e.g., `formattedGiftAmountCurrencyOrFail()`
- `toXxx()` / `fromXxx()` — converters
- `hasXxx()` / `isXxx()` — boolean checks

**Constants:** `UPPER_SNAKE_CASE`, stored in static nested classes:
```java
public static final class Path {
    public static final String EMAIL = "/email";
    public static final String BULK_TASK = "/bulk";
}
public static final class ParamOrVar {
    public static final String ENVIRONMENT_ID = "environmentId";
}
```

---

## Coding Patterns

### Lombok on Every Model

Apply these Lombok annotations consistently on data models:

```java
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class ConsolidatedReceiptResponse {
    private UUID consolidatedReceiptId;
    private String environmentId;
}
```

Use `@Builder(toBuilder = true)` on any class that needs to be copied with modifications.

### MapStruct for Entity-to-DTO Mapping

Never map by hand — use MapStruct interfaces:

```java
@Mapper(componentModel = "spring", config = ErrorUnmappedMapperConfig.class)
public interface ConsolidatedReceiptResponseMapper {
    @Mapping(target = "emailSentDate", source = "emailReceiptStatusDate")
    ConsolidatedReceiptResponse toApi(GivingStatementEntity source);
}
```

- Always use `config = ErrorUnmappedMapperConfig.class` to catch unmapped fields at compile time.
- Name converter methods `toApi()`, `toEntity()`, `toInternal()`.

### REST Resources

All REST controllers follow this structure:

```java
@RestController
@RequestMapping(path = EMAIL, produces = MediaType.APPLICATION_JSON_VALUE)
@SecurityRequirement(name = "BBID")
@PreAuthorize("hasBbAuthPermission('receipts.generate')")
public class EmailResource {

    @ContentSecurityPolicy
    @GetMapping
    public ResponseEntity<EmailTemplateInfoResponse> getDefaultEmailTemplateInfo(
        @RequestParam String type
    ) {
        return emailTemplateInfoService.get(type)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
```

- Always annotate controllers with `@SecurityRequirement(name = "BBID")`.
- Always use `@PreAuthorize("hasBbAuthPermission(...)")` for authorization.
- Always add `@ContentSecurityPolicy` to GET endpoints.
- Use `Optional`-returning services and map to `ResponseEntity` in the controller.
- Define all path strings as constants in `Resource.Path`; never inline strings.

### Service Bus Handlers

#### Files to Create

When adding a new Service Bus consumer, create the following files. Use a dedicated sub-package under `servicebus/` named after the topic (e.g., `servicebus/pdfjobcompleted/`):

| File | Purpose |
|------|---------|
| `*ServiceBusProperties.java` | Binds `servicebus.<topic-name>.*` config properties |
| `*MessageHandler.java` | Entry point — receives the raw `ServiceBusMessage<T>`, validates it, and delegates |
| `*Processor.java` | Interface declaring `processMessage(...)` |
| `*ProcessorFactory.java` | Interface for selecting the right processor implementation by type |
| `*ProcessorConfig.java` | `@Configuration` that wires the `ServiceLocatorFactoryBean` for the factory |
| `*ProcessorType.java` | Constants / logic for resolving which processor type to use |
| Concrete `*Processor` impls | One `@Component("<TYPE>")` per strategy, implementing `*Processor` |

Register each handler and consumer in `ServiceBusConfig`:
- Declare the handler bean with `@Bean`
- Declare the consumer bean using `ServiceBusConsumerBuilder`
- Add the `*ServiceBusProperties` class to `@EnableConfigurationProperties`
- Add `@ComponentScan` for the new sub-package if needed (e.g., for the processor `@Component` beans)

#### Example: `pdfjobcompleted` package

**`PdfJobCompletedServiceBusProperties.java`** — binds config:
```java
@ConfigurationProperties(prefix = "servicebus.pdf-job-completed")
public class PdfJobCompletedServiceBusProperties extends ServiceBusProperties { }
```

**`PdfJobCompletedMessageHandler.java`** — handler (delegates to the correct processor):
```java
@Slf4j
public class PdfJobCompletedMessageHandler implements MessageHandler<PdfJobCompletedPayload> {

    @Autowired
    private BulkTaskService bulkTaskService;

    @Autowired
    private PdfJobCompletedMessageProcessorFactory pdfJobCompletedMessageProcessorFactory;

    @Override
    public boolean accepts(ServiceBusMessage<PdfJobCompletedPayload> message) {
        return message.getPayload().getPdfJobId() != null;
    }

    @Override
    public void process(ServiceBusMessage<PdfJobCompletedPayload> message) {
        PdfJobCompletedPayload payload = message.getPayload();
        String environmentId = RequestContext.get().getEnvironmentId();
        String jobId = payload.getPdfJobId();
        String processorType = resolveProcessorType(environmentId, jobId);
        try {
            pdfJobCompletedMessageProcessorFactory
                    .getProcessor(processorType)
                    .processMessage(environmentId, jobId);
        } catch (NoSuchBeanDefinitionException e) {
            throw new UnknownBulkTaskFormatException("%s to process pdfJobId=%s", e.getMessage(), jobId);
        }
    }

    private String resolveProcessorType(String environmentId, String jobId) {
        BulkTaskEntity bulkTask = bulkTaskService.findByEnvironmentIdAndJobId(environmentId, jobId).orElse(null);
        return PdfJobCompletedMessageProcessorType.processorType(bulkTask);
    }
}
```

**`PdfJobCompletedMessageProcessor.java`** — processor interface:
```java
public interface PdfJobCompletedMessageProcessor {
    void processMessage(String environmentId, String pdfJobId);
}
```

**`PdfJobCompletedMessageProcessorFactory.java`** — factory interface (resolved by `ServiceLocatorFactoryBean`):
```java
public interface PdfJobCompletedMessageProcessorFactory {
    PdfJobCompletedMessageProcessor getProcessor(String type);
}
```

**`PdfJobCompletedMessageProcessorConfig.java`** — wires the factory:
```java
@Configuration
public class PdfJobCompletedMessageProcessorConfig {

    @Bean("pdfJobCompletedMessageProcessorFactory")
    public FactoryBean<Object> serviceLocatorFactoryBean() {
        ServiceLocatorFactoryBean factoryBean = new ServiceLocatorFactoryBean();
        factoryBean.setServiceLocatorInterface(PdfJobCompletedMessageProcessorFactory.class);
        return factoryBean;
    }
}
```

**`StandalonePdfJobCompletedMessageProcessor.java`** — one strategy implementation:
```java
@Slf4j
@Component(STANDALONE)
public class StandalonePdfJobCompletedMessageProcessor implements PdfJobCompletedMessageProcessor {

    @Autowired
    private BulkTaskService bulkTaskService;

    @Autowired
    private BaseReceiptHistoryUpdater baseReceiptHistoryUpdater;

    @Override
    public void processMessage(String environmentId, String pdfJobId) {
        bulkTaskService.findByEnvironmentIdAndJobId(environmentId, pdfJobId)
                .ifPresentOrElse(bulkTask -> {
                    RequestContext.withUserAndVoidResponse(
                            bulkTask.getCreatedByUserId(),
                            () -> {
                                updateReceiptHistoryForCompletedRecords(bulkTask);
                                bulkTaskService.complete(bulkTask);
                            }
                    );
                }, () -> log.error("No bulk task found for pdfJobId={}, environmentId={}", pdfJobId, environmentId));
    }

    private void updateReceiptHistoryForCompletedRecords(BulkTaskEntity bulkTask) {
        bulkTaskService.findTaskRecordsInCompletedStatus(bulkTask)
                .forEach(taskRecord -> baseReceiptHistoryUpdater.updateReceiptHistoryStatusToIssuedOrDuplicate(taskRecord));
    }
}
```

**Registration in `ServiceBusConfig.java`:**
```java
@Bean
public PdfJobCompletedMessageHandler pdfJobCompletedMessageHandler() {
    return new PdfJobCompletedMessageHandler();
}

@Bean
public ServiceBusConsumer pdfJobCompletedConsumer(
        ServiceBusConsumerBuilder.Factory serviceBusConsumerFactory,
        PdfJobCompletedMessageHandler pdfJobCompletedMessageHandler,
        PdfJobCompletedServiceBusProperties serviceBusProperties,
        RetryTopicServiceBusConsumer retryConsumer) {
    return serviceBusConsumerFactory.create()
            .dataSyncTopicServiceBus(serviceBusProperties)
            .jsonMessageHandler(pdfJobCompletedMessageHandler, PdfJobCompletedPayload.class)
            .multiSubscriberBackoffStrategy(retryConsumer)
            .build();
}
```

#### Key Rules
- Guard with `accepts()` when the handler must filter messages before processing.
- Use a `*ProcessorFactory` + `@Component("<TYPE>")` strategy when a single topic requires different behavior based on message content or task type.
- Throw `RetryableProcessException` for transient failures (Service Bus will retry).
- Throw `DeadLetterException` for unrecoverable failures (invalid payload, missing required fields).
- Never hard-code topic names or connection strings — always use `*ServiceBusProperties` bound from config.
- Consumers that must not run in certain profiles are excluded with `@Profile("!vstsTest")` on the consumer `@Bean`.

### Repository Patterns

Prefer Spring Data method-name queries; use `@Query` only for complex operations:

```java
// Method-name derived (preferred)
List<GivingStatementEntity> findAllByEnvironmentIdAndConstituentId(
    String environmentId, String constituentId
);

// Custom query (only when needed)
@Modifying
@Query("DELETE FROM GivingStatementEntity WHERE environmentId = :environmentId")
int deleteAllByEnvironmentId(@Param("environmentId") String environmentId);
```

### Audit Fields on All Entities

Every JPA entity must include audit fields:

```java
@EntityListeners(AuditingEntityListener.class)
public class GivingStatementEntity {
    @CreatedBy
    private UUID createdBy;

    @CreatedDate
    @Convert(converter = OffsetDateTimePersistenceConverter.class)
    private OffsetDateTime createdDate;

    @LastModifiedBy
    private UUID lastModifiedBy;

    @LastModifiedDate
    @Convert(converter = OffsetDateTimePersistenceConverter.class)
    private OffsetDateTime lastModifiedDate;
}
```

### Error Handling

Create domain-specific exceptions that extend framework base exceptions:

```java
public class MergeFieldFormatException extends BadRequestException {
    public MergeFieldFormatException(String field, String giftId) {
        super("Required field %s missing for giftId=%s", field, giftId);
    }
}
```

In resources, catch known exceptions and convert to typed error responses:
```java
} catch (EnumConstantNotPresentException e) {
    throw new BadRequestException(
        ErrorEntity.builder().message(e.getMessage()).build()
    );
}
```

---

## Spring Patterns

### Dependency Injection

Use **`@Autowired` field injection** exclusively. Constructor injection (Lombok `@RequiredArgsConstructor`) is not used on Spring-managed beans in this project.

```java
@Service
@Slf4j
public class ConsolidatedReceiptingService {
    @Autowired
    private GivingStatementRepository givingStatementRepository;

    @Autowired
    private ConsolidatedReceiptResponseMapper consolidatedReceiptResponseMapper;
}
```

- Never use constructor injection on `@Service`, `@Component`, or `@RestController` classes.
- `@Value` is used alongside `@Autowired` to inject individual configuration properties.

### Application Entry Point

The main application class uses `@Configuration` + `@Import` — **not** `@SpringBootApplication`. Never add `@SpringBootApplication` to any class.

```java
@Configuration
@ComponentScan("com.blackbaud.receiptmanager.resources")
@Import({ CoreConfig.class, WebMvcRestServiceConfig.class, DeadLetterQueueConfig.class, PermissionsConfig.class })
public class ReceiptManager {
    public static void main(String[] args) {
        SpringApplication.run(ReceiptManager.class, args);
    }
}
```

`CoreConfig` is the hub for domain-layer configuration: it declares `@ComponentScan` for the core package, imports infrastructure configs (`CosmosSqlConfig`, `ServiceBusConfig`, etc.), and provides `@Bean` methods for complex objects.

### Bean Declaration

Use **stereotype annotations** for application classes and **`@Bean` in `@Configuration`** for infrastructure or third-party objects.

| Use case | Annotation |
|---|---|
| Business logic class | `@Service` |
| Helper / utility class | `@Component` |
| Spring Data repository | `@Repository` |
| REST endpoint class | `@RestController` |
| Infrastructure / third-party object | `@Bean` in a `@Configuration` class |

Named components use explicit bean names for disambiguation:
```java
@Component("INDIVIDUAL_GIVING_STATEMENT_PROCESSOR_SERVICE")
public class IndividualGivingStatementProcessor implements GivingStatementProcessor { ... }
```

Use `@Qualifier` when multiple beans of the same type exist (e.g., named Service Bus publishers).

### Configuration Properties

All Service Bus topic/subscription settings are bound via `@ConfigurationProperties`:

```java
@ConfigurationProperties(prefix = "servicebus.fms-receipts")
public class ReceiptsServiceBusProperties extends ServiceBusProperties { }
```

Register them with `@EnableConfigurationProperties` in `ServiceBusConfig`. Never hard-code topic names or connection strings.

### Controller Structure

All REST controllers follow this pattern:

```java
@RestController
@RequestMapping(produces = MediaType.APPLICATION_JSON_VALUE)
public class ReceiptResource {

    @GetMapping(RECEIPT_PATH + "/{id}")
    @PreAuthorize("hasPermission(...)")
    public ReceiptResponse getReceipt(@PathVariable("id") UUID id) { ... }

    @PostMapping(RECEIPT_PATH)
    public ReceiptResponse createReceipt(@Valid @RequestBody ReceiptRequest request) { ... }
}
```

- Always set `produces = MediaType.APPLICATION_JSON_VALUE` at the class level.
- Always use `@Valid` on `@RequestBody` parameters.
- Secure endpoints with `@PreAuthorize`; use `@BypassAuth` only for explicitly public endpoints.

### Transaction Management

`@Transactional` is used **selectively** — only for SQL operations that must be atomic (e.g., purge/delete sequences). Avoid adding `@Transactional` to Cosmos-only service methods.

```java
@Transactional
public void purgeReceiptHistories(String environmentId) {
    receiptHistorySupportalRepository.deleteAllByEnvironmentId(environmentId);
}
```

Long-running purge operations that should not hold a transaction are explicitly non-transactional.

### Async Processing

There is **no `@Async`** in this project. All asynchronous work is handled via Azure Service Bus message handlers. Never use `@EnableAsync` or `@Async`.

### Service Bus Consumers and Profiles

Service Bus consumers that should not run in the `vstsTest` environment are excluded with `@Profile`:

```java
@Bean
@Profile("!vstsTest")
public ServiceBusConsumer receiptConsumer(...) { ... }
```

### Exception Handling

There is no `@ControllerAdvice`. Exceptions propagate from services; the Blackbaud framework translates domain exceptions to HTTP responses. Throw specific domain exceptions (e.g., `ReceiptNotFoundException`) and use `orElseThrow()` on `Optional` results.

---

## Testing

### Test Layers

| Layer | Source Set | Naming | What belongs here |
|-------|-----------|--------|-------------------|
| Unit | `src/test/groovy/` | `*Spec.groovy` | Pure logic with no Spring context or DB — converters, formatters, `accepts()` guards on message handlers, domain model behavior, simple conditional logic. Keep these minimal; CoreSpecs are preferred when a real DB or Spring context adds meaningful coverage. |
| Integration / CoreSpec | `src/coreTest/groovy/` | `*CoreSpec.groovy` | Behavior-driven tests with a real Spring context and real DB. Preferred for services, message handlers, and any class whose correctness depends on wired dependencies. This layer is growing — favor CoreSpecs over unit tests that mock own classes. |
| Component | `src/componentTest/groovy/` | `*ComponentSpec.groovy` | Full end-to-end service boundary tests with mocked external systems (HTTP clients, email, etc.). |

**Guiding principles:**
- Write CoreSpecs for behavior. Only write unit Specs for pure logic that genuinely has no Spring/DB dependency.
- Do not mock your own classes. Mocking internal classes couples tests to implementation details. Only mock external dependencies (third-party clients, framework interfaces).
- Avoid meaningless tests — tests that assert trivially obvious outcomes, just call a method without asserting behavior, or duplicate what a more integrated test already covers.
- `ResettingMockInjector` is used in CoreSpecs to swap in Spock mocks for specific `@Autowired` fields when an external dependency cannot be wired in the test context.

### Spock Spec Structure

All tests use **Spock Framework** (Groovy BDD):

```groovy
class InternalGiftSpec extends Specification {

    static CoreARandom aRandom = new CoreARandom()

    @Unroll
    def "should return 0 totalBenefitAmount when internalBenefits is #scenario"() {
        given:
        InternalGift internalGift = aRandom.internalGift()
            .internalBenefits(internalBenefits)
            .build()

        expect:
        assert internalGift.totalBenefitAmount == BigDecimal.ZERO

        where:
        scenario      | internalBenefits
        'not present' | null
        'empty'       | []
    }
}
```

**Rules:**
- Use `@Unroll` for all data-driven tests.
- Test method names use plain English: `def "should [verb] when [condition]"`.
- Always use `given:/when:/then:` or `given:/expect:` blocks — never skip them.
- Use `aRandom.*` builders to construct test data — never construct objects directly with `new`.
- Always use explicit `assert` statements — never rely on Spock's implicit last-expression assertion.

### Complete Feature Example: PdfJobCompleted Message Handler

This example shows how a single feature is covered across the appropriate test layers.

**Feature:** When a PDF generation job completes, the service bus handler routes to the correct processor (standalone, email, or audit) based on the bulk task type.

---

**Unit Spec** (`src/test/groovy/`) — tests the `accepts()` guard in isolation, no Spring needed:

```groovy
class PdfJobCompletedMessageHandlerSpec extends Specification {

    PdfJobCompletedMessageHandler pdfJobCompletedMessageHandler
    PdfJobCompletedMessageProcessorFactory mockPdfJobCompletedMessageProcessorFactory
    BulkTaskService mockBulkTaskService

    def setup() {
        mockPdfJobCompletedMessageProcessorFactory = Mock()
        mockBulkTaskService = Mock(BulkTaskService)
        pdfJobCompletedMessageHandler = new PdfJobCompletedMessageHandler(
                bulkTaskService: mockBulkTaskService,
                pdfJobCompletedMessageProcessorFactory: mockPdfJobCompletedMessageProcessorFactory)
    }

    @Unroll
    def "#title accept message when pdf job id is #pdfJobId"() {
        given:
        PdfJobCompletedPayload payload = pdfJobCompletedPayload(pdfJobId)

        when:
        boolean result = pdfJobCompletedMessageHandler.accepts(serviceBusMessage(payload))

        then:
        assert result == expectedResult

        where:
        title        | pdfJobId             | expectedResult
        "should"     | aRandom.uuidString() | true
        "should not" | null                 | false
    }

    private static ServiceBusMessage<PdfJobCompletedPayload> serviceBusMessage(PdfJobCompletedPayload payload) {
        new ServiceBusMessage<>(null, payload)
    }
}
```

---

**CoreSpec** (`src/coreTest/groovy/`) — tests full handler behavior wired into Spring, using `ResettingMockInjector` only for external collaborators:

```groovy
@CoreTest
class PdfJobCompletedMessageHandlerCoreSpec extends EntitledSpecification implements BBAuthSupport {

    @Autowired
    private PdfJobCompletedMessageHandler pdfJobCompletedMessageHandler
    @Autowired
    private StandalonePdfJobCompletedMessageProcessor standalonePdfJobCompletedMessageProcessor
    @Autowired
    private ForEmailPdfJobCompletedMessageProcessor forEmailPdfJobCompletedMessageProcessor
    @Autowired
    private AuditPdfJobCompletedMessageProcessor auditPdfJobCompletedMessageProcessor
    @Autowired
    @Qualifier("testPdfJobCompletedPublisher")
    private JsonMessageTestPublisher jsonMessageTestPublisher

    private BulkTaskService mockBulkTaskService
    private BulkTaskRecordRepository mockBulkTaskRecordRepository
    private GiftReceiptGeneratingTaskRecordProcessor mockGiftReceiptTaskRecordProcessor

    def setup() {
        mockBulkTaskService = ResettingMockInjector.set(standalonePdfJobCompletedMessageProcessor, Mock(BulkTaskService))
        ResettingMockInjector.set(forEmailPdfJobCompletedMessageProcessor, mockBulkTaskService)
        mockBulkTaskRecordRepository = ResettingMockInjector.set(forEmailPdfJobCompletedMessageProcessor, Mock(BulkTaskRecordRepository))
        mockGiftReceiptTaskRecordProcessor = ResettingMockInjector.set(forEmailPdfJobCompletedMessageProcessor, Mock(GiftReceiptGeneratingTaskRecordProcessor))
        ResettingMockInjector.set(auditPdfJobCompletedMessageProcessor, mockBulkTaskService)
        ResettingMockInjector.set(pdfJobCompletedMessageHandler, mockBulkTaskService)
    }

    def "should process standalone PDF job completed message"() {
        given:
        withEntitlements([RENXT])
        PdfJobCompletedPayload payload = pdfJobCompletedPayload(aRandom.uuidString())
        BulkTaskEntity bulkTaskEntity = aRandom.bulkTaskEntity().build()
        List<BulkTaskRecord> completedTaskRecords = aRandom.nonEmptyList({ aRandom.bulkTaskRecordEntity().build() }) as List<BulkTaskRecord>

        and:
        mockBulkTaskService.findByEnvironmentIdAndJobId(environmentId, payload.pdfJobId) >> Optional.of(bulkTaskEntity)

        when:
        jsonMessageTestPublisher.sendAndWaitUntilProcessed(payload)

        then:
        mockBulkTaskService.findTaskRecordsInCompletedStatus(bulkTaskEntity) >> completedTaskRecords
        1 * mockBulkTaskService.complete(bulkTaskEntity)
    }

    def "should process PDF job for email completed message"() {
        given:
        String pdfJobId = aRandom.uuidString()
        PdfJobCompletedPayload payload = pdfJobCompletedPayload(pdfJobId)
        BulkTaskRecordEntity bulkTaskRecordEntity = aRandom.bulkTaskRecordEntity().pdfJobId(pdfJobId).build()
        BulkTaskEntity bulkTaskEntity = aRandom.bulkTaskEntity().build()

        and:
        mockBulkTaskService.findByEnvironmentIdAndJobId(environmentId, payload.pdfJobId) >> Optional.empty()

        when:
        jsonMessageTestPublisher.sendAndWaitUntilProcessed(payload)

        then:
        1 * mockBulkTaskRecordRepository.findByPdfJobId(pdfJobId) >> Optional.of(bulkTaskRecordEntity)
        1 * mockBulkTaskService.findById(bulkTaskRecordEntity.id.taskId) >> bulkTaskEntity
        1 * mockGiftReceiptTaskRecordProcessor.processTaskRecord(bulkTaskEntity, bulkTaskRecordEntity, _, pdfJobId)
        1 * mockBulkTaskService.completeIfAllTaskRecordsAreCompleted(bulkTaskEntity)
    }

    def "should process audit PDF job completed message"() {
        given:
        PdfJobCompletedPayload payload = pdfJobCompletedPayload(aRandom.uuidString())
        BulkTaskEntity auditTask = aRandom.bulkTaskEntity().audit(true).build()

        and:
        mockBulkTaskService.findByEnvironmentIdAndJobId(environmentId, payload.pdfJobId) >> Optional.of(auditTask)

        when:
        jsonMessageTestPublisher.sendAndWaitUntilProcessed(payload)

        then:
        1 * mockBulkTaskService.completePdfBulkTask(environmentId, payload.pdfJobId)
    }
}
```

### Random Data Builders

Use `CoreARandom` for all test object construction:

```groovy
aRandom.internalGift().amount(BigDecimal.TEN).build()
aRandom.renxtGift().constituentId(id).date(OffsetDateTime.now().minusYears(1)).build()
aRandom.nonEmptyList({ aRandom.internalBenefit().build() })
```

Add new random builders to `CoreARandom` via `@Delegate` when introducing new domain objects.

---

## Code Comments

- **Do not** add comments that describe what the code does. The code must be clear enough to explain itself. Avoid redundant comments like `// fetch the gift` or `// loop through records`.
- **Do** add comments to explain *why* a non-obvious decision was made, when the reasoning is not evident from the code alone.
- **TODO comments** must always include a link to the corresponding ADO work item so the work can be found and tracked when that item is picked up. Example:
  ```java
  // TODO: Improve performance. https://dev.azure.com/Blackbaud/Products/_workitems/edit/2475788
  ```

---

## Build & Tooling

### Common Gradle Commands

> **Convention:** Always include `clean` in every `./gradlew` command to ensure a fresh build state.

```bash
./gradlew clean check               # Compile everything and run all tests (recommended)
./gradlew clean bootRun                 # Start service locally (US config)
./gradlew clean bootRun -Pcan           # Start with Canadian zone config
./gradlew clean bootRun -Peur           # Start with European zone config
./gradlew clean test                    # Run unit tests
./gradlew clean coreTest                # Run DB integration tests
./gradlew clean componentTest           # Run component tests
./gradlew clean codenarc                # Run Groovy code quality checks (must pass before PR)
```

### CodeNarc Rules (Groovy)

These are **build-breaking** (priority 1) — they must pass before merging:
- No unused imports
- Class size limit
- Method size limit
- Cyclomatic complexity limit
- No unnecessary getters/setters

### Dependency Versions

All dependency versions are defined as variables in the `ext {}` block of `build.gradle`. Never hardcode a version inline — always use or add an `ext` variable.

---

## Configuration & Environments

- Config lives in `src/main/resources/application*.properties`
- Sensitive values come from environment variables — never commit secrets
- Multi-zone support: `application-can.properties`, `application-eur.properties`, `application-aus.properties`
- Service Bus topics are configured per environment with `servicebus.{topic-name}.*` properties

---

## Key Integrations

| Integration | Purpose |
|-------------|---------|
| Azure Service Bus | Async event processing (PDF completion, retry, email queuing) |
| Azure Blob Storage | PDF templates, merge field data, generated PDFs |
| Azure Cosmos SQL | NoSQL data (receipt templates, settings) |
| MS SQL Server | Primary relational data (Liquibase-managed schema) |
| `java-client-gifts-renxt-gift` | Gift data from RE NXT |
| `java-client-constituent` | Constituent lookups |
| `java-client-pdf` | PDF generation service |
| `java-client-skymail` | Email delivery |
| `email-manager-rest-client` | Email queue management |

---

## Links to Groot's Best Practices

- [Groot Dev Best Practices](https://dev.azure.com/blackbaud/Products/_wiki/wikis/Products.wiki/2573/Groot-Dev-Best-Practices)
- [External Team Contribution Guidelines](https://dev.azure.com/blackbaud/Products/_wiki/wikis/Products.wiki/10932/External-team-contribution-guidelines)
