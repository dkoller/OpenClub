package org.openclub.api.auth

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

internal object BrowserOrigin {
    /** WHATWG-style IPv6 spelling: lowercase hex, compress the first longest zero run. */
    fun canonicalIpv6(literal: String): String {
        require('%' !in literal) { "IPv6 zone identifiers are not valid browser origins" }
        // Java represents IPv4-mapped IPv6 as Inet4Address. Restore the
        // 96-bit ::ffff: prefix rather than incorrectly rejecting a valid
        // bracketed IPv6 URL authority.
        val address = InetAddress.getByName(literal)
        val bytes = when (address) {
            is Inet6Address -> address.address
            is Inet4Address -> ByteArray(16).also { mapped ->
                mapped[10] = 0xff.toByte()
                mapped[11] = 0xff.toByte()
                address.address.copyInto(mapped, destinationOffset = 12)
            }
            else -> throw IllegalArgumentException("Expected an IPv6 address literal")
        }
        val groups = IntArray(8) { i ->
            (bytes[i * 2].toInt() and 0xff) * 256 + (bytes[i * 2 + 1].toInt() and 0xff)
        }
        var bestStart = -1
        var bestLength = 1
        var i = 0
        while (i < 8) {
            if (groups[i] != 0) {
                i++
                continue
            }
            val start = i
            while (i < 8 && groups[i] == 0) i++
            if (i - start > bestLength) {
                bestStart = start
                bestLength = i - start
            }
        }
        if (bestStart < 0) return groups.joinToString(":") { it.toString(16) }
        val left = (0 until bestStart).joinToString(":") { groups[it].toString(16) }
        val right = (bestStart + bestLength until 8).joinToString(":") { groups[it].toString(16) }
        return when {
            left.isEmpty() -> "::$right"
            right.isEmpty() -> "$left::"
            else -> "$left::$right"
        }
    }

}
