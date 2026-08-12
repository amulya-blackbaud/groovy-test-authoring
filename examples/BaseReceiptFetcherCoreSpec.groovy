package com.blackbaud.receiptmanager.core.service.receipt.fetcher

import com.blackbaud.boot.exception.NotFoundException
import com.blackbaud.receiptmanager.CoreTest
import com.blackbaud.receiptmanager.api.receipt.LocationIssued
import com.blackbaud.receiptmanager.core.api.InternalReceipt
import com.blackbaud.receiptmanager.core.api.converters.InternalReceiptConverter
import com.blackbaud.receiptmanager.core.domain.bulktask.BulkTaskEntity
import com.blackbaud.receiptmanager.core.domain.formatters.InternalTimeZone
import com.blackbaud.receiptmanager.core.domain.mappers.LocationIssuedMapper
import com.blackbaud.receiptmanager.core.domain.receipt.InternalReceiptStatus
import com.blackbaud.receiptmanager.core.domain.receipt.ReceiptEntity
import com.blackbaud.receiptmanager.core.domain.receipt.ReceiptHistoryEntity
import com.blackbaud.receiptmanager.core.domain.receipt.ReceiptHistorySupportalRepository
import com.blackbaud.receiptmanager.core.domain.receipt.ReceiptSeriesEntity
import com.blackbaud.receiptmanager.core.domain.receipt.ReceiptSeriesRepository
import com.blackbaud.receiptmanager.core.service.EngSysZone
import com.blackbaud.receiptmanager.core.service.organization.InternalOrganizationService
import com.blackbaud.setsettings.api.OrgSettings
import com.blackbaud.setsettings.client.SettingsSkyApiClient
import com.blackbaud.testsupport.BBAuthSupport
import org.apache.commons.collections4.CollectionUtils
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import spock.lang.Specification

import java.time.OffsetDateTime
import java.time.ZoneId

import static com.blackbaud.receiptmanager.core.CoreARandom.aRandom
import static com.blackbaud.receiptmanager.core.service.receipt.ReceiptCoreSpecUtil.createTaskWithReceiptAndHistory
import static com.blackbaud.receiptmanager.core.service.receipt.ReceiptCoreSpecUtil.saveARandomAmountOfCancelledReceiptsWithTheSameKeyIdentifier

@CoreTest
class BaseReceiptFetcherCoreSpec extends Specification implements BBAuthSupport {
    @Value('${EngSys__Zone}')
    private String zone

    @Autowired
    private BaseReceiptFetcher baseReceiptFetcher

    @Autowired
    private ReceiptHistorySupportalRepository receiptHistorySupportalRepository

    @Autowired
    private ReceiptSeriesRepository receiptSeriesRepository

    @Autowired
    private LocationIssuedMapper locationIssuedMapper

    @Autowired
    private InternalOrganizationService internalOrganizationService

    @Autowired
    private SettingsSkyApiClient mockSettingsSkyApiClient

    def "should return receipts given a list of key identifiers"() {
        given:
        List<ReceiptHistoryEntity> existingReceiptHistory = aRandom.nonEmptyList {
            aRandom.receiptHistoryEntity()
                    .environmentId(environmentId)
                    .build()
        }

        List<ReceiptEntity> receiptsToFind = existingReceiptHistory.collect {
            aRandom.receiptEntity()
                    .withEnvironmentId(environmentId)
                    .saveAndLinkWithOneHistoryAndSeriesAndTask(it)
        }

        aRandom.nonEmptyList {
            aRandom.receiptEntity().saveAndLinkWithOneHistoryAndSeriesAndTask()
        }

        List<String> keyIds = receiptsToFind.collect { it.keyIdentifier }

        OrgSettings orgSettings = aRandom.orgSettings().build()
        mockSettingsSkyApiClient.getOrgSettings() >> orgSettings

        when:
        List<InternalReceipt> foundReceipts = baseReceiptFetcher.fetchInternalReceipts(keyIds, environmentId)

        then:
        assert CollectionUtils.isEqualCollection(foundReceipts, receiptsToFind.collect { receiptEntity ->
            ReceiptHistoryEntity receiptHistoryEntityForReceipt = existingReceiptHistory.find { it.receiptId == receiptEntity.id }
            ZoneId organizationZoneIdOrEngSysDefault = internalOrganizationService.fetchOrganizationTimeZoneIdOrEngSysDefault()
            InternalReceiptConverter.convertToInternalReceipt(receiptEntity, receiptHistoryEntityForReceipt, organizationZoneIdOrEngSysDefault)
        })
    }

