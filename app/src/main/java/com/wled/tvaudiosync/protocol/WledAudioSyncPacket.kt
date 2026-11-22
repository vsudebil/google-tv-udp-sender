package com.wled.tvaudiosync.protocol

import com.wled.tvaudiosync.data.WledProtocolVersion
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Binární serializace paketů kompatibilních s WLED AudioReactive usermodem
 * (usermods/audioreactive/audio_reactive.h).
 *
 * Odesílají se výhradně zvuková / FFT data — nikdy hotové LED barvy (DDP / E1.31).
 */
class WledAudioSyncPacket {

    private val v2Buffer: ByteBuffer = ByteBuffer.allocate(V2_PACKET_SIZE).order(ByteOrder.LITTLE_ENDIAN)
    private val v1Buffer: ByteBuffer = ByteBuffer.allocate(V1_PACKET_SIZE).order(ByteOrder.LITTLE_ENDIAN)

    /**
     * Sestaví 44bajtový paket WLED Audio Sync v2 ("00002\0"):
     *  0..5   char[6]   header ("00002\0")
     *  6..7   uint8[2]  reserved1 (0x00, 0x00)
     *  8..11  float     sampleRaw (0..255)
     * 12..15  float     sampleSmth (0..255)
     * 16      uint8     samplePeak (0 nebo >= 1)
     * 17      uint8     frameCounter (0..255)
     * 18..33  uint8[16] fftResult (16 GEQ pásem, 0..255)
     * 34..35  uint16    zeroCrossingCount
     * 36..39  float     FFT_Magnitude
     * 40..43  float     FFT_MajorPeak (Hz, 1.0..11025.0)
     */
    @Synchronized
    fun encode(
        version: WledProtocolVersion,
        sampleRaw: Float,
        sampleSmth: Float,
        samplePeak: Int,
        frameCounter: Int,
        fftBins: ByteArray,
        zeroCrossingCount: Int,
        fftMagnitude: Float,
        fftMajorPeakHz: Float
    ): ByteArray {
        require(fftBins.size == 16) { "WLED vyžaduje přesně 16 FFT pásem." }
        return when (version) {
            WledProtocolVersion.V2_00002 -> encodeV2(
                sampleRaw, sampleSmth, samplePeak, frameCounter,
                fftBins, zeroCrossingCount, fftMagnitude, fftMajorPeakHz
            )
            WledProtocolVersion.V1_00001 -> encodeV1(
                sampleRaw, sampleSmth, samplePeak,
                fftBins, fftMagnitude, fftMajorPeakHz
            )
        }
    }

    private fun encodeV2(
        sampleRaw: Float,
        sampleSmth: Float,
        samplePeak: Int,
        frameCounter: Int,
        fftBins: ByteArray,
        zeroCrossingCount: Int,
        fftMagnitude: Float,
        fftMajorPeakHz: Float
    ): ByteArray {
        v2Buffer.clear()
        v2Buffer.put(HEADER_V2)
        v2Buffer.put(0.toByte())
        v2Buffer.put(0.toByte())
        v2Buffer.putFloat(sampleRaw.coerceIn(0f, 255f))
        v2Buffer.putFloat(sampleSmth.coerceIn(0f, 255f))
        v2Buffer.put((samplePeak and 0xFF).toByte())
        v2Buffer.put((frameCounter and 0xFF).toByte())
        v2Buffer.put(fftBins, 0, 16)
        v2Buffer.putShort(zeroCrossingCount.coerceIn(0, 65535).toShort())
        v2Buffer.putFloat(fftMagnitude.coerceAtLeast(0f))
        v2Buffer.putFloat(fftMajorPeakHz.coerceIn(1f, 11025f))
        return v2Buffer.array().copyOf()
    }

    /**
     * Sestaví 83bajtový legacy paket WLED Audio Sync v1 ("00001\0") pro starší WLED v0.13.
     */
    private fun encodeV1(
        sampleRaw: Float,
        sampleSmth: Float,
        samplePeak: Int,
        fftBins: ByteArray,
        fftMagnitude: Float,
        fftMajorPeakHz: Float
    ): ByteArray {
        v1Buffer.clear()
        v1Buffer.put(HEADER_V1)
        for (i in 0 until 32) {
            v1Buffer.put(fftBins[i / 2])
        }
        v1Buffer.putInt(sampleSmth.toInt().coerceIn(0, 255))
        v1Buffer.putInt(sampleRaw.toInt().coerceIn(0, 255))
        v1Buffer.putFloat(sampleSmth.coerceIn(0f, 255f))
        v1Buffer.put(if (samplePeak > 0) 1.toByte() else 0.toByte())
        v1Buffer.put(fftBins, 0, 16)
        v1Buffer.putDouble(fftMagnitude.toDouble())
        v1Buffer.putDouble(fftMajorPeakHz.coerceIn(1f, 11025f).toDouble())
        return v1Buffer.array().copyOf()
    }

    companion object {
        const val V2_PACKET_SIZE = 44
        const val V1_PACKET_SIZE = 83
        private val HEADER_V2 = byteArrayOf(
            '0'.code.toByte(), '0'.code.toByte(), '0'.code.toByte(),
            '0'.code.toByte(), '2'.code.toByte(), 0
        )
        private val HEADER_V1 = byteArrayOf(
            '0'.code.toByte(), '0'.code.toByte(), '0'.code.toByte(),
            '0'.code.toByte(), '1'.code.toByte(), 0
        )
    }
}
