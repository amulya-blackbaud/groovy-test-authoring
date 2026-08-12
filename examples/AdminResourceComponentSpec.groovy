package com.blackbaud.receiptmanager.resources

import com.blackbaud.blobstore.azure.TypedAzureBlobContainer
import com.blackbaud.boot.exception.BadRequestException
import com.blackbaud.boot.exception.ForbiddenException
import com.blackbaud.receiptmanager.ComponentTest
import com.blackbaud.receiptmanager.EntitledSpecification
import com.blackbaud.receiptmanager.api.bulktask.BulkTask
import com.blackbaud.receiptmanager.api.bulktask.BulkTaskFormat
import com.blackbaud.receiptmanager.api.bulktask.BulkTaskRecord
import com.blackbaud.receiptmanager.api.gift.GiftIdsPostRequest
import com.blackbaud.receiptmanager.client.AdminClient
import com.blackbaud.receiptmanager.core.domain.bulktask.BulkTaskEntity
import com.blackbaud.receiptmanager.core.domain.bulktask.BulkTaskStatus
import com.blackbaud.receiptmanager.core.domain.bulktask.BulkTaskType
import com.blackbaud.receiptmanager.core.domain.bulktask.ExpandedBulkTaskEntity
import com.blackbaud.receiptmanager.core.domain.bulktaskrecord.BulkTaskRecordEntity
import com.blackbaud.receiptmanager.core.domain.mappers.BulkTaskMapper
import com.blackbaud.receiptmanager.core.domain.mappers.BulkTaskRecordMapper
import com.blackbaud.receiptmanager.core.domain.mappers.BulkTaskSupportalMapper
import com.blackbaud.receiptmanager.core.service.admin.giftbatch.GiftBatchSupportalService
import com.blackbaud.receiptmanager.core.service.bulktask.BulkTaskEntitySqlTestService
import com.blackbaud.receiptmanager.core.service.receipt.ReceiptHistoryService
import com.blackbaud.receiptmanager.core.service.taskprocessing.taskrecord.giftreceipt.queue.EmailGiftReceiptGeneratingQueueService
import com.blackbaud.receiptmanager.core.service.taskprocessing.taskrecord.givingstatement.GivingStatementTaskRecordProcessor
import com.blackbaud.receiptmanager.core.service.taskprocessing.taskrecord.givingstatement.queue.GivingStatementQueueService
import com.blackbaud.receiptmanager.resources.admin.AdminResource
import com.blackbaud.receiptmanager.shared.BulkTaskEntityExpander
import com.blackbaud.testsupport.BBAuthSupport
import com.blackbaud.testsupport.BeanCompare
import com.blackbaud.testsupport.RequiresBbAuthContext
import com.blackbaud.testsupport.ResettingMockInjector
import com.blackbaud.traceability.StubTracerSupport
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import spock.lang.Unroll

import java.time.OffsetDateTime

import static com.blackbaud.receiptmanager.core.CoreARandom.aRandom
import static com.blackbaud.receiptmanager.resources.BaseSupportalComponentSpec.SUPPORTAL_ENVIRONMENT_ID
import static com.blackbaud.receiptmanager.resources.GeneratedPermissions.RECEIPTING_RESTRICTED
import static com.blackbaud.receiptmanager.resources.GeneratedPermissions.RECEIPTING_VIEW
import static com.blackbaud.time.MicroPrecisionOffsetDateTimeProvider.nowAtMicroPrecision

@ComponentTest
@Slf4j
@RequiresBbAuthContext(environmentId = SUPPORTAL_ENVIRONMENT_ID, supportal = true, includedPermissions = RECEIPTING_VIEW)
class AdminResourceComponentSpec extends EntitledSpecification implements BBAuthSupport, StubTracerSupport {

    @Autowired
    private AdminClient adminClient

    @Autowired
    private AdminResource adminResource

    @Autowired
    private BulkTaskSupportalMapper bulkTaskSupportalMapper

    @Autowired
    private GivingStatementTaskRecordProcessor givingStatementTaskRecordProcessor

    @Autowired
    private BulkTaskRecordMapper bulkTaskRecordMapper

    @Autowired
    private BulkTaskEntityExpander bulkTaskEntityExpander

    @Autowired
    private BulkTaskMapper bulkTaskMapper

