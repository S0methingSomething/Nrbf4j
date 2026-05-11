package dev.nrbf4j

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

private const val UNSIGNED_BYTE_MASK = 0xFF
private const val LEB128_CONTINUE_MASK = 0x80
private const val LEB128_VALUE_MASK = 0x7F
private const val LEB128_SHIFT_STEP = 7
private const val LEB128_MAX_BYTES = 5
private const val LEB128_MAX_5TH_VALUE = 0x07
private const val LEB128_MAX_INT32 = 0x7FFFFFFF

/**
 * Decodes a 7-bit encoded (LEB128) length prefix from the buffer.
 *
 * Returns a pair of (decodedValue, bytesConsumed).
 * Throws [NrbfFormatException] if the encoding is malformed or overflows.
 */
fun decode7BitLength(buf: ByteBuffer): Pair<Int, Int> {
    val (result, bytesConsumed, error) = decode7BitLengthInner(buf)
    if (error != null) throw NrbfFormatException(error)
    return result to bytesConsumed
}

private data class Leb128Result(
    val value: Int,
    val bytesConsumed: Int,
    val error: String?,
)

private fun decode7BitLengthInner(buf: ByteBuffer): Leb128Result {
    var result = 0
    var shift = 0

    for (bytesRead in 1..LEB128_MAX_BYTES) {
        if (!buf.hasRemaining()) {
            return Leb128Result(0, 0, "truncated LEB128 length at byte $bytesRead")
        }

        val byte = buf.get().toInt() and UNSIGNED_BYTE_MASK
        result = result or ((byte and LEB128_VALUE_MASK) shl shift)

        if ((byte and LEB128_CONTINUE_MASK) == 0) {
            val error =
                if (bytesRead == LEB128_MAX_BYTES && byte > LEB128_MAX_5TH_VALUE) {
                    "LEB128 length exceeds Int32 range"
                } else {
                    null
                }
            return Leb128Result(result, bytesRead, error)
        }

        shift += LEB128_SHIFT_STEP
    }

    return Leb128Result(0, 0, "LEB128 length exceeds 5 bytes")
}

/**
 * Encodes a non-negative integer as a 7-bit encoded (LEB128) byte sequence.
 *
 * Throws [NrbfException] if [value] is outside the valid Int32 range.
 */
fun encode7BitLength(value: Int): ByteArray {
    require(value in 0..LEB128_MAX_INT32) {
        "length out of range: $value"
    }

    val result = mutableListOf<Byte>()
    var remaining = value

    while (remaining >= LEB128_CONTINUE_MASK) {
        result.add(((remaining and LEB128_VALUE_MASK) or LEB128_CONTINUE_MASK).toByte())
        remaining = remaining shr LEB128_SHIFT_STEP
    }

    result.add(remaining.toByte())
    return result.toByteArray()
}

/**
 * Decodes a BinaryFormatter length-prefixed UTF-8 string from the buffer.
 *
 * First reads the LEB128-encoded byte length, then reads that many bytes
 * and decodes as UTF-8. Returns the decoded string.
 */
fun decodeLengthPrefixedString(buf: ByteBuffer): String {
    val (length, _) = decode7BitLength(buf)

    if (length > buf.remaining()) {
        throw NrbfFormatException(
            "length-prefixed string claims $length bytes, but only ${buf.remaining()} remain",
        )
    }

    val bytes = ByteArray(length)
    buf.get(bytes)
    return String(bytes, StandardCharsets.UTF_8)
}

/**
 * Decodes a length-prefixed string from a byte array at a specific offset.
 *
 * Returns a pair of (decodedString, totalBytesConsumed) where totalBytesConsumed
 * includes both the LEB128 prefix bytes and the UTF-8 string bytes.
 */
fun decodeLengthPrefixedStringAt(
    data: ByteArray,
    offset: Int,
): Pair<String, Int> {
    val buf = ByteBuffer.wrap(data, offset, data.size - offset)
    val (length, prefixSize) = decode7BitLength(buf)

    val valueOffset = offset + prefixSize
    val endOffset = valueOffset + length

    if (endOffset > data.size) {
        throw NrbfFormatException("length-prefixed string at offset $offset extends beyond data")
    }

    val value = String(data, valueOffset, length, StandardCharsets.UTF_8)
    return value to (prefixSize + length)
}

/**
 * Encodes a string as a BinaryFormatter length-prefixed UTF-8 byte sequence.
 */
fun encodeLengthPrefixedString(value: String): ByteArray {
    val encoded = value.toByteArray(StandardCharsets.UTF_8)
    val lengthPrefix = encode7BitLength(encoded.size)
    return lengthPrefix + encoded
}
