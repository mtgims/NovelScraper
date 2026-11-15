package com.novelscraper.app.platform

import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Whether a Linux computer's firewall keeps the other devices out of
 * same-Wi-Fi sync, and exactly which rules would let them in.
 *
 * ufw keeps its rules in files anyone may read, so the rules already there are
 * checked against the network the computer is on now, and only the missing
 * ones are asked for. A rule made on another network (a new router hands out
 * 192.168.1.x where the old one gave 192.168.0.x) is named, since that is the
 * usual reason sync stops on a computer where it used to work. Where the
 * rules can't be read (firewalld, or ufw's files closed off), every rule is
 * asked for; adding one that exists changes nothing.
 */
internal object Firewall {

    data class Net(val address: Long, val bits: Int, val iface: String?) {
        fun contains(other: Net): Boolean = bits <= other.bits && mask(bits) and (address xor other.address) == 0L
        override fun toString() = "${dotted(address)}/$bits"
    }

    data class Port(val proto: String, val port: Int)

    /** One "allow" rule of ufw's, as its rules file records it. */
    data class Rule(val protos: Set<String>, val ports: String, val from: Net?, val iface: String?)

    /** What the firewall needs, for [net]: [missing] rules, and [elsewhere], the networks it lets in instead. */
    data class Need(val net: Net, val missing: List<Port>, val elsewhere: List<String>)

    // --- the network ---------------------------------------------------------------

    /**
     * The network this computer reaches the others on: that of the interface
     * the default route goes out of, or else the first private one with a
     * broadcast address (a VPN's tunnel has none).
     */
    fun localNetwork(routes: String? = read("/proc/net/route")): Net? = runCatching {
        val viaDefault = routes?.lineSequence()?.drop(1)?.map { it.trim().split(Regex("\\s+")) }
            ?.filter { it.size > 2 && it[1] == "00000000" }?.map { it[0] }?.toList().orEmpty()
        val nifs = NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
        val ordered = nifs.filter { it.name in viaDefault } + nifs.filter { it.name !in viaDefault }
        ordered.firstNotNullOfOrNull { nif ->
            nif.interfaceAddresses.firstOrNull {
                it.address is Inet4Address && it.address.isSiteLocalAddress && it.broadcast != null &&
                    it.networkPrefixLength in 8..30
            }?.let { a ->
                val bits = a.networkPrefixLength.toInt()
                Net(toLong(a.address.address) and mask(bits), bits, nif.name)
            }
        }
    }.getOrNull()

    // --- ufw -----------------------------------------------------------------------

    /** The "allow" rules for incoming connections in ufw's rules file. */
    fun parseUfw(rules: String): List<Rule> = rules.lineSequence()
        .map { it.trim() }
        .filter { it.startsWith("### tuple ###") }
        .mapNotNull { line ->
            val f = line.removePrefix("### tuple ###").trim().split(Regex("\\s+")).filter { '=' !in it }
            // action proto dport dst sport src [app names…] direction
            if (f.size < 7 || f[0] !in setOf("allow", "limit")) return@mapNotNull null
            val dir = f.last()
            if (!dir.startsWith("in") || '!' in dir) return@mapNotNull null
            val protos = if (f[1] == "any") setOf("tcp", "udp") else setOf(f[1])
            Rule(protos, f[2], parseNet(f[5]), dir.removePrefix("in").removePrefix("_").ifEmpty { null })
        }.toList()

    /** Which of [ports] the [rules] leave shut to [net]. */
    fun need(net: Net, ports: List<Port>, rules: List<Rule>): Need {
        fun opens(r: Rule, p: Port) = p.proto in r.protos && portMatches(r.ports, p.port) &&
            (r.iface == null || r.iface == net.iface)
        val missing = ports.filter { p -> rules.none { r -> opens(r, p) && (r.from?.contains(net) ?: false) } }
        val elsewhere = rules.filter { r -> ports.any { opens(r, it) } && r.from?.contains(net) == false }
            .map { it.from.toString() }.distinct()
        return Need(net, missing, elsewhere)
    }

    /** ufw's command for [missing], for [net]. */
    fun ufwCommand(net: Net, missing: List<Port>): String = missing.joinToString(" && ") {
        "ufw allow from $net to any port ${it.port} proto ${it.proto} comment 'NovelScraper sync'"
    }

    fun firewalldCommand(net: Net, missing: List<Port>): String =
        "firewall-cmd --permanent " + missing.joinToString(" ") {
            "--add-rich-rule='rule family=ipv4 source address=$net port port=${it.port} protocol=${it.proto} accept'"
        } + " && firewall-cmd --reload"

    // --- helpers ---------------------------------------------------------------------

    private fun portMatches(spec: String, port: Int): Boolean = spec == "any" || spec.split(',').any { part ->
        val range = part.split(':')
        when (range.size) {
            1 -> range[0].toIntOrNull() == port
            2 -> (range[0].toIntOrNull() ?: return@any false) <= port && port <= (range[1].toIntOrNull() ?: return@any false)
            else -> false
        }
    }

    /** "192.168.0.0/24", a bare address or "any"; null for an IPv6 one. */
    fun parseNet(s: String): Net? {
        if (s == "any") return Net(0, 0, null)
        val (ip, bits) = s.split('/').let { it[0] to (it.getOrNull(1)?.toIntOrNull() ?: 32) }
        val parts = ip.split('.').map { it.toIntOrNull() ?: return null }
        if (parts.size != 4 || parts.any { it !in 0..255 } || bits !in 0..32) return null
        return Net(parts.fold(0L) { acc, b -> (acc shl 8) or b.toLong() } and mask(bits), bits, null)
    }

    private fun mask(bits: Int) = if (bits == 0) 0L else (0xffffffffL shl (32 - bits)) and 0xffffffffL
    private fun toLong(b: ByteArray) = b.fold(0L) { acc, x -> (acc shl 8) or (x.toLong() and 0xff) }
    private fun dotted(a: Long) = "${a shr 24 and 255}.${a shr 16 and 255}.${a shr 8 and 255}.${a and 255}"

    fun read(path: String): String? = runCatching { File(path).takeIf { it.canRead() }?.readText() }.getOrNull()
}
