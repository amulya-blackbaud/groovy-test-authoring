package com.blackbaud.receiptmanager.core.service.givingstatement.schema

import com.blackbaud.gift.api.contribution.ContributionType
import com.blackbaud.gift.api.gift.RenxtGiftTypeValues
import com.blackbaud.receiptmanager.core.api.InternalContribution
import com.blackbaud.receiptmanager.core.api.InternalDesignation
import com.blackbaud.receiptmanager.core.api.InternalGift
import com.blackbaud.receiptmanager.core.api.InternalGiftSplit
import com.blackbaud.receiptmanager.core.api.InternalGiftType
import com.blackbaud.receiptmanager.core.api.InternalGivingStatement
import com.blackbaud.receiptmanager.core.api.InternalReceipt
import com.blackbaud.receiptmanager.core.domain.formatters.DateTimeZonedFormatter
import com.blackbaud.receiptmanager.core.domain.formatters.InternalDateTimeFormatter
import com.blackbaud.receiptmanager.core.domain.localization.MergeFieldLocalizer
import com.blackbaud.receiptmanager.shared.HtmlTemplateRendererBuilder
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.select.Elements
import spock.lang.Specification
import spock.lang.Unroll

import static com.blackbaud.receiptmanager.core.CoreARandom.aRandom
import static com.blackbaud.receiptmanager.core.domain.formatters.CurrencyFormatter.formattedAmountCurrencyOrEmpty

class PaymentSectionCreatorSpec extends Specification {

    private static final int NUMBER_COLS_WITH_CONTRIBUTION_AMOUNT = 7
    private static final int NUMBER_COLS_WITHOUT_CONTRIBUTION_AMOUNT = 6
    private static final String MORE_INFORMATION_MARK = "*"

    private static final String PAYMENTS_TITLE_KEY = "payments_title"
    private static final String PAYMENTS_GIFT_TYPE_LABEL_KEY = "payments_gift_type_label"
    private static final String PAYMENTS_GIFT_DATE_LABEL_KEY = "payments_gift_date_label"
    private static final String PAYMENTS_GIFT_AMOUNT_LABEL_KEY = "payments_gift_amount_label"
    private static final String PAYMENTS_FUND_LABEL_KEY = "payments_fund_label"
    private static final String PAYMENTS_CONTRIBUTION_AMOUNT_LABEL_KEY = "payments_contribution_amount_label"
    private static final String PAYMENTS_RECEIPT_NUMBER_KEY = "payments_receipt_number"
    private static final String PAYMENTS_RECEIPT_DATE_KEY = "payments_receipt_date"
    private static final String PAYMENTS_TOTAL_GIFT_AMOUNT_OF_PAYMENTS_KEY = "payments_total_gift_amount_of_payments"
    private static final String PAYMENTS_TOTAL_CONTRIBUTION_AMOUNT_OF_PAYMENTS_KEY = "payments_total_contribution_amount_of_payments"
    private static final String PAYMENTS_TOTAL_NUMBER_OF_PAYMENTS_KEY = "payments_total_number_of_payments"
    private static final String PAYMENTS_GIFT_AID_LABEL_KEY = "payments_gift_aid_label"
    private static final String PAYMENTS_GROSS_AMOUNT_LABEL_KEY = "payments_gross_amount_label"

    private PaymentSectionCreator paymentSectionCreator
    private DateTimeZonedFormatter formatter
    private PaymentRowBuildService paymentRowBuilder

    def setup() {
        formatter = new DateTimeZonedFormatter(zone: aRandom.supportedEngSysZone().value)
        paymentRowBuilder = new PaymentRowBuildService(dateTimeZonedFormatter: formatter)
        paymentSectionCreator = new PaymentSectionCreator(paymentRowBuildService: paymentRowBuilder,
                                                          htmlTemplateRenderer: HtmlTemplateRendererBuilder.build())
    }

