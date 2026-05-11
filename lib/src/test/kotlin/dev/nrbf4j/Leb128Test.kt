package dev.nrbf4j

import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class Leb128Test {
    @Test
    fun decodeSmallValues() {
        assertEquals(0 to 1, decode7BitLength(ByteBuffer.wrap(byteArrayOf(0))))
        assertEquals(1 to 1, decode7BitLength(ByteBuffer.wrap(byteArrayOf(1))))
        assertEquals(0x7F to 1, decode7BitLength(ByteBuffer.wrap(byteArrayOf(0x7F))))
    }

    @Test
    fun decodeTwoByteValues() {
        assertEquals(0x80 to 2, decode7BitLength(ByteBuffer.wrap(byteArrayOf(0x80.toByte(), 0x01))))
        assertEquals(0x3FFF to 2, decode7BitLength(ByteBuffer.wrap(byteArrayOf(0xFF.toByte(), 0x7F))))
    }

    @Test
    fun encodeSmallValues() {
        assertContentEquals(byteArrayOf(0), encode7BitLength(0))
        assertContentEquals(byteArrayOf(1), encode7BitLength(1))
        assertContentEquals(byteArrayOf(0x7F), encode7BitLength(0x7F))
    }

    @Test
    fun encodeMultiByte() {
        assertContentEquals(byteArrayOf(0x80.toByte(), 0x01), encode7BitLength(0x80))
        assertContentEquals(byteArrayOf(0xFF.toByte(), 0x7F), encode7BitLength(0x3FFF))
    }

    @Test
    fun encodeDecodeRoundTrip() {
        for (v in listOf(0, 1, 5, 100, 0x7F, 0x80, 0x3FFF, 1_000_000)) {
            val encoded = encode7BitLength(v)
            val decoded = decode7BitLength(ByteBuffer.wrap(encoded))
            assertEquals(v, decoded.first, "round-trip failed for $v")
            assertEquals(encoded.size, decoded.second, "byte count mismatch for $v")
        }
    }

    @Test
    fun encodeRejectsNegative() {
        assertFailsWith<IllegalArgumentException> { encode7BitLength(-1) }
    }

    @Test
    fun encodeRejectsOverflow() {
        assertFailsWith<IllegalArgumentException> { encode7BitLength(0x80000000.toInt()) }
    }

    @Test
    fun decodeTruncatedInput() {
        assertFailsWith<NrbfFormatException> {
            decode7BitLength(ByteBuffer.wrap(byteArrayOf(0x80.toByte())))
        }
    }

    @Test
    fun lengthPrefixedStringEncode() {
        val encoded = encodeLengthPrefixedString("AB")
        assertEquals(3, encoded.size)
        assertEquals(2.toByte(), encoded[0])
        assertEquals('A'.code.toByte(), encoded[1])
        assertEquals('B'.code.toByte(), encoded[2])
    }

    @Test
    fun lengthPrefixedStringRoundTrip() {
        for (s in listOf("", "hello", "Hello, World!", "a".repeat(300))) {
            val encoded = encodeLengthPrefixedString(s)
            val buf = ByteBuffer.wrap(encoded)
            val (length, prefixSize) = decode7BitLength(buf)
            val decodedStr = String(encoded, prefixSize, length, java.nio.charset.StandardCharsets.UTF_8)
            assertEquals(s, decodedStr)
        }
    }

    @Test
    fun decodeLengthPrefixedStringAt() {
        val data = byteArrayOf(3, 'F'.code.toByte(), 'o'.code.toByte(), 'o'.code.toByte(), 0x42)
        val (result, consumed) = decodeLengthPrefixedStringAt(data, 0)
        assertEquals("Foo", result)
        assertEquals(4, consumed)
    }

    private fun assertContentEquals(
        expected: ByteArray,
        actual: ByteArray,
    ) {
        assertEquals(expected.toList(), actual.toList())
    }
}
