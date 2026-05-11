package dev.nrbf4j

// region Primitive & string records -----------------------------------------------

/**
 * Writes a [BinaryObjectString][RecordType.BinaryObjectString] record.
 */
fun NrbfWriter.binaryObjectString(
    objectId: Int,
    value: String,
): NrbfWriter {
    val encoded = encodeLengthPrefixedString(value)
    buf.add(RecordType.BinaryObjectString.id.toByte())
    buf.addAll(writeInt32Le(objectId))
    buf.addAll(encoded.toList())
    return this
}

/**
 * Writes a [MemberPrimitiveTyped][RecordType.MemberPrimitiveTyped] record.
 */
fun NrbfWriter.memberPrimitiveTyped(
    primitiveType: PrimitiveType,
    value: Any,
): NrbfWriter {
    buf.add(RecordType.MemberPrimitiveTyped.id.toByte())
    buf.add(primitiveType.id.toByte())
    buf.addAll(encodePrimitiveBytes(primitiveType, value))
    return this
}

// endregion

// region Object / Member records --------------------------------------------------

/**
 * Writes a [MemberReference][RecordType.MemberReference] record.
 */
fun NrbfWriter.memberReference(idRef: Int): NrbfWriter {
    buf.add(RecordType.MemberReference.id.toByte())
    buf.addAll(writeInt32Le(idRef))
    return this
}

/**
 * Writes an [ObjectNull][RecordType.ObjectNull] record.
 */
fun NrbfWriter.objectNull(): NrbfWriter {
    buf.add(RecordType.ObjectNull.id.toByte())
    return this
}

/**
 * Writes an [ObjectNullMultiple256][RecordType.ObjectNullMultiple256] record.
 */
fun NrbfWriter.objectNullMultiple256(nullCount: Int): NrbfWriter {
    require(nullCount in 1..OBJECT_NULL_MULTIPLE_256_MAX) {
        "nullCount must be 1..$OBJECT_NULL_MULTIPLE_256_MAX, was $nullCount"
    }
    buf.add(RecordType.ObjectNullMultiple256.id.toByte())
    buf.add(nullCount.toByte())
    return this
}

/**
 * Writes an [ObjectNullMultiple][RecordType.ObjectNullMultiple] record.
 */
fun NrbfWriter.objectNullMultiple(nullCount: Int): NrbfWriter {
    require(nullCount > 0) { "nullCount must be positive" }
    buf.add(RecordType.ObjectNullMultiple.id.toByte())
    buf.addAll(writeInt32Le(nullCount))
    return this
}

/**
 * Writes a [BinaryLibrary][RecordType.BinaryLibrary] record.
 */
fun NrbfWriter.binaryLibrary(
    libraryId: Int,
    libraryName: String,
): NrbfWriter {
    val encoded = encodeLengthPrefixedString(libraryName)
    buf.add(RecordType.BinaryLibrary.id.toByte())
    buf.addAll(writeInt32Le(libraryId))
    buf.addAll(encoded.toList())
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
    buf.add(RecordType.ClassWithMembersAndTypes.id.toByte())
    buf.addAll(writeInt32Le(objectId))

    val nameEncoded = encodeLengthPrefixedString(className)
    buf.addAll(nameEncoded.toList())

    buf.addAll(writeInt32Le(members.size))

    for (m in members) {
        val encoded = encodeLengthPrefixedString(m.name)
        buf.addAll(encoded.toList())
    }

    for (m in members) {
        buf.add(m.binaryType.id.toByte())
    }

    for (m in members) {
        when (val ai = m.additionalInfo) {
            is PrimitiveType -> {
                buf.add(ai.id.toByte())
            }

            is String -> {
                val encoded = encodeLengthPrefixedString(ai)
                buf.addAll(encoded.toList())
            }

            is Pair<*, *> -> {
                val (typeName, libId) = ai
                val encoded = encodeLengthPrefixedString(typeName as String)
                buf.addAll(encoded.toList())
                buf.addAll(writeInt32Le(libId as Int))
            }

            null -> { /* no additional info needed */ }
        }
    }

    if (libraryId != null) {
        buf.addAll(writeInt32Le(libraryId))
    } else {
        buf.addAll(writeInt32Le(0))
    }

    for (valueBytes in memberValues) {
        if (valueBytes == null) {
            buf.add(RecordType.ObjectNull.id.toByte())
        } else {
            buf.addAll(valueBytes)
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
    buf.add(RecordType.ClassWithId.id.toByte())
    buf.addAll(writeInt32Le(objectId))
    buf.addAll(writeInt32Le(metadataId))

    for (valueBytes in memberValues) {
        if (valueBytes == null) {
            buf.add(RecordType.ObjectNull.id.toByte())
        } else {
            buf.addAll(valueBytes)
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
    buf.add(RecordType.SystemClassWithMembersAndTypes.id.toByte())

    val nameEncoded = encodeLengthPrefixedString(className)
    buf.addAll(nameEncoded.toList())

    buf.addAll(writeInt32Le(members.size))

    for (m in members) {
        val encoded = encodeLengthPrefixedString(m.name)
        buf.addAll(encoded.toList())
    }

    for (m in members) {
        buf.add(m.binaryType.id.toByte())
    }

    for (m in members) {
        when (val ai = m.additionalInfo) {
            is PrimitiveType -> {
                buf.add(ai.id.toByte())
            }

            is String -> {
                val encoded = encodeLengthPrefixedString(ai)
                buf.addAll(encoded.toList())
            }

            is Pair<*, *> -> {
                val (typeName, libId) = ai
                val encoded = encodeLengthPrefixedString(typeName as String)
                buf.addAll(encoded.toList())
                buf.addAll(writeInt32Le(libId as Int))
            }

            null -> { /* no additional info needed */ }
        }
    }

    for (valueBytes in memberValues) {
        if (valueBytes == null) {
            buf.add(RecordType.ObjectNull.id.toByte())
        } else {
            buf.addAll(valueBytes)
        }
    }

    return this
}

// endregion
