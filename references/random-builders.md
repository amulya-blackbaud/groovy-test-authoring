# Random Builder Conventions

This document describes how to **create, modify, and register** `Random*Builder` classes in `receipt-manager`.
These builders are the only way test data should be constructed — never use `new` directly on a domain object in a spec.

---

## What a Random Builder Is

A `Random*Builder` is a Groovy class that:

1. **Extends** the Lombok-generated inner builder of the target class (e.g. `BulkTaskEntity.BulkTaskEntityBuilder`).
2. **Pre-fills every required field** with realistic random data in its constructor via `super.field(aRandom.xxx())...`
3. **Exposes fluent `withXxx(...)` override methods** so callers can pin specific fields they care about.
4. **Optionally exposes `save()` and `saveAndLinkWith*()`** when the builder needs to persist to the DB.

The result is that every `aRandom.bulkTaskEntity().build()` call produces a fully valid object with zero effort,
while `aRandom.bulkTaskEntity().withStatus(COMPLETED).build()` lets a test pin exactly one field.

---

## File Locations

| Object type | Source set | Example path |
|---|---|---|
| Core internal models (`InternalXxx`) | `src/sharedTest/groovy/.../core/api/` | `RandomInternalGiftBuilder.groovy` |
| JPA entities (`*Entity`) | `src/sharedTest/groovy/.../core/domain/<domain>/` | `RandomBulkTaskEntityBuilder.groovy` |
| Service schema / merge-field objects | `src/sharedTest/groovy/.../core/service/<domain>/schema/` | `RandomGiftReceiptMergeFieldDataBuilder.groovy` |
| Service Bus payloads | `src/sharedTest/groovy/.../servicebus/<topic>/` | `RandomResGiftPayloadBuilder.groovy` |
| REST API request/response objects | `rest-client/src/mainTest/groovy/.../api/<domain>/` | `RandomGiftReceiptTaskRequestBuilder.groovy` |

Mirror the package of the class being built exactly.

---

## Which `aRandom` to Import

| Builder location | Import |
|---|---|
| `src/sharedTest` (core, entities, schema, servicebus) | `import static com.blackbaud.receiptmanager.core.CoreARandom.aRandom` |
| `rest-client/src/mainTest` | `import static com.blackbaud.receiptmanager.api.RestClientARandom.aRandom` |

Never import the `ARandom` class itself — always use the static `aRandom` instance.

---

## Anatomy of a Minimal Builder

```groovy
package com.blackbaud.receiptmanager.core.api

import static com.blackbaud.receiptmanager.core.CoreARandom.aRandom

class RandomInternalBenefitBuilder extends InternalBenefit.InternalBenefitBuilder {

    RandomInternalBenefitBuilder() {
        super
                .name(aRandom.name())
                .count(aRandom.intBetween(1, 10))
                .totalValue(aRandom.dollarAmount())
    }
}
```

Rules:
- No class-level annotations (`@Component`, `@Service`, etc.).
- The constructor calls `super.field(...)` for every non-nullable field.
- Fields that are always optional can be omitted — callers pin them with `withXxx()` when needed.

---

## Anatomy of a Builder with `with` Override Methods

```groovy
class RandomBulkTaskEntityBuilder extends BulkTaskEntity.BulkTaskEntityBuilder {

    RandomBulkTaskEntityBuilder(BulkTaskRepository bulkTaskRepository) {
        this.bulkTaskRepository = bulkTaskRepository
        super.id(aRandom.uuid())
                .environmentId(aRandom.environmentId())
                .status(aRandom.bulkTaskStatus())
                .format(aRandom.item(BulkTaskFormat.values()))
                // ... all other required fields
    }

    RandomBulkTaskEntityBuilder withId(UUID id) {
        this.id(id)
        this
    }

    RandomBulkTaskEntityBuilder withStatus(BulkTaskStatus status) {
        this.status(status)
        this
    }

    BulkTaskEntity save() {
        bulkTaskRepository.save(build())
    }
}
```

Rules for `withXxx()` methods:
- Named `withXxx`, not `setXxx` or `xxx`.
- Call the Lombok builder setter (`this.xxx(value)`) then `return this`.
- Return type is the concrete `Random*Builder` type, not the parent builder — this preserves the fluent chain.
- Add a `withXxx()` for every field a test is likely to want to pin. Omit trivially-obvious ones only when they are never overridden anywhere in the test codebase.

---

## Builders That Persist to the Database

When the built object is a JPA entity that tests need to save:

1. Accept the `*Repository` via constructor parameter. Store it as a private field.
2. Expose a `save()` method: `return repository.save(build())`.
3. Expose additional `saveAndLinkWith*()` helpers when the entity has required FK relations (e.g. a receipt must link to a series and history).

```groovy
class RandomReceiptSeriesEntityBuilder extends ReceiptSeriesEntity.ReceiptSeriesEntityBuilder {
    private final ReceiptSeriesRepository receiptSeriesRepository

    RandomReceiptSeriesEntityBuilder(ReceiptSeriesRepository receiptSeriesRepository) {
        this.receiptSeriesRepository = receiptSeriesRepository
        super.environmentId(aRandom.environmentId())
                // ...
    }

    ReceiptSeriesEntity save() {
        receiptSeriesRepository.save(build())
    }
}
```

Note: non-entity builders (internal models, DTOs, schema objects) do **not** need a repository or `save()`.

---

## Domain-Aware `with` Methods

For builders that model complex domain rules, a `withXxx()` helper can set multiple related fields together
rather than exposing every raw field separately:

```groovy
// Sets giftType AND conditionally sets internalIssuerDetails to satisfy domain invariants
RandomInternalGiftBuilder withRenxtGiftType(String giftType) {
    this.giftType(giftType)
    if (RenxtGiftTypeValues.STOCK.equalsIgnoreCase(giftType) || ...) {
        this.internalIssuerDetails(aRandom.internalIssuerDetails().build())
    }
    this
}
```

