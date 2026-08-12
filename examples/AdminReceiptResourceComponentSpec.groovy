package com.blackbaud.receiptmanager.resources

import com.blackbaud.blobstore.azure.TypedAzureBlobContainer
import com.blackbaud.boot.exception.ForbiddenException
import com.blackbaud.context.RequestContext
import com.blackbaud.receiptmanager.ComponentTest
import com.blackbaud.receiptmanager.api.admin.GiftReceiptEmailTemplateHistoryBlobResponse
import com.blackbaud.receiptmanager.api.admin.GiftReceiptEmailTemplateHistoryBlobLocationResponse
import com.blackbaud.receiptmanager.api.admin.GiftReceiptEmailTemplateNotMigratedCountResponse
import com.blackbaud.receiptmanager.api.admin.ReceiptHistoryResponse
import com.blackbaud.receiptmanager.api.admin.ResGiftTestRequest
import com.blackbaud.receiptmanager.api.admin.TransactionReceiptRequest
import com.blackbaud.receiptmanager.client.AdminReceiptClient
import com.blackbaud.receiptmanager.core.domain.email.receipt.history.GiftReceiptEmailTemplateHistory
import com.blackbaud.receiptmanager.core.domain.email.receipt.history.GiftReceiptEmailTemplateHistoryBlobKey
import com.blackbaud.receiptmanager.core.domain.mappers.GiftReceiptEmailTemplateHistoryMapper
import com.blackbaud.receiptmanager.core.domain.receipt.ReceiptEntity
import com.blackbaud.receiptmanager.core.domain.receipt.ReceiptHistoryEntity
import com.blackbaud.receiptmanager.core.domain.receipt.ReceiptSeriesEntity
import com.blackbaud.receiptmanager.core.service.admin.receipt.MergeFieldsService
import com.blackbaud.receiptmanager.core.service.admin.resgift.ResGiftSupportalService
import com.blackbaud.receiptmanager.core.service.admin.transaction.TransactionReceiptRequestSupportalService
import com.blackbaud.receiptmanager.core.service.email.history.GiftReceiptEmailTemplateHistoryService
import com.blackbaud.receiptmanager.core.service.receipt.ReceiptHistoryService
import com.blackbaud.receiptmanager.core.service.taskprocessing.taskrecord.giftreceipt.queue.EmailGiftReceiptGeneratingQueueService
import com.blackbaud.receiptmanager.resources.admin.AdminReceiptResource
import com.blackbaud.receiptmanager.shared.TemplateCreator
import com.blackbaud.testsupport.BBAuthSupport
import com.blackbaud.testsupport.BeanCompare
import com.blackbaud.testsupport.RequiresBbAuthContext
import com.blackbaud.testsupport.ResettingMockInjector
import com.blackbaud.traceability.StubTracerSupport
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import spock.lang.Specification

import static com.blackbaud.receiptmanager.core.CoreARandom.aRandom
import static com.blackbaud.receiptmanager.api.email.EmailTemplateType.CONSOLIDATED
import static com.blackbaud.receiptmanager.resources.BaseSupportalComponentSpec.SUPPORTAL_ENVIRONMENT_ID
import static com.blackbaud.receiptmanager.resources.GeneratedPermissions.RECEIPTING_RESTRICTED
import static com.blackbaud.receiptmanager.resources.GeneratedPermissions.RECEIPTING_VIEW

@ComponentTest
@Slf4j
@RequiresBbAuthContext(environmentId = SUPPORTAL_ENVIRONMENT_ID, supportal = true, includedPermissions = RECEIPTING_VIEW)
class AdminReceiptResourceComponentSpec extends Specification implements BBAuthSupport, StubTracerSupport {

    @Autowired
    private AdminReceiptClient adminReceiptClient

    @Autowired
    private MergeFieldsService mergeFieldsService

    @Autowired
    private TypedAzureBlobContainer<Map<String, String>> receiptMergeFieldsContainer

    @Autowired
    private EmailGiftReceiptGeneratingQueueService emailGiftReceiptGeneratingQueueService

    @Autowired
    private GiftReceiptEmailTemplateHistoryService emailTemplateHistoryService

    @Autowired
    private GiftReceiptEmailTemplateHistoryMapper emailTemplateHistoryMapper

    @Autowired
    private AdminReceiptResource adminReceiptResource

    @Autowired
    private TemplateCreator templateCreator

    private BeanCompare beanCompare = new BeanCompare()
    private TransactionReceiptRequestSupportalService transactionReceiptRequestSupportalService
    private ResGiftSupportalService resGiftSupportalService

