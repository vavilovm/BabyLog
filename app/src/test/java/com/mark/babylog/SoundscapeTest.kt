package com.mark.babylog

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mark.babylog.audio.soundscapes
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SoundscapeTest {
    @Test
    fun everyOfflineSoundHasLosslessAudioWithAQuietSeamAndHeadroom() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals(6, soundscapes.map { it.id }.toSet().size)
        soundscapes.forEach { sound ->
            val bytes = context.assets.open(sound.assetPath).use { it.readBytes() }
            val wav = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
            assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
            assertEquals(1, wav.getShort(20).toInt()) // Uncompressed PCM: no codec padding.
            assertEquals(1, wav.getShort(22).toInt())
            assertEquals(48000, wav.getInt(24))
            assertEquals(16, wav.getShort(34).toInt())
            assertEquals(40 * 48000 * 2, wav.getInt(40))
            val samples = ShortArray((bytes.size - 44) / 2) { wav.getShort(44 + it * 2) }
            var energy = 0.0
            var deltaEnergy = 0.0
            samples.forEachIndexed { i, value ->
                assertTrue("${sound.id} clips", abs(value.toInt()) < 30000)
                energy += value.toDouble() * value
                if (i > 0) {
                    val delta = value.toDouble() - samples[i - 1]
                    deltaEnergy += delta * delta
                }
            }
            assertTrue("${sound.id} is silent", sqrt(energy / samples.size) > 1500)
            val seam = abs(samples.first().toDouble() - samples.last())
            assertTrue(
                "${sound.id} clicks at the seam",
                seam < 4 * sqrt(deltaEnergy / (samples.size - 1)),
            )
            val item = sound.mediaItem()
            assertEquals("asset:///${sound.assetPath}", item.localConfiguration?.uri.toString())
            assertEquals(sound.title, item.mediaMetadata.title)
        }
    }
}