    @Unroll
    def "should display payment labels with the expected localized text where displayGiftAid=#displayGiftAid"() {
        given:
        GivingStatementMergeFieldData givingStatementMergeFieldData = aRandom.givingStatementMergeFieldData()
                .displayGiftAid(displayGiftAid)
                .internalGivingStatement(internalGivingStatement)
                .build()

        when:
        String paymentSection = paymentSectionCreator.createHtml(givingStatementMergeFieldData, aRandom.coinFlip())

        then:
        Document doc = Jsoup.parse(paymentSection)
        Elements paymentsTitle = doc.select("#payments-title")
        Elements paymentsGiftTypeLabel = doc.select("#payments-gift-type-label")
        Elements paymentsGiftDateLabel = doc.select("#payments-gift-date-label")
        Elements paymentsFundLabel = doc.select("#payments-fund-label")
        Elements paymentsReceiptNumberLabel = doc.select("#payments-receipt-number-label")
        Elements paymentsReceiptDateLabel = doc.select("#payments-receipt-date-label")
        Elements paymentsGiftAidLabel = doc.select("#payments-gift-aid-label")
        Elements paymentsGrossAmountLabel = doc.select("#payment-gross-amount-label")

        and:
        Locale locale = givingStatementMergeFieldData.locale
        assert paymentsTitle.text() == MergeFieldLocalizer.localize(PAYMENTS_TITLE_KEY, locale)
        assert paymentsGiftTypeLabel.text() == MergeFieldLocalizer.localize(PAYMENTS_GIFT_TYPE_LABEL_KEY, locale)
        assert paymentsGiftDateLabel.text() == MergeFieldLocalizer.localize(PAYMENTS_GIFT_DATE_LABEL_KEY, locale)
        assert paymentsFundLabel.text() == MergeFieldLocalizer.localize(PAYMENTS_FUND_LABEL_KEY, locale)
        assert paymentsReceiptNumberLabel.text() == MergeFieldLocalizer.localize(PAYMENTS_RECEIPT_NUMBER_KEY, locale)
        assert paymentsReceiptDateLabel.text() == MergeFieldLocalizer.localize(PAYMENTS_RECEIPT_DATE_KEY, locale)
        if (displayGiftAid) {
            assert paymentsGiftAidLabel.text() == MergeFieldLocalizer.localize(PAYMENTS_GIFT_AID_LABEL_KEY, locale)
            assert paymentsGrossAmountLabel.text() == MergeFieldLocalizer.localize(PAYMENTS_GROSS_AMOUNT_LABEL_KEY, locale)
        } else {
            assert paymentsGiftAidLabel == []
            assert paymentsGrossAmountLabel == []
        }

        where:
        displayGiftAid | internalGivingStatement
        false          | aRandom.internalGivingStatement().build()
        true           | aRandom.internalGivingStatement().withGiftAid().build()
    }

    @Unroll
    def "should contain payment details when displayGiftAid=#displayGiftAid"() {
        given:
        InternalGivingStatement internalGivingStatement = internalGivingStatementBuilder
                .build()

        GivingStatementMergeFieldData givingStatementMergeFieldData = aRandom.givingStatementMergeFieldData()
                .displayGiftAid(displayGiftAid)
                .internalGivingStatement(internalGivingStatement)
                .build()

        when:
        String paymentSection = paymentSectionCreator.createHtml(givingStatementMergeFieldData, aRandom.coinFlip())

        then:
        Document doc = Jsoup.parse(paymentSection)
        Elements paymentDetailRows = doc.select("tr#payment-rows")
        assertPaymentRows(paymentDetailRows, givingStatementMergeFieldData)

        where:
        displayGiftAid | internalGivingStatementBuilder
        false          | aRandom.internalGivingStatement()
        true           | aRandom.internalGivingStatement().withGiftAid()
    }

