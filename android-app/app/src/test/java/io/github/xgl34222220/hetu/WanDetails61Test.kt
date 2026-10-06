package io.github.xgl34222220.hetu

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WanDetails61Test {
    @Test fun optionalMetadataUsesOnlyActualResponse() {
        val info = parseWanRuntimeInfo("""{"success":true,"ip":"203.0.113.24","type":"IPv4","country":"测试地区","latitude":22.3193,"longitude":114.1694,"connection":{"asn":64500,"org":"Fixture Org","isp":"Fixture ISP"},"timezone":{"id":"Asia/Hong_Kong"}}""")
        assertEquals("AS64500", info.asn); assertEquals("Fixture Org", info.organization)
        assertEquals("IPv4", info.ipType); assertEquals("Asia/Hong_Kong", info.timezone)
        assertEquals("22.3193, 114.1694", info.coordinates)
    }
    @Test fun missingFieldsRemainUnknownNotFakeCoordinates() {
        val info = parseWanRuntimeInfo("""{"success":true,"ip":"203.0.113.24","connection":{"org":null}}""")
        assertEquals("—", info.organization); assertEquals("—", info.ipType)
        assertEquals("—", info.timezone); assertEquals("—", info.coordinates); assertEquals("—", info.asn)
    }
    @Test fun actualZeroCoordinatesAreValidAndOutOfRangeIsNot() {
        assertEquals("0.0000, 0.0000", parseWanRuntimeInfo("""{"success":true,"ip":"203.0.113.24","latitude":0,"longitude":0}""").coordinates)
        assertEquals("—", parseWanRuntimeInfo("""{"success":true,"ip":"203.0.113.24","latitude":91,"longitude":180}""").coordinates)
    }
    @Test fun failedOrAddresslessResponseIsRejected() {
        for (body in listOf("""{"success":false,"ip":"203.0.113.24"}""", """{"success":true}""", """{"success":true,"ip":null}""")) {
            assertTrue(runCatching { parseWanRuntimeInfo(body) }.isFailure)
        }
    }
}
