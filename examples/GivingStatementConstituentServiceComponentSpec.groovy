package com.blackbaud.receiptmanager.core.service.consolidatedreceipting

import com.blackbaud.constituent.api.RenxtConstituent
import com.blackbaud.constituent.api.RenxtSpouse
import com.blackbaud.gift.api.gift.RenxtGift
import com.blackbaud.gift.api.gift.RenxtGiftListRequest
import com.blackbaud.gift.api.gift.RenxtGiftsResponse
import com.blackbaud.gift.client.RenxtGiftSasClient
import com.blackbaud.lists.api.RenxtConstituentListIds
import com.blackbaud.lists.client.RenxtListsClient
import com.blackbaud.receiptmanager.ComponentTest
import com.blackbaud.receiptmanager.EntitledSpecification
import com.blackbaud.receiptmanager.api.consolitatedreceipting.ConsolidatedReceiptConstituentRequest
import com.blackbaud.receiptmanager.api.consolitatedreceipting.GivingStatementConstituentSummary
import com.blackbaud.receiptmanager.api.consolitatedreceipting.GivingStatementConstituentsResponse
import com.blackbaud.receiptmanager.api.givingstatement.GiftPeriod
import com.blackbaud.receiptmanager.core.api.InternalContributor
import com.blackbaud.receiptmanager.core.api.converters.InternalContributorConverter
import com.blackbaud.receiptmanager.core.domain.consolidatedreceipting.GivingStatementEntity
import com.blackbaud.receiptmanager.core.service.consolidatedreceipting.receiptprocessor.HouseholdGivingStatementProcessor
import com.blackbaud.receiptmanager.core.service.consolidatedreceipting.receiptprocessor.IndividualGivingStatementProcessor
import com.blackbaud.receiptmanager.core.service.contributor.RenxtContributorService
import com.blackbaud.receiptmanager.external.gift.api.gift.RenxtGiftWrapper
import com.blackbaud.testsupport.BBAuthSupport
import com.blackbaud.testsupport.BeanCompare
import com.blackbaud.testsupport.RequiresBbAuthContext
import com.blackbaud.testsupport.ResettingMockInjector
import org.apache.commons.collections4.CollectionUtils
import org.springframework.beans.factory.annotation.Autowired

import java.time.OffsetDateTime

import static com.blackbaud.receiptmanager.core.CoreARandom.aRandom
import static com.blackbaud.receiptmanager.core.api.InternalEntitlementValues.HOUSEHOLD_GIVING
import static com.blackbaud.receiptmanager.core.service.consolidatedreceipting.GivingStatementConstituentService.MAX_SQL_IN_CLAUSE_SIZE

@ComponentTest
@RequiresBbAuthContext
class GivingStatementConstituentServiceComponentSpec extends EntitledSpecification implements BBAuthSupport {
    private static final int DEFAULT_PAGE_SIZE = 50

    @Autowired
    private GivingStatementConstituentService givingStatementConstituentSummaryService

    @Autowired
    private HouseholdGivingStatementProcessor householdGivingStatementProcessor

    @Autowired
    private IndividualGivingStatementProcessor individualGivingStatementProcessor

    @Autowired
    private RenxtGiftSasClient renxtGiftSasClient

    @Autowired
    private RenxtListsClient listsClient

    private RenxtContributorService mockRenxtContributorService

    private BeanCompare beanCompare

    def setup() {
        beanCompare = new BeanCompare()
    }

