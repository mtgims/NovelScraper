package com.novelscraper.app.net

import java.net.Inet6Address
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Which addresses third-party code may not reach. */
class PrivateHostsTest {

    private fun ip(s: String) = InetAddress.getByName(s)

    /** An IPv6 address built byte by byte, so the JDK can't turn a mapped one into IPv4. */
    private fun v6(vararg bytes: Int) = Inet6Address.getByAddress(null, ByteArray(16) { bytes[it].toByte() }, null)

    @Test fun privateAndReservedAddressesAreRefused() {
        val refused = listOf(
            "127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.1", "169.254.169.254", "224.0.0.1", "0.0.0.0",
            "100.64.0.1", "100.100.100.100", "100.127.255.255",   // carrier NAT, Tailscale
            "0.1.2.3", "192.0.0.8", "198.18.0.1", "198.19.255.255", "240.0.0.1", "255.255.255.255",
            "::1", "fe80::1", "fc00::1", "fd12:3456::1",
        )
        for (a in refused) assertTrue(ip(a).isPrivateTarget(), "$a must be refused")
    }

    @Test fun ipv4InIpv6ClothingIsJudgedAsIpv4() {
        // ::ffff:192.168.1.1 and 64:ff9b::7f00:1 (NAT64 for 127.0.0.1)
        assertTrue(v6(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff, 0xff, 192, 168, 1, 1).isPrivateTarget())
        assertTrue(v6(0, 0x64, 0xff, 0x9b, 0, 0, 0, 0, 0, 0, 0, 0, 127, 0, 0, 1).isPrivateTarget())
        assertFalse(v6(0, 0x64, 0xff, 0x9b, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1).isPrivateTarget(), "NAT64 for 1.1.1.1")
    }

    @Test fun thePublicInternetIsNot() {
        for (a in listOf("1.1.1.1", "8.8.8.8", "100.63.255.255", "100.128.0.1", "198.17.0.1", "198.20.0.1",
            "192.0.1.1", "93.184.216.34", "2606:4700:4700::1111")) {
            assertFalse(ip(a).isPrivateTarget(), "$a is public")
        }
    }
}
