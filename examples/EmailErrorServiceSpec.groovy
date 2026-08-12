package com.blackbaud.receiptmanager.core.service.email

import com.blackbaud.boot.exception.NotFoundException
import com.blackbaud.emailmanager.api.Email
import com.blackbaud.emailmanager.api.EmailCount
import com.blackbaud.emailmanager.api.FailedEmailCountRequest
import com.blackbaud.emailmanager.api.FailedEmailSearchRequest
import com.blackbaud.emailmanager.api.FailedEmailsResponse
import com.blackbaud.emailmanager.client.EmailClient
import com.blackbaud.receiptmanager.api.email.EmailTemplateType
import com.blackbaud.receiptmanager.api.emailerror.FailurePageData
import com.blackbaud.receiptmanager.api.givingstatement.GiftPeriod
import com.blackbaud.receiptmanager.core.api.InternalContribution
import com.blackbaud.receiptmanager.core.api.InternalGift
import com.blackbaud.receiptmanager.core.api.InternalGiftType
import com.blackbaud.receiptmanager.core.api.InternalGivingStatement
import com.blackbaud.receiptmanager.core.api.converters.FailurePageDataConverter
import com.blackbaud.receiptmanager.core.domain.bulktask.BulkTaskEntity
import com.blackbaud.receiptmanager.core.domain.bulktask.BulkTaskType
import com.blackbaud.receiptmanager.core.domain.email.EmailTemplateInfoEntity
import com.blackbaud.receiptmanager.core.service.bulktask.BulkTaskService
import com.blackbaud.receiptmanager.core.service.contribution.InternalContributionService
import com.blackbaud.receiptmanager.core.service.contribution.InternalContributionServiceSelector
import com.blackbaud.receiptmanager.core.service.givingstatement.InternalGivingStatementService
import com.blackbaud.receiptmanager.core.service.givingstatement.InternalGivingStatementServiceSelector
import com.blackbaud.receiptmanager.core.service.receipt.ReceiptHistoryService
import com.blackbaud.testsupport.BeanCompare
import com.blackbaud.testsupport.ResettingMockInjector
import spock.lang.Specification
import spock.lang.Unroll

import static com.blackbaud.receiptmanager.api.email.EmailTemplateType.CONSOLIDATED
import static com.blackbaud.receiptmanager.api.email.EmailTemplateType.SINGLE
import static com.blackbaud.receiptmanager.core.CoreARandom.aRandom
import static com.blackbaud.receiptmanager.core.service.gift.schema.GiftReceiptMergeField.RAW_GIFT_AMOUNT
import static com.blackbaud.receiptmanager.core.service.gift.schema.GiftReceiptMergeField.RAW_GIFT_DATE
import static com.blackbaud.receiptmanager.core.service.gift.schema.GiftReceiptMergeField.RAW_GIFT_TYPE_ID

class EmailErrorServiceSpec extends Specification {

    private EmailErrorService emailErrorService = new EmailErrorService()
    private BeanCompare beanCompare = new BeanCompare().compareStrict()

    private String environmentId = aRandom.environmentId()
    private EmailClient emailManagerClientMock
    private BulkTaskService bulkTaskServiceMock
    private InternalGivingStatementService internalGivingStatementServiceMock
    private ReceiptHistoryService mockReceiptHistoryService
    private EmailTemplateInfoService mockEmailTemplateInfoService

    def setup() {
        ResettingMockInjector.set(emailErrorService, mockGivingStatementService())
        ResettingMockInjector.set(emailErrorService, Mock(InternalContributionServiceSelector))
        bulkTaskServiceMock = ResettingMockInjector.set(emailErrorService, Mock(BulkTaskService))
        emailManagerClientMock = ResettingMockInjector.set(emailErrorService, Mock(EmailClient))
        mockReceiptHistoryService = ResettingMockInjector.set(emailErrorService, Mock(ReceiptHistoryService))
        mockEmailTemplateInfoService = ResettingMockInjector.set(emailErrorService, Mock(EmailTemplateInfoService))
    }

