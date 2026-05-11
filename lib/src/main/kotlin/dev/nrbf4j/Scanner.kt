@file:Suppress("TooManyFunctions")

package dev.nrbf4j

/** Stride — each record entry occupies this many consecutive ints in the flat index. */
internal const val INDEX_STRIDE = 7

/** Row columns. */
internal const val IX_RECORD_TYPE = 0
internal const val IX_OFFSET = 1
internal const val IX_SIZE = 2
internal const val IX_OBJECT_ID = 3
internal const val IX_LIBRARY_ID = 4
internal const val IX_REFERENCE_ID = 5
internal const val IX_METADATA_ID = 6

/** Sentinel for absent optional fields within a row. */
internal const val NO_VALUE = -1

private const val MSG_FLAG_ARGS_INLINE = 0x00000002
private const val MSG_FLAG_CONTEXT_INLINE = 0x00000020
private const val MSG_FLAG_RETURN_VALUE_INLINE = 0x00000800

// region Public data types --------------------------------------------------------

/**
 * Describes the member layout of a class definition.
 *
 * Registered during scanning for [RecordType.ClassWithMembersAndTypes] and
 * [RecordType.SystemClassWithMembersAndTypes] records.  Later consumed when
 * decoding [RecordType.ClassWithId] records or skipping through members.
 */
data class ClassLayout(
    /** Class name from the NRBF stream. */
    val className: String,
    /** Names of all members declared by this class. */
    val memberNames: List<String>,
    /** Binary type identifiers for each member. */
    val binaryTypes: List<BinaryType>,
    /** Additional type-specific info for each member. */
    val additionalInfos: List<Any?>,
)

/**
 * Single result produced by [scanRecords].
 *
 * Contains the flat scan index, object-id lookup table, and class layout registry.
 */
data class ScanResult(
    /** Flat IntArray with stride [INDEX_STRIDE], one row per NRBF record. */
    val index: IntArray,
    /** Maps each object ID to its row index in [index]. */
    val objectIdToRow: Map<Int, Int>,
    /** Maps class-definition object IDs to their parsed [ClassLayout]. */
    val classLayouts: Map<Int, ClassLayout>,
)

// endregion

// region Top-level scan entry point -----------------------------------------------

/**
 * Scans a complete MS-NRBF binary stream and returns a flat indexed view of
 * every record together with all registered class layouts.
 *
 * The scan performs a single forward pass through [data].  For class-definition
 * records the member layout is registered so that later [RecordType.ClassWithId]
 * records can resolve their metadata references.
 */
fun scanRecords(data: ByteArray): ScanResult {
    val rows = mutableListOf<IntArray>()
    val objectIdToRow = mutableMapOf<Int, Int>()
    val classDefs = mutableMapOf<Int, ClassLayout>()

    var offset = 0
    val totalSize = data.size

    while (offset < totalSize) {
        val recordType = RecordType.fromId(data[offset].toInt() and BYTE_MASK)
        val result = scanRecordEnd(data, offset, classDefs)

        val rowPos = rows.size
        rows.add(
            intArrayOf(
                recordType.id,
                offset,
                result.endOffset - offset,
                result.objectId ?: NO_VALUE,
                result.libraryId ?: NO_VALUE,
                result.referenceId ?: NO_VALUE,
                result.metadataId ?: NO_VALUE,
            ),
        )

        if (result.objectId != null) {
            objectIdToRow[result.objectId] = rowPos
        }

        offset = result.endOffset

        if (recordType == RecordType.MessageEnd) {
            break
        }
    }

    require(offset == totalSize) {
        "scanner stopped at offset $offset, source length is $totalSize"
    }

    // Flatten into a single IntArray for GC-friendly storage.
    val flat = IntArray(rows.size * INDEX_STRIDE)
    for ((i, row) in rows.withIndex()) {
        val base = i * INDEX_STRIDE
        row.copyInto(flat, base, 0, INDEX_STRIDE)
    }

    return ScanResult(flat, objectIdToRow, classDefs)
}

// endregion

// region Record-boundary dispatcher -----------------------------------------------

/** Return value for [scanRecordEnd]. */
internal class ScanRecordResult(
    val endOffset: Int,
    val objectId: Int? = null,
    val libraryId: Int? = null,
    val referenceId: Int? = null,
    val metadataId: Int? = null,
)

/**
 * Computes the byte offset immediately after the NRBF record that starts at
 * [offset] and may register class-definition metadata in [classDefs].
 *
 * This is the central dispatcher — every record type has a specialised branch.
 */