    def "should be able to retrieve constituents with gifts for consolidated receipting grid"() {
        given:
        setupIndividualGiving()

        and:
        GiftPeriod giftPeriod = aRandom.giftPeriod().build()
        List<String> sentGivingStatementConstituentIds = createGivingStatementsWithStatus(giftPeriod, true)
                .collect { it.constituentId }

        and:
        List<String> noGivingStatementConstituentIds = createGivingStatementsWithStatus(giftPeriod, false)
                .collect { it.constituentId }

        List<String> allConstituentIds = sentGivingStatementConstituentIds + noGivingStatementConstituentIds
        int constituentPagesCount = (int) Math.ceil(allConstituentIds.size() / DEFAULT_PAGE_SIZE)

        and:
        List<RenxtGift> allGifts = allConstituentIds.collect { String consId ->
            aRandom.nonEmptyList {
                aRandom.renxtGift()
                        .constituentId(consId)
                        .date(OffsetDateTime.now().minusYears(1))
                        .type(aRandom.renxtPaymentGiftType())
                        .build()
            }
        }.flatten() as List<RenxtGift>

        RenxtGiftsResponse giftsResponse = aRandom.renxtGiftsResponse()
                .nextLink(null)
                .value(allGifts)
                .build()

        and:
        List<InternalContributor> contributors = internalContributorsFromIds(allConstituentIds)

        and:
        boolean includeReceipted = false

        ConsolidatedReceiptConstituentRequest consolidatedReceiptConstituentRequest = aRandom.consolidatedReceiptConstituentRequest()
                .resultLimit(DEFAULT_PAGE_SIZE)
                .giftPeriod(giftPeriod)
                .includeReceipted(includeReceipted)
                .build()

        RenxtConstituentListIds renxtConstituentListIds = RenxtConstituentListIds.builder()
                .count(allConstituentIds.size())
                .ids(allConstituentIds)
                .build()

        when:
        GivingStatementConstituentsResponse response = givingStatementConstituentSummaryService.getGivingStatementConstituentSummaries(
                consolidatedReceiptConstituentRequest,
                environmentId
        )

        then:
        1 * listsClient.getConstituentListIds(consolidatedReceiptConstituentRequest.listId, null) >> renxtConstituentListIds
        constituentPagesCount * mockRenxtContributorService.fetchInternalContributorsAggregately(_) >> contributors
        constituentPagesCount * renxtGiftSasClient.getGiftsAsTrustedCaller(_ as Integer, _ as Integer, _ as Map<String, Object>) >> giftsResponse
        constituentPagesCount * renxtGiftSasClient.postGetGiftList(_ as RenxtGiftListRequest) >> giftsResponse

        and:
        response.value.each { cons ->
            assert allConstituentIds.contains(cons.id)
            assert cons.totalGifts == numberOfGiftsForConstituent(allGifts, cons.id)
            assert cons.totalGiftAmount == calculateTotalGiftAmount(allGifts, cons.id)
            assert cons.totalContributionAmount == calculateTotalContributionAmountForConstituent(allGifts, cons.id)
            assert cons.emailReceiptStatusDate == null
        }
    }

    def "should be able to retrieve constituents with gifts, including already receipted ones, for the consolidated receipting grid"() {
        given:
        setupIndividualGiving()

        and:
        GiftPeriod giftPeriod = aRandom.giftPeriod().build()
        List<String> sentGivingStatementConstituentIds = createGivingStatementsWithStatus(giftPeriod, true)
                .collect { it.constituentId }

        and:
        List<String> noGivingStatementConstituentIds = createGivingStatementsWithStatus(giftPeriod, false)
                .collect { it.constituentId }

        List<String> allConstituentIds = sentGivingStatementConstituentIds + noGivingStatementConstituentIds

        and:
        List<RenxtGift> allGifts = allConstituentIds.collect { String consId ->
            aRandom.nonEmptyList {
                aRandom.renxtGift()
                        .constituentId(consId)
                        .date(OffsetDateTime.now().minusYears(1))
                        .type(aRandom.renxtPaymentGiftType())
                        .build()
            }
        }.flatten() as List<RenxtGift>

        RenxtGiftsResponse giftsResponse = aRandom.renxtGiftsResponse()
                .nextLink(null)
                .value(allGifts)
                .build()

        and:
        List<InternalContributor> contributorsWithHouseholds = internalContributorsFromIds(allConstituentIds)
        RenxtConstituentListIds renxtConstituentListIds = RenxtConstituentListIds.builder()
                .count(allConstituentIds.size())
                .ids(allConstituentIds)
                .build()

        and:
        boolean includeReceipted = true
        ConsolidatedReceiptConstituentRequest consolidatedReceiptConstituentRequest = aRandom.consolidatedReceiptConstituentRequest()
                .resultLimit(DEFAULT_PAGE_SIZE)
                .giftPeriod(giftPeriod)
                .includeReceipted(includeReceipted)
                .build()

        when:
        GivingStatementConstituentsResponse response = givingStatementConstituentSummaryService.getGivingStatementConstituentSummaries(
                consolidatedReceiptConstituentRequest,
                environmentId
        )

        then:
        1 * listsClient.getConstituentListIds(consolidatedReceiptConstituentRequest.listId, null) >> renxtConstituentListIds
        1 * mockRenxtContributorService.fetchInternalContributorsAggregately(_) >> contributorsWithHouseholds
        1 * renxtGiftSasClient.getGiftsAsTrustedCaller(_ as Integer, _ as Integer, _ as Map<String, Object>) >> giftsResponse
        1 * renxtGiftSasClient.postGetGiftList(_ as RenxtGiftListRequest) >> giftsResponse


        and:
        response.value.each { cons ->
            assert allConstituentIds.contains(cons.id)
            assert cons.totalGifts == numberOfGiftsForConstituent(allGifts, cons.id)
            assert cons.totalGiftAmount == calculateTotalGiftAmount(allGifts, cons.id)
            assert cons.totalContributionAmount == calculateTotalContributionAmountForConstituent(allGifts, cons.id)
        }
    }

