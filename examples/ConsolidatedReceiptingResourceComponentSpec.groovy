package com.blackbaud.receiptmanager.resources

import com.blackbaud.boot.exception.BadRequestException
import com.blackbaud.context.RequestContext
import com.blackbaud.gift.api.gift.RenxtGift
import com.blackbaud.gift.api.gift.RenxtGiftListRequest
import com.blackbaud.gift.api.gift.RenxtGiftsResponse
import com.blackbaud.gift.client.RenxtGiftSasClient
import com.blackbaud.lists.api.RenxtConstituentListIds
import com.blackbaud.lists.client.RenxtListsClient
import com.blackbaud.receiptmanager.ComponentTest
import com.blackbaud.receiptmanager.EntitledSpecification
import com.blackbaud.receiptmanager.api.bulktask.BulkTaskFormat
import com.blackbaud.receiptmanager.api.consolitatedreceipting.ConsolidatedReceiptConstituentRequest
import com.blackbaud.receiptmanager.api.consolitatedreceipting.ConsolidatedReceiptResponse
import com.blackbaud.receiptmanager.api.consolitatedreceipting.GivingStatementConstituentSummary
import com.blackbaud.receiptmanager.api.consolitatedreceipting.GivingStatementConstituentsResponse
import com.blackbaud.receiptmanager.api.givingstatement.GiftPeriod
import com.blackbaud.receiptmanager.client.ConsolidatedReceiptingClient
import com.blackbaud.receiptmanager.core.api.InternalContributor
import com.blackbaud.receiptmanager.core.domain.consolidatedreceipting.GivingStatementEntity
import com.blackbaud.receiptmanager.core.domain.consolidatedreceipting.GivingStatementGiftLookupEntity
import com.blackbaud.receiptmanager.core.domain.consolidatedreceipting.GivingStatementGiftLookupKey
import com.blackbaud.receiptmanager.core.domain.consolidatedreceipting.GivingStatementGiftLookupRepository
import com.blackbaud.receiptmanager.core.domain.consolidatedreceipting.GivingStatementRepository
import com.blackbaud.receiptmanager.core.domain.consolidatedreceipting.GivingStatementType
import com.blackbaud.receiptmanager.core.service.consolidatedreceipting.ConsolidatedReceiptingService
import com.blackbaud.receiptmanager.core.service.consolidatedreceipting.receiptprocessor.IndividualGivingStatementProcessor
import com.blackbaud.receiptmanager.core.service.contributor.InternalContributorService
import com.blackbaud.receiptmanager.core.service.contributor.InternalContributorServiceSelector
import com.blackbaud.receiptmanager.core.service.contributor.RenxtContributorService
import com.blackbaud.receiptmanager.core.service.user.client.UserResponse
import com.blackbaud.receiptmanager.core.service.user.client.UserSasClient
import com.blackbaud.receiptmanager.external.gift.api.gift.RenxtGiftWrapper
import com.blackbaud.testsupport.BBAuthSupport
import com.blackbaud.testsupport.RequiresBbAuthContext
import com.blackbaud.testsupport.ResettingMockInjector
import org.springframework.beans.factory.annotation.Autowired
import spock.lang.Unroll

import java.time.LocalDate
import java.time.OffsetDateTime

import static com.blackbaud.receiptmanager.core.CoreARandom.aRandom
import static com.blackbaud.receiptmanager.core.api.InternalEntitlementValues.HOUSEHOLD_GIVING
import static com.blackbaud.time.MicroPrecisionOffsetDateTimeProvider.nowAtMicroPrecision

@RequiresBbAuthContext
@ComponentTest
class ConsolidatedReceiptingResourceComponentSpec extends EntitledSpecification implements BBAuthSupport {

    @Autowired
    private ConsolidatedReceiptingClient consolidatedReceiptingClient

    @Autowired
    private ConsolidatedReceiptingService consolidatedReceiptingService