    @Autowired
    private BulkTaskEntitySqlTestService bulkTaskEntitySqlTestService

    @Autowired
    private TypedAzureBlobContainer<Map<String, String>> receiptMergeFieldsContainer

    @Autowired
    private EmailGiftReceiptGeneratingQueueService emailGiftReceiptGeneratingQueueService

    private BeanCompare beanCompare = new BeanCompare()
    private GiftBatchSupportalService mockGiftBatchSupportalService

    def setup() {
        withEntitlements([])
        beanCompare.excludeFields("id", "blobCreatedBy", "blobCreatedDate")
        ResettingMockInjector.set(emailGiftReceiptGeneratingQueueService, Mock(ReceiptHistoryService))
        ResettingMockInjector.set(givingStatementTaskRecordProcessor, Mock(GivingStatementQueueService))

        mockGiftBatchSupportalService = ResettingMockInjector.set(adminResource, Mock(GiftBatchSupportalService))
    }

    def cleanup() {
        receiptMergeFieldsContainer.forEach { it.delete() }
    }

    @Unroll
    def "should get a list of bulk task records by the given environment id and bulk task id for bulk task type #bulkTaskType"() {
        given:
        BulkTaskEntity bulkTaskEntity = createBulkTaskEntity(bulkTaskType)
        UUID bulkTaskId = bulkTaskEntity.id
        String environmentId = bulkTaskEntity.environmentId

        and:
        List<BulkTaskRecord> expectedTaskRecords = bulkTaskRecordMapper.toApiList(createBulkTaskRecordEntities(bulkTaskId, environmentId))

        when:
        ArrayList<BulkTaskRecord> taskRecords = adminClient.findBulkTaskRecords(bulkTaskId, environmentId)

        then:
        beanCompare.assertEquals(expectedTaskRecords, taskRecords)

        where:
        bulkTaskType << [BulkTaskType.GIFT_RECEIPT, BulkTaskType.GIVING_STATEMENT]
    }

    def "should find bulk task for environmentId "() {
        given:
        List<BulkTaskEntity> bulkTaskEntitiesNotToFind = aRandom.nonEmptyList { aRandom.bulkTaskEntity().save() }

        and:
        List<ExpandedBulkTaskEntity> bulkTasksToFindByEnvironmentId = aRandom.nonEmptyList {
            bulkTaskEntityExpander.expandEntity(createBulkTaskEntity())
        }.sort { it.createdDate }

        when:
        List<BulkTask> foundBulkTasks = adminClient.findBulkTaskByEnvironmentId(environmentId)

        then:
        assert foundBulkTasks == bulkTaskMapper.toApiList(bulkTasksToFindByEnvironmentId)

        and:
        assertBulkTasksAreNotPresent(foundBulkTasks, bulkTaskSupportalMapper.toApiList(bulkTaskEntitiesNotToFind))
    }

    def "should find bulk task for environmentId over last N days"() {
        given:
        int lastNumberOfDays = aRandom.intBetween(1, 10)

        and:
        List<BulkTaskEntity> bulkTaskEntitiesNotToFind = aRandom.nonEmptyList { aRandom.bulkTaskEntity().save() }

        and:
        List<ExpandedBulkTaskEntity> bulkTasksToFindWithinMaxNumberOfDays = createExpandedBulkTaskEntitiesAndCreatedDateBetween(0, lastNumberOfDays)
                .sort { it.createdDate }

        and:
        List<ExpandedBulkTaskEntity> bulkTasksCreatedBeforeMaxDays = createExpandedBulkTaskEntitiesAndCreatedDateBetween(lastNumberOfDays + 1, 100)

        when:
        List<BulkTask> foundBulkTasks = adminClient.findBulkTaskByEnvironmentIdOverLastNumberOfDays(environmentId, lastNumberOfDays)

        then:
        beanCompare.excludeFields("lastModifiedDate")
                .assertEquals(foundBulkTasks, bulkTaskMapper.toApiList(bulkTasksToFindWithinMaxNumberOfDays))

        and:
        assertBulkTasksAreNotPresent(foundBulkTasks, bulkTaskSupportalMapper.toApiList(bulkTaskEntitiesNotToFind))

        and:
        assertBulkTasksAreNotPresent(foundBulkTasks, bulkTaskMapper.toApiList(bulkTasksCreatedBeforeMaxDays))
    }