    def "should get email failure info for bulk task type Single by bulk task id and key identifier"() {
        given:
        BulkTaskEntity bulkTaskEntity = aRandom.bulkTaskEntity()
                .environmentId(environmentId)
                .bulkTaskType(BulkTaskType.GIFT_RECEIPT)
                .build()

        and:
        String keyIdentifier = aRandom.uuidString()
        InternalGift internalGift = aRandom.internalGift().keyIdentifier(keyIdentifier).build()
        InternalContribution internalContribution = aRandom.internalContribution()
                .internalGift(internalGift)
                .build()
        ResettingMockInjector.set(emailErrorService, mockInternalContributionServiceSelector(bulkTaskEntity.id,
                                                                                             keyIdentifier,
                                                                                             internalContribution))

        and:
        Map<String, String> mergeData = buildGiftMergeData()

        and:
        Email emailForBulkTaskRecord = mockFailedEmailForBulkTask(bulkTaskEntity, keyIdentifier)

        when:
        FailurePageData failurePageData = emailErrorService.failurePageData(UUID.fromString(emailForBulkTaskRecord.publisherGroupId), keyIdentifier)

        then:
        1 * mockReceiptHistoryService.findReceiptHistoryMergeDataForFailure(environmentId, bulkTaskEntity.id, keyIdentifier) >> mergeData

        FailurePageData expectedFailurePageData = FailurePageDataConverter.convertToFailurePageData(emailForBulkTaskRecord,
                                                                                                    bulkTaskEntity,
                                                                                                    internalContribution,
                                                                                                    mergeData
        )
        assertFailurePageDataMatches(failurePageData, expectedFailurePageData)
    }

    def "should use new gift info when receipt history not found for bulk task type Single by bulk task id and key identifier"() {
        given:
        BulkTaskEntity bulkTaskEntity = aRandom.bulkTaskEntity()
                .environmentId(environmentId)
                .bulkTaskType(BulkTaskType.GIFT_RECEIPT)
                .build()
        String keyIdentifier = aRandom.uuidString()
        InternalGift internalGift = aRandom.internalGift().keyIdentifier(keyIdentifier).build()

        InternalContribution internalContribution = aRandom.internalContribution()
                .internalGift(internalGift)
                .build()
        ResettingMockInjector.set(emailErrorService, mockInternalContributionServiceSelector(bulkTaskEntity.id,
                                                                                             keyIdentifier,
                                                                                             internalContribution))

        and:
        Map<String, String> mergeData = [:]

        and:
        Email emailForBulkTaskRecord = mockFailedEmailForBulkTask(bulkTaskEntity, keyIdentifier)

        when:
        FailurePageData failurePageData = emailErrorService.failurePageData(bulkTaskEntity.id, keyIdentifier)

        then:
        1 * mockReceiptHistoryService.findReceiptHistoryMergeDataForFailure(environmentId, bulkTaskEntity.id, keyIdentifier) >> mergeData

        FailurePageData expectedFailurePageData = FailurePageDataConverter.convertToFailurePageData(emailForBulkTaskRecord,
                                                                                                    bulkTaskEntity,
                                                                                                    internalContribution,
                                                                                                    mergeData
        )
        assertFailurePageDataMatches(failurePageData, expectedFailurePageData)
    }

    def "should get email failure info for bulk task type Consolidated by bulk task id and key identifier"() {
        given:
        BulkTaskEntity bulkTaskEntity = aRandom.bulkTaskEntity()
                .environmentId(environmentId)
                .bulkTaskType(BulkTaskType.GIVING_STATEMENT)
                .build()
        String keyIdentifier = aRandom.uuidString()
        InternalGivingStatement internalGivingStatement = aRandom.internalGivingStatement().keyIdentifier(keyIdentifier).build()

        and:
        Email emailForBulkTaskRecord = mockFailedEmailForBulkTask(bulkTaskEntity, keyIdentifier)

        when:
        FailurePageData failurePageData = emailErrorService.failurePageData(
                UUID.fromString(emailForBulkTaskRecord.publisherGroupId),
                internalGivingStatement.keyIdentifier
        )

        then:
        1 * internalGivingStatementServiceMock.createInternalGivingStatement(keyIdentifier, bulkTaskEntity) >> internalGivingStatement

        and:
        FailurePageData expectedFailurePageData = FailurePageDataConverter.convertToFailurePageData(emailForBulkTaskRecord, bulkTaskEntity, internalGivingStatement)
        assertFailurePageDataMatches(failurePageData, expectedFailurePageData)
    }