    @Autowired
    private RenxtGiftSasClient renxtGiftSasClient

    @Autowired
    private GivingStatementRepository givingStatementRepository

    @Autowired
    private GivingStatementGiftLookupRepository givingStatementGiftLookupRepository

    @Autowired
    private UserSasClient userSasClient

    @Autowired
    private IndividualGivingStatementProcessor individualGivingStatementProcessor

    @Autowired
    private RenxtListsClient listsClient

    private RenxtContributorService mockRenxtContributorService
    private InternalContributorService mockInternalContributorService

    def setup() {
        mockRenxtContributorService = ResettingMockInjector.set(individualGivingStatementProcessor, Mock(RenxtContributorService))
        ResettingMockInjector.set(consolidatedReceiptingService, mockContributor())
    }

    def "should aggregate gift and existing receipts from ConsolidatedReceiptingClient"() {
        given:
        withEntitlements([])
        OffsetDateTime dateGiftAdded = nowAtMicroPrecision().minusYears(1)
        LocalDate periodStart = LocalDate.now().minusYears(2)
        LocalDate periodEnd = LocalDate.now()
        OffsetDateTime receiptStatusDate = nowAtMicroPrecision().minusMonths(6)

        String constituentIdForPdfTask = aRandom.intId()
        List<RenxtGift> giftsForConstituentWithPdfTask = aRandom.nonEmptyList {
            createGiftsForDate(dateGiftAdded, constituentIdForPdfTask)
        }

        String constituentIdForEmailTask = aRandom.intId()
        List<RenxtGift> giftsForConstituentWithEmailTask = aRandom.nonEmptyList {
            createGiftsForDate(dateGiftAdded, constituentIdForEmailTask)
        }

        and:
        RenxtGiftsResponse giftsResponse = aRandom.renxtGiftsResponse()
                .nextLink(null)
                .value(giftsForConstituentWithPdfTask + giftsForConstituentWithEmailTask)
                .build()

        and:
        GivingStatementEntity givingStatementEmailFormatEntity = aRandom.givingStatementEntity()
                .environmentId(environmentId)
                .constituentId(constituentIdForEmailTask)
                .periodStartDate(periodStart)
                .periodEndDate(periodEnd)
                .emailReceiptStatusDate(receiptStatusDate)
                .bulkTaskFormat(BulkTaskFormat.EMAIL)
                .build()

        GivingStatementEntity givingStatementPdfFormatEntity = aRandom.givingStatementEntity()
                .environmentId(environmentId)
                .constituentId(constituentIdForPdfTask)
                .periodStartDate(periodStart)
                .periodEndDate(periodEnd)
                .emailReceiptStatusDate(receiptStatusDate)
                .bulkTaskFormat(BulkTaskFormat.PDF)
                .build()

        GivingStatementEntity givingStatementEntityOtherEnv = aRandom.givingStatementEntity()
                .environmentId(aRandom.environmentId())
                .build()
        givingStatementRepository.saveAll([
                givingStatementEmailFormatEntity,
                givingStatementPdfFormatEntity,
                givingStatementEntityOtherEnv])

        List<InternalContributor> contributors = [constituentIdForEmailTask, constituentIdForPdfTask]
                .collect { aRandom.internalContributor().keyIdentifier(it).build() }

        and:
        GiftPeriod giftPeriod = aRandom.giftPeriod()
                .periodStartDate(periodStart)
                .periodEndDate(periodEnd)
                .build()
        ConsolidatedReceiptConstituentRequest consolidatedReceiptConstituentRequest = aRandom.consolidatedReceiptConstituentRequest()
                .resultLimit(contributors.size())
                .giftPeriod(giftPeriod)
                .includeReceipted(true)
                .build()
        RenxtConstituentListIds renxtConstituentListIds = RenxtConstituentListIds.builder()
                .count(contributors.size())
                .ids(contributors.collect { it.keyIdentifier })
                .build()

        when:
        GivingStatementConstituentsResponse response =
                consolidatedReceiptingClient.getConsolidatedReceiptConstituents(consolidatedReceiptConstituentRequest)

        then:
        1 * listsClient.getConstituentListIds(consolidatedReceiptConstituentRequest.listId, null) >> renxtConstituentListIds
        1 * mockRenxtContributorService.fetchInternalContributorsAggregately(_) >> contributors
        1 * renxtGiftSasClient.getGiftsAsTrustedCaller(_ as Integer, _ as Integer, _ as Map<String, Object>) >> giftsResponse
        1 * renxtGiftSasClient.postGetGiftList(_ as RenxtGiftListRequest) >> buildRenxtGiftsResponse(giftsForConstituentWithPdfTask + giftsForConstituentWithEmailTask)

        and:
        assert response.value.size() == 2

        assertEmailFormatReceiptConstituentMatchesExpected(response, giftPeriod, receiptStatusDate, constituentIdForEmailTask, giftsForConstituentWithEmailTask)
        assertPdfFormatReceiptConstituentMatchesExpected(response, giftPeriod, receiptStatusDate, constituentIdForPdfTask, giftsForConstituentWithPdfTask)
    }

