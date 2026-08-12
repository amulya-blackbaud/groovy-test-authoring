package com.blackbaud.receiptmanager.core.service.receipt.transaction

import com.blackbaud.boot.exception.BadRequestException
import com.blackbaud.receiptmanager.core.api.transaction.TransactionReceiptRequestPayload
import spock.lang.Specification

import java.time.OffsetDateTime

import static com.blackbaud.receiptmanager.servicebus.ReceiptManagerServiceBusClientARandom.aRandom

class TransactionReceiptRequestValidatorSpec extends Specification {

    def validator = new TransactionReceiptRequestValidator()

    def "should throw when payload is null"() {
        when:
        validator.validate(null)

        then:
        def ex = thrown(BadRequestException)
        assert ex.message == "Payload is required"
    }

    def "should throw when environment_id is missing or blank"() {
        given:
        TransactionReceiptRequestPayload payload = aRandom.transactionReceiptRequestPayload().build()
        payload.environmentId = envId

        when:
        validator.validate(payload)

        then:
        def ex = thrown(BadRequestException)
        assert ex.message == "Environment Id is required"

        where:
        envId << [null, "", "   "]
    }

    def "should throw when transactionId is missing or blank"() {
        given:
        TransactionReceiptRequestPayload payload = aRandom.transactionReceiptRequestPayload().build()
        payload.transactionId = transactionId

        when:
        validator.validate(payload)

        then:
        def ex = thrown(BadRequestException)
        assert ex.message == "Transaction Id is required"

        where:
        transactionId << [null, "", "   "]
    }

    def "should throw when amount is null"() {
        given:
        TransactionReceiptRequestPayload payload = aRandom.transactionReceiptRequestPayload().build()
        payload.amount = null

        when:
        validator.validate(payload)

        then:
        def ex = thrown(BadRequestException)
        assert ex.message == "Contribution Amount is required and must be greater than zero"
    }

    def "should throw when amount is zero or negative"() {
        given:
        TransactionReceiptRequestPayload payload = aRandom.transactionReceiptRequestPayload().build()
        payload.amount = badAmount

        when:
        validator.validate(payload)

        then:
        def ex = thrown(BadRequestException)
        assert ex.message == "Contribution Amount is required and must be greater than zero"

        where:
        badAmount << [BigDecimal.ZERO, BigDecimal.valueOf(-1)]
    }

    def "should throw when transaction_date is missing"() {
        given:
        TransactionReceiptRequestPayload payload = aRandom.transactionReceiptRequestPayload().build()
        payload.transactionDate = null

        when:
        validator.validate(payload)

        then:
        def ex = thrown(BadRequestException)
        assert ex.message == "Transaction date is required and cannot be in the future"
    }

    def "should throw when transaction_date is in the future"() {
        given:
        TransactionReceiptRequestPayload payload = aRandom.transactionReceiptRequestPayload().build()
        payload.transactionDate = OffsetDateTime.now().plusMinutes(5)

        when:
        validator.validate(payload)

        then:
        def ex = thrown(BadRequestException)
        assert ex.message == "Transaction date is required and cannot be in the future"
    }

    def "should throw when full_name is missing or blank"() {
        given:
        TransactionReceiptRequestPayload payload = aRandom.transactionReceiptRequestPayload().build()
        payload.fullName = fullName

        when:
        validator.validate(payload)

        then:
        def ex = thrown(BadRequestException)
        assert ex.message == "Full name is required"

        where:
        fullName << [null, "", "   "]
    }

    def "should pass when all fields are valid"() {
        given:
        TransactionReceiptRequestPayload payload = aRandom.transactionReceiptRequestPayload().build()

        when:
        validator.validate(payload)

        then:
        noExceptionThrown()
    }

    def "should throw when PDF Template ID is null"() {
        given:
        TransactionReceiptRequestPayload payload = aRandom.transactionReceiptRequestPayload().build()
        payload.pdfTemplateId = null

        when:
        validator.validate(payload)

        then:
        def ex = thrown(BadRequestException)
        assert ex.message == "PDF Template Id is required"
    }

    def "should throw when Receipt Series ID is null"() {
        given:
        TransactionReceiptRequestPayload payload = aRandom.transactionReceiptRequestPayload().build()
        payload.receiptSeriesId = null

        when:
        validator.validate(payload)

        then:
        def ex = thrown(BadRequestException)
        assert ex.message == "Receipt Series Id is required"
    }

    def "should throw when fund splits is null or empty"() {
        given:
        TransactionReceiptRequestPayload payload = aRandom.transactionReceiptRequestPayload().build()
        payload.fundSplits = fundSplits

        when:
        validator.validate(payload)

        then:
        def ex = thrown(BadRequestException)
        assert ex.message == "At least one fund split is required"

        where:
        fundSplits << [null, []]
    }

    def "should throw when fund id is missing or blank in fund split"() {
        given:
        TransactionReceiptRequestPayload payload = aRandom.transactionReceiptRequestPayload().build()
        payload.fundSplits = [aRandom.transactionReceiptFundSplit().fundId(fundId).fundDescription(null).build()]

        when:
        validator.validate(payload)

        then:
        def ex = thrown(BadRequestException)
        assert ex.message == "Fund Id is required"

        where:
        fundId << [null, "", "   "]
    }

    def "should pass when fund splits have valid fund ids"() {
        given:
        TransactionReceiptRequestPayload payload = aRandom.transactionReceiptRequestPayload().build()
        TransactionReceiptRequestPayload.FundSplit fundSplit1 = aRandom.transactionReceiptFundSplit().build()
        TransactionReceiptRequestPayload.FundSplit fundSplit2 = aRandom.transactionReceiptFundSplit().build()
        payload.fundSplits = [fundSplit1, fundSplit2]

        when:
        validator.validate(payload)

        then:
        noExceptionThrown()
    }
}