    def "should mark failure as reviewed"() {
        given:
        Email failedEmail = aRandom.emmEmail().build()

        and:
        emailManagerClientMock.markAsReviewed(failedEmail.id) >> {}

        when:
        emailErrorService.markFailureAsReviewed(failedEmail.id)

        then:
        1 * emailManagerClientMock.markAsReviewed(failedEmail.id)
    }

    def "should mark failure as reviewed by finding the appropriate failure by its key identifier"() {
        given:
        String keyIdentifier = aRandom.uuid()

        and:
        Email failedEmail = aRandom.emmEmail()
                .publisherId(keyIdentifier)
                .build()
        FailedEmailsResponse emails = aRandom.failedEmailsResponse()
                .failedEmails([failedEmail])
                .build()

        and:
        emailManagerClientMock.markAsReviewed(failedEmail.id) >> {}

        when:
        emailErrorService.markGiftReceiptFailuresAsReviewed(environmentId, keyIdentifier)

        then:
        1 * emailManagerClientMock.searchFailed(_ as FailedEmailSearchRequest, null) >> emails
        1 * emailManagerClientMock.markAsReviewed(failedEmail.id)
    }

    def "should not mark any failures as reviewed when the failure(s) cannot be found"() {
        given:
        String keyIdentifier = aRandom.intIdString()

        and:
        FailedEmailsResponse noFailuresForKeyIdentifier = FailedEmailsResponse.builder()
                .failedEmails([])
                .build()

        when:
        emailErrorService.markGiftReceiptFailuresAsReviewed(environmentId, keyIdentifier)

        then:
        1 * emailManagerClientMock.searchFailed(_ as FailedEmailSearchRequest, null) >> noFailuresForKeyIdentifier
        0 * emailManagerClientMock.markAsReviewed(_ as String)
    }

    @Unroll
    def "should call email-manager with the emailIds from the repository for email template type #emailTemplateType"() {
        given:
        List<EmailTemplateInfoEntity> savedEmailTemplateInfos = aRandom.nonEmptyList {
            aRandom.emailTemplateInfoEntity()
                    .environmentId(environmentId)
                    .build()
        }
        List<String> savedEmailIds = savedEmailTemplateInfos*.emailId

        and:
        FailedEmailCountRequest failedEmailCountRequest = FailedEmailCountRequest.builder()
                .emailIds(savedEmailIds)
                .publisherId(null)
                .build()

        and:
        EmailCount expectedEmailCount = EmailCount.builder()
                .count(aRandom.tinyInt() as long)
                .build()

        when:
        EmailCount emailCount = emailErrorService.countFailedEmails(environmentId, emailTemplateType)

        then:
        1 * mockEmailTemplateInfoService.findEmailIdsOrFail(environmentId, emailTemplateType) >> savedEmailIds
        1 * emailManagerClientMock.countFailed(failedEmailCountRequest) >> expectedEmailCount

        and:
        assert emailCount == expectedEmailCount

        where:
        emailTemplateType << [SINGLE, CONSOLIDATED]
    }

    def "should find email failures by emailIds for email template type"() {
        given:
        List<String> emailIdsForEmailType = aRandom.nonEmptyList { aRandom.uuidString() }
        EmailTemplateType emailTemplateType = aRandom.emailTemplateType()
        String continuationToken = aRandom.optionalUuidString()

        and:
        mockEmailTemplateInfoService.findEmailIdsOrFail(environmentId, emailTemplateType) >> emailIdsForEmailType

        when:
        emailErrorService.findFailedEmails(environmentId, emailTemplateType, continuationToken)

        then:
        interaction {
            FailedEmailSearchRequest request = FailedEmailSearchRequest.builder()
                    .emailIds(emailIdsForEmailType)
                    .continuationToken(continuationToken)
                    .build()
            1 * emailManagerClientMock.searchFailed(request, EmailErrorService.FAILED_EMAILS_PAGE_SIZE)
        }
    }