internal fun scanRecordEnd(
    data: ByteArray,
    offset: Int,
    classDefs: MutableMap<Int, ClassLayout>,
): ScanRecordResult {
    val typeByte = data[offset].toInt() and BYTE_MASK
    return dispatchScan(data, offset, typeByte, classDefs)
}

private fun dispatchScan(
    data: ByteArray,
    offset: Int,
    typeByte: Int,
    classDefs: MutableMap<Int, ClassLayout>,
): ScanRecordResult =
    when (typeByte) {
        // -- fixed-size control records --
        0 -> {
            ScanRecordResult(offset + 17)
        }

        // SerializedStreamHeader
        11 -> {
            ScanRecordResult(offset + 1)
        }

        // MessageEnd
        10 -> {
            ScanRecordResult(offset + 1)
        }

        // ObjectNull
        13 -> {
            ScanRecordResult(offset + 2)
        }

        // ObjectNullMultiple256
        14 -> {
            ScanRecordResult(offset + 5)
        }

        // ObjectNullMultiple

        // -- simple length-prefixed records --
        12 -> { // BinaryLibrary
            val libraryId = readInt32At(data, offset + 1)
            val (_, consumed) = decodeLengthPrefixedStringAt(data, offset + 5)
            ScanRecordResult(offset + 5 + consumed, libraryId = libraryId)
        }

        6 -> { // BinaryObjectString
            val objectId = readInt32At(data, offset + 1)
            val (_, consumed) = decodeLengthPrefixedStringAt(data, offset + 5)
            ScanRecordResult(offset + 5 + consumed, objectId = objectId)
        }

        9 -> { // MemberReference
            ScanRecordResult(offset + 5, referenceId = readInt32At(data, offset + 1))
        }

        8 -> { // MemberPrimitiveTyped
            val primType = PrimitiveType.fromId(data[offset + 1].toInt() and BYTE_MASK)
            val (_, consumed) = decodePrimitiveValue(data, offset + 2, primType)
            ScanRecordResult(offset + 2 + consumed)
        }

        // -- array records --
        15 -> {
            scanArraySinglePrimitive(data, offset)
        }

        // ArraySinglePrimitive
        16 -> {
            scanArraySingleReference(data, offset, classDefs)
        }

        // ArraySingleObject
        17 -> {
            scanArraySingleReference(data, offset, classDefs)
        }

        // ArraySingleString
        7 -> {
            scanBinaryArray(data, offset, classDefs)
        }

        // BinaryArray

        // -- method records --
        21 -> {
            ScanRecordResult(skipMethodBody(data, offset))
        }

        // MethodCall
        22 -> {
            ScanRecordResult(skipMethodReturnBody(data, offset))
        }

        // MethodReturn

        // -- class records --
        4 -> {
            scanClassWithMembersAndTypes(data, offset, classDefs)
        }

        // SystemClassWithMembersAndTypes
        5 -> {
            scanClassWithMembersAndTypes(data, offset, classDefs)
        }

        // ClassWithMembersAndTypes
        1 -> {
            scanClassWithId(data, offset, classDefs)
        }

        // ClassWithId

        else -> {
            throw NrbfFormatException("unsupported record type id $typeByte at offset $offset")
        }
    }

// endregion

// region Array record helpers -----------------------------------------------------

private fun scanArraySinglePrimitive(
    data: ByteArray,
    offset: Int,
): ScanRecordResult {
    val objectId = readInt32At(data, offset + 1)
    val length = readInt32At(data, offset + 5)
    require(length >= 0)
    val primType = PrimitiveType.fromId(data[offset + 9].toInt() and BYTE_MASK)
    val elemSz = primitiveByteSize(primType)
    return ScanRecordResult(offset + 10 + (length * elemSz), objectId = objectId)
}

private fun scanArraySingleReference(
    data: ByteArray,
    offset: Int,
    classDefs: MutableMap<Int, ClassLayout>,
): ScanRecordResult {
    val objectId = readInt32At(data, offset + 1)
    val length = readInt32At(data, offset + 5)
    require(length >= 0)
    val end = skipRecordItems(data, offset + 9, length, classDefs)
    return ScanRecordResult(end, objectId = objectId)
}