    @Unroll
    def "should #scenario with totals in payment summary"() {
        given:
        InternalGivingStatement internalGivingStatement = internalGivingStatementBuilder
                .build()

        GivingStatementMergeFieldData givingStatementMergeFieldData = aRandom.givingStatementMergeFieldData()
                .displayGiftAid(displayGiftAid)
                .internalGivingStatement(internalGivingStatement)
                .build()

        when:
        String paymentSection = paymentSectionCreator.createHtml(givingStatementMergeFieldData, aRandom.coinFlip())

        then:
        Document doc = Jsoup.parse(paymentSection)
        Elements totalGiftAid = doc.select("payment-total-gift-aid-amount")
        Elements totalGrossAmount = doc.select("payment-total-gross-amount")

        if (displayGiftAid == false) {
            assert totalGiftAid == []
            assert totalGrossAmount == []
        } else {
            totalGiftAid.select("td:eq(0)").text() == MergeFieldLocalizer.localize("payments_total_gift_aid_amount_of_payments", givingStatementMergeFieldData.locale)
            totalGiftAid.select("td:eq(1)").text() == amountAsMoney(internalGivingStatement.totalGiftAidAmount, givingStatementMergeFieldData.currencySymbol)
            totalGrossAmount.select("td:eq(0)").text() == MergeFieldLocalizer.localize("payments_total_gross_amount_of_payments", givingStatementMergeFieldData.locale)
            totalGrossAmount.select("td:eq(1)").text() == amountAsMoney(internalGivingStatement.totalGrossAmount, givingStatementMergeFieldData.currencySymbol)
        }

        where:
        scenario                           | displayGiftAid | internalGivingStatementBuilder
        "not show gift aid"                | false          | aRandom.internalGivingStatement()
        "show gift aid w/ data"            | true           | aRandom.internalGivingStatement().withGiftAid()
        "show gift aid handling null data" | true           | aRandom.internalGivingStatement().withGiftAid(null)
    }

    @Unroll
    def "should create payment section containing internal designation when payments are present for #scenario"() {
        given:
        InternalGivingStatement internalGivingStatement = internalGivingStatementBuilder.build()

        internalGivingStatement.internalDesignations = buildDesignations(internalGivingStatement.paymentInternalContributions)

        and:
        GivingStatementMergeFieldData givingStatementMergeFieldData = aRandom.givingStatementMergeFieldData()
                .displayContributionAmount(true)
                .internalGivingStatement(internalGivingStatement)
                .build()

        when:
        String paymentSection = paymentSectionCreator.createHtml(givingStatementMergeFieldData, aRandom.coinFlip())

        then:
        assert paymentSection.contains(amountAsMoney(internalGivingStatement.totalGiftAmount, givingStatementMergeFieldData.currencySymbol))
        assert paymentSection.contains(amountAsMoney(internalGivingStatement.totalContributionAmount, givingStatementMergeFieldData.currencySymbol))

        Map<String, InternalDesignation> designationMap = internalGivingStatement.internalDesignations.collectEntries {
            [it.keyIdentifier, it]
        }

        internalGivingStatement.paymentInternalContributions.each {
            assert paymentSection.contains(amountAsMoney(it.internalGift.amount, givingStatementMergeFieldData.currencySymbol))
            assert paymentSection.contains(displayNameConverter.call(it.internalGift.giftType) as String)
            it.internalGift.internalGiftSplits.each {
                assert paymentSection.contains(designationMap.get(it.designationId).name)
            }
        }

        assert paymentSection.contains("<td>${internalGivingStatement.paymentInternalContributions.size()}</td>")

        where:
        scenario | internalGivingStatementBuilder             | displayNameConverter
        'RENXT'  | aRandom.internalGivingStatement()          | { String it -> RenxtGiftTypeValues.displayNameOrNull(it) }
        'TCS'    | aRandom.internalGivingStatement().forTcs() | { String it -> ContributionType.displayNameOrNull(it) }
    }

    def "should not have payment section when no payments are present"() {
        given:
        InternalGivingStatement internalGivingStatement = aRandom.internalGivingStatement().withNoPayments().build()

        and:
        GivingStatementMergeFieldData givingStatementMergeFieldData = aRandom.givingStatementMergeFieldData()
                .internalGivingStatement(internalGivingStatement)
                .build()

        when:
        String paymentSection = paymentSectionCreator.createHtml(givingStatementMergeFieldData, aRandom.coinFlip())

        and:
        Document doc = Jsoup.parse(paymentSection)
        Elements payments = doc.select("#payment-details")

        then:
        assert payments == []
    }

