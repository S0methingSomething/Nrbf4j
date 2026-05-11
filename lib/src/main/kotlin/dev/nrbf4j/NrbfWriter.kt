package dev.nrbf4j

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal const val OBJECT_NULL_MULTIPLE_256_MAX = 256

/**
 * Definition of a class member for [NrbfWriter.classWithMembersAndTypes].
 */
data class MemberDef(
    /** Member name as it appears in the NRBF stream. */
    val name: String,
    /** Binary type of the member. */
    val binaryType: BinaryType,
    /**
     * Additional type info.
     *
     * - [BinaryType.Primitive] / [BinaryType.PrimitiveArray] → [PrimitiveType]
     * - [BinaryType.SystemClass] → String (class name)
     * - [BinaryType.Class] → Pair<String, Int> (typeName, libraryId)
     * - others → null
     */
    val additionalInfo: Any? = null,
)

/**
 * Streaming NRBF (BinaryFormatter) byte-stream builder.
 *
 * Writes individual records into an internal buffer.  Callers compose a
 * complete stream by calling [beginStream] first, then any sequence of
 * record-writing methods, followed by [messageEnd].
 *
 * Record-writing methods are defined as extension functions in
 * [NrbfWriterRecords.kt][dev.nrbf4j].
 *
 * ```
 * val writer = NrbfWriter()
 * writer.beginStream()
 * writer.binaryObjectString(1, "Hello")
 * writer.messageEnd()
 * writer.writeTo(File("out.bin"))
 * ```
 */
class NrbfWriter {
    @PublishedApi internal val buf = mutableListOf<Byte>()

    // region Stream framing --------------------------------------------------------

    /**
     * Writes the mandatory [SerializedStreamHeader][RecordType.SerializedStreamHeader]
     * record.  Must be the first record in every NRBF stream.
     */
    fun beginStream(
        majorVersion: Int = 1,
        minorVersion: Int = 0,
    ): NrbfWriter {
        buf.add(RecordType.SerializedStreamHeader.id.toByte())
        buf.addAll(writeInt32Le(1))
        buf.addAll(writeInt32Le(1))
        buf.addAll(writeInt32Le(majorVersion))
        buf.addAll(writeInt32Le(minorVersion))
        return this
    }

    /** Appends the [MessageEnd][RecordType.MessageEnd] record. */
    fun messageEnd(): NrbfWriter {
        buf.add(RecordType.MessageEnd.id.toByte())
        return this
    }

    // endregion

    // region Output -----------------------------------------------------------------

    /** Raw accumulated bytes. */
    fun toByteArray(): ByteArray = buf.toByteArray()

    /** Writes the accumulated bytes to [file]. */
    fun writeTo(file: File) {
        file.writeBytes(toByteArray())
    }

    // endregion

    // region Internal helpers -------------------------------------------------------

    internal fun encodePrimitiveBytes(
        type: PrimitiveType,
        value: Any,
    ): List<Byte> =
        ByteBuffer
            .allocate(type.byteSize.coerceAtLeast(8))
            .order(
                ByteOrder.LITTLE_ENDIAN,
            ).also { buf ->
                when (type) {
                    PrimitiveType.Boolean -> {
                        buf.put(if (value as Boolean) 1 else 0)
                    }

                    PrimitiveType.Byte -> {
                        buf.put((value as Int).toByte())
                    }

                    PrimitiveType.SByte -> {
                        buf.put((value as Int).toByte())
                    }

                    PrimitiveType.Char -> {
                        buf.putChar((value as Int).toChar())
                    }

                    PrimitiveType.Int16 -> {
                        buf.putShort((value as Int).toShort())
                    }

                    PrimitiveType.UInt16 -> {
                        buf.putChar((value as Int).toChar())
                    }

                    PrimitiveType.Int32 -> {
                        buf.putInt(value as Int)
                    }

                    PrimitiveType.UInt32 -> {
                        buf.putInt(value as Int)
                    }

                    PrimitiveType.Int64 -> {
                        buf.putLong(value as Long)
                    }

                    PrimitiveType.UInt64 -> {
                        buf.putLong(value as Long)
                    }

                    PrimitiveType.Single -> {
                        buf.putFloat(value as Float)
                    }

                    PrimitiveType.Double -> {
                        buf.putDouble(value as Double)
                    }

                    PrimitiveType.TimeSpan -> {
                        buf.putLong(value as Long)
                    }

                    PrimitiveType.DateTime -> {
                        buf.putLong(value as Long)
                    }

                    PrimitiveType.Decimal,
                    PrimitiveType.String,
                    -> {
                        throw NrbfException(
                            "String and Decimal are variable-length — encode externally",
                        )
                    }

                    PrimitiveType.Null,
                    PrimitiveType.Reserved4,
                    -> { /* zero bytes */ }
                }
            }.array()
            .take(type.byteSize.coerceAtLeast(0))
            .toList()
}

// endregion
