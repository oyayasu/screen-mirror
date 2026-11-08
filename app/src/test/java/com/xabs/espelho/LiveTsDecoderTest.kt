package com.xabs.espelho

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** Verifica que os segmentos HLS produzidos podem ser abertos por um decodificador real. */
class LiveTsDecoderTest {
    private fun command(vararg args: String): Pair<Int, String> {
        val process = ProcessBuilder(*args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(25, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw AssertionError("Comando demorou demais: " + args.joinToString(" "))
        }
        return process.exitValue() to output
    }

    @Test
    fun generatedTransportStreamIsRecognizedAndDecodable() {
        val binary = try { command("ffmpeg", "-version").first == 0 } catch (_: Exception) { false }
        assumeTrue("ffmpeg não instalado no ambiente", binary)
        val directory = Files.createTempDirectory("espelho-ts-probe").toFile()
        try {
            val h264 = File(directory, "frame.h264")
            val encoding = command(
                "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
                "-f", "lavfi", "-i", "color=c=red:s=320x180:r=25",
                "-frames:v", "1", "-c:v", "libx264", "-preset", "ultrafast",
                "-pix_fmt", "yuv420p", "-f", "h264", h264.absolutePath
            )
            assertEquals("Falha ao produzir H.264: ${encoding.second}", 0, encoding.first)
            val sample = h264.readBytes()
            assertTrue(sample.isNotEmpty())
            val muxer = LiveTsMuxer()
            for (i in 0..4) muxer.addVideo(sample, i * 2_000_000L, true)
            val data = muxer.getSegment(1) ?: error("Segmento MPEG-TS não produzido")
            assertEquals(0, data.size % 188)
            val path = File(directory, "segment.ts")
            path.writeBytes(data)
            val probe = command(
                "ffprobe", "-v", "error", "-show_entries", "stream=codec_name",
                "-of", "default=nokey=1:noprint_wrappers=1", path.absolutePath
            )
            assertEquals("FFprobe falhou: ${probe.second}", 0, probe.first)
            assertTrue("FFprobe não identificou H.264: ${probe.second}", probe.second.contains("h264"))
            val decode = command(
                "ffmpeg", "-hide_banner", "-v", "error", "-xerror", "-i", path.absolutePath,
                "-frames:v", "1", "-f", "null", "-"
            )
            assertEquals("FFmpeg não decodificou o segmento: ${decode.second}", 0, decode.first)
        } finally {
            directory.deleteRecursively()
        }
    }
}
