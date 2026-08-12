package com.blackbaud.receiptmanager.resources

import com.blackbaud.boot.exception.BadRequestException
import com.blackbaud.boot.exception.ForbiddenException
import com.blackbaud.boot.exception.WebApplicationException
import com.blackbaud.context.PersonContext
import com.blackbaud.receiptmanager.ComponentTest
import com.blackbaud.receiptmanager.EntitledSpecification
import com.blackbaud.receiptmanager.api.bulktask.GiftReceiptTaskRequest
import com.blackbaud.receiptmanager.api.bulktask.GivingStatementTaskRequest
import com.blackbaud.receiptmanager.client.PortalClient
import com.blackbaud.receiptmanager.core.domain.BlobCleanupSupport
import com.blackbaud.receiptmanager.core.domain.bulktask.GiftReceiptBulkTaskSetup
import com.blackbaud.receiptmanager.core.domain.mappers.QueueGiftReceiptTaskMapper
import com.blackbaud.receiptmanager.core.domain.receipt.ReceiptSeriesEntity
import com.blackbaud.receiptmanager.core.service.bulktask.giftreceipt.GiftReceiptGeneratingBulkTaskService
import com.blackbaud.receiptmanager.core.service.bulktask.givingstatement.GivingStatementBulkTaskService
import com.blackbaud.receiptmanager.shared.TemplateCreator
import com.blackbaud.testsupport.BBAuthSupport
import com.blackbaud.testsupport.BeanCompare
import com.blackbaud.testsupport.ResettingMockInjector
import org.springframework.beans.factory.annotation.Autowired
import spock.lang.Unroll

import static com.blackbaud.receiptmanager.api.email.EmailTemplateType.SINGLE
import static com.blackbaud.receiptmanager.core.CoreARandom.aRandom
import static com.blackbaud.receiptmanager.core.api.InternalEntitlementValues.GIFT_TRANSACTION_SERVICE
import static com.blackbaud.receiptmanager.shared.TemplateCreator.createDefaultEmailTemplateInfoAndReturnId

@ComponentTest
class PortalResourceComponentSpec extends EntitledSpecification implements BBAuthSupport, BlobCleanupSupport {

    @Autowired
    private PortalClient portalClient
    @Autowired
    private PortalResource portalResource
    @Autowired
    private QueueGiftReceiptTaskMapper queueGiftReceiptTaskMapper
    @Autowired
    private TemplateCreator templateCreator

    private GiftReceiptGeneratingBulkTaskService giftReceiptGeneratingBulkTaskService
    private GivingStatementBulkTaskService givingStatementBulkTaskService

    private BeanCompare beanCompare

    def setup() {
        givingStatementBulkTaskService = ResettingMockInjector.set(portalResource, Mock(GivingStatementBulkTaskService))
        giftReceiptGeneratingBulkTaskService = ResettingMockInjector.set(portalResource, Mock(GiftReceiptGeneratingBulkTaskService))

        beanCompare = new BeanCompare()
        beanCompare.excludeFields('receiptSeriesId', 'templateId')
    }

    def "should throw 500 error when queuing gift receipt failed"() {
        given: "a default receipt series and email template"
        withEntitlements([GIFT_TRANSACTION_SERVICE])
        aRandom.receiptSeriesEntity()
                .withEnvironmentId(environmentId)
                .withDefaultSeries(true)
                .save()

        templateCreator.createDefaultEmailTemplateInfo(environmentId, SINGLE, null)

        and:
        GiftReceiptTaskRequest request = aRandom.giftReceiptTaskRequest()
                .build()

        and: "a valid portal user"
        PersonContext personContext = aRandom.personContext().portalUser(true).build()

        when:
        PersonContext.withPersonContextAndVoidResponse(personContext, {
            portalClient.queueReceipt(request)
        })

        then:
        1 * giftReceiptGeneratingBulkTaskService.queueGiftReceiptBulkTask(_ as GiftReceiptBulkTaskSetup, environmentId, _ as Locale) >> {
            throw new Exception(aRandom.text())
        }

        and:
        thrown(WebApplicationException)
    }

    @Unroll
    def "should throw 400 error for an invalid portal user when queueing giftReceipt and #scenario"() {
        given:
        ReceiptSeriesEntity defaultReceiptSeriesEntity = aRandom.receiptSeriesEntity()
                .withEnvironmentId(environmentId)
                .withDefaultSeries(true)
                .save()

        and:
        GiftReceiptTaskRequest request = aRandom.giftReceiptTaskRequest()
                .receiptSeriesId(defaultReceiptSeriesEntity.id)
                .build()

        and: "a valid portal user"
        PersonContext personContext = PersonContext.builder()
                .personId(jwtPersonId)
                .portalUser(isPortalUser as boolean)
                .build()

        when:
        PersonContext.withPersonContextAndVoidResponse(personContext, {
            portalClient.queueReceipt(request)
        })

        then:
        thrown(BadRequestException)

        where:
        scenario                    | jwtPersonId          | isPortalUser
        'personId is not present'   | null                 | aRandom.coinFlip()
        'user is not a portal user' | aRandom.uuidString() | false
    }