    def "should get more gifts if there are less than a page of constituents in the first call"() {
        given:
        setupIndividualGiving()

        and:
        GiftPeriod giftPeriod = aRandom.giftPeriod().build()
        int numFirstPageOfConstituents = DEFAULT_PAGE_SIZE
        int numLastPageOfConstituents = aRandom.intBetween(1, DEFAULT_PAGE_SIZE)

        and:
        List<String> firstPageOfConstituentIds = createGivingStatementsWithStatus(giftPeriod,
                                                                                  false,
                                                                                  numFirstPageOfConstituents)
                .collect { it.constituentId }

        and:
        List<RenxtGift> initialGifts = buildGiftsForSomeOfTheConstituentIds(firstPageOfConstituentIds)

        and:
        RenxtGiftsResponse initialGiftsResponse = aRandom.renxtGiftsResponse()
                .value(initialGifts)
                .build()

        and:
        List<String> lastPageOfConstituentIds = createGivingStatementsWithStatus(giftPeriod, false, numLastPageOfConstituents)
                .collect { it.constituentId }
        List<RenxtGift> lastGifts = buildGiftsForSomeOfTheConstituentIds(firstPageOfConstituentIds)

        RenxtGiftsResponse lastGiftsResponse = aRandom.renxtGiftsResponse()
                .nextLink(null)
                .value(lastGifts)
                .build()

        and:
        List<RenxtGift> allGifts = initialGifts + lastGifts
        RenxtGiftsResponse allGiftsResponse = aRandom.renxtGiftsResponse()
                .nextLink(null)
                .value(allGifts)
                .build()

        and:
        List<String> allConstituentIds = firstPageOfConstituentIds + lastPageOfConstituentIds
        List<InternalContributor> internalContributors = internalContributorsFromIds(allConstituentIds)

        and:
        boolean includeReceipted = false
        ConsolidatedReceiptConstituentRequest consolidatedReceiptConstituentRequest = aRandom.consolidatedReceiptConstituentRequest()
                .resultLimit(DEFAULT_PAGE_SIZE)
                .giftPeriod(giftPeriod)
                .includeReceipted(includeReceipted)
                .build()
        RenxtConstituentListIds renxtConstituentListIds = RenxtConstituentListIds.builder()
                .count(allConstituentIds.size())
                .ids(allConstituentIds)
                .build()

        when:
        GivingStatementConstituentsResponse response = givingStatementConstituentSummaryService.getGivingStatementConstituentSummaries(
                consolidatedReceiptConstituentRequest,
                environmentId
        )

        then:
        1 * listsClient.getConstituentListIds(consolidatedReceiptConstituentRequest.listId, null) >> renxtConstituentListIds
        (2.._) * mockRenxtContributorService.fetchInternalContributorsAggregately(_) >> internalContributors
        3 * renxtGiftSasClient.getGiftsAsTrustedCaller(_ as Integer, _ as Integer, _ as Map<String, Object>) >>> [initialGiftsResponse, lastGiftsResponse, allGiftsResponse]
        4 * renxtGiftSasClient.postGetGiftList(_ as RenxtGiftListRequest) >>> [initialGiftsResponse, lastGiftsResponse, allGiftsResponse]


        and:
        response.value.each { cons ->
            assert firstPageOfConstituentIds.contains(cons.id)
            assert cons.totalGifts == numberOfGiftsForConstituent(allGifts, cons.id)
            assert cons.totalGiftAmount == calculateTotalGiftAmount(allGifts, cons.id)
            assert cons.totalContributionAmount == calculateTotalContributionAmountForConstituent(allGifts, cons.id)
        }
    }

