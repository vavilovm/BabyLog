package com.mark.babylog

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mark.babylog.audio.soundscapes
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.max
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SoundscapeTest {
    @Test
    fun offlineAssetsMatchVerifiedAudioAndPreserveStereoRecordings() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manifest =
            JSONObject(
                context.assets.open("soundscapes/sources.json").bufferedReader().use {
                    it.readText()
                }
            )
        val measured = manifest.getJSONArray("measurements")
        assertEquals(6, soundscapes.map { it.id }.toSet().size)
        assertEquals(6, measured.length())
        soundscapes.forEach { sound ->
            val report =
                (0 until measured.length())
                    .map { measured.getJSONObject(it) }
                    .single { it.getString("id") == sound.id }
            val bytes = context.assets.open(sound.assetPath).use { it.readBytes() }
            assertEquals("fLaC", String(bytes, 0, 4, Charsets.US_ASCII))
            assertEquals("FLAC STREAMINFO must be first", 0, bytes[4].toInt() and 0x7f)
            val format = ByteBuffer.wrap(bytes, 18, 8).order(ByteOrder.BIG_ENDIAN).long
            val sampleRate = (format ushr 44).toInt()
            val channels = ((format ushr 41) and 7).toInt() + 1
            val bits = ((format ushr 36) and 31).toInt() + 1
            val frames = format and ((1L shl 36) - 1)
            assertEquals(16, bits)
            assertEquals(report.getInt("sample_rate"), sampleRate)
            assertEquals(report.getInt("channels"), channels)
            assertEquals(
                report.getDouble("duration_seconds"),
                frames.toDouble() / sampleRate,
                .00001,
            )
            assertTrue(frames.toDouble() / sampleRate >= 20)
            assertEquals(report.getString("file"), sound.assetPath.substringAfterLast('/'))
            val sha =
                MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                    "%02x".format(it)
                }
            assertEquals(
                "Audio must match its decoded quality report",
                report.getString("sha256"),
                sha,
            )
            assertTrue(abs(report.getDouble("lufs") + 27) < .75)
            assertTrue(report.getDouble("peak_dbfs") <= -5.9)
            assertTrue(
                report.getDouble("seam_max") < max(.002, 6 * report.getDouble("adjacent_delta_rms"))
            )
            if (sound.id in setOf("rain", "fire", "ocean", "brook")) assertEquals(2, channels)
            if (sound.id in setOf("rain", "fire", "ocean", "brook"))
                assertTrue(frames.toDouble() / sampleRate > 100)
            val item = sound.mediaItem()
            assertEquals("asset:///${sound.assetPath}", item.localConfiguration?.uri.toString())
            assertEquals(sound.title, item.mediaMetadata.title)
            assertEquals(sound.author, item.mediaMetadata.artist)
        }
    }

    @Test
    fun fieldRecordingsHaveBundledAuthorSourceAndRedistributionLicense() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sources =
            JSONObject(
                    context.assets.open("soundscapes/sources.json").bufferedReader().use {
                        it.readText()
                    }
                )
                .getJSONArray("recordings")
        assertEquals(4, sources.length())
        for (index in 0 until sources.length()) {
            val source = sources.getJSONObject(index)
            val sound = soundscapes.single { it.id == source.getString("id") }
            assertEquals(sound.author, source.getString("author"))
            assertTrue(source.getString("source_url").startsWith("https://"))
            assertTrue(source.getString("source_sha256").matches(Regex("[0-9a-f]{64}")))
            assertTrue(
                source.getString("license") in setOf("CC BY 4.0", "CC0 1.0", "Public Domain")
            )
            assertTrue(source.getString("changes").contains("crossfade"))
        }
        listOf("CC-BY-4.0.txt", "CC0-1.0.txt").forEach { file ->
            assertTrue(
                context.assets.open("soundscapes/licenses/$file").use { it.readBytes() }.size > 500
            )
        }
    }
}