Use domain-aware helpers when:
- Setting field A implies a constraint on field B.
- A common test scenario requires a particular combination of fields.

---

## Overriding `build()` for Post-Construction Mutation

When field relationships cannot be expressed through simple constructor chains
(e.g. one field's value must be copied onto a nested object after both are built),
override `build()`:

```groovy
@Override
GiftReceiptMergeFieldData build() {
    GiftReceiptMergeFieldData result = super.build()

    if (result.contributorMergeFieldData != null) {
        result.contributorMergeFieldData.internalContributor = internalContribution?.internalContributor
        result.contributorMergeFieldData.internalGift = internalContribution.internalGift
    }
    // ...

    result
}
```

Only override `build()` when cross-field wiring is genuinely impossible in the constructor.
Prefer domain-aware `withXxx()` helpers when possible.

---

## Constructor Parameters for Non-Repository Dependencies

Some builders need a collaborator that is neither a repo nor `aRandom` — typically a factory or creator.
Accept it as an optional constructor parameter defaulting to `null`:

```groovy
class RandomGiftMergeFieldDataBuilder extends GiftMergeFieldData.GiftMergeFieldDataBuilder {
    RandomGiftMergeFieldDataBuilder(PdfDownloadLinkCreator pdfDownloadLinkCreator = null) {
        super
                .pdfDownloadLinkCreator(pdfDownloadLinkCreator)
                // ...
    }
}
```

---

## Registering a New Builder

Every new `Random*Builder` must be registered in exactly one `*RandomBuilderSupport.java` class before it can be reached via `aRandom`.

### Step 1 — choose the right support class

| Builder location | Support class to update |
|---|---|
| `src/sharedTest/.../core/` (entities, internal models, schema) | `CoreRandomBuilderSupport.java` |
| `rest-client/src/mainTest/.../api/` | `RestClientRandomBuilderSupport.java` |
| `src/sharedTest/.../servicebus/` | `ReceiptManagerServiceBusClientRandomBuilderSupport.java` |

### Step 2 — add a factory method

```java
// In CoreRandomBuilderSupport.java

// 1. Add an @Autowired field if the builder needs a repository
@Autowired
private MyNewRepository myNewRepository;

// 2. Add the factory method
public RandomMyNewEntityBuilder myNewEntity() {
    return new RandomMyNewEntityBuilder(myNewRepository);
}
```

For builders with **no repository** (plain models, DTOs):
```java
public RandomMyNewModelBuilder myNewModel() {
    return new RandomMyNewModelBuilder();
}
```

For builders with an **optional collaborator** parameter:
```java
public RandomMyNewBuilder myNew() {
    return new RandomMyNewBuilder();  // uses the null default
}

public RandomMyNewBuilder myNew(SomeCreator creator) {
    return new RandomMyNewBuilder(creator);
}
```

### Step 3 — add the import

Add the import for `RandomMyNewEntityBuilder` at the top of the support class, alongside the other `Random*` imports.

### Step 4 — verify the `@Delegate` chain

Check `CoreARandom.java` (or `RestClientARandom.java`) to confirm the support class you edited is already `@Delegate`d there.
You should **not** need to modify `CoreARandom.java` for a new builder in an existing support class.
Only add a new `@Delegate` if you are creating an entirely new support class.

---

## Available `aRandom` Primitives (common ones)

| Call | Returns |
|---|---|
| `aRandom.uuid()` | `UUID` |
| `aRandom.uuidString()` | `String` UUID |
| `aRandom.environmentId()` | `String` environment ID |
| `aRandom.name()` / `aRandom.firstName()` / `aRandom.lastName()` | name strings |
| `aRandom.email()` | email string |
| `aRandom.words(n)` | string of n random words |
| `aRandom.text(n)` | random alphanumeric string of length n |
| `aRandom.dollarAmount()` / `aRandom.currencyAmount()` | `BigDecimal` |
| `aRandom.intBetween(min, max)` | `Integer` |
| `aRandom.positiveInt()` | positive `Integer` |
| `aRandom.coinFlip()` | `boolean` |
| `aRandom.item(EnumType.values())` | random enum constant |
| `aRandom.item(list)` | random element from a list |
| `aRandom.nonEmptyList({ aRandom.xxx().build() })` | `List` with ≥1 element |
| `aRandom.nonEmptySet({ aRandom.xxx() })` | `Set` with ≥1 element |
| `aRandom.optionalItem(value)` | `value` or `null` (50/50) |
| `aRandom.optionalWords(n)` | n words or `null` (50/50) |
| `aRandom.offsetDateTime()` / `aRandom.offsetDateTimeInPast()` | `OffsetDateTime` |
| `aRandom.localDateInPast()` | `LocalDate` |
| `aRandom.supportedLocale()` | `Locale` from supported set |
| `aRandom.supportedCurrencySymbol()` | one of `$`, `CA$`, `€`, `£`, etc. |

---

## Quick Checklist Before Opening a PR

- [ ] Class is named `Random<TargetClass>Builder`, extends `<TargetClass>.<TargetClass>Builder`
- [ ] Constructor uses `static import CoreARandom.aRandom` (or `RestClientARandom.aRandom` for rest-client)
- [ ] All non-nullable fields are filled in the constructor via `super.field(...)`
- [ ] `with` override methods are named `withXxx`, return the concrete builder type, and end with `this`
- [ ] If an entity: receives repository via constructor, exposes `save()`, stores repo as private field
- [ ] Registered in the correct `*RandomBuilderSupport.java` with a public factory method
- [ ] Import added to the support class
- [ ] No Spring annotations on the builder class
