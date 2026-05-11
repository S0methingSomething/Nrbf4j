package dev.nrbf4j

internal fun NrbfDocument.decodeArraySinglePrimitive(
    data: ByteArray,
    offset: Int,
): NrbfArray {
    val length = readInt32At(data, offset + 5)
    val primType = PrimitiveType.fromId(data[offset + 9].toInt() and BYTE_MASK)
    val values = mutableListOf<Any>()
    var cursor = offset + 10

    repeat(length) {
        val (value, consumed) = decodePrimitiveValue(data, cursor, primType)
        values.add(value!!)
        cursor += consumed
    }

    return SimpleNrbfArray(length, primitives = values)
}

internal fun NrbfDocument.decodeArraySingleObject(
    data: ByteArray,
    offset: Int,
): NrbfArray {
    val length = readInt32At(data, offset + 5)
    val refs = mutableListOf<Int>()
    var remainingNulls = 0
    var cursor = offset + 9

    repeat(length) {
        if (remainingNulls > 0) {
            remainingNulls -= 1
            return@repeat
        }
        val subType = RecordType.fromId(data[cursor].toInt() and BYTE_MASK)
        when (subType) {
            RecordType.MemberReference -> {
                refs.add(readInt32At(data, cursor + 1))
                cursor += 5
            }

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

            else -> {
                val result = scanRecordEnd(data, cursor, classLayouts)
                cursor = result.endOffset
            }
        }
    }

    return SimpleNrbfArray(length, objectRefs = refs)
}

internal fun NrbfDocument.decodeArraySingleString(
    data: ByteArray,
    offset: Int,
): NrbfArray {
    val length = readInt32At(data, offset + 5)
    val strings = mutableListOf<String?>()
    var remainingNulls = 0
    var cursor = offset + 9

    repeat(length) {
        if (remainingNulls > 0) {
            remainingNulls -= 1
            strings.add(null)
            return@repeat
        }
        val subType = RecordType.fromId(data[cursor].toInt() and BYTE_MASK)
        when (subType) {
            RecordType.BinaryObjectString -> {
                val (str, consumed) = decodeLengthPrefixedStringAt(data, cursor + 5)
                strings.add(str)
                cursor += 5 + consumed
            }

            RecordType.ObjectNull -> {
                strings.add(null)
                cursor += 1
            }

            RecordType.ObjectNullMultiple256 -> {
                remainingNulls = (data[cursor + 1].toInt() and BYTE_MASK) - 1
                strings.add(null)
                cursor += 2
            }

            RecordType.ObjectNullMultiple -> {
                remainingNulls = readInt32At(data, cursor + 1) - 1
                strings.add(null)
                cursor += 5
            }

            RecordType.MemberReference -> {
                val refId = readInt32At(data, cursor + 1)
                val strRow = objectIdToRow[refId]
                if (strRow != null) {
                    val strOffset = index[strRow * INDEX_STRIDE + IX_OFFSET]
                    strings.add(decodeLengthPrefixedStringAt(data, strOffset + 5).first)
                } else {
                    strings.add(null)
                }
                cursor += 5
            }

            else -> {
                val result = scanRecordEnd(data, cursor, classLayouts)
                cursor = result.endOffset
                strings.add(null)
            }
        }
    }

    return SimpleNrbfArray(
        length,
        strings = strings.filterNotNull().toList(),
    )
}

internal fun NrbfDocument.decodeBinaryArray(
    data: ByteArray,
    offset: Int,
): NrbfArray {
    val header = parseBinaryArrayHeader(data, offset)
    val itemCount = header.lengths.fold(1) { acc, len -> acc * len }

    return when (header.typeEnum) {
        BinaryType.Primitive, BinaryType.PrimitiveArray -> {
            val primType = header.additionalInfo as PrimitiveType
            val values = mutableListOf<Any>()
            var cursor = header.membersOffset

            repeat(itemCount) {
                val (value, consumed) = decodePrimitiveValue(data, cursor, primType)
                if (value != null) values.add(value)
                cursor += consumed
            }

            SimpleNrbfArray(itemCount, primitives = values)
        }

        else -> {
            val refs = mutableListOf<Int>()
            skipRecordItems(data, header.membersOffset, itemCount, classLayouts)
            var cursor = header.membersOffset
            var remainingNulls = 0

            repeat(itemCount) {
                if (remainingNulls > 0) {
                    remainingNulls -= 1
                    return@repeat
                }
                val subType = RecordType.fromId(data[cursor].toInt() and BYTE_MASK)
                when (subType) {
                    RecordType.MemberReference -> {
                        refs.add(readInt32At(data, cursor + 1))
                        cursor += 5
                    }

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

                    else -> {
                        val result = scanRecordEnd(data, cursor, classLayouts)
                        cursor = result.endOffset
                    }
                }
            }

            SimpleNrbfArray(itemCount, objectRefs = refs)
        }
    }
}
