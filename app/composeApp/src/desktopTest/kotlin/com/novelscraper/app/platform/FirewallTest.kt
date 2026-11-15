package com.novelscraper.app.platform

import com.novelscraper.app.platform.Firewall.Port
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FirewallTest {

    /** Shaped as a real ufw rules file: other apps' rules, interface rules, comments. */
    private val rules = """
        *filter
        ### RULES ###
        ### tuple ### allow udp 53317 0.0.0.0/0 any 0.0.0.0/0 in
        -A ufw-user-input -p udp --dport 53317 -j ACCEPT
        ### tuple ### allow udp 53 172.17.0.1 any 192.168.0.0/16 in comment=616c6c6f772d646f636b65722d646e73
        ### tuple ### route:allow any any 0.0.0.0/0 any 0.0.0.0/0 in_virbr0
        ### tuple ### allow any any 0.0.0.0/0 any 0.0.0.0/0 in_virbr0
        ### tuple ### route:allow any any 0.0.0.0/0 any 0.0.0.0/0 in_enp4s0!out_waydroid0
        ### tuple ### allow udp 1714:1764 0.0.0.0/0 any 0.0.0.0/0 in
        ### tuple ### allow udp 47811 0.0.0.0/0 any 192.168.0.0/24 in
        -A ufw-user-input -p udp --dport 47811 -s 192.168.0.0/24 -j ACCEPT
        ### tuple ### allow tcp 47812 0.0.0.0/0 any 192.168.0.0/24 in
        ### END RULES ###
    """.trimIndent()

    private val ports = listOf(Port("udp", 47811), Port("tcp", 47812))
    private fun net(s: String, iface: String = "enp4s0") = Firewall.parseNet(s)!!.copy(iface = iface)

    @Test
    fun rulesMadeOnAnotherNetworkAreNamedAndBothPortsAskedFor() {
        val need = Firewall.need(net("192.168.1.0/24"), ports, Firewall.parseUfw(rules))
        assertEquals(ports, need.missing)
        assertEquals(listOf("192.168.0.0/24"), need.elsewhere)
    }

    @Test
    fun nothingIsAskedForWhereTheRulesAlreadyLetThemIn() {
        val need = Firewall.need(net("192.168.0.0/24"), ports, Firewall.parseUfw(rules))
        assertTrue(need.missing.isEmpty())
        assertTrue(need.elsewhere.isEmpty())
    }

    @Test
    fun onlyTheMissingPortIsAskedFor() {
        val r = Firewall.parseUfw("### tuple ### allow tcp 47812 0.0.0.0/0 any 192.168.1.0/24 in")
        assertEquals(listOf(Port("udp", 47811)), Firewall.need(net("192.168.1.0/24"), ports, r).missing)
    }

    @Test
    fun wideRulesRangesAndAnyProtocolCount() {
        fun open(rule: String) = Firewall.need(net("192.168.1.0/24"), ports, Firewall.parseUfw(rule)).missing.isEmpty()
        assertTrue(open("### tuple ### allow any 47811:47812 0.0.0.0/0 any 192.168.0.0/16 in"), "a /16 holds the /24")
        assertTrue(open("### tuple ### allow any any 0.0.0.0/0 any 0.0.0.0/0 in_enp4s0"), "everything on this card")
        assertTrue(open("### tuple ### limit any 47811,47812 0.0.0.0/0 any 192.168.1.0/24 in"), "a list of ports")
    }

    @Test
    fun rulesForAnotherCardOrForwardingOrDenyingDoNotCount() {
        fun open(rule: String) = Firewall.need(net("192.168.1.0/24"), ports, Firewall.parseUfw(rule)).missing.isEmpty()
        assertTrue(!open("### tuple ### allow any any 0.0.0.0/0 any 0.0.0.0/0 in_virbr0"), "another card")
        assertTrue(!open("### tuple ### route:allow any any 0.0.0.0/0 any 0.0.0.0/0 in"), "forwarding")
        assertTrue(!open("### tuple ### deny any 47811:47812 0.0.0.0/0 any 0.0.0.0/0 in"), "a deny")
        assertTrue(!open("### tuple ### allow any 47811:47812 0.0.0.0/0 any 0.0.0.0/0 out"), "outgoing")
    }

    @Test
    fun theCommandAddsOnlyWhatIsMissingForThisNetwork() {
        assertEquals(
            "ufw allow from 192.168.1.0/24 to any port 47811 proto udp comment 'NovelScraper sync'",
            Firewall.ufwCommand(net("192.168.1.0/24"), listOf(Port("udp", 47811))),
        )
    }
}
