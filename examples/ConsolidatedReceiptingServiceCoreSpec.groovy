package com.blackbaud.receiptmanager.core.service.consolidatedreceipting

import com.blackbaud.receiptmanager.CoreTest
import com.blackbaud.receiptmanager.core.domain.consolidatedreceipting.GivingStatementEntity
import com.blackbaud.receiptmanager.core.domain.consolidatedreceipting.GivingStatementRepository
import com.blackbaud.testsupport.BBAuthSupport
import com.blackbaud.testsupport.BeanCompare
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.TransactionSystemException
import spock.lang.Specification

import static com.blackbaud.receiptmanager.core.CoreARandom.aRandom

@CoreTest
class ConsolidatedReceiptingServiceCoreSpec extends Specification implements BBAuthSupport {

    @Autowired
    private GivingStatementRepository givingStatementRepository

    @Autowired
    private ConsolidatedReceiptingService consolidatedReceiptingService

    private BeanCompare beanCompare

    def setup() {
        beanCompare = new BeanCompare()
    }

    def "should store a giving statement with all properties and emailReceiptStatusDate when one does not currently exist"() {
        given:
        GivingStatementEntity givingStatement = aRandom.givingStatementEntity()
                .emailReceiptStatusDate(null)
                .orgName(aRandom.text(20))
                .orgTaxId(aRandom.text(12))
                .build()

        when:
        GivingStatementEntity upsertedStatement = consolidatedReceiptingService
                .upsertGivingStatementEntity(givingStatement)

        and:
        Optional<GivingStatementEntity> foundStatementEntity = givingStatementRepository
                .findById(upsertedStatement.consolidatedReceiptId)

        then:
        assert foundStatementEntity.present
        GivingStatementEntity statement = foundStatementEntity.get()

        beanCompare
                .includeFields("environmentId",
                        "constituentId",
                        "householdId",
                        "periodStartDate",
                        "periodEndDate",
                        "orgName",
                        "orgTaxId",
                        "bulkTaskName",
                        "bulkTaskFormat",
                        "givingStatementType")
                .assertEquals(givingStatement, statement)

        assert statement.emailReceiptStatusDate != null
    }

    def "should update a giving statement with all properties when one exists"() {
        given:
        GivingStatementEntity statementEntity = aRandom.givingStatementEntity()
                .orgName(aRandom.text(20))
                .orgTaxId(aRandom.text(12))
                .bulkTaskName("Original bulk task name")
                .build()

        givingStatementRepository.save(statementEntity)

        and:
        GivingStatementEntity givingStatement = aRandom.givingStatementEntity()
                .environmentId(statementEntity.environmentId)
                .constituentId(statementEntity.constituentId)
                .periodStartDate(statementEntity.periodStartDate)
                .periodEndDate(statementEntity.periodEndDate)
                .orgName(statementEntity.orgName)
                .orgTaxId(statementEntity.orgTaxId)
                .bulkTaskName("Updated bulk task name")
                .build()

        when:
        GivingStatementEntity upsertedStatement = consolidatedReceiptingService
                .upsertGivingStatementEntity(givingStatement)

        and:
        Optional<GivingStatementEntity> updatedStatementEntity = givingStatementRepository
                .findById(statementEntity.consolidatedReceiptId)

        then:
        assert upsertedStatement.consolidatedReceiptId == statementEntity.consolidatedReceiptId

        assert updatedStatementEntity.present
        GivingStatementEntity statement = updatedStatementEntity.get()

        beanCompare
                .includeFields("environmentId",
                        "constituentId",
                        "householdId",
                        "periodStartDate",
                        "periodEndDate",
                        "orgName",
                        "orgTaxId",
                        "bulkTaskName",
                        "bulkTaskFormat",
                        "givingStatementType")
                .assertEquals(givingStatement, statement)

        assert statement.emailReceiptStatusDate != null
    }

    def "should explode on seeing too long an org_name"() {
        given:
        GivingStatementEntity givingStatement = aRandom.givingStatementEntity()
                .orgName(aRandom.text(2049))
                .orgTaxId(aRandom.text(12))
                .build()

        when:
        consolidatedReceiptingService.upsertGivingStatementEntity(givingStatement)

        then:
        thrown(TransactionSystemException)
    }
}