    def "should throw 500 error when queuing giving statement failed"() {
        given:
        String constituentId = aRandom.intIdString()
        GivingStatementTaskRequest request = aRandom.givingStatementTaskRequest()
                .constituentIds([constituentId] as Set)
                .build()

        and: "a valid portal user"
        PersonContext personContext = aRandom.personContext()
                .portalUser(true)
                .personId(constituentId)
                .build()

        when:
        PersonContext.withPersonContextAndVoidResponse(personContext, {
            portalClient.queueGivingStatement(request)
        })

        then:
        1 * givingStatementBulkTaskService.queueGivingStatementBulkTask(request, environmentId, _ as Locale) >> {
            throw new Exception(aRandom.text())
        }

        and:
        thrown(WebApplicationException)
    }

    @Unroll
    def "should throw 400 error for an invalid portal user when queueing givingStatement and #scenario"() {
        given:
        GivingStatementTaskRequest request = aRandom.givingStatementTaskRequest().build()

        and:
        PersonContext invalidPersonContext = PersonContext.builder()
                .personId(jwtPersonId)
                .portalUser(isPortalUser as boolean)
                .build()

        when:
        PersonContext.withPersonContextAndVoidResponse(invalidPersonContext, {
            portalClient.queueGivingStatement(request)
        })

        then:
        thrown(BadRequestException)

        where:
        scenario                    | jwtPersonId          | isPortalUser
        'personId is not present'   | null                 | aRandom.coinFlip()
        'user is not a portal user' | aRandom.uuidString() | false
    }

    def "should throw a 403 for a portal user that attempts to queue a giving statement for anyone other than themselves"() {
        given:
        GivingStatementTaskRequest request = aRandom.givingStatementTaskRequest()
                .constituentIds([aRandom.intIdString()] as Set)
                .build()

        and:
        PersonContext invalidPersonContext = PersonContext.builder()
                .personId(aRandom.intIdString())
                .portalUser(true)
                .build()

        when:
        PersonContext.withPersonContextAndVoidResponse(invalidPersonContext, {
            portalClient.queueGivingStatement(request)
        })

        then:
        thrown ForbiddenException
    }

    def "should throw a 403 for a portal user that attempts to queue giving statements for multiple constituents"() {
        given:
        String constituentId = aRandom.intIdString()
        Set<String> requestConstituentIds = aRandom.validIntIdStringSet()
        requestConstituentIds.add(constituentId)
        GivingStatementTaskRequest request = aRandom.givingStatementTaskRequest()
                .constituentIds(requestConstituentIds)
                .build()

        and:
        PersonContext personContext = PersonContext.builder()
                .personId(constituentId)
                .portalUser(true)
                .build()

        when:
        PersonContext.withPersonContextAndVoidResponse(personContext, {
            portalClient.queueGivingStatement(request)
        })

        then:
        thrown ForbiddenException
    }

    def "should throw a 403 when attempting to queue a gift receipt in a non sky platform environment"() {
        given:
        withEntitlements([])
        GiftReceiptTaskRequest request = aRandom.giftReceiptTaskRequest()
                .build()

        and:
        PersonContext personContext = aRandom.personContext()
                .portalUser(true)
                .build()

        when:
        PersonContext.withPersonContextAndVoidResponse(personContext, {
            portalClient.queueReceipt(request)
        })

        then:
        thrown ForbiddenException

        and:
        0 * giftReceiptGeneratingBulkTaskService.queueGiftReceiptBulkTask(*_)
    }

    def "should find the default receipt series and email template and set it to GiftReceiptTaskRequest"() {
        given:
        withEntitlements([GIFT_TRANSACTION_SERVICE])
        GiftReceiptTaskRequest originalRequest = aRandom.giftReceiptTaskRequest()
                .receiptSeriesId(null)
                .templateId(null)
                .build()
        GiftReceiptBulkTaskSetup bulkTaskSetup = queueGiftReceiptTaskMapper.fromRequest(originalRequest)

        and:
        ReceiptSeriesEntity defaultReceiptSeriesEntity = aRandom.receiptSeriesEntity()
                .withEnvironmentId(environmentId)
                .withDefaultSeries(true)
                .save()
        UUID defaultEmailTemplateId = createDefaultEmailTemplateInfoAndReturnId(environmentId,
                                                                                SINGLE,
                                                                                null)

        and: "a valid portal user"
        PersonContext personContext = aRandom.personContext().portalUser(true).build()

        when:
        PersonContext.withPersonContextAndVoidResponse(personContext, {
            portalClient.queueReceipt(originalRequest)
        })

        then:
        1 * giftReceiptGeneratingBulkTaskService.queueGiftReceiptBulkTask(_, _, _) >> { GiftReceiptBulkTaskSetup actualBulkTaskSetup, String envId, Locale locale ->
            assert envId == environmentId

            beanCompare.assertEquals(actualBulkTaskSetup, bulkTaskSetup)
            assert actualBulkTaskSetup.receiptSeriesId == defaultReceiptSeriesEntity.id
            assert actualBulkTaskSetup.templateId == defaultEmailTemplateId
            assert actualBulkTaskSetup.generatedManually
            assert locale != null
        }
    }
}
