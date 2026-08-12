package com.blackbaud.receiptmanager.core.domain.receipt

import com.blackbaud.receiptmanager.core.api.transaction.TransactionReceiptRequestPayload
import com.blackbaud.receiptmanager.core.domain.bulktask.BulkTaskEntity
import com.blackbaud.receiptmanager.core.domain.bulktask.BulkTaskGenerationType
import spock.lang.Specification
import spock.lang.Unroll

import java.time.OffsetDateTime

import static com.blackbaud.receiptmanager.core.CoreARandom.aRandom

class ReceiptEntitySpec extends Specification {

    private static final String SHARED_IDENTIFIER = aRandom.uuidString()

    def "should combine receipt number and prefix for latest receipt number"() {
        given:
        ReceiptEntity receiptEntity = aRandom.receiptEntity()
                .receiptPrefix(aRandom.text())
                .receiptNumber(aRandom.intBetween(ReceiptEntity.MIN_RECEIPT_NUMBER, ReceiptEntity.MAX_RECEIPT_NUMBER))
                .build()

        expect:
        assert receiptEntity.latestReceiptNumber == receiptEntity.receiptPrefix + receiptEntity.receiptNumber
    }

    def "should combine empty string with number when null receipt prefix instead of 'null' as string"() {
        given:
        ReceiptEntity receiptEntity = aRandom.receiptEntity()
                .receiptPrefix(null)
                .receiptNumber(aRandom.intBetween(ReceiptEntity.MIN_RECEIPT_NUMBER, ReceiptEntity.MAX_RECEIPT_NUMBER))
                .build()

        expect:
        assert receiptEntity.latestReceiptNumber == receiptEntity.receiptNumber as String

    }

    @Unroll
    def "should return #expectedResult for isReplacement when replacesReceiptId is #replacesReceiptId"() {
        given:
        ReceiptEntity receiptEntity = aRandom.receiptEntity().withReplacesReceiptId(replacesReceiptId).build()

        when:
        boolean result = receiptEntity.replacement

        then:
        assert result == expectedResult

        where:
        replacesReceiptId | expectedResult
        aRandom.uuid()    | true
        null              | false

    }

    def "should not allow receipt date time to be null when building a receipt entity"() {
        when:
        ReceiptEntity.builder().receiptDateTime(null).build()

        then:
        thrown(NullPointerException)
    }

    @Unroll
    def "should return #expectedResult for isTransactionReceipt when transactionId=#transactionId and keyIdentifier=#keyIdentifier"() {
        given:
        ReceiptEntity receiptEntity = aRandom.receiptEntity()
                .transactionId(transactionId)
                .keyIdentifier(keyIdentifier)
                .build()

        when:
        boolean result = receiptEntity.transactionReceipt

        then:
        assert result == expectedResult

        where:
        scenario                             | transactionId        | keyIdentifier        | expectedResult
        'transactionId equals keyIdentifier' | SHARED_IDENTIFIER    | SHARED_IDENTIFIER    | true
        'keyIdentifier relinked to giftId'   | aRandom.uuidString() | aRandom.uuidString() | false
        'transactionId is null'              | null                 | aRandom.uuidString() | false
        'transactionId is blank'             | ''                   | ''                   | false
    }

    def "should build transaction receipt entity with provided values"() {
        given:
        String environmentId = aRandom.environmentId()
        OffsetDateTime createdDate = OffsetDateTime.now()
        TransactionReceiptRequestPayload payload = aRandom.transactionReceiptRequestPayload()
                .environmentId(environmentId)
                .build()
        BulkTaskEntity bulkTaskEntity = aRandom.bulkTaskEntity()
                .withEnvironmentId(environmentId)
                .withGenerationType(BulkTaskGenerationType.AUTO)
                .withCreationDate(createdDate)
                .build()

        when:
        ReceiptEntity receipt = ReceiptEntity.buildPendingReceiptEntityFromTransaction(bulkTaskEntity, payload)

        then:
        assert receipt.keyIdentifier == payload.transactionId
        assert receipt.contributionAmount == payload.amount
        assert receipt.environmentId == payload.environmentId
        assert receipt.receiptSeriesId == payload.receiptSeriesId
        assert receipt.transactionId == payload.transactionId
        assert receipt.receiptDateTime == createdDate
    }

    def "should set pending status for transaction receipt entity"() {
        given:
        String environmentId = aRandom.environmentId()
        OffsetDateTime createdDate = OffsetDateTime.now()
        TransactionReceiptRequestPayload payload = aRandom.transactionReceiptRequestPayload()
                .environmentId(environmentId)
                .build()
        BulkTaskEntity bulkTaskEntity = aRandom.bulkTaskEntity()
                .withEnvironmentId(environmentId)
                .withGenerationType(BulkTaskGenerationType.AUTO)
                .withCreationDate(createdDate)
                .build()

        when:
        ReceiptEntity receipt = ReceiptEntity.buildPendingReceiptEntityFromTransaction(bulkTaskEntity, payload)

        then:
        assert receipt.keyIdentifier == payload.transactionId
        assert  receipt.contributionAmount == payload.amount
        assert receipt.environmentId == environmentId
        assert receipt.receiptSeriesId == payload.receiptSeriesId
        assert receipt.transactionId == payload.transactionId
        assert receipt.receiptDateTime == bulkTaskEntity.createdDate
        assert receipt.receiptStatus == InternalReceiptStatus.PENDING
    }
}