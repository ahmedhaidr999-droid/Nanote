package com.nanote.ink.engine

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/**
 * Binary stroke storage — versioned with forward migration (spec 8.2).
 *
 * Layout after DEFLATE:
 *
 *   v1 (Nanote ≤ 1.4.0):
 *     magic INK1 (u32) | version u16 = 1 | stroke count (u32)
 *     per stroke: id (i32) | pen ordinal (u8) | argb (i32) | baseWidth (f32)
 *                 | point count (u32) | count * 4 f32 (x, y, pressure, tMs)
 *
 *   v2 (Nanote 2.0.0 — adds the per-stroke highlighter opacity):
 *     magic INK1 (u32) | version u16 = 2 | stroke count (u32)
 *     per stroke: id (i32) | pen ordinal (u8) | argb (i32) | baseWidth (f32)
 *                 | alphaPercent (u8, 0..100; 0 = legacy default)
 *                 | point count (u32) | count * 4 f32 (x, y, pressure, tMs)
 *
 * Compatibility contract:
 *  - [decode] reads BOTH v1 and v2 blobs (v1 strokes get alphaPercent = 0 and
 *    render exactly as they did in v1 — the legacy 35% highlighter default).
 *  - [decodeRecovering] additionally salvages what it can from damaged blobs:
 *    each stroke is parsed independently; a truncated or corrupt tail stops
 *    parsing and reports how many strokes were dropped, instead of failing
 *    the whole page (partial recovery, spec 8.2).
 *  - A blob claiming an unknown NEWER version is best-effort parsed as v2
 *    (forward tolerance); the version number is reported to the caller.
 *
 * Pure JVM (java.io + java.util.zip) so serialization round-trips are tested
 * outside of Robolectric.
 */
object InkFormat {
    private const val MAGIC = 0x494E4B31 // "INK1"
    const val VERSION = 2
    const val LEGACY_VERSION = 1

    /** Result of a fault-tolerant decode (partial recovery, spec 8.2). */
    data class DecodeResult(
        val strokes: List<Stroke>,
        val version: Int,
        /** Strokes that could not be parsed and were skipped. */
        val dropped: Int,
        /** True when the blob ended mid-stroke (truncated write/crash). */
        val truncated: Boolean
    )

    fun encode(strokes: List<Stroke>): ByteArray {
        val bos = ByteArrayOutputStream(256)
        DataOutputStream(BufferedOutputStream(DeflaterOutputStream(bos))).use { d ->
            d.writeInt(MAGIC)
            d.writeShort(VERSION)
            d.writeInt(strokes.size)
            for (s in strokes) {
                d.writeInt(s.id)
                d.writeByte(s.pen.ordinal)
                d.writeInt(s.argb)
                d.writeFloat(s.baseWidth)
                d.writeByte(s.alphaPercent.coerceIn(0, 100))
                d.writeInt(s.count)
                for (f in s.pts) d.writeFloat(f)
            }
        }
        return bos.toByteArray()
    }

    /** Strict decode of v1/v2 blobs; throws on damaged input. */
    @Throws(IOException::class, IllegalStateException::class)
    fun decode(bytes: ByteArray): List<Stroke> {
        DataInputStream(BufferedInputStream(InflaterInputStream(ByteArrayInputStream(bytes)))).use { d ->
            require(d.readInt() == MAGIC) { "Not an InkFormat blob (bad magic)" }
            val version = d.readShort().toInt()
            check(version == VERSION || version == LEGACY_VERSION) {
                "Unsupported InkFormat version $version"
            }
            val count = d.readInt()
            check(count >= 0) { "Negative stroke count" }
            return List(count) { readStroke(d, version) }
        }
    }

    /**
     * Fault-tolerant decode: parses strokes one by one; the first stroke that
     * fails to parse (truncation or corruption) ends the salvage. Never
     * throws for per-stroke damage — only for a completely unusable header.
     */
    fun decodeRecovering(bytes: ByteArray): DecodeResult {
        try {
            DataInputStream(BufferedInputStream(InflaterInputStream(ByteArrayInputStream(bytes)))).use { d ->
                val magic = d.readInt()
                if (magic != MAGIC) {
                    return DecodeResult(emptyList(), 0, 0, truncated = false)
                }
                var version = d.readShort().toInt()
                // Forward tolerance: an unknown future version is parsed as v2.
                if (version != LEGACY_VERSION && version != VERSION) version = VERSION
                val count = d.readInt()
                if (count < 0) return DecodeResult(emptyList(), version, 0, truncated = false)
                val out = ArrayList<Stroke>(minOf(count, 1024))
                for (i in 0 until count) {
                    try {
                        out.add(readStroke(d, version))
                    } catch (e: EOFException) {
                        return DecodeResult(out, version, count - out.size, truncated = true)
                    } catch (e: Exception) {
                        return DecodeResult(out, version, count - out.size, truncated = true)
                    }
                }
                return DecodeResult(out, version, 0, truncated = false)
            }
        } catch (e: Exception) {
            return DecodeResult(emptyList(), 0, 0, truncated = false)
        }
    }

    /** Reads one stroke in the given wire version. */
    private fun readStroke(d: DataInputStream, version: Int): Stroke {
        val id = d.readInt()
        val pen = PenType.entries[d.readByte().toInt()]
        val argb = d.readInt()
        val w = d.readFloat()
        val alpha = if (version >= 2) d.readByte().toInt().coerceIn(0, 100) else 0
        val n = d.readInt()
        check(n >= 0) { "Negative point count" }
        check(n < 4_000_000) { "Implausible point count" }
        return Stroke(id, pen, argb, w, FloatArray(n * STRIDE) { d.readFloat() }, alpha)
    }
}
