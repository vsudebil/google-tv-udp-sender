package com.wled.tvaudiosync.network

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * UDP síťová vrstva pro WLED Audio Sync (výchozí port 11988).
 * Podporuje Broadcast v lokální síti i přímou Unicast IP adresu ESP32.
 */
class UdpAudioSender {

    private var socket: DatagramSocket? = null
    private var cachedHost: String? = null
    private var cachedAddress: InetAddress? = null
    private var lastSentNs: Long = 0L

    fun open() {
        close()
        socket = DatagramSocket().apply {
            broadcast = true
            reuseAddress = true
        }
    }

    fun sendIfDue(payload: ByteArray, host: String, port: Int, targetFps: Int): Boolean {
        val sock = socket ?: return false
        val nowNs = System.nanoTime()
        val minIntervalNs = 1_000_000_000L / targetFps.coerceIn(20, 60)
        if (nowNs - lastSentNs < minIntervalNs) {
            return false
        }

        val addr = resolveAddress(host) ?: return false
        return try {
            val packet = DatagramPacket(payload, payload.size, addr, port.coerceIn(1, 65535))
            sock.send(packet)
            lastSentNs = nowNs
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun resolveAddress(host: String): InetAddress? {
        val clean = host.trim().ifEmpty { "255.255.255.255" }
        if (clean == cachedHost && cachedAddress != null) {
            return cachedAddress
        }
        return runCatching {
            val resolved = InetAddress.getByName(clean)
            cachedHost = clean
            cachedAddress = resolved
            resolved
        }.getOrNull()
    }

    fun close() {
        runCatching { socket?.close() }
        socket = null
        cachedHost = null
        cachedAddress = null
    }
}
