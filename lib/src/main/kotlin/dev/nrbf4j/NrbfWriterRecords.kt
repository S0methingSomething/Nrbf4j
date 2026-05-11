package dev.nrbf4j

// region Primitive & string records -----------------------------------------------

/**
 * Writes a [BinaryObjectString][RecordType.BinaryObjectString] record.
 */
fun NrbfWriter.binaryObjectString(
    objectId: Int,
    value: String,
): NrbfWriter {
    writeByte(RecordType.BinaryObjectString.id)
    writeInt32LeBytes(objectId)
    writeBytes(encodeLengthPrefixedString(value))
    return this
}

/**
 * Writes a [MemberPrimitiveTyped][RecordType.MemberPrimitiveTyped] record.
 */
fun NrbfWriter.memberPrimitiveTyped(
    primitiveType: PrimitiveType,
    value: Any,
): NrbfWriter {
    writeByte(RecordType.MemberPrimitiveTyped.id)
    writeByte(primitiveType.id)
    writeBytes(encodePrimitiveBytes(primitiveType, value))
    return this
}

// endregion

// region Object / Member records --------------------------------------------------

/**
 * Writes a [MemberReference][RecordType.MemberReference] record.
 */
fun NrbfWriter.memberReference(idRef: Int): NrbfWriter {
    writeByte(RecordType.MemberReference.id)
    writeInt32LeBytes(idRef)
    return this
}

/**
 * Writes an [ObjectNull][RecordType.ObjectNull] record.
 */
fun NrbfWriter.objectNull(): NrbfWriter {
    writeByte(RecordType.ObjectNull.id)
    return this
}

/**
 * Writes an [ObjectNullMultiple256][RecordType.ObjectNullMultiple256] record.
 */
fun NrbfWriter.objectNullMultiple256(nullCount: Int): NrbfWriter {
    require(nullCount in 1..OBJECT_NULL_MULTIPLE_256_MAX) {
        "nullCount must be 1..$OBJECT_NULL_MULTIPLE_256_MAX, was $nullCount"
    }
    writeByte(RecordType.ObjectNullMultiple256.id)
    writeByte(nullCount)
    return this
}

/**
 * Writes an [ObjectNullMultiple][RecordType.ObjectNullMultiple] record.
 */
fun NrbfWriter.objectNullMultiple(nullCount: Int): NrbfWriter {
    require(nullCount > 0) { "nullCount must be positive" }
    writeByte(RecordType.ObjectNullMultiple.id)
    writeInt32LeBytes(nullCount)
    return this
}

/**
 * Writes a [BinaryLibrary][RecordType.BinaryLibrary] record.
 */
fun NrbfWriter.binaryLibrary(
    libraryId: Int,
    libraryName: String,
): NrbfWriter {
    writeByte(RecordType.BinaryLibrary.id)
    writeInt32LeBytes(libraryId)
    writeBytes(encodeLengthPrefixedString(libraryName))
    return this
}

// endregion

// region Class record builders ----------------------------------------------------

/**
 * Writes a full [ClassWithMembersAndTypes][RecordType.ClassWithMembersAndTypes]
 * record with inlined member values.
 *
 * [memberValues] must be a list of byte sequences, one per member in
 * [members], representing the encoded NRBF record body for each member.
 * A null entry is encoded as [ObjectNull].
 */
fun NrbfWriter.classWithMembersAndTypes(
    objectId: Int,
    className: String,
    members: List<MemberDef>,
    memberValues: List<List<Byte>?>,
    libraryId: Int? = null,
): NrbfWriter {
    require(members.size == memberValues.size) {
        "member count mismatch: ${members.size} defs vs ${memberValues.size} values"
    }
    writeByte(RecordType.ClassWithMembersAndTypes.id)
    writeInt32LeBytes(objectId)
    writeEncodedString(className)
    writeInt32LeBytes(members.size)

    for (m in members) {
        writeEncodedString(m.name)
    }
    for (m in members) {
        writeByte(m.binaryType.id)
    }
    for (m in members) {
        when (val ai = m.additionalInfo) {
            is PrimitiveType -> {
                writeByte(ai.id)
            }

            is String -> {
                writeEncodedString(ai)
            }

            is Pair<*, *> -> {
                writeEncodedString(ai.first as String)
                writeInt32LeBytes(ai.second as Int)
            }

            null -> { /* no additional info needed */ }
        }
    }

    writeInt32LeBytes(libraryId ?: 0)

    for (valueBytes in memberValues) {
        if (valueBytes == null) {
            writeByte(RecordType.ObjectNull.id)
        } else {
            valueBytes.forEach { writeByte(it.toInt()) }
        }
    }

    return this
}

/**
 * Writes a [ClassWithId][RecordType.ClassWithId] record (refers to a
 * previously defined layout via [metadataId]).
 *
 * [memberValues] follow the same convention as [classWithMembersAndTypes].
 */
fun NrbfWriter.classWithId(
    objectId: Int,
    metadataId: Int,
    memberValues: List<List<Byte>?>,
): NrbfWriter {
    writeByte(RecordType.ClassWithId.id)
    writeInt32LeBytes(objectId)
    writeInt32LeBytes(metadataId)

    for (valueBytes in memberValues) {
        if (valueBytes == null) {
            writeByte(RecordType.ObjectNull.id)
        } else {
            valueBytes.forEach { writeByte(it.toInt()) }
        }
    }

    return this
}

/**
 * Writes a [SystemClassWithMembersAndTypes][RecordType.SystemClassWithMembersAndTypes]
 * record.  Same layout as [classWithMembersAndTypes] but record type 4,
 * no objectId, and no libraryId.
 */
fun NrbfWriter.systemClassWithMembersAndTypes(
    className: String,
    members: List<MemberDef>,
    memberValues: List<List<Byte>?>,
): NrbfWriter {
    require(members.size == memberValues.size)
    writeByte(RecordType.SystemClassWithMembersAndTypes.id)
    writeEncodedString(className)
    writeInt32LeBytes(members.size)

    for (m in members) {
        writeEncodedString(m.name)
    }
    for (m in members) {
        writeByte(m.binaryType.id)
    }
    for (m in members) {
        when (val ai = m.additionalInfo) {
            is PrimitiveType -> {
                writeByte(ai.id)
            }

            is String -> {
                writeEncodedString(ai)
            }

            is Pair<*, *> -> {
                writeEncodedString(ai.first as String)
                writeInt32LeBytes(ai.second as Int)
            }

            null -> { /* no additional info needed */ }
        }
    }

    for (valueBytes in memberValues) {
        if (valueBytes == null) {
            writeByte(RecordType.ObjectNull.id)
        } else {
            valueBytes.forEach { writeByte(it.toInt()) }
        }
    }

    return this
}

// endregion