    def "should not show commitment gift types in payment section"() {
        given:
        InternalGivingStatement internalGivingStatement = aRandom.internalGivingStatement().build()

        and:
        GivingStatementMergeFieldData givingStatementMergeFieldData = aRandom.givingStatementMergeFieldData()
                .internalGivingStatement(internalGivingStatement)
                .build()

        when:
        String commitmentSection = paymentSectionCreator.createHtml(givingStatementMergeFieldData, aRandom.coinFlip())

        and:
        Document doc = Jsoup.parse(commitmentSection)
        Elements commitments = doc.select("div#commitments")

        then:
        assert commitments == []
    }

    @Unroll
    def "should not throw exception if receiptNumber is not present for gift when buildPaymentRows and #scenario"() {
        given:
        InternalGivingStatement internalGivingStatement = aRandom.internalGivingStatement().build()
        internalGivingStatement.paymentInternalContributions.each { internalContribution ->
            internalContribution.latestInternalReceipt.receiptNumber = null
        }

        and:
        GivingStatementMergeFieldData givingStatementMergeFieldData = aRandom.givingStatementMergeFieldData()
                .internalGivingStatement(internalGivingStatement)
                .build()

        when:
        paymentSectionCreator.createHtml(givingStatementMergeFieldData, aRandom.coinFlip())

        then:
        noExceptionThrown()

        where:
        scenario                             | displayContributionAmount
        'displayContributionAmount is true'  | true
        'displayContributionAmount is false' | false
    }

    @Unroll
    def "should not throw exception if receiptDate is not present for gift when buildPaymentRows and #scenario"() {
        given:
        InternalGivingStatement internalGivingStatement = aRandom.internalGivingStatement().build()
        internalGivingStatement.paymentInternalContributions.each { internalContribution ->
            internalContribution.latestInternalReceipt.receiptDateTime = null
        }

        and:
        GivingStatementMergeFieldData givingStatementMergeFieldData = aRandom.givingStatementMergeFieldData()
                .internalGivingStatement(internalGivingStatement)
                .build()

        when:
        paymentSectionCreator.createHtml(givingStatementMergeFieldData, aRandom.coinFlip())

        then:
        noExceptionThrown()

        where:
        scenario                             | displayContributionAmount
        'displayContributionAmount is true'  | true
        'displayContributionAmount is false' | false
    }