    def "should page through failed emails when more failures for a key identifier exist to mark as reviewed"() {
        given:
        String keyIdentifier = aRandom.intIdString()

        and:
        List<Email> failedEmailsMatchingKeyIdentifierInFirstPage = aRandom.nonEmptyList {
            aRandom.emmEmail().publisherId(keyIdentifier).build()
        }
        FailedEmailsResponse failedEmailsResponseFirstPage = aRandom.failedEmailsResponse()
                .failedEmails(failedEmailsMatchingKeyIdentifierInFirstPage)
                .continuationToken(aRandom.text())
                .build()

        and:
        List<Email> failedEmailsMatchingKeyIdentifierInSecondPage = aRandom.nonEmptyList {
            aRandom.emmEmail().publisherId(keyIdentifier).build()
        }
        FailedEmailsResponse failedEmailsResponseSecondPage = aRandom.failedEmailsResponse()
                .failedEmails(failedEmailsMatchingKeyIdentifierInSecondPage)
                .continuationToken(null)
                .build()

        when:
        emailErrorService.markGiftReceiptFailuresAsReviewed(environmentId, keyIdentifier)

        then:
        interaction {
            mockSearchFailuresRequests(keyIdentifier, failedEmailsResponseFirstPage, failedEmailsResponseSecondPage)
            assertEachMatchingGiftReceiptFailureIsMarkedAsReviewed(failedEmailsMatchingKeyIdentifierInFirstPage, failedEmailsMatchingKeyIdentifierInSecondPage)
        }
    }

    private assertEachMatchingGiftReceiptFailureIsMarkedAsReviewed(List<Email> failedEmailsMatchingKeyIdentifierInFirstPage, List<Email> failedEmailsMatchingKeyIdentifierInSecondPage) {
        (failedEmailsMatchingKeyIdentifierInFirstPage + failedEmailsMatchingKeyIdentifierInSecondPage).forEach {
            1 * emailManagerClientMock.markAsReviewed(it.id)
        }
    }

    def "should find failed email even when paging through results"() {
        given:
        String keyIdentifier = aRandom.intIdString()
        BulkTaskEntity bulkTaskUsedForFailedEmail = bulkTaskUsedForFailure(keyIdentifier)
        List<String> emailIds = aRandom.nonEmptyList { aRandom.uuidString() }

        and:
        FailedEmailsResponse firstPageWithoutMatch = aRandom.failedEmailsResponse()
                .failedEmails([])
                .continuationToken(aRandom.text())
                .build()

        and:
        Email matchingFailedEmail = aRandom.emmEmail()
                .publisherId(keyIdentifier)
                .publisherGroupId(bulkTaskUsedForFailedEmail.id.toString())
                .build()
        FailedEmailsResponse secondPageWithMatchingFailure = aRandom.failedEmailsResponse()
                .failedEmails([matchingFailedEmail])
                .continuationToken(null)
                .build()

        when:
        emailErrorService.failurePageData(bulkTaskUsedForFailedEmail.id, keyIdentifier)

        then:
        1 * mockEmailTemplateInfoService.findEmailIdsOrFail(environmentId, SINGLE) >> emailIds
        1 * emailManagerClientMock.searchFailed(FailedEmailSearchRequest.builder()
                .publisherId(keyIdentifier)
                .emailIds(emailIds)
                .build(), null) >> firstPageWithoutMatch
        1 * emailManagerClientMock.searchFailed(FailedEmailSearchRequest.builder()
                .publisherId(keyIdentifier)
                .emailIds(emailIds)
                .continuationToken(firstPageWithoutMatch.continuationToken)
                .build(), null) >> secondPageWithMatchingFailure
        notThrown(NotFoundException)
    }

    private BulkTaskEntity bulkTaskUsedForFailure(String keyIdentifier) {
        BulkTaskEntity bulkTaskUsedForFailedEmail = aRandom.bulkTaskEntity()
                .environmentId(environmentId)
                .bulkTaskType(BulkTaskType.GIFT_RECEIPT)
                .build()
        bulkTaskServiceMock.findById(bulkTaskUsedForFailedEmail.id) >> bulkTaskUsedForFailedEmail
        if (bulkTaskUsedForFailedEmail.bulkTaskType().giftReceipt) {
            mockReceiptHistoryService.findReceiptHistoryMergeDataForFailure(environmentId, bulkTaskUsedForFailedEmail.id, keyIdentifier) >> buildGiftMergeData()

            ResettingMockInjector.set(emailErrorService,
                                      mockInternalContributionServiceSelector(bulkTaskUsedForFailedEmail.id,
                                                                              keyIdentifier,
                                                                              aRandom.internalContribution().build()))
        } else {
            ResettingMockInjector.set(emailErrorService, mockGivingStatementService())
        }

        bulkTaskUsedForFailedEmail
    }