    def "should find bulk task for environmentId for today when lastNumberOfDays is zero"() {
        given:
        List<BulkTaskEntity> bulkTaskEntitiesNotToFind = aRandom.nonEmptyList { aRandom.bulkTaskEntity().save() }

        and:
        List<ExpandedBulkTaskEntity> bulkTaskEntitiesToFindByEnvironmentIdCreatedToday = createExpandedBulkTaskEntitiesAndSetCreatedDate(nowAtMicroPrecision())
                .sort { it.createdDate }

        and:
        List<ExpandedBulkTaskEntity> bulkTaskEntitiesByEnvironmentIdAfterToday = createExpandedBulkTaskEntitiesAndCreatedDateBetween(1, aRandom.intBetween(2, 100))

        when:
        List<BulkTask> foundBulkTasks = adminClient.findBulkTaskByEnvironmentIdOverLastNumberOfDays(environmentId, 0)

        then:
        beanCompare.excludeFields("lastModifiedDate")
                .assertEquals(foundBulkTasks, bulkTaskMapper.toApiList(bulkTaskEntitiesToFindByEnvironmentIdCreatedToday))

        and:
        assertBulkTasksAreNotPresent(foundBulkTasks, bulkTaskSupportalMapper.toApiList(bulkTaskEntitiesNotToFind))

        and:
        assertBulkTasksAreNotPresent(foundBulkTasks, bulkTaskMapper.toApiList(bulkTaskEntitiesByEnvironmentIdAfterToday))
    }

    @RequiresBbAuthContext(supportal = true, includedPermissions = RECEIPTING_VIEW)
    def "should return successfully for GET /admin/environments if client has view permission"() {
        when:
        adminClient.listEnvironmentsWithData()

        then:
        noExceptionThrown()
    }

    @RequiresBbAuthContext(supportal = true, includedPermissions = [])
    def "should return 403 Forbidden for GET /admin/environments if client does not have view permission"() {
        when:
        adminClient.listEnvironmentsWithData()

        then:
        thrown(ForbiddenException)
    }

    @RequiresBbAuthContext(supportal = true, includedPermissions = RECEIPTING_VIEW)
    def "should return successfully for GET /admin/environments/{environmentId} if client has view permission"() {
        given:
        String environmentToSearch = aRandom.environmentId()

        when:
        adminClient.findDataSummaryForEnvironment(environmentToSearch)

        then:
        noExceptionThrown()
    }

    @RequiresBbAuthContext(supportal = true, includedPermissions = [])
    def "should return 403 Forbidden for GET /admin/environments/{environmentId} if client does not have view permission"() {
        given:
        String environmentToSearch = aRandom.environmentId()

        when:
        adminClient.findDataSummaryForEnvironment(environmentToSearch)

        then:
        thrown(ForbiddenException)
    }

    @RequiresBbAuthContext(supportal = true, includedPermissions = RECEIPTING_VIEW)
    def "should return BulkTask successfully for GET /admin/bulk/{bulkTaskId} if client has view permission"() {
        given:
        BulkTaskEntity savedBulkTask = aRandom.bulkTaskEntity().save()

        and:
        ExpandedBulkTaskEntity expandedBulkTask = bulkTaskEntityExpander.expandEntity(savedBulkTask)

        when:
        BulkTask foundBulkTask = adminClient.findBulkTask(savedBulkTask.id)

        then:
        assert foundBulkTask == bulkTaskMapper.toApi(expandedBulkTask)
    }

    @RequiresBbAuthContext(supportal = true, includedPermissions = [])
    def "should return 403 Forbidden for GET /admin/bulk/{bulkTaskId} if client does not have view permission"() {
        when:
        adminClient.findBulkTask(aRandom.uuid())

        then:
        thrown(ForbiddenException)
    }

    @RequiresBbAuthContext(supportal = true, includedPermissions = RECEIPTING_VIEW)
    def "should return 403 Forbidden for POST /admin/gifts/markNotReceipted/{environmentId} if client does not have restricted permission"() {
        given:
        String envId = aRandom.environmentId(environmentId)

        GiftIdsPostRequest postRequest = GiftIdsPostRequest.builder()
                .giftIds(aRandom.nonEmptyList { aRandom.intIdString() })
                .build()

        when:
        adminClient.markGiftsAsNotReceipted(envId, postRequest)

        then:
        thrown(ForbiddenException)
    }