    def "should be able to retrieve household giving statements when household giving is enabled"() {
        given:
        setupHouseholdGiving()

        and:
        List<RenxtConstituent> headsOfHousehold = aRandom.nonEmptyList { aRandom.constituent().build() }
        List<RenxtConstituent> spouses = aRandom.list(headsOfHousehold.size(), { aRandom.constituent().build() })
        List<RenxtConstituent> constituentsWithoutSpouses =
                aRandom.nonEmptyList { aRandom.constituent().spouse(null).build() }

        List<RenxtConstituent> listPageConstituents = headsOfHousehold + constituentsWithoutSpouses
        List<RenxtConstituent> allConstituents = headsOfHousehold + spouses + constituentsWithoutSpouses

        and:
        associateHeadsOfHouseholdWithSpouses(headsOfHousehold, spouses)

        and:
        Map<String, List<RenxtGift>> giftsByConstituent = mapGiftsByConstituentIds(allConstituents)
        List<RenxtGift> allGifts = giftsByConstituent.values().flatten() as List<RenxtGift>

        RenxtGiftsResponse giftsResponse = aRandom.renxtGiftsResponse()
                .nextLink(null)
                .value(allGifts)
                .build()

        and:

        List<InternalContributor> constituentListContributors = getInternalContributorsFromConstituents(listPageConstituents)
        List<InternalContributor> allContributors = getInternalContributorsFromConstituents(allConstituents)

        Set<String> constituentListContributorIds = constituentListContributors.collect { it.keyIdentifier }
        Set<String> allContributorIds = constituentListContributors.collectMany { it.internalHousehold.allHouseholdMemberIds }.toSet()

        and:
        GiftPeriod giftPeriod = aRandom.giftPeriod().build()

        and:
        boolean includeReceipted = false
        ConsolidatedReceiptConstituentRequest consolidatedReceiptConstituentRequest = aRandom.consolidatedReceiptConstituentRequest()
                .resultLimit(allContributorIds.size())
                .giftPeriod(giftPeriod)
                .includeReceipted(includeReceipted)
                .build()
        RenxtConstituentListIds renxtConstituentListIds = RenxtConstituentListIds.builder()
                .count(allConstituents.size())
                .ids(constituentListContributorIds.toList())
                .build()
        when:
        GivingStatementConstituentsResponse response = givingStatementConstituentSummaryService.getGivingStatementConstituentSummaries(
                consolidatedReceiptConstituentRequest,
                environmentId
        )

        then:
        1 * listsClient.getConstituentListIds(consolidatedReceiptConstituentRequest.listId, null) >> renxtConstituentListIds
        1 * mockRenxtContributorService.fetchInternalContributorsAggregately(constituentListContributorIds) >> constituentListContributors
        1 * mockRenxtContributorService.fetchInternalContributorsAggregately(_ as Set<String>) >> {
            Set<String> ids ->
                assert CollectionUtils.isEqualCollection(ids, allContributorIds)
                return allContributors
        }

        1 * renxtGiftSasClient.getGiftsAsTrustedCaller(_, _, _) >> giftsResponse
        1 * renxtGiftSasClient.postGetGiftList(_ as RenxtGiftListRequest) >> giftsResponse

        and:
        response.value.each { GivingStatementConstituentSummary cons ->
            RenxtConstituent constituent = allConstituents.find { RenxtConstituent c -> c.id == cons.id }

            if (constituent.spouse != null) {
                RenxtConstituent headOfHousehold = headsOfHousehold.find { RenxtConstituent c -> c.id == cons.id }

                List<RenxtGift> headOfHouseholdGifts = giftsByConstituent[headOfHousehold.id]
                List<RenxtGift> spouseGifts = giftsByConstituent[headOfHousehold.spouse.id]

                assertHouseholdGivingStatement(cons,
                                               headOfHousehold,
                                               giftPeriod,
                                               headOfHouseholdGifts,
                                               spouseGifts)
            } else {
                RenxtConstituent constituentWithoutSpouse = constituentsWithoutSpouses.find
                        { RenxtConstituent c -> c.id == cons.id }
                List<RenxtGift> constituentWithoutSpouseGifts = giftsByConstituent[constituentWithoutSpouse.id]

                assertIndividualConsolidatedReceipt(cons,
                                                    constituentWithoutSpouse,
                                                    giftPeriod,
                                                    constituentWithoutSpouseGifts)
            }
        }
    }