    @Unroll
    def "should #scenario in payment section when contributionAndBenefitAmountDisplayed=#contributionAndBenefitAmountDisplayed"() {
        given:
        GivingStatementMergeFieldData givingStatementMergeFieldData = aRandom.givingStatementMergeFieldData()
                .displayContributionAmount(contributionAndBenefitAmountDisplayed)
                .internalGivingStatement(internalGivingStatement)
                .build()

        when:
        String paymentHtml = paymentSectionCreator.createHtml(givingStatementMergeFieldData, aRandom.coinFlip())

        and:
        Document doc = Jsoup.parse(paymentHtml)
        Elements totalGiftAmountElements = doc.select("#payment-total-gift-amount")
        Elements totalContributionAmountElements = doc.select("#payment-total-contribution-amount")
        Elements totalNumberElements = doc.select("#payment-total-number")
        Elements paymentDetailsElements = doc.select("#payment-details")

        then:
        assert totalGiftAmountElements.select("td:eq(0)").text() == MergeFieldLocalizer.localize(PAYMENTS_TOTAL_GIFT_AMOUNT_OF_PAYMENTS_KEY, givingStatementMergeFieldData.locale)
        assert totalGiftAmountElements.select("td:eq(1)").text() ==
                amountAsMoney(givingStatementMergeFieldData.internalGivingStatement.totalGiftAmount, givingStatementMergeFieldData.currencySymbol)

        and:
        assert totalContributionAmountElements.hasText() == contributionAndBenefitAmountDisplayed
        assertTotalContributionAmountOfPaymentsLabelIfPresent(contributionAndBenefitAmountDisplayed,
                                                              totalContributionAmountElements,
                                                              givingStatementMergeFieldData)

        and:
        assert totalNumberElements.select("td:eq(0)").text() == MergeFieldLocalizer.localize(PAYMENTS_TOTAL_NUMBER_OF_PAYMENTS_KEY, givingStatementMergeFieldData.locale)
        assert totalNumberElements.select("td:eq(1)").text() ==
                givingStatementMergeFieldData.internalGivingStatement.paymentInternalContributions.size().toString()

        and:
        Elements rows = paymentDetailsElements.select("tr")
        assert rows[0].text().contains(MergeFieldLocalizer.localize(PAYMENTS_CONTRIBUTION_AMOUNT_LABEL_KEY, givingStatementMergeFieldData.locale)) == contributionAndBenefitAmountDisplayed
        int totalNumberOfColumnsDisplayed = contributionAndBenefitAmountDisplayed ? NUMBER_COLS_WITH_CONTRIBUTION_AMOUNT :
                NUMBER_COLS_WITHOUT_CONTRIBUTION_AMOUNT
        rows.each { Element row ->
            assert row.select("td").size() == totalNumberOfColumnsDisplayed
        }

        where:
        scenario                                      | contributionAndBenefitAmountDisplayed | internalGivingStatement
        'show contributionAmount without receipt'     | true                                  | aRandom.internalGivingStatement().forTcs().withNoReceipt().build()
        'not show contributionAmount without receipt' | false                                 | aRandom.internalGivingStatement().forTcs().withNoReceipt().build()
        'show contributionAmount with receipt'        | true                                  | aRandom.internalGivingStatement().forTcs().build()
        'not show contributionAmount with receipt'    | false                                 | aRandom.internalGivingStatement().forTcs().build()
    }

    def "should display mark next to gift amount when contribution has voluntaryContributionEnabled"() {
        given:
        GivingStatementMergeFieldData givingStatementMergeFieldData = aRandom.givingStatementMergeFieldData().build()
        List<InternalContribution> internalContributions = givingStatementMergeFieldData.internalGivingStatement.paymentInternalContributions

        when:
        String paymentHtml = paymentSectionCreator.createHtml(givingStatementMergeFieldData, aRandom.coinFlip())

        and:
        Document doc = Jsoup.parse(paymentHtml)
        Elements paymentDetailsElements = doc.select("#payment-details")
        Elements paymentRows = paymentDetailsElements.select("tr")
        Elements paymentHeaderCells = paymentRows[0].select("td")
        int giftAmountColumnIndex = headerColumnIndex(paymentHeaderCells, MergeFieldLocalizer.localize(PAYMENTS_GIFT_AMOUNT_LABEL_KEY, givingStatementMergeFieldData.locale))

        then:
        internalContributions.eachWithIndex { InternalContribution internalContribution, int contributionIndex ->
            int paymentRowIndex = contributionIndex + 1
            assertGiftAmountVoluntaryContributionMark(paymentRows[paymentRowIndex], giftAmountColumnIndex, internalContribution)
        }
    }

    def "should display designation subtotals section with row for each designation present"() {
        given:
        GivingStatementMergeFieldData givingStatementMergeFieldData = aRandom.givingStatementMergeFieldData().build()
        InternalGivingStatement internalGivingStatement = givingStatementMergeFieldData.internalGivingStatement

        and:
        List<InternalGiftSplit> paymentInternalGiftSplits = internalGivingStatement.paymentInternalContributions.collectMany {
            it.internalGift.internalGiftSplits
        }

        Set<String> paymentDesignationIds = paymentInternalGiftSplits*.designationId

        List<InternalDesignation> sortedInternalDesignations = internalGivingStatement.internalDesignations
                .findAll { it -> paymentDesignationIds.contains(it.keyIdentifier) }
                .toSorted { a, b -> a.name <=> b.name ?: a.keyIdentifier <=> b.keyIdentifier }

        when:
        String paymentHtml = paymentSectionCreator.createHtml(givingStatementMergeFieldData, aRandom.coinFlip())

        and:
        Document doc = Jsoup.parse(paymentHtml)
        Element designationSubtotalsElement = doc.select("#designation-subtotals").first()
        Elements designationSubtotalsRows = designationSubtotalsElement.select("tbody").select("tr")

        then:
        sortedInternalDesignations.eachWithIndex { InternalDesignation internalDesignation, int designationIndex ->
            assertDesignationSubtotalRow(designationSubtotalsRows[designationIndex], internalDesignation, paymentInternalGiftSplits, givingStatementMergeFieldData.currencySymbol)
        }
    }