    def setup() {
        beanCompare.excludeFields("id", "blobCreatedBy", "blobCreatedDate", "blobStorageLocation")
        ResettingMockInjector.set(emailGiftReceiptGeneratingQueueService, Mock(ReceiptHistoryService))
        transactionReceiptRequestSupportalService = ResettingMockInjector.set(adminReceiptResource, Mock(TransactionReceiptRequestSupportalService))
        resGiftSupportalService = ResettingMockInjector.set(adminReceiptResource, Mock(ResGiftSupportalService))
    }

    def cleanup() {
        receiptMergeFieldsContainer.forEach { it.delete() }
    }

    @RequiresBbAuthContext(supportal = true, includedPermissions = [])
    def "should return 403 Forbidden for GET /admin/receiptHistory/environmentId/{environmentId}/bulkTaskId/{bulkTaskId} if client does not have view permission"() {
        when:
        adminReceiptClient.findReceiptHistoryByEnvironmentIdAndTaskId(aRandom.environmentId(), aRandom.uuid())

        then:
        thrown(ForbiddenException)
    }

    @RequiresBbAuthContext(supportal = true, includedPermissions = RECEIPTING_VIEW)
    def "should return 403 Forbidden for GET /admin/giftreceiptemailtemplatehistoryblob if client does not have view permission"() {
        when:
        adminReceiptClient.findEmailTemplateHistoryBlob(environmentId, aRandom.intIdString(), aRandom.tinyInt())

        then:
        thrown(ForbiddenException)
    }

    @RequiresBbAuthContext(supportal = true, includedPermissions = RECEIPTING_RESTRICTED)
    def "should return successfully for GET /admin/giftreceiptemailtemplatehistoryblob if client has the restricted permission"() {
        given:
        GiftReceiptEmailTemplateHistoryBlobKey emailTemplateHistoryBlobKey = aRandom.giftReceiptEmailTemplateHistoryBlobKey()
                .environmentId(environmentId)
                .build()

        and:
        templateCreator.createDefaultEmailTemplateInfo(environmentId: environmentId,
                                                      emailId: emailTemplateHistoryBlobKey.emailId,
                                                      emailVersion: emailTemplateHistoryBlobKey.emailVersion,
                                                      emailTemplateHistoryMigrated: false)

        and:
        GiftReceiptEmailTemplateHistory emailTemplateHistory = aRandom.oldGiftReceiptEmailTemplateHistory().build()

        and:
        emailTemplateHistoryService.saveTemplateHistory(emailTemplateHistoryBlobKey, emailTemplateHistory, false)

        when:
        GiftReceiptEmailTemplateHistoryBlobResponse response = adminReceiptClient.findEmailTemplateHistoryBlob(environmentId,
                                                                                                               emailTemplateHistoryBlobKey.emailId,
                                                                                                               emailTemplateHistoryBlobKey.emailVersion)

        then:
        beanCompare.assertEquals(emailTemplateHistoryMapper.toApi(emailTemplateHistory), response)
        assert response.blobStorageLocation == "old"
    }

    @RequiresBbAuthContext(supportal = true, includedPermissions = RECEIPTING_RESTRICTED)
    def "should return blob location details for GET /admin/giftreceiptemailtemplatehistoryblob/{environmentId}/{emailId}/{emailVersion}/location"() {
        given:
        GiftReceiptEmailTemplateHistoryBlobKey emailTemplateHistoryBlobKey = aRandom.giftReceiptEmailTemplateHistoryBlobKey()
                .environmentId(environmentId)
                .build()

        and:
        templateCreator.createDefaultEmailTemplateInfo(environmentId: environmentId,
                                                      emailId: emailTemplateHistoryBlobKey.emailId,
                                                      emailVersion: emailTemplateHistoryBlobKey.emailVersion,
                                                      emailTemplateHistoryMigrated: false)

        and:
        GiftReceiptEmailTemplateHistory emailTemplateHistory = aRandom.oldGiftReceiptEmailTemplateHistory().build()
        emailTemplateHistoryService.saveTemplateHistory(emailTemplateHistoryBlobKey, emailTemplateHistory, false)

        when:
        GiftReceiptEmailTemplateHistoryBlobLocationResponse response = adminReceiptClient.findEmailTemplateHistoryBlobLocation(environmentId,
                                                                                                                              emailTemplateHistoryBlobKey.emailId,
                                                                                                                              emailTemplateHistoryBlobKey.emailVersion)

        then:
        assert response.blobStorageLocation == "old"
        assert response.presentInOldContainer
        assert response.presentInNewContainer == false
    }

    @RequiresBbAuthContext(supportal = true, includedPermissions = RECEIPTING_VIEW)
    def "should return 403 Forbidden for GET /admin/giftreceipts/{receiptId}/mergefields if client does not have restricted permission"() {
        when:
        adminReceiptClient.findMergeFieldsForGiftReceipt(aRandom.uuid())

        then:
        thrown ForbiddenException
    }

