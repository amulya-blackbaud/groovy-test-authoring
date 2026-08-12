package com.blackbaud.receiptmanager.core.domain.mappers

import com.blackbaud.receiptmanager.api.receipt.LocationIssuedPatchRequest
import com.blackbaud.receiptmanager.api.receipt.RandomReceiptSeriesPatchRequestBuilder
import com.blackbaud.receiptmanager.api.receipt.ReceiptSeriesPatchRequest
import com.blackbaud.receiptmanager.api.receipt.ReceiptSeriesRequest
import com.blackbaud.receiptmanager.api.receipt.ReceiptSeriesResponse
import com.blackbaud.receiptmanager.core.domain.receipt.ReceiptSeriesEntity
import com.blackbaud.testsupport.BeanCompare
import org.apache.commons.beanutils.BeanUtils
import org.mapstruct.factory.Mappers
import org.openapitools.jackson.nullable.JsonNullable
import spock.lang.Specification

import static com.blackbaud.receiptmanager.core.CoreARandom.aRandom

class ReceiptSeriesMapperSpec extends Specification {

    private ReceiptSeriesMapper mapper

    private BeanCompare beanCompare

    def setup() {
        mapper = Mappers.getMapper(ReceiptSeriesMapper.class)
        beanCompare = new BeanCompare()
    }

    def "should map receiptSeriesRequest and environmentId to ReceiptSeriesEntity"() {
        given:
        String environmentId = aRandom.environmentId()

        and:
        ReceiptSeriesRequest source = aRandom.receiptSeriesRequest().build()

        when:
        ReceiptSeriesEntity target = mapper.newReceiptSeriesEntity(source, environmentId)

        then:
        assert target.environmentId == environmentId
        beanCompare
                .excludeFields("environmentId", "id", "used", "deleted", "inactive", "lastModifiedDate",
                        "lastModifiedByUserId", "createdByUserId", "createdDate", "locationIssued")
                .assertEquals(target, source)
        beanCompare.assertEquals(target.locationIssued, source.locationIssued)
    }

    def "should map ReceiptSeriesEntity to ReceiptSeriesResponse"() {
        given:
        ReceiptSeriesEntity source = aRandom.receiptSeriesEntity().id(aRandom.uuid()).build()

        when:
        ReceiptSeriesResponse target = mapper.toReceiptSeriesResponse(source)

        then:
        assertReceiptSeriesResponseEqualsReceiptSeriesEntity(target, source)
    }

    def "should map ReceiptSeriesEntity List to a ReceiptSeriesResponse List"() {
        given:
        List<ReceiptSeriesEntity> source = aRandom.nonEmptyList { aRandom.receiptSeriesEntity().id(aRandom.uuid()).build() }

        when:
        List<ReceiptSeriesResponse> target = mapper.toReceiptSeriesResponseList(source)

        then:
        source.eachWithIndex { ReceiptSeriesEntity receiptSeriesEntity, int index ->
            assertReceiptSeriesResponseEqualsReceiptSeriesEntity(target[index], receiptSeriesEntity)
        }
    }

    def "should patch ReceiptSeriesPatch to ReceiptSeriesEntity"() {
        given:
        ReceiptSeriesEntity target = aRandom.receiptSeriesEntity().build()
        ReceiptSeriesPatchRequest patchRequest = aRandom.receiptSeriesPatchRequest()
                .withInactive()
                .build()

        when:
        mapper.patchEntity(patchRequest, target)

        then:
        assert target.name == patchRequest.name.get()
        assert target.prefix == patchRequest.prefix.get()
        assert target.defaultSeries == patchRequest.defaultSeries.get()
        assert target.inactive == patchRequest.inactive.get()
        assert target.locationIssued != null
        assert target.locationIssued.country == patchRequest.locationIssued.country.get()
        assert target.locationIssued.city == patchRequest.locationIssued.city.get()
        assert target.locationIssued.stateOrProvince == patchRequest.locationIssued.stateOrProvince.get()
        assert target.locationIssued.postalCode == patchRequest.locationIssued.postalCode.get()
        assert target.locationIssued.street == patchRequest.locationIssued.street.get()
    }

    def "should patch target fields to null/false when source is JsonNullable.of(null)"() {
        given:
        ReceiptSeriesPatchRequest source = patchRequestWIthAllFieldsSetTo(JsonNullable.of(null))
                .inactive(JsonNullable.of(false))
                .defaultSeries(JsonNullable.of(false))
                .build()

        and:
        ReceiptSeriesEntity target = aRandom.receiptSeriesEntity().build()

        when:
        mapper.patchEntity(source, target)

        then:
        assert target.name == null
        assert target.prefix == null
        assert target.inactive == false
        assert target.defaultSeries == false
        assert target.locationIssued.country == null
        assert target.locationIssued.city == null
        assert target.locationIssued.stateOrProvince == null
        assert target.locationIssued.postalCode == null
        assert target.locationIssued.street == null
    }

    def "should leave fields unchanged on target when source patch fields are null"() {
        given:
        ReceiptSeriesEntity target = aRandom.receiptSeriesEntity().build()
        ReceiptSeriesEntity originalTarget = ReceiptSeriesEntity.builder().build()
        BeanUtils.copyProperties(originalTarget, target)

        and:
        ReceiptSeriesPatchRequest source = patchRequestWIthAllFieldsSetTo(null)
                .inactive(null)
                .defaultSeries(null)
                .build()

        when:
        mapper.patchEntity(source, target)

        then:
        assert target == originalTarget
    }

    private void assertReceiptSeriesResponseEqualsReceiptSeriesEntity(ReceiptSeriesResponse target, ReceiptSeriesEntity source) {
        assert target.id == source.id.toString()
        assert target.name == source.name
        assert target.prefix == source.prefix
        assert target.nextReceiptNumber == source.nextReceiptNumber
        assert target.defaultSeries == source.defaultSeries
        assert target.used == source.used
        assert target.inactive == source.inactive
        assert target.lastModifiedDate == source.lastModifiedDate
        assert target.locationIssued != null
        assert target.locationIssued.country == source.locationIssued.country
        assert target.locationIssued.city == source.locationIssued.city
        assert target.locationIssued.stateOrProvince == source.locationIssued.stateOrProvince
        assert target.locationIssued.postalCode == source.locationIssued.postalCode
        assert target.locationIssued.street == source.locationIssued.street
    }

    private RandomReceiptSeriesPatchRequestBuilder patchRequestWIthAllFieldsSetTo(Object o) {
        LocationIssuedPatchRequest locationIssuedPatchRequest = aRandom.locationIssuedPatchRequest()
                .country(o)
                .city(o)
                .stateOrProvince(o)
                .postalCode(o)
                .street(o)
                .build()
        aRandom.receiptSeriesPatchRequest()
                .name(o)
                .prefix(o)
                .locationIssued(locationIssuedPatchRequest)
    }
}