    def "should aggregate gift info per constituent from ConsolidatedReceiptingClient with limit and offset"() {
        given:
        withEntitlements([])
        RenxtGift giftInEnvId = createGiftsForDate(nowAtMicroPrecision().minusYears(1))
        RenxtGiftsResponse giftsResponse = aRandom.renxtGiftsResponse().nextLink(null).value([giftInEnvId]).build()

        and:
        ConsolidatedReceiptConstituentRequest consolidatedReceiptConstituentRequest = aRandom.consolidatedReceiptConstituentRequest()
                .giftPeriod(aRandom.giftPeriod().build())
                .build()
        RenxtConstituentListIds renxtConstituentListIds = RenxtConstituentListIds.builder()
                .count(1)
                .ids([giftInEnvId.constituentId])
                .build()

        when:
        GivingStatementConstituentsResponse response =
                consolidatedReceiptingClient.getConsolidatedReceiptConstituents(consolidatedReceiptConstituentRequest)

        then:
        1 * listsClient.getConstituentListIds(consolidatedReceiptConstituentRequest.listId, null) >> renxtConstituentListIds
        1 * mockRenxtContributorService.fetchInternalContributorsAggregately(_) >> [
                aRandom.internalContributor().keyIdentifier(giftInEnvId.constituentId).build()
        ]
        1 * renxtGiftSasClient.getGiftsAsTrustedCaller(_ as Integer, _ as Integer, _ as Map<String, Object>) >> giftsResponse
        1 * renxtGiftSasClient.postGetGiftList(_ as RenxtGiftListRequest) >> buildRenxtGiftsResponse([giftInEnvId])

        and:
        assert response.value.size() == 1
        assert response.value.first().id == giftInEnvId.constituentId
        assert response.value.first().totalGifts == 1
        assert response.value.first().totalGiftAmount == giftInEnvId.amount.value
        assert response.value.first().totalContributionAmount == extractContributionAmountFromGift(giftInEnvId)
    }