private fun scanBinaryArray(
    data: ByteArray,
    offset: Int,
    classDefs: MutableMap<Int, ClassLayout>,
): ScanRecordResult {
    val header = parseBinaryArrayHeader(data, offset)
    val itemCount = header.lengths.fold(1) { acc, len -> acc * len }

    val end =
        if (header.typeEnum == BinaryType.Primitive || header.typeEnum == BinaryType.PrimitiveArray) {
            val elemSz = primitiveByteSize(header.additionalInfo as PrimitiveType)
            header.membersOffset + itemCount * elemSz
        } else {
            skipRecordItems(data, header.membersOffset, itemCount, classDefs)
        }

    return ScanRecordResult(end, objectId = header.objectId)
}

internal data class BinaryArrayHeader(
    val objectId: Int,
    val binaryArrayTypeId: Int,
    val rank: Int,
    val lengths: List<Int>,
    val lowerBounds: List<Int>,
    val typeEnum: BinaryType,
    val additionalInfo: Any?,
    val membersOffset: Int,
)

internal fun parseBinaryArrayHeader(
    data: ByteArray,
    offset: Int,
): BinaryArrayHeader {
    var cursor = offset + 1 // skip record-type byte

    val objectId = readInt32At(data, cursor)
    cursor += 4

    val arrayTypeId = data[cursor].toInt() and BYTE_MASK
    val withLowerBounds = arrayTypeId in VAL_BINARY_ARRAY_TYPES_WITH_LOWER_BOUNDS
    cursor += 1

    val rank = readInt32At(data, cursor)
    require(rank >= 0)
    cursor += 4

    val lengths = mutableListOf<Int>()
    for (i in 0 until rank) {
        val len = readInt32At(data, cursor)
        require(len >= 0)
        lengths.add(len)
        cursor += 4
    }

    val lowerBounds = mutableListOf<Int>()
    if (withLowerBounds) {
        for (i in 0 until rank) {
            lowerBounds.add(readInt32At(data, cursor))
            cursor += 4
        }
    }

    val typeEnum = BinaryType.fromId(data[cursor].toInt() and BYTE_MASK)
    cursor += 1

    val additionalInfo = parseBinaryTypeAdditionalInfo(data, cursor, typeEnum)
    cursor = additionalInfo.endOffset

    return BinaryArrayHeader(
        objectId,
        arrayTypeId,
        rank,
        lengths,
        lowerBounds,
        typeEnum,
        additionalInfo.value,
        cursor,
    )
}

private data class AdditionalInfoResult(
    val value: Any?,
    val endOffset: Int,
)

private fun parseBinaryTypeAdditionalInfo(
    data: ByteArray,
    offset: Int,
    typeEnum: BinaryType,
): AdditionalInfoResult =
    when (typeEnum) {
        BinaryType.Primitive, BinaryType.PrimitiveArray -> {
            AdditionalInfoResult(PrimitiveType.fromId(data[offset].toInt() and BYTE_MASK), offset + 1)
        }

        BinaryType.SystemClass -> {
            val (name, consumed) = decodeLengthPrefixedStringAt(data, offset)
            AdditionalInfoResult(name, offset + consumed)
        }

        BinaryType.Class -> {
            val (typeName, consumed) = decodeLengthPrefixedStringAt(data, offset)
            val libraryId = readInt32At(data, offset + consumed)
            AdditionalInfoResult(mapOf("type_name" to typeName, "library_id" to libraryId), offset + consumed + 4)
        }

        else -> {
            AdditionalInfoResult(null, offset)
        }
    }

// endregion

// region Class-record helpers -----------------------------------------------------

private fun scanClassWithMembersAndTypes(
    data: ByteArray,
    offset: Int,
    classDefs: MutableMap<Int, ClassLayout>,
): ScanRecordResult {
    val recordType = RecordType.fromId(data[offset].toInt() and 0xFF)
    var cursor = offset + 1

    // Header: object-id, class name, member count, member names.
    val classInfo = parseClassInfo(data, cursor)
    cursor = classInfo.endOffset

    // Per-member binary types and their additional info.
    val typeInfo = parseMemberTypeInfo(data, cursor, classInfo.memberNames.size)
    cursor = typeInfo.endOffset

    // Library id for non-system classes.
    var libraryId: Int? = null
    if (recordType == RecordType.ClassWithMembersAndTypes) {
        libraryId = readInt32At(data, cursor)
        cursor += 4
    }

    // Register layout so ClassWithId records can resolve later.
    classDefs[classInfo.objectId] =
        ClassLayout(
            className = classInfo.className,
            memberNames = classInfo.memberNames,
            binaryTypes = typeInfo.binaryTypes,
            additionalInfos = typeInfo.additionalInfos,
        )

    // Walk through member values to find the true end of this record.
    cursor =
        walkClassMembers(
            data,
            cursor,
            classInfo.memberNames,
            typeInfo.binaryTypes,
            typeInfo.additionalInfos,
            classDefs,
        )

    return ScanRecordResult(cursor, objectId = classInfo.objectId, libraryId = libraryId)
}

