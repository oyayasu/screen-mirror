package com.xabs.espelho

import java.io.ByteArrayOutputStream
import java.util.ArrayDeque
import java.util.Locale
import java.time.Instant
import kotlin.math.max

/** MPEG-TS local HLS: H.264 Annex B + AAC-LC 48 kHz stereo ADTS. */
internal class LiveTsMuxer {
    data class Segment(val id: Int, val durationSeconds: Double, val bytes: ByteArray, val startedAtMs: Long, val discontinuity: Boolean = false)
    private val lock = Any()
    private val segments = ArrayDeque<Segment>()
    private val buffer = ByteArrayOutputStream()
    private val continuity = IntArray(8192)
    private var nextId = 0
    private var segmentStartUs = -1L
    private var segmentStartWallMs = 0L
    private var lastVideoPtsUs = -1L
    private var pendingDiscontinuity = false
    private var removedDiscontinuities = 0
    private val targetDurationSeconds = 6
    private var videoSamples = 0
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null

    fun configureVideo(a: ByteArray?, b: ByteArray?) = synchronized(lock) {
        sps = a?.let { annexB(it) }
        pps = b?.let { annexB(it) }
    }

    fun addVideo(data: ByteArray, ptsUs: Long, key: Boolean) = synchronized(lock) {
        if (segmentStartUs < 0 && !key) return@synchronized
        if (key && segmentStartUs >= 0 && ptsUs - segmentStartUs >= 1_800_000L) {
            complete(max(0.5, (ptsUs - segmentStartUs) / 1_000_000.0))
            segmentStartUs = -1L
        }
        if (segmentStartUs < 0) {
            segmentStartUs = ptsUs
            segmentStartWallMs = System.currentTimeMillis()
            tables()
        }
        val output = ByteArrayOutputStream(data.size + 100)
        if (key) {
            sps?.let { output.write(it) }
            pps?.let { output.write(it) }
        }
        output.write(annexB(data))
        packetize(0x101, pes(0xE0, output.toByteArray(), ptsUs), ptsUs * 90L / 1000L)
        videoSamples++
        lastVideoPtsUs = ptsUs
    }

    /**
     * Close the previous format before a rotated AVC encoder starts.
     * HLS.js then recreates the decoder for the new SPS/PPS and aspect ratio.
     */
    fun videoFormatChanged() = synchronized(lock) {
        if (segmentStartUs >= 0 && videoSamples > 0) {
            complete(max(0.5, (lastVideoPtsUs - segmentStartUs) / 1_000_000.0))
        }
        segmentStartUs = -1L
        lastVideoPtsUs = -1L
        sps = null
        pps = null
        pendingDiscontinuity = true
        continuity.fill(0)
    }

    fun addAudio(aac: ByteArray, ptsUs: Long) = synchronized(lock) {
        if (segmentStartUs < 0 || ptsUs < segmentStartUs - 200_000L || aac.isEmpty()) return@synchronized
        packetize(0x102, pes(0xC0, adts(aac), ptsUs), null)
    }

    fun playlist(base: String): String = synchronized(lock) {
        val list = segments.toList()
        val first = list.firstOrNull()?.id ?: nextId
        buildString {
            // Live playlist: sliding window, no ENDLIST, fixed TARGETDURATION.
            append("#EXTM3U\n#EXT-X-VERSION:3\n")
            append("#EXT-X-TARGETDURATION:").append(targetDurationSeconds).append("\n")
            append("#EXT-X-MEDIA-SEQUENCE:").append(first).append("\n")
            append("#EXT-X-DISCONTINUITY-SEQUENCE:").append(removedDiscontinuities).append("\n")
            for (seg in list) {
                if (seg.discontinuity) append("#EXT-X-DISCONTINUITY\n")
                append("#EXT-X-PROGRAM-DATE-TIME:").append(Instant.ofEpochMilli(seg.startedAtMs)).append("\n")
                append("#EXTINF:")
                append(String.format(Locale.US, "%.3f", seg.durationSeconds))
                append(",\n")
                append(base).append("/segment-").append(seg.id).append(".ts\n")
            }
        }
    }

    fun getSegment(id: Int): ByteArray? = synchronized(lock) { segments.firstOrNull { it.id == id }?.bytes }
    fun segmentCount(): Int = synchronized(lock) { segments.size }
    fun latestSegmentId(): Int = synchronized(lock) { segments.peekLast()?.id ?: -1 }

    private fun complete(duration: Double) {
        if (videoSamples > 0) {
            segments.addLast(Segment(nextId++, duration, buffer.toByteArray(), segmentStartWallMs, pendingDiscontinuity))
            pendingDiscontinuity = false
            while (segments.size > 6) {
                val old = segments.removeFirst()
                if (old.discontinuity) removedDiscontinuities++
            }
        }
        buffer.reset()
        videoSamples = 0
    }