    def "should get all giving statements for a specified constituent and exclude other constituents"() {
        given:
        withEntitlements([])
        String constituentId = aRandom.intIdString()

        and:
        UserResponse admin1 = aRandom.userResponse().build()
        UserResponse admin2 = aRandom.userResponse().build()

        and:
        GivingStatementEntity firstReceipt = aRandom.givingStatementEntity()
                .constituentId(constituentId)
                .environmentId(environmentId)
                .periodStartDate(LocalDate.now().minusMonths(6))
                .build()
        GivingStatementEntity secondReceipt = aRandom.givingStatementEntity()
                .constituentId(constituentId)
                .environmentId(environmentId)
                .build()
        GivingStatementEntity otherConsReceipt = aRandom.givingStatementEntity()
                .environmentId(environmentId)
                .build()
        RequestContext.withUserAndEnvironment(UUID.fromString(admin1.bbid), environmentId, {
            givingStatementRepository.save(firstReceipt)
        })
        RequestContext.withUserAndEnvironment(UUID.fromString(admin2.bbid), environmentId, {
            givingStatementRepository.saveAll([secondReceipt, otherConsReceipt])
        })

        and:
        RenxtGift lastYearGift1 = buildGiftForConstituent(constituentId)
        RenxtGift lastYearGift2 = buildGiftForConstituent(constituentId)

        and:
        GivingStatementGiftLookupEntity lookup1 = buildGiftLookupForGiftAndReceipt(lastYearGift1, firstReceipt)
        GivingStatementGiftLookupEntity lookup2 = buildGiftLookupForGiftAndReceipt(lastYearGift2, firstReceipt)
        givingStatementGiftLookupRepository.saveAll([lookup1, lookup2])

        when:
        ConsolidatedReceiptResponse[] receiptResponses = consolidatedReceiptingClient.getConsolidatedReceipts(constituentId)

        then:
        1 * userSasClient.getUsersById(_) >> [admin1, admin2]
        0 * mockInternalContributorService.fetchInternalContributor(_)

        and:
        assert receiptResponses.size() == 2
        ConsolidatedReceiptResponse firstReceiptResponse = receiptResponses.find { it.consolidatedReceiptId == secondReceipt.consolidatedReceiptId }
        assert firstReceiptResponse.consolidatedReceiptId == secondReceipt.consolidatedReceiptId
        assert firstReceiptResponse.initiatedByUser.email == admin2.email
        ConsolidatedReceiptResponse secondReceiptResponse = receiptResponses.find { it.consolidatedReceiptId == firstReceipt.consolidatedReceiptId }
        assert secondReceiptResponse.consolidatedReceiptId == firstReceipt.consolidatedReceiptId
        assert secondReceiptResponse.initiatedByUser.email == admin1.email
    }

    def "should get all individual and household giving statements for specified constituent and exclude other receipts"() {
        given:
        withEntitlements([HOUSEHOLD_GIVING])
        InternalContributor nonPrimaryContributor = aRandom.internalContributor()
                .keyIdentifier(aRandom.intIdString())
                .withInternalFamilyHousehold(false)
                .build()

        String householdId = nonPrimaryContributor.internalHousehold.id

        and:
        UserResponse admin = aRandom.userResponse().build()

        and:
        GivingStatementEntity individualStatement = aRandom.givingStatementEntity()
                .constituentId(nonPrimaryContributor.keyIdentifier)
                .environmentId(environmentId)
                .givingStatementType(GivingStatementType.INDIVIDUAL.id)
                .build()
        GivingStatementEntity householdStatement = aRandom.givingStatementEntity()
                .constituentId(nonPrimaryContributor.internalHousehold.primaryHouseholdMember.id)
                .householdId(householdId)
                .givingStatementType(GivingStatementType.HOUSEHOLD.id)
                .environmentId(environmentId)
                .build()
        GivingStatementEntity headOfHouseholdIndividualStatement = aRandom.givingStatementEntity()
                .constituentId(nonPrimaryContributor.internalHousehold.primaryHouseholdMember.id)
                .householdId(householdId)
                .environmentId(environmentId)
                .periodStartDate(LocalDate.now().minusMonths(6))
                .givingStatementType(GivingStatementType.INDIVIDUAL.id)
                .build()
        RequestContext.withUserAndEnvironment(UUID.fromString(admin.bbid), environmentId, {
            givingStatementRepository.saveAll([individualStatement, householdStatement, headOfHouseholdIndividualStatement])
        })

        and:
        RenxtGift lastYearGift1 = buildGiftForConstituent(nonPrimaryContributor.keyIdentifier)
        RenxtGift lastYearGift2 = buildGiftForConstituent(nonPrimaryContributor.keyIdentifier)

        and:
        GivingStatementGiftLookupEntity lookup1 = buildGiftLookupForGiftAndReceipt(lastYearGift1, individualStatement)
        GivingStatementGiftLookupEntity lookup2 = buildGiftLookupForGiftAndReceipt(lastYearGift2, individualStatement)
        givingStatementGiftLookupRepository.saveAll([lookup1, lookup2])

        when:
        ConsolidatedReceiptResponse[] receiptResponses = consolidatedReceiptingClient.getConsolidatedReceipts(nonPrimaryContributor.keyIdentifier)

        then:
        1 * mockInternalContributorService.fetchInternalContributor(nonPrimaryContributor.keyIdentifier) >> nonPrimaryContributor
        1 * userSasClient.getUsersById(_) >> [admin]

        and:
        assert receiptResponses.size() == 2
        ConsolidatedReceiptResponse individualReceiptResponse = receiptResponses.find { it.consolidatedReceiptId == individualStatement.consolidatedReceiptId }
        assert individualReceiptResponse.consolidatedReceiptId == individualStatement.consolidatedReceiptId
        assert individualReceiptResponse.constituentId == nonPrimaryContributor.keyIdentifier
        assert individualReceiptResponse.givingStatementType == "INDIVIDUAL"

        ConsolidatedReceiptResponse householdReceiptResponse = receiptResponses.find { it.consolidatedReceiptId == householdStatement.consolidatedReceiptId }
        assert householdReceiptResponse.consolidatedReceiptId == householdStatement.consolidatedReceiptId
        assert householdReceiptResponse.constituentId == nonPrimaryContributor.internalHousehold.primaryHouseholdMember.id
        assert householdReceiptResponse.givingStatementType == "HOUSEHOLD"
    }

