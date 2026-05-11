package dev.nrbf4j

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal const val BYTE_MASK = 0xFF
internal const val UINT32_MASK = 0xFFFF_FFFFL

/**
 * Reads a little-endian Int32 from [data] at [offset].
 */
fun readInt32At(
    data: ByteArray,
    offset: Int,
): Int = ByteBuffer.wrap(data, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

/**
 * Returns the fixed byte size for [type].
 * Throws if the type is variable-length (String, Decimal).
 */
internal fun primitiveByteSize(type: PrimitiveType): Int {
    val sz = type.byteSize
    require(sz > 0) { "no fixed size for ${type.name}" }
    return sz
}

/**
 * Decodes one primitive NRBF value at [offset] and returns (value, bytesConsumed).
 *
 * Variable-length types (String, Decimal) are decoded via length-prefixed UTF-8.
 * This function handles inlined primitive members (BinaryType.Primitive) and
 * MemberPrimitiveTyped record bodies.
 */
fun decodePrimitiveValue(
    data: ByteArray,
    offset: Int,
    type: PrimitiveType,
): Pair<Any?, Int> {
    val buf = ByteBuffer.wrap(data, offset, data.size - offset).order(ByteOrder.LITTLE_ENDIAN)

    return when (type) {
        PrimitiveType.Null -> {
            null to 0
        }

        PrimitiveType.String,
        PrimitiveType.Decimal,
        -> {
            val (value, consumed) = decodeLengthPrefixedStringAt(data, offset)
            value to consumed
        }

        PrimitiveType.Boolean -> {
            (buf.get() != 0.toByte()) to 1
        }

        PrimitiveType.Byte -> {
            (buf.get().toInt() and BYTE_MASK) to 1
        }

        PrimitiveType.SByte -> {
            buf.get().toInt() to 1
        }

        PrimitiveType.Char -> {
            buf.char.code to 2
        }

        PrimitiveType.Int16 -> {
            buf.short.toInt() to 2
        }

        PrimitiveType.UInt16 -> {
            buf.char.code to 2
        }

        PrimitiveType.Int32 -> {
            buf.int to 4
        }

        PrimitiveType.UInt32 -> {
            (buf.int.toLong() and UINT32_MASK) to 4
        }

        PrimitiveType.Int64 -> {
            buf.long to 8
        }

        PrimitiveType.UInt64 -> {
            buf.long to 8
        }

        PrimitiveType.Single -> {
            buf.float to 4
        }

        PrimitiveType.Double -> {
            buf.double to 8
        }

        PrimitiveType.TimeSpan,
        PrimitiveType.DateTime,
        -> {
            buf.long to 8
        }

        PrimitiveType.Reserved4 -> {
            throw NrbfFormatException("reserved primitive type at offset $offset")
        }
    }
}

/**
 * Writes an Int32 as little-endian bytes.
 */
internal fun writeInt32Le(value: Int): List<Byte> {
    val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
    buf.putInt(value)
    return buf.array().toList()
}

internal fun writeInt32LeTo(
    value: Int,
    out: ByteArrayOutputStream,
) {
    val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
    buf.putInt(value)
    out.write(buf.array())
}

/**
 * Copies [size] bytes from [data] starting at [offset] into [out].
 */
internal fun copyRaw(
    data: ByteArray,
    offset: Int,
    size: Int,
    out: ByteArrayOutputStream,
) {
    out.write(data, offset, size)
}

/**
 * Copies [size] bytes from [data] starting at [offset] into [out].
 */
internal fun copyRaw(
    data: ByteArray,
    offset: Int,
    size: Int,
    out: MutableList<Byte>,
) {
    for (i in 0 until size) {
        out.add(data[offset + i])
    }
}
