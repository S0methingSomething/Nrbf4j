package dev.nrbf4j

/**
 * BinaryFormatter record type identifiers per [MS-NRBF].
 */
enum class RecordType(
    /** Numeric record type identifier. */
    val id: Int,
) {
    /** Record type 0: SerializedStreamHeader. */
    SerializedStreamHeader(0),

    /** Record type 1: ClassWithId. */
    ClassWithId(1),

    /** Record type 2: SystemClassWithMembers. */
    SystemClassWithMembers(2),

    /** Record type 3: ClassWithMembers. */
    ClassWithMembers(3),

    /** Record type 4: SystemClassWithMembersAndTypes. */
    SystemClassWithMembersAndTypes(4),

    /** Record type 5: ClassWithMembersAndTypes. */
    ClassWithMembersAndTypes(5),

    /** Record type 6: BinaryObjectString. */
    BinaryObjectString(6),

    /** Record type 7: BinaryArray. */
    BinaryArray(7),

    /** Record type 8: MemberPrimitiveTyped. */
    MemberPrimitiveTyped(8),

    /** Record type 9: MemberReference. */
    MemberReference(9),

    /** Record type 10: ObjectNull. */
    ObjectNull(10),

    /** Record type 11: MessageEnd. */
    MessageEnd(11),

    /** Record type 12: BinaryLibrary. */
    BinaryLibrary(12),

    /** Record type 13: ObjectNullMultiple256. */
    ObjectNullMultiple256(13),

    /** Record type 14: ObjectNullMultiple. */
    ObjectNullMultiple(14),

    /** Record type 15: ArraySinglePrimitive. */
    ArraySinglePrimitive(15),

    /** Record type 16: ArraySingleObject. */
    ArraySingleObject(16),

    /** Record type 17: ArraySingleString. */
    ArraySingleString(17),

    /** Record type 21: MethodCall. */
    MethodCall(21),

    /** Record type 22: MethodReturn. */
    MethodReturn(22),
    ;

    /** Companion for [RecordType]. */
    companion object {
        private val byId = entries.associateBy { it.id }

        /** Returns the [RecordType] for the given type identifier byte. */
        fun fromId(id: Int): RecordType = byId[id] ?: throw NrbfFormatException("unknown record type id: $id")
    }
}

/**
 * Member and array item type identifiers per [MS-NRBF].
 */
enum class BinaryType(
    /** Numeric binary type identifier. */
    val id: Int,
) {
    /** Binary type 0: Primitive. */
    Primitive(0),

    /** Binary type 1: String. */
    String(1),

    /** Binary type 2: Object. */
    Object(2),

    /** Binary type 3: SystemClass. */
    SystemClass(3),

    /** Binary type 4: Class. */
    Class(4),

    /** Binary type 5: ObjectArray. */
    ObjectArray(5),

    /** Binary type 6: StringArray. */
    StringArray(6),

    /** Binary type 7: PrimitiveArray. */
    PrimitiveArray(7),
    ;

    /** Companion for [BinaryType]. */
    companion object {
        private val byId = entries.associateBy { it.id }

        /** Returns the [BinaryType] for the given type identifier byte. */
        fun fromId(id: Int): BinaryType = byId[id] ?: throw NrbfFormatException("unknown binary type id: $id")
    }
}

/**
 * Primitive value type identifiers per [MS-NRBF].
 */
enum class PrimitiveType(
    /** Numeric primitive type identifier. */
    val id: Int,
    /** Fixed byte size, or -1 for variable-length types. */
    val byteSize: Int,
) {
    /** Primitive type 1: Boolean. */
    Boolean(1, 1),

    /** Primitive type 2: Byte. */
    Byte(2, 1),

    /** Primitive type 3: Char. */
    Char(3, 2),

    /** Primitive type 4: Reserved4. */
    Reserved4(4, 0),

    /** Primitive type 5: Decimal. */
    Decimal(5, -1),

    /** Primitive type 6: Double. */
    Double(6, 8),

    /** Primitive type 7: Int16. */
    Int16(7, 2),

    /** Primitive type 8: Int32. */
    Int32(8, 4),

    /** Primitive type 9: Int64. */
    Int64(9, 8),

    /** Primitive type 10: SByte. */
    SByte(10, 1),

    /** Primitive type 11: Single. */
    Single(11, 4),

    /** Primitive type 12: TimeSpan. */
    TimeSpan(12, 8),

    /** Primitive type 13: DateTime. */
    DateTime(13, 8),

    /** Primitive type 14: UInt16. */
    UInt16(14, 2),

    /** Primitive type 15: UInt32. */
    UInt32(15, 4),

    /** Primitive type 16: UInt64. */
    UInt64(16, 8),

    /** Primitive type 17: Null. */
    Null(17, 0),

    /** Primitive type 18: String. */
    String(18, -1),
    ;

    /** Companion for [PrimitiveType]. */
    companion object {
        private val byId = entries.associateBy { it.id }

        /** Returns the [PrimitiveType] for the given type identifier byte. */
        fun fromId(id: Int): PrimitiveType = byId[id] ?: throw NrbfFormatException("unknown primitive type id: $id")
    }
}
