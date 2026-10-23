package com.nanote.ink.engine

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/**
 * Binary stroke storage. Layout after DEFLATE:
 *
 *   magic INK1 (u32) | version (u16 = 1) | stroke count (u32)
 *   per stroke: id (i32) | pen ordinal (u8) | argb (i32) | baseWidth (f32)
 *               | point count (u32) | count * 4 f32 (x, y, pressure, tMs)
 *
 * Pure JVM (java.io + java.util.zip) so serialization round-trips are tested
 * outside of Robolectric.
 */
object InkFormat {
    private const val MAGIC = 0x494E4B31 // "INK1"
    const val VERSION = 1

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
                d.writeInt(s.count)
                for (f in s.pts) d.writeFloat(f)
            }
        }
        return bos.toByteArray()
    }

    @Throws(IOException::class, IllegalStateException::class)
    fun decode(bytes: ByteArray): List<Stroke> =
        DataInputStream(BufferedInputStream(InflaterInputStream(ByteArrayInputStream(bytes)))).use { d ->
            require(d.readInt() == MAGIC) { "Not an InkFormat blob (bad magic)" }
            val version = d.readShort().toInt()
            check(version == VERSION) { "Unsupported InkFormat version $version" }
            val count = d.readInt()
            check(count >= 0) { "Negative stroke count" }
            List(count) {
                val id = d.readInt()
                val pen = PenType.entries[d.readByte().toInt()]
                val argb = d.readInt()
                val w = d.readFloat()
                val n = d.readInt()
                check(n >= 0) { "Negative point count" }
                Stroke(id, pen, argb, w, FloatArray(n * STRIDE) { d.readFloat() })
            }
        }
}
