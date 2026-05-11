package dev.nrbf4j

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PrimitivesTest {
    @Test
    fun readInt32LeEdgeCases() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x7F)
        assertEquals(Int.MAX_VALUE, readInt32At(bytes, 0))

        val bytes2 = byteArrayOf(0x00, 0x00, 0x00, 0x80.toByte())
        assertEquals(Int.MIN_VALUE, readInt32At(bytes2, 0))
    }

    @Test
    fun readInt32LeZero() {
        assertEquals(0, readInt32At(ByteArray(4), 0))
        assertEquals(42, readInt32At(byteArrayOf(42, 0, 0, 0), 0))
    }

    @Test
    fun decodeNull() {
        val (value, consumed) = decodePrimitiveValue(ByteArray(1), 0, PrimitiveType.Null)
        assertNull(value)
        assertEquals(0, consumed)
    }

    @Test
    fun decodeBoolean() {
        val (vTrue, _) = decodePrimitiveValue(byteArrayOf(1), 0, PrimitiveType.Boolean)
        assertEquals(true, vTrue)
        val (vFalse, _) = decodePrimitiveValue(byteArrayOf(0), 0, PrimitiveType.Boolean)
        assertEquals(false, vFalse)
    }

    @Test
    fun decodeInt32() {
        val bytes = byteArrayOf(0x78, 0x56, 0x34, 0x12)
        val (value, consumed) = decodePrimitiveValue(bytes, 0, PrimitiveType.Int32)
        assertEquals(0x12345678, value)
        assertEquals(4, consumed)
    }

    @Test
    fun decodeInt64() {
        val bytes = ByteArray(8)
        bytes[0] = 0x01
        val (value, consumed) = decodePrimitiveValue(bytes, 0, PrimitiveType.Int64)
        assertEquals(1L, value)
        assertEquals(8, consumed)
    }

    @Test
    fun decodeSingleFloat() {
        val bytes = byteArrayOf(0, 0, 0x80.toByte(), 0x3F)
        val (value, consumed) = decodePrimitiveValue(bytes, 0, PrimitiveType.Single)
        assertEquals(1.0f, value as Float, 0.0001f)
        assertEquals(4, consumed)
    }

    @Test
    fun decodeDouble() {
        val buf =
            java.nio.ByteBuffer
                .allocate(8)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.putDouble(Math.PI)
        val (value, consumed) = decodePrimitiveValue(buf.array(), 0, PrimitiveType.Double)
        assertEquals(Math.PI, value as Double, 0.0001)
        assertEquals(8, consumed)
    }

    @Test
    fun primitiveByteSize() {
        assertEquals(1, primitiveByteSize(PrimitiveType.Boolean))
        assertEquals(4, primitiveByteSize(PrimitiveType.Int32))
        assertEquals(8, primitiveByteSize(PrimitiveType.Int64))
    }

    @Test
    fun decodeAllPrimitives() {
        assertEquals(0xFF, decodePrimitiveValue(byteArrayOf(0xFF.toByte()), 0, PrimitiveType.Byte).first)
        assertEquals(-1, decodePrimitiveValue(byteArrayOf(0xFF.toByte()), 0, PrimitiveType.SByte).first)
        assertEquals(0x41, decodePrimitiveValue(byteArrayOf(0x41, 0x00), 0, PrimitiveType.Char).first)
        assertEquals(0x7FFF, decodePrimitiveValue(byteArrayOf(0xFF.toByte(), 0x7F), 0, PrimitiveType.Int16).first)
        assertEquals(
            0xFFFF,
            decodePrimitiveValue(byteArrayOf(0xFF.toByte(), 0xFF.toByte()), 0, PrimitiveType.UInt16).first,
        )
        val u32 =
            decodePrimitiveValue(
                byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()),
                0,
                PrimitiveType.UInt32,
            ).first
        assertEquals(0xFFFF_FFFFL, u32)
        assertEquals(1000L, decodePrimitiveValue(longBytes(1000), 0, PrimitiveType.TimeSpan).first)
        assertEquals(
            637728000000000000L,
            decodePrimitiveValue(longBytes(637728000000000000L), 0, PrimitiveType.DateTime).first,
        )
    }

    @Test
    fun encodePrimitiveBytesRoundTrip() {
        val w = NrbfWriter()
        assertEquals(42.toByte(), w.encodePrimitiveBytes(PrimitiveType.Byte, 42)[0])
        assertEquals(1.toByte(), w.encodePrimitiveBytes(PrimitiveType.Boolean, true)[0])
        assertEquals(0.toByte(), w.encodePrimitiveBytes(PrimitiveType.Boolean, false)[0])

        val fltBytes = w.encodePrimitiveBytes(PrimitiveType.Single, 2.5f)
        assertEquals(4, fltBytes.size)
        val fltDecoded =
            decodePrimitiveValue(fltBytes, 0, PrimitiveType.Single).first as Float
        assertEquals(2.5f, fltDecoded, 0.0001f)

        val dblBytes = w.encodePrimitiveBytes(PrimitiveType.Double, Math.PI)
        assertEquals(8, dblBytes.size)
    }

    private fun longBytes(value: Long): ByteArray {
        val buf =
            java.nio.ByteBuffer
                .allocate(8)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.putLong(value)
        return buf.array()
    }
}
