package dev.nrbf4j

// region Array record builders ----------------------------------------------------

/**
 * Writes a [BinaryArray][RecordType.BinaryArray] record with full header.
 *
 * [additionalInfo] depends on [binaryType]:
 * - [BinaryType.Primitive] / [BinaryType.PrimitiveArray] → [PrimitiveType]
 * - [BinaryType.SystemClass] → String (class name)
 * - [BinaryType.Class] → Pair<String, Int> (typeName, libraryId)
 */
fun NrbfWriter.binaryArray(
    objectId: Int,
    binaryArrayTypeId: Int,
    rank: Int,
    lengths: List<Int>,
    lowerBounds: List<Int>?,
    binaryType: BinaryType,
    additionalInfo: Any?,
    memberValueBytes: List<Byte>,
): NrbfWriter {
    require(lengths.size == rank)
    writeByte(RecordType.BinaryArray.id)
    writeInt32LeBytes(objectId)
    writeByte(binaryArrayTypeId)
    writeInt32LeBytes(rank)

    for (len in lengths) {
        writeInt32LeBytes(len)
    }

    if (lowerBounds != null) {
        for (lb in lowerBounds) {
            writeInt32LeBytes(lb)
        }
    }

    writeByte(binaryType.id)

    when (additionalInfo) {
        is PrimitiveType -> {
            writeByte(additionalInfo.id)
        }

        is String -> {
            writeEncodedString(additionalInfo)
        }

        is Pair<*, *> -> {
            writeEncodedString(additionalInfo.first as String)
            writeInt32LeBytes(additionalInfo.second as Int)
        }
    }

    memberValueBytes.forEach { writeByte(it.toInt()) }
    return this
}

/**
 * Writes an [ArraySinglePrimitive][RecordType.ArraySinglePrimitive] record.
 */
fun NrbfWriter.arraySinglePrimitive(
    primitiveType: PrimitiveType,
    values: List<Any>,
): NrbfWriter {
    writeByte(RecordType.ArraySinglePrimitive.id)
    writeInt32LeBytes(values.size)
    writeByte(primitiveType.id)

    for (value in values) {
        writeBytes(encodePrimitiveBytes(primitiveType, value))
    }
    return this
}

/**
 * Writes an [ArraySingleString][RecordType.ArraySingleString] record.
 */
fun NrbfWriter.arraySingleString(strings: List<String>): NrbfWriter {
    writeByte(RecordType.ArraySingleString.id)
    writeInt32LeBytes(strings.size)
    for (s in strings) {
        writeBytes(encodeLengthPrefixedString(s))
    }
    return this
}

/**
 * Writes an [ArraySingleObject][RecordType.ArraySingleObject] record.
 */
fun NrbfWriter.arraySingleObject(objectIds: List<Int>): NrbfWriter {
    writeByte(RecordType.ArraySingleObject.id)
    writeInt32LeBytes(objectIds.size)
    for (id in objectIds) {
        memberReference(id)
    }
    return this
}

// endregion
