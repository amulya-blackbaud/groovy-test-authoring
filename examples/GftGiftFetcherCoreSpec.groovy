package com.blackbaud.receiptmanager.core.service.gift

import com.blackbaud.gft.api.gift.GiftInternalRead
import com.blackbaud.gft.api.gift.GiftType
import com.blackbaud.gft.api.gift.InternalGiftCollection
import com.blackbaud.gft.api.gift.InternalGiftListRequestOptions
import com.blackbaud.gft.client.GftGiftsSasClient
import com.blackbaud.receiptmanager.CoreTest
import com.blackbaud.receiptmanager.EntitledSpecification
import com.blackbaud.receiptmanager.api.givingstatement.GiftPeriod
import com.blackbaud.receiptmanager.core.api.InternalGift
import com.blackbaud.receiptmanager.core.api.InternalGiftType
import com.blackbaud.receiptmanager.core.api.converters.GftInternalGiftConverter
import com.blackbaud.testsupport.BBAuthSupport
import org.springframework.beans.factory.annotation.Autowired

import java.time.OffsetDateTime

import static com.blackbaud.receiptmanager.core.CoreARandom.aRandom
import static com.blackbaud.receiptmanager.core.api.InternalEntitlementValues.TEST_ENVIRONMENT
import static com.blackbaud.receiptmanager.core.service.gift.origin.OriginInfoService.DONATION_FORM_MODE
import static com.blackbaud.receiptmanager.core.service.gift.origin.OriginInfoService.TEST_DATA
import static com.blackbaud.receiptmanager.core.service.gift.apifetcher.GiftApiFetcher.GIFT_API_LIMIT

@CoreTest
class GftGiftFetcherCoreSpec extends EntitledSpecification implements BBAuthSupport {

    @Autowired
    private GftGiftFetcher gftGiftFetcher
    @Autowired
    private GftGiftsSasClient mockGftGiftsSasClient

    def "should find gift by id"() {
        given:
        withEntitlements([])
        String giftId = aRandom.text()

        when:
        GiftInternalRead giftRead = gftGiftFetcher.retrieveGiftFromApi(giftId, environmentId)

        then:
        assert giftRead != null
        1 * mockGftGiftsSasClient.getGiftInternal(giftId) >> aRandom.gftGifts.giftInternalRead().build()
    }

    def "should throw NotFoundException if giftRead is null"() {
        when:
        withEntitlements([])
        gftGiftFetcher.retrieveGiftFromApi(aRandom.text(), environmentId)

        then:
        thrown GiftNotFoundException
        1 * mockGftGiftsSasClient.getGiftInternal(_) >> null
    }

    def "should find payment and commitment gifts for constituent for a given time period with receipts, sorted by date descending"() {
        given:
        withEntitlements([])
        GiftPeriod giftPeriod = aRandom.giftPeriod().build()
        String constituentId = aRandom.intIdString()

        InternalGiftListRequestOptions expectedApiRequestBody = InternalGiftListRequestOptions.builder()
                .constituentId(constituentId)
                .startDate(giftPeriod.periodStartDate)
                .endDate(giftPeriod.periodEndDate)
                .includeReceipts(true)
                .limit(GIFT_API_LIMIT)
                .build()

        GiftInternalRead newestGift = aRandom.gftGifts.giftInternalRead()
                .giftType(aRandom.item(GiftType.PAYMENT_GIFT_TYPES))
                .giftDate(OffsetDateTime.MAX)
                .build()

        and:
        List<GiftInternalRead> giftsOfAllTypesIncludingNewestPaymentGift = [
                newestGift,
                *aRandom.nonEmptyList {
                    aRandom.gftGifts.giftInternalRead().build()
                }
        ]

        when:
        List<InternalGift> paymentAndCommitmentInternalGiftsFromApi = gftGiftFetcher.fetchPaymentsAndCommitments(
                constituentId,
                giftPeriod,
                environmentId
        )

        then:
        1 * mockGftGiftsSasClient.postGetGiftList(expectedApiRequestBody) >> aRandom.gftGifts.internalGiftCollection()
                .gifts(giftsOfAllTypesIncludingNewestPaymentGift)
                .nextLink(null)
                .build()

        assert paymentAndCommitmentInternalGiftsFromApi.every {
            InternalGiftType.isPaymentGift(it.giftType) || InternalGiftType.isCommitmentGift(it.giftType)
        }
        assert paymentAndCommitmentInternalGiftsFromApi[0] == GftInternalGiftConverter.convertToInternalGift(newestGift)
    }

    def "should return payments with test data when environment is a test environment"() {
        given:
        withEntitlements([TEST_ENVIRONMENT])

        and:
        GiftInternalRead testDataGift = testDataGift()
        mockGftGiftsSasClient.postGetGiftList(_ as InternalGiftListRequestOptions) >> InternalGiftCollection.builder()
                .gift(testDataGift)
                .build()

        when:
        List<InternalGift> internalGifts = gftGiftFetcher.fetchPaymentsAndCommitments(
                aRandom.intIdString(),
                aRandom.giftPeriod().build(),
                environmentId
        )

        then:
        assert internalGifts.size() == 1
    }

    def "should not return payments with test data"() {
        given:
        withEntitlements([])

        and:
        GiftInternalRead testDataGift = testDataGift()
        mockGftGiftsSasClient.postGetGiftList(_ as InternalGiftListRequestOptions) >> InternalGiftCollection.builder()
                .gift(testDataGift)
                .count(1)
                .build()

        when:
        List<InternalGift> internalGifts = gftGiftFetcher.fetchPaymentsAndCommitments(
                aRandom.intIdString(),
                aRandom.giftPeriod().build(),
                environmentId
        )

        then:
        assert internalGifts.empty
    }

    def "should fetch more than 500 gifts in the giving statement"() {
        given:
        withEntitlements([])
        InternalGiftCollection page1 = InternalGiftCollection.builder()
                .gifts(paymentGifts(GIFT_API_LIMIT))
                .nextLink("nextPage")
                .build()
        InternalGiftCollection page2 = InternalGiftCollection.builder()
                .gifts(paymentGifts(101))
                .nextLink(null)
                .build()
        mockGftGiftsSasClient.postGetGiftList(_ as InternalGiftListRequestOptions) >>> [page1, page2]

        when:
        List<InternalGift> internalGifts = gftGiftFetcher.fetchPaymentsAndCommitments(
                aRandom.intIdString(), aRandom.giftPeriod().build(), environmentId)

        then:
        assert internalGifts.size() > 500
        assert internalGifts.size() == GIFT_API_LIMIT + 101
    }

    private static GiftInternalRead testDataGift() {
        String extendedInfoString = "{\"${DONATION_FORM_MODE}\":\"${TEST_DATA}\"}"
        String origin = "{\"extended_info\":${extendedInfoString}}"

        aRandom.gftGifts.giftInternalRead()
                .giftType(aRandom.item(GiftType.PAYMENT_GIFT_TYPES))
                .origin(origin)
                .build()
    }

    private static List<GiftInternalRead> paymentGifts(int count) {
        List<GiftInternalRead> gifts = new ArrayList<>()
        for (int i = 0; i < count; i++) {
            gifts.add(aRandom.gftGifts.giftInternalRead()
                    .giftType(aRandom.item(GiftType.PAYMENT_GIFT_TYPES))
                    .build())
        }
        return gifts
    }
}
