package app.yourneeds.foliocue

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AirPlayDiscoveryTest {
    private val speaker = InetAddress.getByAddress(byteArrayOf(10, 0, 0, 72))
    private val ipv6 = InetAddress.getByAddress(ByteArray(16) { 1 })

    @Test fun usesTheIPv4AddressAndStableDeviceId() {
        val attributes = mapOf("DEVICEID" to " AA:BB ".toByteArray(), "Features" to "0X40000200,0x1".toByteArray())
        val first = discoveredAirPlaySpeaker("Studio", 7000, listOf(ipv6, speaker), attributes)
        assertEquals(AirPlaySpeaker("aa:bb", "Studio", "10.0.0.72", 7000), first)
        assertEquals(first?.id, discoveredAirPlaySpeaker("Renamed", 7200, listOf(speaker), attributes)?.id)
    }

    @Test fun ignoresExplicitlyVideoOnlyReceivers() {
        val attributes = mapOf("features" to "0x1,0x200".toByteArray())
        assertNull(discoveredAirPlaySpeaker("Video", 7000, listOf(speaker), attributes))
    }

    @Test fun retainsOlderReceiversWithoutOptionalTxtRecords() {
        val found = discoveredAirPlaySpeaker("Speaker", 5000, listOf(speaker), emptyMap())
        assertEquals("Speaker", found?.id)
        val unknown = mapOf("features" to "unknown".toByteArray(), "deviceid" to null)
        assertNotNull(discoveredAirPlaySpeaker("Speaker", 5000, listOf(speaker), unknown))
    }

    @Test fun excludesUnusableNetworkEndpoints() {
        val unresolved = listOf(ipv6, InetAddress.getByAddress(byteArrayOf(0, 0, 0, 0)),
            InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)),
            InetAddress.getByAddress(byteArrayOf(224.toByte(), 0, 0, 1)))
        assertNull(discoveredAirPlaySpeaker("Studio", 7000, unresolved, emptyMap()))
        for (port in listOf(-1, 0, 65536))
            assertNull(discoveredAirPlaySpeaker("Studio", port, listOf(speaker), emptyMap()))
    }
}