    def "should return latest receipt for each gift given a list of key identifiers"() {
        given:
        List<ReceiptHistoryEntity> existingReceiptHistory = aRandom.nonEmptyList {
            aRandom.receiptHistoryEntity()
                    .environmentId(environmentId)
                    .build()
        }
        List<ReceiptEntity> receiptsToFind = existingReceiptHistory.collect {
            aRandom.receiptEntity()
                    .withEnvironmentId(environmentId)
                    .withReceiptStatus(aRandom.notCancelledReceiptStatus())
                    .saveAndLinkWithOneHistoryAndSeriesAndTask(it)
        }

        aRandom.nonEmptyList {
            aRandom.receiptEntity().saveAndLinkWithOneHistoryAndSeriesAndTask()
        }

        List<String> keyIds = receiptsToFind.collect { it.keyIdentifier }

        OrgSettings orgSettings = aRandom.orgSettings().build()
        mockSettingsSkyApiClient.getOrgSettings() >> orgSettings
        ZoneId organizationZoneIdOrEngSysDefault = internalOrganizationService.fetchOrganizationTimeZoneIdOrEngSysDefault()

        when:
        Map<String, InternalReceipt> idToLatestInternalReceipt = baseReceiptFetcher.fetchNotCancelledInternalReceiptsByGiftId(keyIds, environmentId)

        then:

        assert idToLatestInternalReceipt == receiptsToFind.collectEntries { giftReceipt ->
            ReceiptHistoryEntity receiptHistoryEntityForReceipt = existingReceiptHistory.find { it.receiptId == giftReceipt.id }
            [(giftReceipt.keyIdentifier): InternalReceiptConverter.convertToInternalReceipt(giftReceipt, receiptHistoryEntityForReceipt, organizationZoneIdOrEngSysDefault)]
        }
    }

    def "should return the latest receipt for a gift when the latest receipt was not cancelled, and all previous receipts are cancelled"() {
        given:
        String giftId = aRandom.intIdString()
        saveARandomAmountOfCancelledReceiptsWithTheSameKeyIdentifier(giftId, environmentId)

        and:
        mockSettingsSkyApiClient.getOrgSettings() >> aRandom.orgSettings().build()

        ReceiptEntity nonCancelledReceipt = aRandom.receiptEntity()
                .withEnvironmentId(environmentId)
                .withKeyIdentifier(giftId)
                .withReceiptStatus(aRandom.notCancelledReceiptStatus())
                .withReceiptDateTime(OffsetDateTime.now())
                .saveAndLinkWithOneHistoryAndSeriesAndTask()

        when:
        Map<String, InternalReceipt> idToLatestInternalReceipt = baseReceiptFetcher
                .fetchNotCancelledInternalReceiptsByGiftId([giftId], environmentId)

        then:
        InternalReceipt nonCancelledInternalReceipt = convertReceiptEntityWithSavedHistoryToInternalReceipt(nonCancelledReceipt)
        assert idToLatestInternalReceipt[giftId] == nonCancelledInternalReceipt
    }

    def "should return null for a gift when all of its receipts have been cancelled"() {
        given:
        String giftId = aRandom.intIdString()
        saveARandomAmountOfCancelledReceiptsWithTheSameKeyIdentifier(giftId, environmentId)
        mockSettingsSkyApiClient.getOrgSettings() >> aRandom.orgSettings().build()

        when:
        Map<String, InternalReceipt> idToLatestInternalReceipt = baseReceiptFetcher
                .fetchNotCancelledInternalReceiptsByGiftId([giftId], environmentId)

        then:
        assert idToLatestInternalReceipt[giftId] == null
    }

    def "should return empty list given a list of key identifiers that are not found"() {
        given:
        aRandom.nonEmptyList {
            aRandom.receiptEntity().saveAndLinkWithOneHistoryAndSeriesAndTask()
        }

        List<String> keyIds = aRandom.nonEmptyList { aRandom.uuidString() }
        mockSettingsSkyApiClient.getOrgSettings() >> aRandom.orgSettings().build()

        when:
        List<InternalReceipt> foundReceipts = baseReceiptFetcher.fetchInternalReceipts(keyIds, environmentId)

        then:
        assert foundReceipts == []
    }

    def "should throw NotFound when there is no saved receipts"() {
        when:
        baseReceiptFetcher.fetchActionableInternalReceipt(aRandom.uuid(),
                                                          aRandom.intIdString(),
                                                          environmentId)

        then:
        thrown(NotFoundException)
    }