    def "should display gift aid data default to 0.00 even if gift aid is null when should display gift aid"() {
        given:
        InternalGivingStatement internalGivingStatementWithGiftAid = aRandom.internalGivingStatement()
                .withGiftAid(null)
                .build()

        GivingStatementMergeFieldData givingStatementMergeFieldData = aRandom.givingStatementMergeFieldData()
                .displayGiftAid(true)
                .internalGivingStatement(internalGivingStatementWithGiftAid)
                .build()

        when:
        String paymentSection = paymentSectionCreator.createHtml(givingStatementMergeFieldData, aRandom.coinFlip())

        then:
        Elements paymentDetailRows = Jsoup.parse(paymentSection).select("tr#payment-rows")
        internalGivingStatementWithGiftAid.paymentInternalContributions.eachWithIndex { InternalContribution contribution, int index ->
            Element paymentDetailRow = paymentDetailRows[index]

            assert textOnElementOrNull(paymentDetailRow, "#gift-aid") == amountAsMoney(BigDecimal.ZERO, givingStatementMergeFieldData.currencySymbol)
            assert textOnElementOrNull(paymentDetailRow, "#gross-amount") == amountAsMoney(contribution.internalGift.amount, givingStatementMergeFieldData.currencySymbol)
        }
    }

    private String amountAsMoney(BigDecimal value, String currencySymbol) {
        formattedAmountCurrencyOrEmpty(value, currencySymbol)
    }

    private static void assertGiftAmountVoluntaryContributionMark(Element paymentRow, int giftAmountColumnIndex, InternalContribution internalContribution) {
        Element giftAmountCell = paymentRow.select("td").get(giftAmountColumnIndex)
        boolean giftAmountMarkedAsVcEnabled = isMoreInformationMarkEnabled(giftAmountCell)
        boolean voluntaryContributionEnabled = internalContribution.voluntaryContributionEnabled
        assert giftAmountMarkedAsVcEnabled == voluntaryContributionEnabled
    }

    private static boolean isMoreInformationMarkEnabled(Element cell) {
        Elements spans = cell.select("span")
        Node textNode = spans[1].childNodes()[0]
        textNode?.value?.contains(MORE_INFORMATION_MARK) ?: false
    }

    private void assertDesignationSubtotalRow(Element designationSubtotalRow, InternalDesignation internalDesignation, List<InternalGiftSplit> paymentInternalGiftSplits, String currencySymbol) {
        Element designationNameCell = designationSubtotalRow.select("td")[0]
        Element totalsCell = designationSubtotalRow.select("td")[1]

        assert designationNameCell.text().contains(internalDesignation.name)

        List<InternalGiftSplit> giftSplitsForDesignation = paymentInternalGiftSplits
                .findAll {
                    it -> it.designationId == internalDesignation.keyIdentifier
                }

        BigDecimal totalAmount = giftSplitsForDesignation.sum {
            it.amount
        } as BigDecimal

        assert totalsCell.text() == formattedAmountCurrencyOrEmpty(totalAmount, currencySymbol)
    }