    @RequiresBbAuthContext(supportal = true, includedPermissions = RECEIPTING_RESTRICTED)
    def "should return the merge fields used for the gift receipt provided"() {
        given:
        ReceiptSeriesEntity receiptSeriesEntity = createReceiptSeries()

        ReceiptEntity receiptEntity = aRandom.receiptEntity()
                .withEnvironmentId(environmentId)
                .withReceiptSeriesId(receiptSeriesEntity.id)
                .save()

        Map<String, String> expectedMergeFields = aRandom.nonEmptyStringMap()
        UUID blobId = mergeFieldsService.saveMergeFieldsToBlob(expectedMergeFields)

        aRandom.receiptHistoryEntity()
                .withEnvironmentId(environmentId)
                .withKeyIdentifier(receiptEntity.keyIdentifier)
                .withReceiptId(receiptEntity.id)
                .withTaskId(null)
                .withReceiptStatus(receiptEntity.receiptStatus)
                .withReceiptSeriesId(receiptEntity.receiptSeriesId)
                .withMergeFieldHistoryId(blobId)
                .save()

        when:
        Map<String, String> actualMergeFields = adminReceiptClient.findMergeFieldsForGiftReceipt(receiptEntity.id)

        then:
        assert actualMergeFields == expectedMergeFields
    }

    @RequiresBbAuthContext(supportal = true, includedPermissions = RECEIPTING_VIEW)
    def "should return 403 Forbidden for GET /admin/giftreceipts/{receiptId}/histories if client does not have restricted permission"() {
        when:
        adminReceiptClient.findHistoriesForGiftReceipt(aRandom.uuid())

        then:
        thrown ForbiddenException
    }

    @RequiresBbAuthContext(supportal = true, includedPermissions = RECEIPTING_RESTRICTED)
    def "should return a list of receipt histories that have the receipt id provided"() {
        given:
        List<ReceiptHistoryEntity> receiptHistoryEntities = aRandom.nonEmptyList {
            aRandom.receiptHistoryEntity().build()
        }

        and:
        ReceiptEntity receipt = aRandom.receiptEntity()
                .saveAndLinkWithHistoriesAndSeriesAndTasks(receiptHistoryEntities)

        when:
        List<ReceiptHistoryResponse> receiptHistoryResponses = adminReceiptClient.findHistoriesForGiftReceipt(receipt.id)

        then:
        assert receiptHistoryResponses.size() == receiptHistoryEntities.size()
        assert receiptHistoryResponses.every { it.receiptId == receipt.id }
        assert receiptHistoryResponses*.mergeFieldHistoryId as Set == receiptHistoryEntities*.mergeFieldHistoryId as Set
    }

    @RequiresBbAuthContext(supportal = true, includedPermissions = RECEIPTING_VIEW)
    def "should return 403 Forbidden for GET /admin/giftreceipts/failed"() {
        when:
        adminReceiptClient.findFailedReceipts(aRandom.environmentId())

        then:
        thrown ForbiddenException
    }

    @RequiresBbAuthContext(supportal = true, includedPermissions = RECEIPTING_VIEW)
    def "should return 403 Forbidden for GET /admin/environments/{environmentId}/giftreceipts"() {
        when:
        adminReceiptClient.findReceipts(aRandom.environmentId(), aRandom.intIdString(), aRandom.tinyInt())

        then:
        thrown ForbiddenException
    }

    @RequiresBbAuthContext(supportal = true, includedPermissions = RECEIPTING_VIEW)
    def "should return 403 Forbidden for GET /admin/giftreceipts/duplicatereceiptnumber"() {
        when:
        adminReceiptClient.findDuplicateReceiptNumbers()

        then:
        thrown ForbiddenException
    }

    @RequiresBbAuthContext(supportal = true, includedPermissions = RECEIPTING_VIEW)
    def "should return 403 Forbidden for GET /admin/emailtemplates/migrated/count"() {
        when:
        adminReceiptClient.findMigratedEmailTemplateCountGroupedByEnvironmentId()

        then:
        thrown ForbiddenException
    }

    def "should return 403 Forbidden for GET /admin/environments/{environmentId}/receipt_history/keyIdentifier/{keyIdentifier}"() {
        when:
        adminReceiptClient.findReceiptHistoriesByEnvironmentIdAndKeyIdentifier(aRandom.environmentId(), aRandom.intIdString())

        then:
        thrown ForbiddenException
    }

    @RequiresBbAuthContext(supportal = true, includedPermissions = RECEIPTING_RESTRICTED)
    def "should return successfully for POST /admin/transactionreceiptrequest if client has restricted permission"() {
        given:
        TransactionReceiptRequest payload = TransactionReceiptRequest.builder()
                .environmentId(aRandom.environmentId(environmentId))
                .transactionId(aRandom.uuidString())
                .build()

        when:
        adminReceiptClient.sendTransactionReceiptRequest(payload)

        then:
        1 * transactionReceiptRequestSupportalService.sendTransactionReceiptRequest({ TransactionReceiptRequest actualRequest ->
            assert actualRequest.environmentId == payload.environmentId
            assert actualRequest.transactionId == payload.transactionId
            assert RequestContext.get().environmentId == payload.environmentId
            true
        })
    }

