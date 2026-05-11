@file:Suppress("TooManyFunctions")

package dev.nrbf4j

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Opens a BinaryFormatter (.dat) file, scans its records, and provides
 * fast, low-allocation access to specific named members on specific objects.
 *
 * The scanner produces a flat [IntArray] index — no [IndexedRecord] objects
 * are allocated during scanning, keeping GC pressure near zero for the
 * list-view use case where several files are read sequentially.
 *
 * Designed for the summary-card use case: scan → cherry-pick a handful of
 * fields → close.  Not suitable for heavy editing workloads; use
 * [NrbfDocument] for that.
 *
 * Implements [AutoCloseable] so the caller can wrap it in `use {}`.
 */
class NrbfReader private constructor(
    private val data: ByteArray,
    private val index: IntArray,
    private val objectIdToRow: Map<Int, Int>,
    private val classLayouts: Map<Int, ClassLayout>,
) : AutoCloseable {
    /** Companion for [NrbfReader]. */
    companion object {
        /**
         * Opens [file], scans its NRBF stream in one pass, and returns a
         * ready-to-use [NrbfReader].
         *
         * Equivalent to `scanRecords(file.readBytes())` followed by the
         * constructor.
         */
        fun open(file: File): NrbfReader {
            val bytes = file.readBytes()
            val scan = scanRecords(bytes)
            return NrbfReader(bytes, scan.index, scan.objectIdToRow, scan.classLayouts)
        }
    }

    // region Row access utilities ----------------------------------------------------

    /** Compact record reference used for member lookups. */
    data class RecordInfo(
        /** Row index in the flat scan index. */
        val row: Int,
        /** Byte offset where this record starts. */
        val offset: Int,
        /** Byte size of this record. */
        val size: Int,
        /** NRBF record type. */
        val type: RecordType,
    )

    private fun recordOffset(row: Int): Int = index[row * INDEX_STRIDE + IX_OFFSET]

    private fun recordSize(row: Int): Int = index[row * INDEX_STRIDE + IX_SIZE]

    private fun recordMetadataId(row: Int): Int = index[row * INDEX_STRIDE + IX_METADATA_ID]

    private fun recordTypeId(row: Int): Int = index[row * INDEX_STRIDE + IX_RECORD_TYPE]

    // endregion

    // region Lookup ----------------------------------------------------------------

    /**
     * Looks up a record by its [objectId].
     *
     * @throws [ObjectNotFoundException] if the object is not in the stream.
     */
    fun recordById(objectId: Int): RecordInfo {
        val row = objectIdToRow[objectId] ?: throw ObjectNotFoundException("object $objectId not found")
        val offset = recordOffset(row)
        val size = recordSize(row)
        val typeId = recordTypeId(row)
        return RecordInfo(row, offset, size, RecordType.fromId(typeId))
    }

    // endregion

    // region Member read convenience -----------------------------------------------

    /** Reads and decodes a named member from the object identified by [objectId]. */
    fun readMember(
        objectId: Int,
        memberName: String,
    ): Any? {
        val rec = recordById(objectId)
        if (rec.type != RecordType.ClassWithId) {
            throw NrbfFormatException("cannot read member from record type ${rec.type.name}")
        }
        val metadataId = recordMetadataId(rec.row)
        val layout =
            classLayouts[metadataId]
                ?: throw NrbfFormatException("ClassWithId $objectId refs unknown metadata $metadataId")
        val idx = layout.memberNames.indexOf(memberName)
        if (idx < 0) {
            throw MemberNotFoundException("member '$memberName' not found on ${layout.className}")
        }
        return decodeMemberAt(data, rec.offset + 9, layout, idx, classLayouts.toMutableMap())
    }

    /** Reads a MemberReference field and returns the referenced objectId. */
    fun readMemberRef(
        objectId: Int,
        memberName: String,
    ): Int {
        val value =
            readMember(objectId, memberName)
                ?: throw NrbfException("member '$memberName' is null")
        return value as? Int ?: throw NrbfException(
            "member '$memberName' is not a reference (got $value)",
        )
    }

    // endregion

    // region Convenience methods for BitLife save files ----------------------------

    /**
     * Returns the full name (firstName + lastName) of the SimPersonName
     * object referenced from [personObjectId].
     */
    fun readPersonFullName(personObjectId: Int): String {
        val nameObjId = readMemberRef(personObjectId, "Name")
        val firstName = readMember(nameObjId, "FirstName") as? String ?: ""
        val lastName = readMember(nameObjId, "LastName") as? String ?: ""
        return "$firstName $lastName".trim()
    }

    /**
     * Reads the [heroFirstName] and [heroLastName] member values from the
     * `Name`-referenced SimPersonName object on the SimHero identified by
     * [heroObjectId].
     */
    fun readHeroNameDisplay(heroObjectId: Int): String = readPersonFullName(heroObjectId)

    // endregion

    // region Lifecycle --------------------------------------------------------------

    /**
     * Closes the reader, releasing the scan index and source buffer.
     *
     * After calling this, any further access will fail.
     */
    override fun close() {
        // data, index, objectIdToRow, classLayouts all become eligible for GC.
    }

    // endregion
}

// region Shared decode helpers -------------------------------------------------------

/**
 * Skips over [targetIndex] members of [layout] starting at [offset] and
 * returns the byte offset of the target member's value.
 *
 * All members *before* [targetIndex] are skipped via cursor arithmetic only;
 * no values are decoded.
 *
 * @return The byte offset of the [targetIndex]-th member's raw value (the
 *   value bytes for BinaryType.Primitive, or the record-type byte for
 *   nested-record types).
 */
