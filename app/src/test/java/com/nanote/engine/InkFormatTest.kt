package com.nanote.engine

import com.google.common.truth.Truth.assertThat
import com.nanote.ink.engine.InkFormat
import com.nanote.ink.engine.PenType
import com.nanote.ink.engine.Stroke
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.random.Random

class InkFormatTest {

    private fun randomStroke(rng: Random, id: Int): Stroke {
        val n = rng.nextInt(1, 50)
        val pts = FloatArray(n * 4)
        for (i in 0 until n) {
            pts[4 * i] = rng.nextFloat() * 595f
            pts[4 * i + 1] = rng.nextFloat() * 842f
            pts[4 * i + 2] = rng.nextFloat()
            pts[4 * i + 3] = rng.nextFloat() * 10_000f
        }
        return Stroke(id, PenType.entries[rng.nextInt(PenType.entries.size)], rng.nextInt(), rng.nextFloat() * 10f, pts)
    }

    @Test
    fun `round trip preserves every field exactly`() {
        val rng = Random(42)
        val strokes = List(100) { i -> randomStroke(rng, i + 1) }
        val decoded = InkFormat.decode(InkFormat.encode(strokes))
        assertThat(decoded).hasSize(strokes.size)
        strokes.zip(decoded).forEach { (a, b) ->
            assertThat(b.id).isEqualTo(a.id)
            assertThat(b.pen).isEqualTo(a.pen)
            assertThat(b.argb).isEqualTo(a.argb)
            assertThat(b.baseWidth).isEqualTo(a.baseWidth)
            assertThat(b.pts).isEqualTo(a.pts) // float array exact: values are float32 both ways
        }
    }

    @Test
    fun `compressed blob is smaller than raw points`() {
        // A long straight line should compress extremely well.
        val pts = FloatArray(2000)
        for (i in 0 until 500) {
            pts[4 * i] = i * 1.0f
            pts[4 * i + 1] = 0f
            pts[4 * i + 2] = 0.5f
            pts[4 * i + 3] = i * 8f
        }
        val s = Stroke(1, PenType.BALLPOINT, 0xFF000000.toInt(), 2f, pts)
        val blob = InkFormat.encode(listOf(s))
        assertThat(blob.size.toLong()).isLessThan(8000L)
    }

    @Test
    fun `bad magic is rejected`() {
        // Valid DEFLATE stream carrying a wrong magic -> require() fires.
        val raw = byteArrayOf(0, 0, 0, 0, 1, 0, 0, 0, 0, 0) // magic=0, version=1, count=0
        val bos = ByteArrayOutputStream()
        java.util.zip.DeflaterOutputStream(bos).use { it.write(raw) }
        assertThrows(IllegalArgumentException::class.java) { InkFormat.decode(bos.toByteArray()) }
    }

    @Test
    fun `garbage bytes are rejected`() {
        assertThrows(java.io.IOException::class.java) {
            InkFormat.decode(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        }
    }

    @Test
    fun `truncated blob throws instead of returning garbage`() {
        val strokes = List(50) { i ->
            randomStroke(Random(i), i)
        }
        val blob = InkFormat.encode(strokes)
        val cut = blob.copyOf(blob.size / 2)
        assertThrows(Exception::class.java) { InkFormat.decode(cut) }
    }
}
