# PR comment log (traceability + staging)

Resolved `.groovy` review comments mined from ADO and promoted into the rule
files. Newly mined comments are appended here first (e.g. by `scripts/ado_sync.py`),
then curated into `conventions.md` / the per-type file.

## Promoted
- [PR 547471] RenxtGiftServiceCoreSpec.groovy (coreTest) — "In a CoreSpec use @Autowired instead of mocking; ResettingMockInjector belongs in setup()." → coreTest.md, conventions.md
- [PR 547471] RenxtGivingStatementServiceCoreSpec.groovy (coreTest) — add the false-path regression, not only the true path. → coreTest.md, conventions.md
- [PR 549889] ResGiftSupportalServiceSpec.groovy (test) — mock interaction counts must match the implementation exactly. → test.md, conventions.md
- [PR 549889] TransactionReceiptRequestValidatorSpec.groovy (test) — no BigDecimal.FIVE; use BigDecimal.valueOf(5). → test.md, conventions.md
- [PR 549889] TransactionReceiptRequestSupportalServiceSpec.groovy (test) — don't exclude a newly-added mapped field from BeanCompare; assert it. → test.md, conventions.md

## New (to curate)
_(ado_sync.py appends here)_