    private void assertPaymentRows(Elements paymentDetailRows, GivingStatementMergeFieldData givingStatementMergeFieldData) {
        InternalGivingStatement internalGivingStatement = givingStatementMergeFieldData.internalGivingStatement
        Locale locale = givingStatementMergeFieldData.locale
        assert paymentDetailRows.size() == internalGivingStatement.paymentInternalContributions.size()

        internalGivingStatement.paymentInternalContributions.eachWithIndex { InternalContribution contribution, int index ->
            Element paymentDetailRow = paymentDetailRows[index]
            InternalGift gift = contribution.internalGift
            InternalReceipt receipt = contribution.latestInternalReceipt
            assert textOnElementOrNull(paymentDetailRow, "#gift-type") == InternalGiftType.getDisplayNameByGiftTypeOrNull(gift.giftType)
            assert textOnElementOrNull(paymentDetailRow, "#gift-date") == InternalDateTimeFormatter.formatDate(gift.createDate, locale)
            assert textOnElementOrNull(paymentDetailRow, "#gift-amount") == amountAsMoney(gift.amount, givingStatementMergeFieldData.currencySymbol)
            assert textOnElementOrNull(paymentDetailRow, "#designations") != null

            if (givingStatementMergeFieldData.displayGiftAid) {
                assertGiftAidAndGrossAmountArePresent(paymentDetailRow, contribution, givingStatementMergeFieldData)
            } else {
                assertGiftAidAndGrossAmountAreNotPresent(paymentDetailRow)
            }

            if (givingStatementMergeFieldData.displayContributionAmount) {
                assert textOnElementOrNull(paymentDetailRow, "#contribution-amount") == amountAsMoney(contribution.latestInternalReceiptAmount(), givingStatementMergeFieldData.currencySymbol)
            } else {
                assert textOnElementOrNull(paymentDetailRow, "#contribution-amount-field") == null
            }

            assert textOnElementOrNull(paymentDetailRow, "#receipt-number") == receipt.receiptNumberOrEmpty()
            assert textOnElementOrNull(paymentDetailRow, "#receipt-date") == formatter.formattedReceiptDateOrEmpty(receipt, locale, givingStatementMergeFieldData.internalOrganization.timeZoneName)
        }
    }

    private void assertGiftAidAndGrossAmountAreNotPresent(Element paymentDetailRow) {
        assert textOnElementOrNull(paymentDetailRow, "#gift-aid") == null
        assert textOnElementOrNull(paymentDetailRow, "#gross-amount") == null
    }

    private void assertGiftAidAndGrossAmountArePresent(Element paymentDetailRow, InternalContribution contribution, GivingStatementMergeFieldData givingStatementMergeFieldData) {
        InternalGift gift = contribution.internalGift
        assert textOnElementOrNull(paymentDetailRow, "#gift-aid") == amountAsMoney(gift.giftAid.taxClaimAmount, givingStatementMergeFieldData.currencySymbol)
        assert textOnElementOrNull(paymentDetailRow, "#gross-amount") == amountAsMoney(gift.giftAid.grossAmount, givingStatementMergeFieldData.currencySymbol)
    }

    private String textOnElementOrNull(Element element, String cssSelector) {
        Element selected = element.selectFirst(cssSelector)
        selected?.text()
    }

    private static List<InternalDesignation> buildDesignations(List<InternalContribution> contributions) {
        List<InternalGiftSplit> internalGiftSplits = contributions.collectMany {
            it.internalGift.internalGiftSplits
        }.asList()

        List<String> designationIds = internalGiftSplits.collect {
            it.designationId
        }

        designationIds.collect {
            aRandom.internalDesignation().keyIdentifier(it).build()
        }.toList()
    }

    private int headerColumnIndex(Elements cells, String columnHeading) {
        cells.findIndexOf { it.toString().contains(columnHeading) }
    }

    private static void assertTotalContributionAmountOfPaymentsLabelIfPresent(Boolean contributionAndBenefitAmountDisplayed,
                                                                              Elements totalContributionAmountElements,
                                                                              GivingStatementMergeFieldData givingStatementMergeFieldData) {
        if (contributionAndBenefitAmountDisplayed) {
            assert totalContributionAmountElements.select("td:eq(0)").text() == MergeFieldLocalizer.localize(PAYMENTS_TOTAL_CONTRIBUTION_AMOUNT_OF_PAYMENTS_KEY,
                                                                                                             givingStatementMergeFieldData.locale)
        }
    }
}