    @Unroll
    def "should populate receipt format when format is #description on consolidated receipt for constituent"() {
        given:
        withEntitlements([])
        String constituentId = aRandom.intIdString()

        and:
        UserResponse admin1 = aRandom.userResponse().build()

        and:
        GivingStatementEntity statement = aRandom.givingStatementEntity()
                .constituentId(constituentId)
                .periodStartDate(LocalDate.now().minusYears(1))
                .periodEndDate(LocalDate.now())
                .emailReceiptStatusDate(OffsetDateTime.now().minusDays(1))
                .bulkTaskFormat(format)
                .environmentId(environmentId)
                .build()

        givingStatementRepository.save(statement)

        when:
        ConsolidatedReceiptResponse[] receiptResponses = consolidatedReceiptingClient.getConsolidatedReceipts(constituentId)

        then:
        1 * userSasClient.getUsersById(_) >> [admin1]
        0 * mockInternalContributorService.fetchInternalContributor(_)

        and:
        assert receiptResponses.size() == 1
        assert receiptResponses[0].receiptFormat == expectedFormat

        where:
        format               | description | expectedFormat
        BulkTaskFormat.EMAIL | 'email'     | 'EMAIL'
        BulkTaskFormat.PDF   | 'pdf'       | 'PDF'
    }

    @Unroll
    def "should throw BadRequestException when constituent id is #description"() {
        when:
        consolidatedReceiptingClient.getConsolidatedReceipts(constituentId)

        then:
        thrown(BadRequestException)

        where:
        constituentId    | description
        aRandom.text(10) | 'a non-numeric string'
        null             | 'not present'
        ''               | 'empty'
    }

    private RenxtGift buildGiftForConstituent(String consId) {
        aRandom.renxtGift()
                .id(aRandom.intIdString())
                .constituentId(consId)
                .build()
    }

    private GivingStatementGiftLookupEntity buildGiftLookupForGiftAndReceipt(RenxtGift gift, GivingStatementEntity receipt) {
        GivingStatementGiftLookupEntity.builder()
                .id(GivingStatementGiftLookupKey.builder()
                            .givingStatementId(receipt.consolidatedReceiptId)
                            .environmentId(environmentId)
                            .giftId(gift.id)
                            .build())
                .build()
    }

