package com.xabs.espelho

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** A CU7700 espera H.264/AAC válidos dentro de MPEG-TS. */
class LiveTsAudioTest {
    private fun command(vararg args: String): Pair<Int, String> {
        val process = ProcessBuilder(*args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(25, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("Comando expirou: " + args.joinToString(" "))
        }
        return process.exitValue() to output
    }

    @Test
    fun mpegTsContainsDecodableVideoAndAac() {
        assumeTrue(try { command("ffmpeg", "-version").first == 0 } catch (_: Exception) { false })
        val dir = Files.createTempDirectory("espelho-av").toFile()
        try {
            val h264 = File(dir, "video.h264")
            val aac = File(dir, "audio.aac")
            val videoResult = command(
                "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
                "-f", "lavfi", "-i", "color=black:s=320x180:r=25",
                "-frames:v", "1", "-c:v", "libx264", "-preset", "ultrafast",
                "-pix_fmt", "yuv420p", "-f", "h264", h264.absolutePath
            )
            assertEquals(videoResult.second, 0, videoResult.first)
            val audioResult = command(
                "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
                "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=1",
                "-ac", "2", "-c:a", "aac", "-b:a", "128k", "-f", "adts", aac.absolutePath
            )
            assertEquals(audioResult.second, 0, audioResult.first)
            val stream = aac.readBytes()
            val mux = LiveTsMuxer()
            mux.addVideo(h264.readBytes(), 0L, true)
            var at = 0
            var frame = 0
            while (at + 7 <= stream.size) {
                val b0 = stream[at].toInt() and 255
                val b1 = stream[at + 1].toInt() and 255
                if (b0 != 0xff || b1 and 0xf0 != 0xf0) break
                val headerSize = if (b1 and 1 == 1) 7 else 9
                val n = ((stream[at + 3].toInt() and 3) shl 11) or
                    ((stream[at + 4].toInt() and 255) shl 3) or
                    ((stream[at + 5].toInt() and 0xe0) shr 5)
                if (n <= headerSize || at + n > stream.size) break
                val raw = stream.copyOfRange(at + headerSize, at + n)
                val ptsUs = frame.toLong() * 1_024_000_000L / 48_000L
                mux.addAudio(raw, ptsUs)
                at += n
                frame++
            }
            assertTrue("AAC original não gerou quadros válidos", frame > 10)
            mux.addVideo(h264.readBytes(), 2_000_000L, true)
            val ts = File(dir, "live.ts")
            ts.writeBytes(mux.getSegment(0) ?: error("Não gerou segmento HLS"))
            val probe = command(
                "ffprobe", "-v", "error", "-show_entries", "stream=codec_name",
                "-of", "default=nokey=1:noprint_wrappers=1", ts.absolutePath
            )
            assertEquals("FFprobe: " + probe.second, 0, probe.first)
            assertTrue("H.264 não encontrado: " + probe.second, probe.second.contains("h264"))
            assertTrue("AAC não encontrado: " + probe.second, probe.second.contains("aac"))
            val decode = command(
                "ffmpeg", "-hide_banner", "-loglevel", "error", "-xerror",
                "-i", ts.absolutePath, "-map", "0:v:0", "-frames:v", "1",
                "-map", "0:a:0", "-frames:a", "5", "-f", "null", "-"
            )
            assertEquals("FFmpeg não reproduziu A/V: " + decode.second, 0, decode.first)
        } finally {
            dir.deleteRecursively()
        }
    }
}