    def "should handle more than twice the MAX_SQL_IN_CLAUSE_SIZE ids when looking up existing giving statements"() {
        given:
        Map<String, GivingStatementConstituentSummary> givingStatementConstituentsMap = [:]
        (MAX_SQL_IN_CLAUSE_SIZE * 2 + 1).times {
            GivingStatementConstituentSummary givingStatementConstituentSummary =
                    aRandom.givingStatementConstituentSummary().build()
            givingStatementConstituentsMap.put(givingStatementConstituentSummary.id, givingStatementConstituentSummary)
        }

        when:
        //noinspection GroovyAccessibility
        givingStatementConstituentSummaryService.lookupBatchOfExistingGivingStatements(
                givingStatementConstituentsMap,
                aRandom.giftPeriod().build(),
                0
        )

        then:
        noExceptionThrown()
    }

    def "should handle less than the MAX_SQL_IN_CLAUSE_SIZE ids when looking up existing giving statements"() {
        given:
        Map<String, GivingStatementConstituentSummary> givingStatementConstituentsMap = [:]
        (MAX_SQL_IN_CLAUSE_SIZE - 1).times {
            GivingStatementConstituentSummary givingStatementConstituentSummary =
                    aRandom.givingStatementConstituentSummary().build()
            givingStatementConstituentsMap.put(givingStatementConstituentSummary.id, givingStatementConstituentSummary)
        }

        when:
        //noinspection GroovyAccessibility
        givingStatementConstituentSummaryService.lookupBatchOfExistingGivingStatements(
                givingStatementConstituentsMap,
                aRandom.giftPeriod().build(),
                0
        )

        then:
        noExceptionThrown()
    }

