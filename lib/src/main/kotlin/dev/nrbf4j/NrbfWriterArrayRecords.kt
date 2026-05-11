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
    buf.add(RecordType.BinaryArray.id.toByte())
    buf.addAll(writeInt32Le(objectId))
    buf.add(binaryArrayTypeId.toByte())
    buf.addAll(writeInt32Le(rank))

    for (len in lengths) {
        buf.addAll(writeInt32Le(len))
    }

    if (lowerBounds != null) {
        for (lb in lowerBounds) {
            buf.addAll(writeInt32Le(lb))
        }
    }

    buf.add(binaryType.id.toByte())

    when (additionalInfo) {
        is PrimitiveType -> {
            buf.add(additionalInfo.id.toByte())
        }

        is String -> {
            val encoded = encodeLengthPrefixedString(additionalInfo)
            buf.addAll(encoded.toList())
        }

        is Pair<*, *> -> {
            val (typeName, libId) = additionalInfo
            val encoded = encodeLengthPrefixedString(typeName as String)
            buf.addAll(encoded.toList())
            buf.addAll(writeInt32Le(libId as Int))
        }
    }

    buf.addAll(memberValueBytes)
    return this
}

/**
 * Writes an [ArraySinglePrimitive][RecordType.ArraySinglePrimitive] record.
 */
fun NrbfWriter.arraySinglePrimitive(
    primitiveType: PrimitiveType,
    values: List<Any>,
): NrbfWriter {
    buf.add(RecordType.ArraySinglePrimitive.id.toByte())
    buf.addAll(writeInt32Le(values.size))
    buf.add(primitiveType.id.toByte())

    for (value in values) {
        buf.addAll(encodePrimitiveBytes(primitiveType, value))
    }
    return this
}

/**
 * Writes an [ArraySingleString][RecordType.ArraySingleString] record.
 */
fun NrbfWriter.arraySingleString(strings: List<String>): NrbfWriter {
    buf.add(RecordType.ArraySingleString.id.toByte())
    buf.addAll(writeInt32Le(strings.size))
    for (s in strings) {
        val encoded = encodeLengthPrefixedString(s)
        buf.addAll(encoded.toList())
    }
    return this
}

/**
 * Writes an [ArraySingleObject][RecordType.ArraySingleObject] record.
 */
fun NrbfWriter.arraySingleObject(objectIds: List<Int>): NrbfWriter {
    buf.add(RecordType.ArraySingleObject.id.toByte())
    buf.addAll(writeInt32Le(objectIds.size))
    for (id in objectIds) {
        memberReference(id)
    }
    return this
}

// endregion