private fun scanClassWithId(
    data: ByteArray,
    offset: Int,
    classDefs: MutableMap<Int, ClassLayout>,
): ScanRecordResult {
    val objectId = readInt32At(data, offset + 1)
    val metadataId = readInt32At(data, offset + 5)

    val layout =
        classDefs[metadataId]
            ?: throw NrbfFormatException("ClassWithId $objectId references unknown metadata $metadataId")

    val end =
        walkClassMembers(
            data,
            offset + 9,
            layout.memberNames,
            layout.binaryTypes,
            layout.additionalInfos,
            classDefs,
        )

    return ScanRecordResult(end, objectId = objectId, metadataId = metadataId)
}

internal data class ParseClassInfoResult(
    val objectId: Int,
    val className: String,
    val memberNames: List<String>,
    val endOffset: Int,
)

internal data class ParseMemberTypeInfoResult(
    val binaryTypes: List<BinaryType>,
    val additionalInfos: List<Any?>,
    val endOffset: Int,
)

internal fun parseClassInfo(
    data: ByteArray,
    offset: Int,
): ParseClassInfoResult {
    val objectId = readInt32At(data, offset)
    val (className, nameLen) = decodeLengthPrefixedStringAt(data, offset + 4)
    var cursor = offset + 4 + nameLen

    val memberCount = readInt32At(data, cursor)
    require(memberCount >= 0)
    cursor += 4

    val memberNames = mutableListOf<String>()
    for (i in 0 until memberCount) {
        val (name, consumed) = decodeLengthPrefixedStringAt(data, cursor)
        memberNames.add(name)
        cursor += consumed
    }

    return ParseClassInfoResult(objectId, className, memberNames, cursor)
}

internal fun parseMemberTypeInfo(
    data: ByteArray,
    offset: Int,
    memberCount: Int,
): ParseMemberTypeInfoResult {
    var cursor = offset

    // Read binary-type bytes.
    val binaryTypes =
        (0 until memberCount).map {
            BinaryType.fromId(data[cursor + it].toInt() and BYTE_MASK)
        }
    cursor += memberCount

    // Read additional info per member.
    val additionalInfos =
        binaryTypes.map { bt ->
            when (bt) {
                BinaryType.Primitive, BinaryType.PrimitiveArray -> {
                    PrimitiveType.fromId(data[cursor].toInt() and BYTE_MASK).also { cursor += 1 }
                }

                BinaryType.SystemClass -> {
                    val (name, consumed) = decodeLengthPrefixedStringAt(data, cursor)
                    cursor += consumed
                    name
                }

                BinaryType.Class -> {
                    val (typeName, consumed) = decodeLengthPrefixedStringAt(data, cursor)
                    val libId = readInt32At(data, cursor + consumed)
                    cursor += consumed + 4
                    mapOf("type_name" to typeName, "library_id" to libId)
                }

                else -> {
                    null
                }
            }
        }

    return ParseMemberTypeInfoResult(binaryTypes, additionalInfos, cursor)
}

/**
 * Walks from [offset] through every member value in a class record.
 * Handles inlined primitives, nested sub-records, and null-run collapsing.
 * Returns the offset immediately after the last member.
 */
internal fun walkClassMembers(
    data: ByteArray,
    offset: Int,
    memberNames: List<String>,
    binaryTypes: List<BinaryType>,
    additionalInfos: List<Any?>,
    classDefs: MutableMap<Int, ClassLayout>,
): Int {
    var cursor = offset
    var remainingNulls = 0

    for (i in memberNames.indices) {
        if (remainingNulls > 0) {
            remainingNulls -= 1
            continue
        }

        val bt = binaryTypes[i]
        val ai = additionalInfos[i]

        when (bt) {
            BinaryType.Primitive -> {
                val primType = ai as PrimitiveType
                val (_, consumed) = decodePrimitiveValue(data, cursor, primType)
                cursor += consumed
            }

            else -> {
                // Every non-primitive member is a nested NRBF record.
                val memberStart = cursor
                @Suppress("UNUSED_EXPRESSION")
                data[cursor]
                val result = scanRecordEnd(data, cursor, classDefs)
                cursor = result.endOffset

                // Handle null runs produced by ObjectNullMultiple* sub-records.
                val subType = RecordType.fromId(data[memberStart].toInt() and 0xFF)
                when (subType) {
                    RecordType.ObjectNullMultiple256 -> {
                        remainingNulls = (data[memberStart + 1].toInt() and BYTE_MASK) - 1
                    }

                    RecordType.ObjectNullMultiple -> {
                        remainingNulls = readInt32At(data, memberStart + 1) - 1
                    }

                    else -> {}
                }
            }
        }
    }

    return cursor
}

