package com.blackbaud.receiptmanager.core.service.email.history

import com.blackbaud.azure.servicebus.publisher.JsonMessagePublisher
import com.blackbaud.azure.servicebus.publisher.JsonMessageTestPublisher
import com.blackbaud.common.fundraising.api.FundReadRequest
import com.blackbaud.common.fundraising.client.FundraisingClient
import com.blackbaud.constituent.api.RenxtConstituentNameFormatSummaryResponse
import com.blackbaud.constituent.api.RenxtNameFormat
import com.blackbaud.constituent.client.ConstituentNameFormatTrustedClient
import com.blackbaud.constituent.client.ConstituentTrustedClient
import com.blackbaud.context.RequestContext
import com.blackbaud.emailmanager.api.QueueEmailPayload
import com.blackbaud.gft.api.gift.GiftInternalRead
import com.blackbaud.gft.api.gift.InternalGiftCollection
import com.blackbaud.gft.api.gift.InternalGiftListRequestOptions
import com.blackbaud.gift.api.gift.RenxtGiftListRequest
import com.blackbaud.gift.api.gift.RenxtGiftsResponse
import com.blackbaud.gift.client.RenxtGiftSasClient
import com.blackbaud.logging.StubAppenderSupport
import com.blackbaud.pdf.api.CreatePdfJobRequest
import com.blackbaud.pdf.api.IdResponse
import com.blackbaud.pdf.api.PdfJobItemAddRequest
import com.blackbaud.pdf.api.PdfJobItemBatchAddRequest
import com.blackbaud.pdf.client.PdfClient
import com.blackbaud.receiptmanager.CoreTest
import com.blackbaud.receiptmanager.api.bulktask.BulkTaskFormat
import com.blackbaud.receiptmanager.core.domain.BlobCleanupSupport
import com.blackbaud.receiptmanager.core.domain.bulktask.BulkTaskEntity
import com.blackbaud.receiptmanager.core.domain.bulktask.BulkTaskRepository
import com.blackbaud.receiptmanager.core.domain.bulktask.BulkTaskStatus
import com.blackbaud.receiptmanager.core.domain.bulktask.BulkTaskType
import com.blackbaud.receiptmanager.core.domain.bulktask.GiftReceiptBulkTaskSetup
import com.blackbaud.receiptmanager.core.domain.receipt.InternalReceiptStatus
import com.blackbaud.receiptmanager.core.service.BatchChangesSupport
import com.blackbaud.receiptmanager.core.service.GiftReceiptBulkTaskSetupHelper
import com.blackbaud.receiptmanager.core.service.bulktask.giftreceipt.GiftReceiptGeneratingBulkTaskService
import com.blackbaud.receiptmanager.core.service.gift.GftGiftFetcher
import com.blackbaud.receiptmanager.core.service.gift.InternalGiftFetcherSelector
import com.blackbaud.receiptmanager.core.service.receipt.BaseReceiptUpserter
import com.blackbaud.receiptmanager.servicebus.ServiceBusMessageRouter
import com.blackbaud.receiptmanager.servicebus.rctprocesstask.RctProcessTaskPayload
import com.blackbaud.receiptmanager.shared.ReceiptReplaceHelper
import com.blackbaud.receiptmanager.shared.core.domain.receipt.ReceiptHistoryFinder
import com.blackbaud.receiptmanager.shared.core.receipt.MergeDataExpectationBuilder
import com.blackbaud.rest.api.BlackbaudCollection
import com.blackbaud.setsettings.client.SettingsSkyApiClient
import com.blackbaud.skymail.client.SkymailClient
import com.blackbaud.testsupport.BBAuthSupport
import com.blackbaud.testsupport.BeanCompare
import com.blackbaud.testsupport.ResettingMockInjector
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier

import static com.blackbaud.receiptmanager.core.CoreARandom.aRandom
import static com.blackbaud.receiptmanager.core.api.InternalEntitlementValues.RENXT
import static com.blackbaud.receiptmanager.core.domain.bulktask.BulkTaskGenerationType.MANUAL
import static com.blackbaud.receiptmanager.shared.GiftInternalReadFixtureBuilder.buildInternalGiftCollection
import static com.blackbaud.receiptmanager.shared.GiftInternalReadFixtureBuilder.receiptableOneTimeGiftInternalReadWithDatedNotReceiptedReceipt
import static com.blackbaud.receiptmanager.shared.servicebus.PayloadBuilder.rctProcessTaskPayload