    def "should use non-constituent spouse name if constituent record for spouse doesn't exist"() {
        given:
        setupHouseholdGiving()

        and:
        List<RenxtGift> gifts = aRandom.nonEmptyList { aRandom.renxtGift().build() }
        Map<RenxtConstituent, RenxtSpouse> constituentsWithSpousesForGifts = gifts
                .collect { gift -> gift.constituentId }.toSet()
                .collectEntries { constituentId ->
                    RenxtSpouse spouse = aRandom.spouse().headOfHousehold(false).build()
                    RenxtConstituent constituent = aRandom.constituent()
                            .id(constituentId)
                            .spouse(spouse)
                            .build()
                    [(constituent): spouse]
                }
        List<RenxtConstituent> constituentsForGifts = constituentsWithSpousesForGifts.keySet().toList()
        Set<String> constituentIdsForGifts = constituentsForGifts.collect { it.id }

        List<RenxtSpouse> spouses = constituentsWithSpousesForGifts.values().toList()

        and:
        RenxtGiftsResponse giftsResponse = aRandom.renxtGiftsResponse()
                .nextLink(null)
                .value(gifts)
                .build()

        List<InternalContributor> internalContributors = getInternalContributorsFromConstituents(constituentsForGifts)

        and:
        boolean includeReceipted = false
        ConsolidatedReceiptConstituentRequest consolidatedReceiptConstituentRequest = aRandom.consolidatedReceiptConstituentRequest()
                .resultLimit(DEFAULT_PAGE_SIZE)
                .includeReceipted(includeReceipted)
                .build()
        RenxtConstituentListIds renxtConstituentListIds = RenxtConstituentListIds.builder()
                .count(constituentsForGifts.size())
                .ids(constituentIdsForGifts.toList())
                .build()

        when:
        List<GivingStatementConstituentSummary> givingStatementConstituentSummaries = givingStatementConstituentSummaryService
                .getGivingStatementConstituentSummaries(
                        consolidatedReceiptConstituentRequest,
                        environmentId)
                .value

        then:
        1 * listsClient.getConstituentListIds(consolidatedReceiptConstituentRequest.listId, null) >> renxtConstituentListIds
        1 * renxtGiftSasClient.getGiftsAsTrustedCaller(_ as Integer, _ as Integer, _ as Map<String, Object>) >> giftsResponse
        1 * renxtGiftSasClient.postGetGiftList(_ as RenxtGiftListRequest) >> giftsResponse
        2 * mockRenxtContributorService.fetchInternalContributorsAggregately(constituentIdsForGifts) >> internalContributors

        givingStatementConstituentSummaries.forEach { consReceipt ->
            RenxtConstituent constituent = constituentsForGifts.find { cons -> cons.id == consReceipt.id }
            RenxtSpouse spouse = spouses.find { spouse -> spouse.id == constituent.spouse.id }

            assert consReceipt.name == constituent.name
            assert consReceipt.householdMemberName == spouse.name
            assert consReceipt.householdMemberId == spouse.id
        }
    }

    private List<GivingStatementEntity> createGivingStatementsWithStatus(GiftPeriod giftPeriod, boolean emailSent, int size = DEFAULT_PAGE_SIZE) {
        aRandom.list(size) {
            aRandom.givingStatementEntity()
                    .withEnvironmentId(environmentId)
                    .withEmailReceiptStatusDate(emailSent ? OffsetDateTime.now() : null)
                    .withGiftPeriod(giftPeriod)
                    .save()
        }
    }

    private static List<RenxtGift> buildGiftsForSomeOfTheConstituentIds(List<String> constituentIds) {
        constituentIds.collect { String constituentId ->
            aRandom.coinFlip() ? [] : renxtGiftsForConstituentId(constituentId)
        }.flatten() as List<RenxtGift>
    }

    private static List<RenxtGift> renxtGiftsForConstituentId(String constituentId) {
        aRandom.nonEmptyList {
            aRandom.renxtGift()
                    .constituentId(constituentId)
                    .date(OffsetDateTime.now().minusYears(1))
                    .type(aRandom.renxtPaymentGiftType())
                    .build()
        }
    }

    private static BigDecimal extractContributionAmountFromGift(RenxtGift gift) {
        RenxtGiftWrapper.of(gift).latestReceipt().amount.value
    }

    private static long numberOfGiftsForConstituent(List<RenxtGift> gifts, String constituentId) {
        gifts
                .findAll { gift -> gift.constituentId == constituentId }
                .size()
    }

    private static BigDecimal calculateTotalGiftAmount(List<RenxtGift> gifts, String constituentId) {
        gifts
                .findAll { gift -> gift.constituentId == constituentId }
                .sum { RenxtGift gift -> gift.amount.value } as BigDecimal
    }

    private static BigDecimal calculateTotalContributionAmountForConstituent(List<RenxtGift> gifts, String constituentId) {
        gifts
                .findAll { RenxtGift gift -> gift.constituentId == constituentId }
                .sum { RenxtGift gift -> extractContributionAmountFromGift(gift) } as BigDecimal
    }

    private static BigDecimal calculateTotalContributionAmount(List<RenxtGift> gifts) {
        gifts.sum { RenxtGift gift -> extractContributionAmountFromGift(gift) } as BigDecimal
    }