// endregion

// region Record-item skipping (for arrays of objects/strings) -----------------------

/**
 * Fast-forwards past [itemCount] nested records starting at [offset].
 * Null-run collapsing is handled so ObjectNullMultiple* records consume the
 * correct number of subsequent slots.
 */
internal fun skipRecordItems(
    data: ByteArray,
    offset: Int,
    itemCount: Int,
    classDefs: MutableMap<Int, ClassLayout>,
): Int {
    var cursor = offset
    var remainingNulls = 0
    var consumed = 0

    while (consumed < itemCount) {
        consumed += 1

        if (remainingNulls > 0) {
            remainingNulls -= 1
            continue
        }

        val subType = RecordType.fromId(data[cursor].toInt() and BYTE_MASK)

        // For simple fixed-size records, skip without full scanRecordEnd.
        when (subType) {
            RecordType.ObjectNull -> {
                cursor += 1
            }

            RecordType.ObjectNullMultiple256 -> {
                remainingNulls = (data[cursor + 1].toInt() and BYTE_MASK) - 1
                cursor += 2
            }

            RecordType.ObjectNullMultiple -> {
                remainingNulls = readInt32At(data, cursor + 1) - 1
                cursor += 5
            }

            RecordType.MemberReference -> {
                cursor += 5
            }

            else -> {
                val result = scanRecordEnd(data, cursor, classDefs)
                cursor = result.endOffset
            }
        }
    }

    return cursor
}

// endregion

// region Method body helpers -------------------------------------------------------

/** Skips past a MethodCall record body. */
internal fun skipMethodBody(
    data: ByteArray,
    offset: Int,
): Int {
    val flags = readInt32At(data, offset + 1)
    var cursor = offset + 5

    // method name (String value-with-code).
    cursor = skipValueWithCode(data, cursor)
    // type name (String value-with-code).
    cursor = skipValueWithCode(data, cursor)

    if ((flags and MSG_FLAG_CONTEXT_INLINE) != 0) {
        cursor = skipValueWithCode(data, cursor)
    }
    if ((flags and MSG_FLAG_ARGS_INLINE) != 0) {
        cursor = skipArrayOfValueWithCode(data, cursor)
    }

    return cursor
}

/** Skips past a MethodReturn record body. */
internal fun skipMethodReturnBody(
    data: ByteArray,
    offset: Int,
): Int {
    val flags = readInt32At(data, offset + 1)
    var cursor = offset + 5

    if ((flags and MSG_FLAG_RETURN_VALUE_INLINE) != 0) {
        cursor = skipValueWithCode(data, cursor)
    }
    if ((flags and MSG_FLAG_CONTEXT_INLINE) != 0) {
        cursor = skipValueWithCode(data, cursor)
    }
    if ((flags and MSG_FLAG_ARGS_INLINE) != 0) {
        cursor = skipArrayOfValueWithCode(data, cursor)
    }
    return cursor
}

private fun skipValueWithCode(
    data: ByteArray,
    offset: Int,
): Int {
    val primType = PrimitiveType.fromId(data[offset].toInt() and BYTE_MASK)
    return when (primType) {
        PrimitiveType.Null -> {
            offset + 1
        }

        PrimitiveType.String -> {
            val (_, consumed) = decodeLengthPrefixedStringAt(data, offset + 1)
            offset + 1 + consumed
        }

        else -> {
            val (_, consumed) = decodePrimitiveValue(data, offset + 1, primType)
            offset + 1 + consumed
        }
    }
}

private fun skipArrayOfValueWithCode(
    data: ByteArray,
    offset: Int,
): Int {
    val count = readInt32At(data, offset)
    require(count >= 0)
    var cursor = offset + 4
    for (i in 0 until count) {
        cursor = skipValueWithCode(data, cursor)
    }
    return cursor
}

// endregion

// region Binary-array type constants -----------------------------------------------

private val VAL_BINARY_ARRAY_TYPES_WITH_LOWER_BOUNDS = setOf(3, 4, 5)

// endregion
