package com.themoon.y1.cast

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL

/**
 * Finds the players on the Wi-Fi: Sonos and other DLNA players by SSDP, Google Cast devices (Chromecast,
 * Google TV) by mDNS. Both searches send from a normal UDP port, so the answers come back to it directly
 * (no multicast lock). A device with Cast and DLNA (a Google TV) shows once, as Cast: Cast shows the cover
 * and does not ask for a confirmation on the TV.
 */
object CastDiscovery {
    private const val TAG = "CastDiscovery"

    /** Blocks for about [timeoutMs]: call it from a background thread. */
    fun discover(timeoutMs: Int = 2500): List<CastDevice> {
        val upnp = ArrayList<CastDevice>()
        val cast = ArrayList<CastDevice>()
        val t1 = Thread { val r = searchSsdp(timeoutMs); synchronized(upnp) { upnp.addAll(r) } }
        val t2 = Thread { val r = searchMdns(timeoutMs); synchronized(cast) { cast.addAll(r) } }
        t1.start(); t2.start()
        t1.join(timeoutMs + 4000L); t2.join(timeoutMs + 1000L)
        val castHosts = synchronized(cast) { cast.map { it.host }.toSet() }
        val all = synchronized(cast) { cast.toList() } + synchronized(upnp) { upnp.filter { it.host !in castHosts } }
        return all.sortedBy { it.name.lowercase() }
    }

    private fun searchSsdp(timeoutMs: Int): List<CastDevice> {
        val found = LinkedHashMap<String, Pair<String, String>>() // location -> (location, server)
        try {
            val socket = DatagramSocket()
            try {
                socket.soTimeout = 300
                val msg = CastProtocol.SSDP_SEARCH.toByteArray()
                val group = InetAddress.getByName("239.255.255.250")
                repeat(2) { socket.send(DatagramPacket(msg, msg.size, group, 1900)) }
                val end = System.currentTimeMillis() + timeoutMs
                val buf = ByteArray(4096)
                while (System.currentTimeMillis() < end) {
                    val p = DatagramPacket(buf, buf.size)
                    try { socket.receive(p) } catch (_: SocketTimeoutException) { continue }
                    val r = CastProtocol.parseSsdpResponse(String(p.data, 0, p.length, Charsets.UTF_8)) ?: continue
                    found[r.first] = r
                }
            } finally {
                socket.close()
            }
        } catch (e: Exception) {
            Log.w(TAG, "SSDP: $e")
        }
        return found.values.mapNotNull { (location, server) ->
            try {
                CastProtocol.parseDescription(httpGet(location), location, server)
            } catch (e: Exception) {
                Log.w(TAG, "description $location: $e")
                null
            }
        }.distinctBy { it.id }
    }

    private fun searchMdns(timeoutMs: Int): List<CastDevice> {
        val found = LinkedHashMap<String, CastDevice>()
        try {
            val socket = DatagramSocket()
            try {
                socket.soTimeout = 300
                val q = CastProtocol.mdnsQuery()
                val group = InetAddress.getByName("224.0.0.251")
                repeat(2) { socket.send(DatagramPacket(q, q.size, group, 5353)) }
                val end = System.currentTimeMillis() + timeoutMs
                val buf = ByteArray(9000)
                while (System.currentTimeMillis() < end) {
                    val p = DatagramPacket(buf, buf.size)
                    try { socket.receive(p) } catch (_: SocketTimeoutException) { continue }
                    val host = p.address.hostAddress ?: continue
                    for (c in CastProtocol.parseMdnsCast(p.data, p.length)) {
                        val name = c.friendlyName ?: c.instance.substringBefore('.')
                        val d = CastDevice(name, CastDevice.Kind.CAST, host, c.port)
                        found[d.id] = d
                    }
                }
            } finally {
                socket.close()
            }
        } catch (e: Exception) {
            Log.w(TAG, "mDNS: $e")
        }
        return found.values.toList()
    }

    fun httpGet(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 2000
        c.readTimeout = 3000
        try {
            return c.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
        } finally {
            c.disconnect()
        }
    }
}