    @RequiresBbAuthContext(supportal = true, includedPermissions = RECEIPTING_VIEW)
    def "should return 403 Forbidden for POST /admin/transactionreceiptrequest if client does not have restricted permission"() {
        given:
        TransactionReceiptRequest payload = TransactionReceiptRequest.builder()
                .environmentId(aRandom.environmentId(environmentId))
                .build()

        when:
        adminReceiptClient.sendTransactionReceiptRequest(payload)

        then:
        thrown(ForbiddenException)
    }

    @RequiresBbAuthContext(supportal = true, includedPermissions = RECEIPTING_RESTRICTED)
    def "should call ResGift supportal service with the expected environment id"() {
        given:
        String testEnvironmentId = aRandom.environmentId()
        String giftId = aRandom.intIdString()
        ResGiftTestRequest payload = resGiftTestPayload(testEnvironmentId, giftId)

        when:
        adminReceiptClient.testResGiftAutoReceiptWithExistingReceipt(payload)

        then:
        1 * resGiftSupportalService.testAutoReceiptWithExistingReceipt({ ResGiftTestRequest actualRequest ->
            assert actualRequest.environmentId == testEnvironmentId
            assert actualRequest.id == giftId
            assert actualRequest.details.constituentId == payload.details.constituentId
            assert actualRequest.details.originInfo == payload.details.originInfo
            assert actualRequest.details.resGiftType == payload.details.resGiftType
            assert actualRequest.details.transactionId == payload.details.transactionId
            assert RequestContext.get().environmentId == testEnvironmentId
            true
        })
    }

    @RequiresBbAuthContext(supportal = true, includedPermissions = RECEIPTING_VIEW)
    def "should return 403 Forbidden for POST /admin/resgifttest if client does not have permission"() {
        given:
        ResGiftTestRequest payload = resGiftTestPayload(aRandom.environmentId(), aRandom.intIdString())

        when:
        adminReceiptClient.testResGiftAutoReceiptWithExistingReceipt(payload)

        then:
        thrown ForbiddenException
    }

    private ReceiptSeriesEntity createReceiptSeries() {
        aRandom.receiptSeriesEntity()
                .withEnvironmentId(environmentId)
                .save()
    }

    private static ResGiftTestRequest resGiftTestPayload(String environmentId, String giftId) {
        ResGiftTestRequest.Details details = ResGiftTestRequest.Details.builder()
                .constituentId(100)
                .originInfo("{\"name\":\"Online Donation Forms\"}")
                .resGiftType(1)
                .transactionId(aRandom.uuidString())
                .build()
        return ResGiftTestRequest.builder()
                .environmentId(environmentId)
                .added(true)
                .id(giftId)
                .details(details)
                .build()
    }

    @RequiresBbAuthContext(supportal = true, includedPermissions = RECEIPTING_VIEW)
    def "should return 403 Forbidden for GET /admin/emailtemplates/notmigrated/count"() {
        when:
        adminReceiptClient.findNotMigratedGiftReceiptEmailTemplateCount()

        then:
        thrown ForbiddenException
    }

    @RequiresBbAuthContext(supportal = true, includedPermissions = RECEIPTING_RESTRICTED)
    def "should return count of not migrated gift receipt email templates for GET /admin/emailtemplates/notmigrated/count"() {
        given:
        long initialNotMigratedCount = adminReceiptClient.findNotMigratedGiftReceiptEmailTemplateCount().notMigratedEmailTemplateCount

        and:
        templateCreator.createDefaultEmailTemplateInfo(environmentId: environmentId,
                                                       emailTemplateHistoryMigrated: false)
        aRandom.emailTemplateInfoEntity()
                .withEnvironmentId(environmentId)
                .withType(null)
                .withEmailTemplateHistoryMigrated(false)
                .save()
        templateCreator.createDefaultEmailTemplateInfo(environmentId: environmentId,
                                                       emailTemplateHistoryMigrated: true)
        templateCreator.createDefaultEmailTemplateInfo(environmentId: environmentId,
                                                       emailTemplateType: CONSOLIDATED,
                                                       emailTemplateHistoryMigrated: false)

        when:
        GiftReceiptEmailTemplateNotMigratedCountResponse response = adminReceiptClient.findNotMigratedGiftReceiptEmailTemplateCount()

        then:
        assert response.notMigratedEmailTemplateCount == initialNotMigratedCount + 2
    }
}