    private void mockSearchFailuresRequests(String keyIdentifier, FailedEmailsResponse failedEmailsResponseFirstPage, FailedEmailsResponse failedEmailsResponseSecondPage) {
        List<String> emailIds = aRandom.nonEmptyList { aRandom.uuidString() }
        1 * mockEmailTemplateInfoService.findEmailIdsOrFail(environmentId, SINGLE) >> emailIds

        FailedEmailSearchRequest initialSearchRequest = FailedEmailSearchRequest.builder()
                .publisherId(keyIdentifier)
                .emailIds(emailIds)
                .build()
        emailManagerClientMock.searchFailed(initialSearchRequest, null) >> failedEmailsResponseFirstPage

        FailedEmailSearchRequest nextSearchRequestWithContinuationToken = FailedEmailSearchRequest.builder()
                .publisherId(keyIdentifier)
                .emailIds(emailIds)
                .continuationToken(failedEmailsResponseFirstPage.continuationToken)
                .build()
        emailManagerClientMock.searchFailed(nextSearchRequestWithContinuationToken, null) >> failedEmailsResponseSecondPage
    }

    private Email mockFailedEmailForBulkTask(BulkTaskEntity bulkTaskEntity, String keyIdentifier) {
        bulkTaskServiceMock.findById(bulkTaskEntity.id) >> bulkTaskEntity

        Email emailForBulkTaskRecord = aRandom.emmEmail()
                .environmentId(environmentId)
                .publisherGroupId(bulkTaskEntity.id as String)
                .publisherId(keyIdentifier)
                .build()

        FailedEmailsResponse emailsResponse = aRandom.failedEmailsResponse().build()
        emailsResponse.failedEmails << emailForBulkTaskRecord

        FailedEmailSearchRequest searchByPublisherId = FailedEmailSearchRequest.builder()
                .publisherId(keyIdentifier)
                .build()
        emailManagerClientMock.searchFailed(searchByPublisherId, null) >> emailsResponse

        emailForBulkTaskRecord
    }

    private void assertFailurePageDataMatches(FailurePageData actual, FailurePageData expected) {
        beanCompare.assertEquals(actual.failureInfo, expected.failureInfo)
        beanCompare.assertEquals(actual.donorInfo, expected.donorInfo)
        beanCompare.assertEquals(actual.giftInfo, expected.giftInfo)
    }

    private InternalContributionServiceSelector mockInternalContributionServiceSelector(UUID bulkTaskId,
                                                                                        String keyIdentifier,
                                                                                        InternalContribution internalContribution) {
        InternalContributionService mockContributionService = Mock(InternalContributionService) {
            fetchPaymentAndCommitmentContributions(environmentId, _ as String, _ as GiftPeriod) >> [internalContribution]
            buildInternalContributionFromActionableReceipt(bulkTaskId, environmentId, keyIdentifier) >> internalContribution
        }

        Mock(InternalContributionServiceSelector) {
            internalContributionService(environmentId) >> mockContributionService
        }
    }

    private mockGivingStatementService() {
        internalGivingStatementServiceMock = Mock(InternalGivingStatementService) {
            createInternalGivingStatement(_ as String, _ as BulkTaskEntity) >> aRandom.internalGivingStatement().build()
        }
        Mock(InternalGivingStatementServiceSelector) {
            getService(_ as String) >> internalGivingStatementServiceMock
        }
    }

    private static Map<String, String> buildGiftMergeData() {
        [
                (RAW_GIFT_TYPE_ID.fieldName): aRandom.item(InternalGiftType.values()).id as String,
                (RAW_GIFT_AMOUNT.fieldName) : aRandom.dollarAmount() as String,
                (RAW_GIFT_DATE.fieldName)   : aRandom.localDate() as String
        ]
    }
}