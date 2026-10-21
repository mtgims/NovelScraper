package com.novelscraper.app.net

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Keeps third-party code (a source, or an image address a source handed over)
 * on the public internet. From someone's phone or desktop it can otherwise see
 * a home network invisible from outside: a router's admin page, a NAS, whatever
 * else is listening.
 *
 * Two checks, because one is not enough:
 *
 * - **Before connecting**, the request's host is resolved here and refused if
 *   any address is private. OkHttp skips DNS for a literal IP, so a custom
 *   `Dns` alone would never see `http://192.168.1.1/`; `getAllByName` handles a
 *   literal and a name alike.
 * - **Once connected**, the address the socket actually reached is checked. The
 *   first check resolves the name, then the client resolves it again to
 *   connect, and a name that answers "public" to one and "this machine" to the
 *   other (DNS rebinding) got through. A network interceptor runs for every
 *   redirect hop as well.
 */
object PrivateHosts {

    /** Refuse a host that resolves to a private address, before connecting. */
    val beforeConnect = Interceptor { chain ->
        val host = chain.request().url.host
        if (isPrivateHost(host)) throw refused(host)
        chain.proceed(chain.request())
    }

    /** Refuse a connection that reached a private address after all. */
    val onConnection = Interceptor { chain ->
        val reached = chain.connection()?.socket()?.inetAddress
        if (reached != null && reached.isPrivateTarget()) throw refused(chain.request().url.host)
        chain.proceed(chain.request())
    }

    /** [builder] with both checks. */
    fun guard(builder: OkHttpClient.Builder): OkHttpClient.Builder =
        builder.addInterceptor(beforeConnect).addNetworkInterceptor(onConnection)

    /** Whether [host] (a name or a literal address) resolves to anything private. */
    fun isPrivateHost(host: String): Boolean =
        runCatching { InetAddress.getAllByName(host).toList() }.getOrDefault(emptyList())
            .any { it.isPrivateTarget() }

    private fun refused(host: String) =
        IOException("$host is on a private network; sources may only reach the public internet")
}

/**
 * Whether connecting here would reach this machine, a private or reserved
 * network, or something that stands for one.
 */
fun InetAddress.isPrivateTarget(): Boolean {
    if (isLoopbackAddress || isAnyLocalAddress || isLinkLocalAddress || isSiteLocalAddress || isMulticastAddress) {
        return true
    }
    val b = address
    if (this is Inet6Address) {
        // An IPv4 address in IPv6 clothing is judged as the IPv4 address:
        // mapped (::ffff:a.b.c.d) and NAT64 (64:ff9b::a.b.c.d).
        val mapped = (0..9).all { b[it].toInt() == 0 } && b[10].toInt() == -1 && b[11].toInt() == -1
        val nat64 = b[0].toInt() == 0x00 && b[1].toInt() == 0x64 && (b[2].toInt() and 0xFF) == 0xFF &&
            (b[3].toInt() and 0xFF) == 0x9B && (4..11).all { b[it].toInt() == 0 }
        if (mapped || nat64) return InetAddress.getByAddress(b.copyOfRange(12, 16)).isPrivateTarget()
        // Unique local, fc00::/7. The JDK has no predicate for it.
        return (b[0].toInt() and 0xFE) == 0xFC
    }
    if (this is Inet4Address) {
        val o0 = b[0].toInt() and 0xFF
        val o1 = b[1].toInt() and 0xFF
        val o2 = b[2].toInt() and 0xFF
        return o0 == 0 ||                                   // "this network", 0.0.0.0/8
            (o0 == 100 && o1 in 64..127) ||                 // carrier NAT and Tailscale, 100.64.0.0/10
            (o0 == 192 && o1 == 0 && o2 == 0) ||            // IETF protocol assignments, 192.0.0.0/24
            (o0 == 198 && (o1 == 18 || o1 == 19)) ||        // benchmarking, 198.18.0.0/15
            o0 >= 240                                       // reserved, and broadcast
    }
    return false
}