    private void assertPdfFormatReceiptConstituentMatchesExpected(GivingStatementConstituentsResponse response,
                                                                  GiftPeriod giftPeriod,
                                                                  OffsetDateTime receiptStatusDate,
                                                                  String constituentIdForPdfTask,
                                                                  List<RenxtGift> giftsForConstituentWithPdfTask
    ) {
        GivingStatementConstituentSummary pdfFormatReceiptConstituent = response.value.find { it.id == constituentIdForPdfTask }
        assert pdfFormatReceiptConstituent.id == constituentIdForPdfTask
        assert pdfFormatReceiptConstituent.totalGifts == giftsForConstituentWithPdfTask.size().toLong()
        assert pdfFormatReceiptConstituent.totalGiftAmount == giftsForConstituentWithPdfTask.collect { it.amount.value }.sum()
        assert pdfFormatReceiptConstituent.totalContributionAmount == giftsForConstituentWithPdfTask.collect { extractContributionAmountFromGift(it) }.sum()
        assert pdfFormatReceiptConstituent.periodStartDate == giftPeriod.periodStartDate
        assert pdfFormatReceiptConstituent.periodEndDate == giftPeriod.periodEndDate
        assert pdfFormatReceiptConstituent.emailReceiptStatusDate == receiptStatusDate
        assert pdfFormatReceiptConstituent.receiptFormat == BulkTaskFormat.PDF.toString()
    }

    private void assertEmailFormatReceiptConstituentMatchesExpected(GivingStatementConstituentsResponse response,
                                                                    GiftPeriod giftPeriod,
                                                                    OffsetDateTime receiptStatusDate,
                                                                    String constituentIdForEmailTask,
                                                                    List<RenxtGift> giftsForConstituentWithEmailTask
    ) {
        GivingStatementConstituentSummary emailFormatReceiptConstituent = response.value.find { it.id == constituentIdForEmailTask }
        assert emailFormatReceiptConstituent.id == constituentIdForEmailTask
        assert emailFormatReceiptConstituent.totalGifts == giftsForConstituentWithEmailTask.size().toLong()
        assert emailFormatReceiptConstituent.totalGiftAmount == giftsForConstituentWithEmailTask.collect { it.amount.value }.sum()
        assert emailFormatReceiptConstituent.totalContributionAmount == giftsForConstituentWithEmailTask.collect { extractContributionAmountFromGift(it) }.sum()
        assert emailFormatReceiptConstituent.periodStartDate == giftPeriod.periodStartDate
        assert emailFormatReceiptConstituent.periodEndDate == giftPeriod.periodEndDate
        assert emailFormatReceiptConstituent.emailReceiptStatusDate == receiptStatusDate
        assert emailFormatReceiptConstituent.receiptFormat == BulkTaskFormat.EMAIL.toString()
    }

    private mockContributor() {
        mockInternalContributorService = Mock(InternalContributorService)
        Mock(InternalContributorServiceSelector) {
            internalContributorService(_ as String) >> mockInternalContributorService
        }
    }

    private static BigDecimal extractContributionAmountFromGift(RenxtGift gift) {
        RenxtGiftWrapper.of(gift).latestReceipt().amount.value
    }

    private buildRenxtGiftsResponse(List<RenxtGift> gifts) {
        RenxtGiftsResponse.builder()
                .value(gifts)
                .build()
    }

    private RenxtGift createGiftsForDate(OffsetDateTime dateGiftAdded, String constituentId = aRandom.intId().toString()) {
        aRandom.renxtGift()
                .id(aRandom.intIdString())
                .constituentId(constituentId)
                .date(dateGiftAdded)
                .type(aRandom.renxtPaymentGiftType())
                .build()
    }
}