@CoreTest
abstract class AbstractGiftReceiptEmailTemplateHistoryCoreSpec extends GiftReceiptBulkTaskSetupHelper implements BBAuthSupport, StubAppenderSupport, BatchChangesSupport, BlobCleanupSupport {
    @Autowired
    @Qualifier('testRctProcessTaskPublisher')
    protected JsonMessageTestPublisher testRctProcessTaskPublisher
    @Autowired
    @Qualifier('testPdfJobCompletedPublisher')
    protected JsonMessageTestPublisher testPdfJobCompletedPublisher
    @Autowired
    @Qualifier('emmStatusUpdatePublisher')
    protected JsonMessageTestPublisher testEmmStatusUpdatePublisher

    @Autowired
    protected GiftReceiptGeneratingBulkTaskService giftReceiptGeneratingBulkTaskService

    @Autowired
    protected BulkTaskRepository bulkTaskRepository

    @Autowired
    protected ReceiptHistoryFinder receiptHistoryFinder

    @Autowired
    protected BaseReceiptUpserter baseReceiptUpserter

    @Autowired
    protected ReceiptReplaceHelper receiptReplaceHelper

    @Autowired
    protected GftGiftFetcher gftGiftFetcher

    @Autowired
    protected ServiceBusMessageRouter serviceBusMessageRouter

    @Autowired
    protected MergeDataExpectationBuilder mergeDataExpectationBuilder

    @Autowired
    protected ConstituentTrustedClient mockConstituentTrustedClient

    @Autowired
    protected PdfClient mockPdfClient

    @Autowired
    protected ConstituentNameFormatTrustedClient mockConstituentNameFormatTrustedClient

    @Autowired
    protected FundraisingClient mockFundraisingClient

    @Autowired
    protected SettingsSkyApiClient mockSettingsSkyApiClient

    @Autowired
    protected RenxtGiftSasClient mockRenxtGiftSasClient

    @Autowired
    protected SkymailClient skymailClient

    protected JsonMessagePublisher mockTaskPublisher
    protected JsonMessagePublisher mockEmmQueueEmailPublisher

    protected BeanCompare rctProcessTaskPayloadBeanCompare

    def setup() {
        withEntitlements([RENXT])
        withOrgSettings()
        mockEmmQueueEmailPublisher = ResettingMockInjector.set(serviceBusMessageRouter, 'emmQueueEmailPublisher', Mock(JsonMessagePublisher))
        mockTaskPublisher = ResettingMockInjector.set(serviceBusMessageRouter, 'rctProcessTaskPublisher', Mock(JsonMessagePublisher))

        ResettingMockInjector.set(baseReceiptUpserter, Mock(InternalGiftFetcherSelector) {
            String environmentId = RequestContext.get().environmentId
            selectForEnvironmentId(environmentId) >> gftGiftFetcher
        })
        rctProcessTaskPayloadBeanCompare = new BeanCompare().excludeFields('version')
    }

    protected void withOrgSettings() {
        mockSettingsSkyApiClient.getOrgSettings() >> aRandom.orgSettings().build()
    }

    protected RenxtConstituentNameFormatSummaryResponse buildRenxtConstituentNameFormatSummaryResponseIncluding(String constituentId,
                                                                                                                String nameFormatType,
                                                                                                                String formattedName) {
        RenxtConstituentNameFormatSummaryResponse renxtConstituentNameFormatSummaryResponse = aRandom.nameFormatSummaryResponse(constituentId).build()
        RenxtNameFormat renxtNameFormat = aRandom.nameFormat()
                .type(nameFormatType)
                .formattedName(formattedName)
                .constituentId(constituentId)
                .build()
        renxtConstituentNameFormatSummaryResponse.additionalNameFormats += renxtNameFormat
        renxtConstituentNameFormatSummaryResponse.additionalNameFormats.shuffle()
        renxtConstituentNameFormatSummaryResponse
    }

    protected void assertEmailRequestedWithMergeData(Map<String, String> mergeData) {
        1 * mockEmmQueueEmailPublisher.sendSync(_ as QueueEmailPayload) >> { QueueEmailPayload emailPayload ->
            assert emailPayload.mergeData == mergeData
        }
    }