    def "should throw NotFound when the receipt status on the receipt is not actionable"() {
        given:
        ReceiptHistoryEntity existingReceiptHistory = createTaskWithReceiptAndHistory(environmentId,
                                                                                      aRandom.intIdString(),
                                                                                      aRandom.nonActionableReceiptStatus())

        when:
        baseReceiptFetcher.fetchActionableInternalReceipt(existingReceiptHistory.taskId,
                                                          existingReceiptHistory.keyIdentifier,
                                                          environmentId)

        then:
        thrown(NotFoundException)
    }

    def "should return actionable internal receipt"() {
        given:
        ReceiptEntity actionableReceipt = aRandom.createActionableReceiptEntity(environmentId)

        and:
        ReceiptHistoryEntity existingReceiptHistory = aRandom.receiptHistoryEntity()
                .saveAndLinkWithReceiptAndBulkTask(environmentId, actionableReceipt)

        and:
        ReceiptSeriesEntity receiptSeriesEntity = receiptSeriesRepository.findById(existingReceiptHistory.receiptSeriesId).get()

        and:
        LocationIssued expectedLocationIssued = locationIssuedMapper.fromEntity(receiptSeriesEntity.locationIssued)

        OrgSettings orgSettings = aRandom.orgSettings().build()
        mockSettingsSkyApiClient.getOrgSettings() >> orgSettings
        ZoneId organizationTimeZoneId = InternalTimeZone.zoneIdFromTimeZoneNameOrEngSysDefault(orgSettings.timeZoneId, EngSysZone.forValue(zone))

        when:
        InternalReceipt fetchedReceipt = baseReceiptFetcher.fetchActionableInternalReceipt(existingReceiptHistory.taskId,
                                                                                           existingReceiptHistory.keyIdentifier,
                                                                                           environmentId)

        then:
        assert fetchedReceipt == InternalReceiptConverter.convertToInternalReceipt(actionableReceipt,
                                                                                   existingReceiptHistory,
                                                                                   organizationTimeZoneId,
                                                                                   expectedLocationIssued)
    }

    def "should return internal receipt with issued status and location when fetching receipt with location from a bulk task"() {
        given:
        ReceiptEntity receiptEntity = aRandom.receiptEntity()
                .withEnvironmentId(environmentId)
                .withReceiptStatus(aRandom.notCancelledReceiptStatus())
                .save()
        BulkTaskEntity bulkTaskEntity = aRandom.bulkTaskEntity()
                .withEnvironmentId(environmentId)
                .withReceiptId(receiptEntity.id)
                .save()
        ReceiptHistoryEntity receiptHistoryEntity = aRandom.receiptHistoryEntity()
                .saveAndLinkWithReceiptAndBulkTask(environmentId, receiptEntity, bulkTaskEntity)

        and:
        OrgSettings orgSettings = aRandom.orgSettings().build()
        mockSettingsSkyApiClient.getOrgSettings() >> orgSettings

        when:
        InternalReceipt foundReceipt = baseReceiptFetcher.fetchInternalReceiptWithLocation(bulkTaskEntity)

        then:
        ReceiptSeriesEntity receiptSeriesEntity = receiptSeriesRepository.findById(receiptHistoryEntity.receiptSeriesId).get()
        LocationIssued expectedLocationIssued = locationIssuedMapper.fromEntity(receiptSeriesEntity.locationIssued)
        ZoneId organizationTimeZoneId = InternalTimeZone.zoneIdFromTimeZoneNameOrEngSysDefault(orgSettings.timeZoneId, EngSysZone.forValue(zone))
        ReceiptEntity receiptEntityWithIssuedStatus = receiptEntity.toBuilder().receiptStatus(InternalReceiptStatus.ISSUED).build()
        assert foundReceipt == InternalReceiptConverter.convertToInternalReceipt(receiptEntityWithIssuedStatus,
                                                                                 receiptHistoryEntity,
                                                                                 organizationTimeZoneId,
                                                                                 expectedLocationIssued)
    }

    private InternalReceipt convertReceiptEntityWithSavedHistoryToInternalReceipt(ReceiptEntity receiptEntity) {
        ReceiptHistoryEntity receiptHistoryEntity = receiptHistorySupportalRepository
                .findFirstByEnvironmentIdAndReceiptIdOrderByIdDesc(environmentId, receiptEntity.id).get()

        ZoneId organizationZoneIdOrEngSysDefault = internalOrganizationService.fetchOrganizationTimeZoneIdOrEngSysDefault()
        InternalReceiptConverter.convertToInternalReceipt(receiptEntity, receiptHistoryEntity, organizationZoneIdOrEngSysDefault)
    }
}