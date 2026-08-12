package com.blackbaud.receiptmanager.core.service.contributor

import com.blackbaud.receiptmanager.CoreTest
import com.blackbaud.receiptmanager.EntitledSpecification
import com.blackbaud.testsupport.BBAuthSupport
import org.springframework.beans.factory.annotation.Autowired

import static com.blackbaud.receiptmanager.core.api.InternalEntitlementValues.GIFT_TRANSACTION_SERVICE
import static com.blackbaud.receiptmanager.core.api.InternalEntitlementValues.RENXT

@CoreTest
class InternalContributorServiceSelectorCoreSpec extends EntitledSpecification implements BBAuthSupport {
    @Autowired
    private InternalContributorServiceSelector internalContributorServiceSelector

    def "should return RenxtContributorService for RENXT_SERVICE service type"() {
        given:
        withEntitlements([RENXT])

        when:
        InternalContributorService internalContributorService = internalContributorServiceSelector.internalContributorService(environmentId)

        then:
        assert internalContributorService instanceof RenxtContributorService
    }

    def "should return TcsContributorService for TCS_PLATFORM_SERVICE service type"() {
        given:
        withEntitlements([GIFT_TRANSACTION_SERVICE])

        when:
        InternalContributorService internalContributorService = internalContributorServiceSelector.internalContributorService(environmentId)

        then:
        assert internalContributorService instanceof TcsContributorService
    }
}