    protected void assertPdfContainsReceiptDataStatusAndStatusDate(PdfJobItemBatchAddRequest request,
                                                                   InternalReceiptStatus receiptStatus,
                                                                   String receiptStatusDate) {
        assert request.items.size() == 1
        PdfJobItemAddRequest item = request.items[0]
        assert item.html.contains(receiptStatus.readableName)
        assert item.html.contains(receiptStatusDate)
        assert item.html.contains('Gift amount:')
        assert item.html.contains('<img id="header-image"')
        assert item.html.contains('<img id="signature-image"')
    }

    protected void mockGftGiftsPostGetGiftList(Set<String> giftIds) {
        List<GiftInternalRead> giftInternalReads = giftIds.collect { giftId ->
            receiptableOneTimeGiftInternalReadWithDatedNotReceiptedReceipt(giftId)
        }
        InternalGiftCollection internalGiftCollection = buildInternalGiftCollection(giftInternalReads)
        mockGftGiftsSasClient.postGetGiftList(_ as InternalGiftListRequestOptions) >> internalGiftCollection
    }

    protected void assertBulkTaskMatchesExpectations(BulkTaskEntity bulkTask, BulkTaskFormat format) {
        assert bulkTask.environmentId == environmentId
        assert bulkTask.format == format
        assert bulkTask.bulkTaskType == BulkTaskType.GIFT_RECEIPT
        assert bulkTask.generationType == MANUAL
        assert bulkTask.status == BulkTaskStatus.QUEUED
    }

    protected void mockHtmlForPdfBuilding(BulkTaskType bulkTaskType = BulkTaskType.GIFT_RECEIPT, String keyIdentifier = null) {
        mockSettingsSkyApiClient.getOrgBranding() >> aRandom.orgBrandingRead().build()
        mockGftGiftsSasClient.getGiftInternal(_ as String) >> { String giftId ->
            receiptableOneTimeGiftInternalReadWithDatedNotReceiptedReceipt(giftId)
        }
        mockConstituentTrustedClient.find(_ as String) >> { String constituentId ->
            aRandom.constituent().id(bulkTaskType.givingStatement ? keyIdentifier : constituentId).build()
        }
        mockFundraisingClient.getFundsPost(_ as FundReadRequest) >> BlackbaudCollection.builder().value([]).nextLink(null).build()

        if (bulkTaskType.givingStatement) {
            RenxtGiftsResponse giftsResponse = aRandom.renxtGiftsResponse()
                    .nextLink(null)
                    .build()
            mockRenxtGiftSasClient.postGetGiftList(_ as RenxtGiftListRequest) >> giftsResponse
            mockGftGiftsPostGetGiftList([keyIdentifier] as Set)
        }
    }

    protected void assertPublishedTaskMatchesTaskSetupAndFormat(payload,
                                                                GiftReceiptBulkTaskSetup giftReceiptBulkTaskSetup,
                                                                BulkTaskFormat format) {
        BulkTaskEntity bulkTaskEntity = bulkTaskRepository.findByName(giftReceiptBulkTaskSetup.taskName)
        assertBulkTaskMatchesExpectations(bulkTaskEntity, format)
        RctProcessTaskPayload expectedPayload = rctProcessTaskPayload(bulkTaskEntity.id)
        rctProcessTaskPayloadBeanCompare.assertEquals(expectedPayload, payload.first())
    }

    protected void assertPdfWithStatusReceiptDataAndReceiptStatusDateWasRequested(InternalReceiptStatus receiptStatus,
                                                                                  String pdfJobId,
                                                                                  String receiptStatusDate) {
        1 * mockPdfClient.createPdfJob(_ as CreatePdfJobRequest) >> { new IdResponse(pdfJobId) }
        1 * mockPdfClient.addPdfJobItemsBatch(pdfJobId, _ as PdfJobItemBatchAddRequest) >> { String jobId, PdfJobItemBatchAddRequest request ->
            assertPdfContainsReceiptDataStatusAndStatusDate(request, receiptStatus, receiptStatusDate)
        }
        1 * mockPdfClient.activatePdfJob(_ as String)
    }
}