internal fun skipMembersTo(
    data: ByteArray,
    offset: Int,
    layout: ClassLayout,
    targetIndex: Int,
): Int {
    var cursor = offset
    var remainingNulls = 0

    for (i in 0 until targetIndex) {
        if (remainingNulls > 0) {
            remainingNulls -= 1
            continue
        }

        val bt = layout.binaryTypes[i]
        when (bt) {
            BinaryType.Primitive -> {
                val primType = layout.additionalInfos[i] as PrimitiveType
                val (_, consumed) = decodePrimitiveValue(data, cursor, primType)
                cursor += consumed
            }

            else -> {
                val memberStart = cursor
                val typeByte = data[cursor].toInt() and 0xFF
                val subType = RecordType.fromId(typeByte)

                // Fast-skip the sub-record without a full recursive scanRecordEnd.
                cursor =
                    when (subType) {
                        RecordType.ObjectNull -> {
                            cursor + 1
                        }

                        RecordType.ObjectNullMultiple256 -> {
                            val nullCount = data[cursor + 1].toInt() and 0xFF
                            remainingNulls = nullCount - 1
                            cursor + 2
                        }

                        RecordType.ObjectNullMultiple -> {
                            remainingNulls = readInt32At(data, cursor + 1) - 1
                            cursor + 5
                        }

                        RecordType.MemberReference -> {
                            cursor + 5
                        }

                        RecordType.BinaryObjectString -> {
                            val (_, consumed) = decodeLengthPrefixedStringAt(data, cursor + 5)
                            cursor + 5 + consumed
                        }

                        else -> {
                            val result = scanRecordEnd(data, cursor, mutableMapOf())
                            result.endOffset
                        }
                    }
            }
        }
    }

    // Skip any remaining null runs that span past our target.
    var localNulls = remainingNulls
    while (localNulls > 0 && targetIndex < layout.memberNames.size) {
        localNulls -= 1
        if (localNulls == 0) break
    }

    return cursor
}

/**
 * Skips to the [targetIndex]-th member and FULLY DECODES it, returning the
 * decoded value.
 *
 * Used when a value is needed (not just its byte offset).
 */
internal fun decodeMemberAt(
    data: ByteArray,
    offset: Int,
    layout: ClassLayout,
    targetIndex: Int,
    classDefs: MutableMap<Int, ClassLayout>,
): Any? {
    var cursor = skipMembersTo(data, offset, layout, targetIndex)

    // Handle remaining null runs that span the target.
    var remainingNulls = 0
    for (i in 0 until targetIndex) {
        val bt = layout.binaryTypes[i]
        if (bt != BinaryType.Primitive) {
            val memberStart =
                when (i) {
                    0 -> offset
                    else -> cursor
                }
            val typeByte = data.getOrNull(memberStart)?.toInt()?.and(0xFF) ?: return null
            val subType = RecordType.fromId(typeByte)
            when (subType) {
                RecordType.ObjectNullMultiple256 -> {
                    val count = data.getOrNull(memberStart + 1)?.toInt()?.and(0xFF) ?: return null
                    if (targetIndex < i + count) {
                        remainingNulls = count - (targetIndex - i) - 1
                    }
                }

                RecordType.ObjectNullMultiple -> {
                    val count = readInt32At(data, memberStart + 1)
                    if (targetIndex < i + count) {
                        remainingNulls = count - (targetIndex - i) - 1
                    }
                }

                else -> {}
            }
        }
    }

    // Now at the target — decode fully.
    if (remainingNulls > 0) {
        return null
    }

    val bt = layout.binaryTypes[targetIndex]
    return when (bt) {
        BinaryType.Primitive -> {
            val primType = layout.additionalInfos[targetIndex] as PrimitiveType
            val (value, _) = decodePrimitiveValue(data, cursor, primType)
            value
        }

        BinaryType.String -> {
            val typeByte = data[cursor].toInt() and 0xFF
            when (RecordType.fromId(typeByte)) {
                RecordType.BinaryObjectString -> decodeLengthPrefixedStringAt(data, cursor + 5).first

                RecordType.ObjectNull, RecordType.ObjectNullMultiple256, RecordType.ObjectNullMultiple -> null

                else -> throw NrbfFormatException(
                    "unexpected record type $typeByte for String member at offset $cursor",
                )
            }
        }

        BinaryType.Class,
        BinaryType.Object,
        BinaryType.SystemClass,
        -> {
            val typeByte = data[cursor].toInt() and 0xFF
            when (RecordType.fromId(typeByte)) {
                RecordType.MemberReference -> readInt32At(data, cursor + 1)

                RecordType.ObjectNull, RecordType.ObjectNullMultiple256, RecordType.ObjectNullMultiple -> null

                else -> throw NrbfFormatException(
                    "unexpected record type $typeByte for reference member at offset $cursor",
                )
            }
        }

        BinaryType.ObjectArray,
        BinaryType.StringArray,
        BinaryType.PrimitiveArray,
        -> {
            val typeByte = data[cursor].toInt() and 0xFF
            val typeEnum = RecordType.fromId(typeByte)
            when (typeEnum) {
                RecordType.BinaryArray,
                RecordType.ArraySingleObject,
                RecordType.ArraySingleString,
                RecordType.ArraySinglePrimitive,
                -> {
                    val result = scanRecordEnd(data, cursor, classDefs)
                    result.objectId
                }

                RecordType.MemberReference -> {
                    readInt32At(data, cursor + 1)
                }

                RecordType.ObjectNull, RecordType.ObjectNullMultiple256, RecordType.ObjectNullMultiple -> {
                    null
                }

                else -> {
                    throw NrbfFormatException(
                        "unexpected record type $typeByte for array member at offset $cursor",
                    )
                }
            }
        }
    }
}

// endregion