    @RequiresBbAuthContext(supportal = true, includedPermissions = [])
    def "should return 403 Forbidden for POST /admin/gifts/create/{environmentId} if client does not have restricted permission"() {
        given:
        String envId = aRandom.environmentId(environmentId)

        when:
        adminClient.createGifts(envId, aRandom.tinyInt(), aRandom.localeString())

        then:
        thrown(ForbiddenException)
    }

    @RequiresBbAuthContext(supportal = true, includedPermissions = RECEIPTING_RESTRICTED)
    @Unroll
    def "should return BadRequestException POST /admin/gifts/create/{environmentId} if number of gifts is #scenario"() {
        given:
        String envId = aRandom.environmentId(environmentId)

        when:
        adminClient.createGifts(envId, numberOfGifts, aRandom.localeString())

        then:
        thrown(BadRequestException)

        where:
        scenario           | numberOfGifts
        "less than 1"      | 0
        "greater than 300" | 301
    }

    @RequiresBbAuthContext(supportal = true, includedPermissions = RECEIPTING_RESTRICTED)
    def "should return 204 for POST /admin/gifts/create/{environmentId} if number of gifts is within range"() {
        given:
        String envId = aRandom.environmentId(environmentId)
        int numberOfGifts = aRandom.intBetween(1, 300)
        Locale locale = aRandom.supportedLocale()

        when:
        adminClient.createGifts(envId, numberOfGifts, locale.toString())

        then:
        1 * mockGiftBatchSupportalService.createBatchOfGifts(numberOfGifts, locale)
    }

    private static BulkTaskEntity createBulkTaskEntity(String environmentId = aRandom.getEnvironmentIdFromRequestContextOrCreate()) {
        aRandom.bulkTaskEntity()
                .withEnvironmentId(environmentId)
                .save()
    }

    private static BulkTaskEntity createBulkTaskEntity(BulkTaskType bulkTaskType, BulkTaskStatus status = aRandom.bulkTaskStatus(), String environmentId = aRandom.getEnvironmentIdFromRequestContextOrCreate()) {
        aRandom.bulkTaskEntity()
                .withEnvironmentId(environmentId)
                .withBulkTaskType(bulkTaskType)
                .withStatus(status)
                .withFormat(BulkTaskFormat.EMAIL)
                .save()
    }

    private static List<BulkTaskRecordEntity> createBulkTaskRecordEntities(UUID bulkTaskId, String environmentId) {
        aRandom.list(5, 10, {
            aRandom.bulkTaskRecordEntity()
                    .withId(aRandom.bulkTaskRecordKey()
                                    .environmentId(environmentId)
                                    .taskId(bulkTaskId)
                                    .build())
                    .withErrorInfo(aRandom.text(10))
                    .save()
        })
    }

    private List<ExpandedBulkTaskEntity> createExpandedBulkTaskEntitiesAndCreatedDateBetween(int start, int end) {
        aRandom.nonEmptyList { createBulkTaskEntity() }
                .collect {
                    setCreatedDateAndExpand(it, nowAtMicroPrecision().minusDays(aRandom.intBetween(start, end)))
                }
    }

    private List<ExpandedBulkTaskEntity> createExpandedBulkTaskEntitiesAndSetCreatedDate(OffsetDateTime offsetDateTime) {
        aRandom.nonEmptyList { createBulkTaskEntity() }
                .collect {
                    setCreatedDateAndExpand(it, offsetDateTime)
                }
    }

    private ExpandedBulkTaskEntity setCreatedDateAndExpand(BulkTaskEntity bulkTaskEntity, OffsetDateTime offsetDateTime) {
        BulkTaskEntity updatedBulkTaskEntity = bulkTaskEntitySqlTestService.updateCreatedDateOnBulkTask(offsetDateTime, bulkTaskEntity)
        bulkTaskEntityExpander.expandEntity(updatedBulkTaskEntity)
    }

    private static void assertBulkTasksAreNotPresent(List<BulkTask> foundBulkTasks, List<BulkTask> bulkTasksNotToFind) {
        assert foundBulkTasks.containsAll(bulkTasksNotToFind) == false
    }
}