    private fun tables() {
        psi(0, byteArrayOf(
            0, 0xB0.toByte(), 0x0D, 0, 1, 0xC1.toByte(), 0, 0, 0, 1, 0xF0.toByte(), 0
        ))
        psi(0x1000, byteArrayOf(
            2, 0xB0.toByte(), 0x17, 0, 1, 0xC1.toByte(), 0, 0,
            0xE1.toByte(), 1, 0xF0.toByte(), 0,
            0x1B, 0xE1.toByte(), 1, 0xF0.toByte(), 0,
            0x0F, 0xE1.toByte(), 2, 0xF0.toByte(), 0
        ))
    }

    private fun psi(pid: Int, section: ByteArray) {
        var crc = -1
        for (v in section) {
            crc = crc xor ((v.toInt() and 255) shl 24)
            repeat(8) { crc = if (crc < 0) (crc shl 1) xor 0x04C11DB7 else crc shl 1 }
        }
        val data = ByteArrayOutputStream()
        data.write(0)
        data.write(section)
        for (shift in listOf(24, 16, 8, 0)) data.write((crc ushr shift) and 255)
        packetize(pid, data.toByteArray(), null)
    }

    private fun pes(streamId: Int, payload: ByteArray, us: Long): ByteArray {
        val out = ByteArrayOutputStream(payload.size + 32)
        val len = if (streamId == 0xE0 || payload.size + 8 > 65535) 0 else payload.size + 8
        out.write(byteArrayOf(0, 0, 1, streamId.toByte(), (len shr 8).toByte(), len.toByte(),
            0x80.toByte(), 0x80.toByte(), 5))
        val pts = (us * 90L / 1000L) and 0x1FFFFFFFFL
        out.write(0x21 or (((pts shr 30).toInt() and 7) shl 1))
        out.write((pts shr 22).toInt() and 255)
        out.write(1 or (((pts shr 15).toInt() and 127) shl 1))
        out.write((pts shr 7).toInt() and 255)
        out.write(1 or ((pts.toInt() and 127) shl 1))
        out.write(payload)
        return out.toByteArray()
    }

    private fun packetize(pid: Int, data: ByteArray, pcr90: Long?) {
        var offset = 0
        var first = true
        while (offset < data.size) {
            val packet = ByteArray(188) { 0xFF.toByte() }
            packet[0] = 0x47
            packet[1] = (((pid shr 8) and 31) or if (first) 0x40 else 0).toByte()
            packet[2] = pid.toByte()
            val withPcr = first && pcr90 != null
            val available = if (withPcr) 176 else 184
            val size = minOf(available, data.size - offset)
            val adapt = 184 - size
            val cc = continuity[pid] and 15
            continuity[pid] = (cc + 1) and 15
            packet[3] = ((if (adapt > 0) 0x30 else 0x10) or cc).toByte()
            var index = 4
            if (adapt > 0) {
                packet[index++] = (adapt - 1).toByte()
                if (adapt > 1) {
                    packet[index++] = if (withPcr) 0x10 else 0
                    if (withPcr) {
                        val base = (pcr90 ?: 0L) and 0x1FFFFFFFFL
                        packet[index++] = (base shr 25).toByte()
                        packet[index++] = (base shr 17).toByte()
                        packet[index++] = (base shr 9).toByte()
                        packet[index++] = (base shr 1).toByte()
                        packet[index++] = (((base and 1) shl 7) or 0x7EL).toByte()
                        packet[index++] = 0
                    }
                }
                index = 4 + adapt
            }
            System.arraycopy(data, offset, packet, index, size)
            buffer.write(packet)
            offset += size
            first = false
        }
    }

    private fun adts(aac: ByteArray): ByteArray {
        val n = aac.size + 7
        // AAC LC, sample-rate 48 kHz, channel config 2.
        return byteArrayOf(
            0xFF.toByte(), 0xF1.toByte(), 0x4C,
            (0x80 or ((n shr 11) and 3)).toByte(),
            ((n shr 3) and 255).toByte(),
            (((n and 7) shl 5) or 0x1F).toByte(), 0xFC.toByte()
        ) + aac
    }

    private fun annexB(raw: ByteArray): ByteArray {
        if (raw.size < 4) return raw
        if ((raw[0] == 0.toByte() && raw[1] == 0.toByte() && raw[2] == 1.toByte()) ||
            (raw[0] == 0.toByte() && raw[1] == 0.toByte() && raw[2] == 0.toByte() && raw[3] == 1.toByte())) return raw
        val out = ByteArrayOutputStream()
        var i = 0
        while (i + 4 <= raw.size) {
            val n = ((raw[i].toInt() and 255) shl 24) or
                ((raw[i+1].toInt() and 255) shl 16) or
                ((raw[i+2].toInt() and 255) shl 8) or (raw[i+3].toInt() and 255)
            if (n <= 0 || n > raw.size - i - 4) return raw
            out.write(byteArrayOf(0, 0, 0, 1))
            out.write(raw, i+4, n)
            i += 4+n
        }
        return if (i == raw.size && out.size() > 0) out.toByteArray() else raw
    }
}
