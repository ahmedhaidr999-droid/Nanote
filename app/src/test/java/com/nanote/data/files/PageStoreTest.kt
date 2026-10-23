package com.nanote.data.files

import com.google.common.truth.Truth.assertThat
import com.nanote.domain.model.LinkObj
import com.nanote.domain.model.LinkTarget
import com.nanote.domain.model.TextBox
import com.nanote.domain.model.identityMatrix
import com.nanote.ink.engine.Mat3
import com.nanote.ink.engine.PenType
import com.nanote.ink.engine.strokeOf
import org.junit.Test
import java.io.File
import kotlin.random.Random

class PageStoreTest {

    private fun newStore(): PageStore {
        val tmp = File("/tmp", "nanote-test-" + Random.nextLong().toString(16))
        tmp.mkdirs()
        return PageStore(tmp)
    }

    private val stroke = strokeOf(1, PenType.FOUNTAIN, 0xFF123456.toInt(), 2.5f, floatArrayOf(1f, 2f, 3f, 0.5f, 16f, 5f, 3f, 0.6f, 32f))
    private val textBox = TextBox("o1", "Hello نانوت", identityMatrix(), 0, 0xFF0000FF.toInt(), 18f)
    private val link = LinkObj("o2", LinkTarget.UrlTarget("https://example.org"), 10f, 20f, 1, "example")

    @Test
    fun `save then load round trips strokes and objects`() {
        val store = newStore()
        val nb = "nb1"
        val page = "pg1"
        store.save(nb, page, listOf(stroke), listOf(textBox, link))

        val loaded = store.load(nb, page)
        assertThat(loaded.strokes).isEqualTo(listOf(stroke))
        assertThat(loaded.objects).containsExactly(textBox, link).inOrder()
        assertThat(store.listPageIds(nb)).containsExactly(page)
    }

    @Test
    fun `corrupt main ink file falls back to bak`() {
        val store = newStore()
        val nb = "nb2"
        val page = "pg2"
        store.save(nb, page, listOf(stroke), listOf(textBox))
        store.save(nb, page, listOf(stroke), listOf(textBox)) // second save -> .bak exists

        // corrupt the main file, keep the .bak intact
        store.pageInk(nb, page).writeBytes(byteArrayOf(9, 9, 9, 9))

        val loaded = store.load(nb, page)
        assertThat(loaded.strokes).isEqualTo(listOf(stroke))
    }

    @Test
    fun `missing files yield empty page`() {
        val store = newStore()
        val loaded = store.load("ghost", "ghost-page")
        assertThat(loaded.strokes).isEmpty()
        assertThat(loaded.objects).isEmpty()
    }

    @Test
    fun `no tmp file left behind after save`() {
        val store = newStore()
        store.save("nb3", "pg3", listOf(stroke), emptyList())
        val dir = store.pagesDir("nb3")
        val names = dir.listFiles()!!.map { it.name }
        assertThat(names.any { it.endsWith(".tmp") }).isFalse()
    }

    @Test
    fun `deletePage removes ink json and siblings`() {
        val store = newStore()
        store.save("nb4", "pg4", listOf(stroke), emptyList())
        store.save("nb4", "pg4", listOf(stroke), emptyList()) // second save -> .bak exists
        store.deletePage("nb4", "pg4")
        assertThat(store.pagesDir("nb4").listFiles()).isEmpty()
    }
}

class StrokeTransformTest {

    @Test
    fun `transformed stroke maps points and scales width`() {
        val s = strokeOf(1, PenType.BALLPOINT, 0xFF000000.toInt(), 2f, floatArrayOf(0f, 0f, 100f, 50f, 200f, 50f))
        val m = Mat3()
        m.postScale(2f, 2f, 0f, 0f)
        m.postTranslate(10f, 20f)

        val t = s.transformed(m, 99)
        assertThat(t.id).isEqualTo(99)
        assertThat(t.x(0)).isWithin(1e-3f).of(10f)
        assertThat(t.y(0)).isWithin(1e-3f).of(20f)
        assertThat(t.x(2)).isWithin(1e-3f).of(410f)
        assertThat(t.y(2)).isWithin(1e-3f).of(120f)
        // width scale = sqrt(|det|) = 2
        assertThat(t.baseWidth).isWithin(1e-3f).of(4f)
    }
}