    private static associateHeadsOfHouseholdWithSpouses(List<RenxtConstituent> headsOfHousehold, List<RenxtConstituent> spouses) {
        headsOfHousehold.eachWithIndex { RenxtConstituent headOfHousehold, int index ->
            RenxtConstituent spouseConstituent = spouses[index]
            headOfHousehold.spouse = aRandom.spouse()
                    .id(spouseConstituent.id)
                    .first(spouseConstituent.first)
                    .last(spouseConstituent.last)
                    .headOfHousehold(false)
                    .build()

            spouseConstituent.spouse = aRandom.spouse()
                    .id(headOfHousehold.id)
                    .first(headOfHousehold.first)
                    .last(headOfHousehold.last)
                    .headOfHousehold(true)
                    .build()
        }
    }

    private static Map<String, List<RenxtGift>> mapGiftsByConstituentIds(List<RenxtConstituent> allConstituents) {
        allConstituents.collectEntries { RenxtConstituent constituent ->
            List<RenxtGift> gifts = aRandom.nonEmptyList {
                aRandom.renxtGift()
                        .constituentId(constituent.id)
                        .date(OffsetDateTime.now().minusYears(1))
                        .type(aRandom.renxtPaymentGiftType())
                        .build()
            }
            [(constituent.id): gifts]
        }
    }

    private static List<InternalContributor> internalContributorsFromIds(List<String> constituentIds) {
        constituentIds.collect {
            aRandom.internalContributor().keyIdentifier(it).build()
        }
    }

    private static List<InternalContributor> getInternalContributorsFromConstituents(List<RenxtConstituent> constituents) {
        constituents.collect {
            InternalContributorConverter.convertToInternalContributor(it)
        }
    }

    private static void assertHouseholdGivingStatement(GivingStatementConstituentSummary cons,
                                                       RenxtConstituent headOfHousehold,
                                                       GiftPeriod giftPeriod,
                                                       List<RenxtGift> headOfHouseholdGifts,
                                                       List<RenxtGift> spouseGifts) {
        RenxtSpouse spouse = headOfHousehold.spouse

        assert cons.id == headOfHousehold.id
        assert cons.name == headOfHousehold.name
        assert cons.householdMemberId == spouse.id
        assert cons.householdMemberName == "${spouse.first} ${spouse.last}"
        assert cons.email == headOfHousehold.email.address
        assert cons.periodStartDate == giftPeriod.periodStartDate
        assert cons.periodEndDate == giftPeriod.periodEndDate
        assert cons.totalGifts == headOfHouseholdGifts.size() + spouseGifts.size()
        assert cons.totalGiftAmount == (headOfHouseholdGifts + spouseGifts).sum { RenxtGift g -> g.amount.value }
        assert cons.totalContributionAmount == calculateTotalContributionAmount(headOfHouseholdGifts + spouseGifts)
    }

    private static void assertIndividualConsolidatedReceipt(GivingStatementConstituentSummary cons,
                                                            RenxtConstituent constituentWithoutSpouse,
                                                            GiftPeriod giftPeriod,
                                                            List<RenxtGift> constituentWithoutSpouseGifts) {
        assert cons.id == constituentWithoutSpouse.id
        assert cons.name == constituentWithoutSpouse.name
        assert cons.email == constituentWithoutSpouse.email.address
        assert cons.periodStartDate == giftPeriod.periodStartDate
        assert cons.periodEndDate == giftPeriod.periodEndDate
        assert cons.totalGifts == constituentWithoutSpouseGifts.size() as long
        assert cons.totalGiftAmount == constituentWithoutSpouseGifts.sum { RenxtGift g -> g.amount.value }
        assert cons.totalContributionAmount == calculateTotalContributionAmount(constituentWithoutSpouseGifts)
        assert cons.householdMemberId == null
        assert cons.householdMemberName == null
    }

    private setupHouseholdGiving() {
        mockRenxtContributorService = ResettingMockInjector.set(householdGivingStatementProcessor, Mock(RenxtContributorService))
        withEntitlements([HOUSEHOLD_GIVING])
    }

    private setupIndividualGiving() {
        mockRenxtContributorService = ResettingMockInjector.set(individualGivingStatementProcessor, Mock(RenxtContributorService))
        withEntitlements([])
    }
}
