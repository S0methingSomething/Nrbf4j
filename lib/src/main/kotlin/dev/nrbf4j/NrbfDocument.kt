@file:Suppress("TooManyFunctions")

package dev.nrbf4j

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val MAX_OBJECT_ID_ALLOCATED = 10_000_000

/**
 * Full-editor representation of an MS-NRBF binary stream.
 *
 * Opens a file, scans records into a flat [IntArray] index, and provides lazy
 * access to decoded objects through [object] and [member] DSL.  Structural
 * dirty-tracking by `(objectId, memberName)` enables edits that may change
 * record sizes (e.g. null→string transitions) and safe stream rebuild on
 * [write].
 *
 * Implements [AutoCloseable]; use `try { ... }` or Kotlin `.use {}`.
 */
class NrbfDocument internal constructor(
    internal var data: ByteArray,
    internal var index: IntArray,
    internal var objectIdToRow: Map<Int, Int>,
    internal val classLayouts: MutableMap<Int, ClassLayout>,
) : AutoCloseable {
    private val decodedObjects = mutableMapOf<Int, ObjectNode>()

    private val dirtyValues = mutableMapOf<Pair<Int, String>, Any?>()

    private var nextObjectId: Int
    private var closed = false

    init {
        var maxId = 0
        for (i in 0 until (index.size / INDEX_STRIDE)) {
            val objId = index[i * INDEX_STRIDE + IX_OBJECT_ID]
            if (objId != NO_VALUE && objId > maxId) maxId = objId
        }
        nextObjectId = maxOf(maxId + 1, 1)
    }

    // region Static factory -----------------------------------------------------------

    /** Companion for [NrbfDocument]. */
    companion object {
        /**
         * Opens [file] and builds the full document representation in memory.
         */
        fun open(file: File): NrbfDocument {
            val bytes = file.readBytes()
            val scan = scanRecords(bytes)
            return NrbfDocument(bytes, scan.index, scan.objectIdToRow, scan.classLayouts.toMutableMap())
        }

        /**
         * Opens a document from an in-memory byte array.
         */
        fun open(data: ByteArray): NrbfDocument {
            val scan = scanRecords(data)
            return NrbfDocument(data, scan.index, scan.objectIdToRow, scan.classLayouts.toMutableMap())
        }
    }

    // endregion

    // region Object access ------------------------------------------------------------

    /**
     * Returns the [ObjectNode] for [objectId], decoding it lazily if necessary.
     *
     * @throws ObjectNotFoundException if [objectId] is not in the stream.
     */
    fun objectNode(objectId: Int): ObjectNode {
        checkNotClosed()
        return decodedObjects.getOrPut(objectId) {
            val row =
                objectIdToRow[objectId]
                    ?: throw ObjectNotFoundException("object $objectId not found")
            decodeObject(data, objectId, row)
        }
    }

    /**
     * Convenience: the first (and typically only) object with a given class name.
     *
     * @throws AmbiguousObjectException if multiple objects share the class name.
     */
    fun objectByClass(className: String): ObjectNode {
        checkNotClosed()
        val matches = mutableListOf<Int>()
        for ((objId, row) in objectIdToRow) {
            val metadataId = index[row * INDEX_STRIDE + IX_METADATA_ID]
            if (metadataId != NO_VALUE) {
                val layout = classLayouts[metadataId]
                if (layout != null && layout.className == className) {
                    matches.add(objId)
                }
            }
        }
        if (matches.isEmpty()) throw ObjectNotFoundException("no object of class '$className'")
        if (matches.size > 1) {
            throw AmbiguousObjectException(
                "multiple objects of class '$className': $matches",
            )
        }
        return objectNode(matches.single())
    }

    // endregion

    // region Editing ------------------------------------------------------------------

    /**
     * Registers a dirty value for the given structural coordinate.
     *
     * The new value will be reflected in [ObjectNode.member] after this call but
     * the raw byte stream is not modified until [write] is invoked.
     */
    internal fun putDirty(
        objectId: Int,
        memberName: String,
        value: Any?,
    ) {
        dirtyValues[objectId to memberName] = value
    }

    /**
     * Allocates and returns a fresh, guaranteed-unique object id.
     */
    internal fun allocateObjectId(): Int {
        val id = nextObjectId
        nextObjectId += 1
        if (nextObjectId > MAX_OBJECT_ID_ALLOCATED) {
            throw NrbfException("object id overflow — too many edits")
        }
        return id
    }

    // endregion

    // region Write (stream rebuild) --------------------------------------------------

    /**
     * Rebuilds the NRBF stream with all dirty edits applied and writes the
     * result to [outputFile].
     *
     * Algorithm:
     * 1. Walk records in original scan order.
     * 2. For clean records → copy raw bytes unchanged.
     * 3. For dirty BinaryObjectString records → regenerate with new string value.
     * 4. For dirty ClassWithId records → regenerate member bytes, substituting
     *    dirty values.  Null→string transitions create new BinaryObjectString
     *    records which are appended before MessageEnd.
     * 5. Write MessageEnd, truncating any orphaned trailing records.
     */
    fun write(outputFile: File) {
        checkNotClosed()
        val out = ByteArrayOutputStream(data.size + OUTPUT_BUFFER_PADDING)

        val rowCount = index.size / INDEX_STRIDE
        var newStrings = listOf<Pair<Int, String>>()

        for (i in 0 until rowCount) {
            val row = i * INDEX_STRIDE
            val recordType = RecordType.fromId(index[row + IX_RECORD_TYPE])
            val offset = index[row + IX_OFFSET]
            val size = index[row + IX_SIZE]
            val objectId = index[row + IX_OBJECT_ID]

            when {
                recordType == RecordType.MessageEnd -> {
                    continue
                }

                recordType == RecordType.BinaryObjectString && objectId != NO_VALUE -> {
                    val dirty = dirtyValues.filterKeys { (oid, _) -> oid == objectId }
                    if (dirty.isEmpty()) {
                        copyRaw(data, offset, size, out)
                    } else {
                        val newValue = dirty.values.single() as? String ?: ""
                        val encoded = encodeLengthPrefixedString(newValue)
                        out.write(RecordType.BinaryObjectString.id)
                        writeInt32Le(objectId).forEach { out.write(it.toInt() and BYTE_MASK) }
                        encoded.forEach { out.write(it.toInt() and BYTE_MASK) }
                    }
                }

                (
                    recordType == RecordType.SystemClassWithMembersAndTypes ||
                        recordType == RecordType.ClassWithMembersAndTypes
                ) && objectId != NO_VALUE -> {
                    val dirtyFiltered = dirtyValues.filterKeys { (oid, _) -> oid == objectId }
                    if (dirtyFiltered.isEmpty()) {
                        copyRaw(data, offset, size, out)
                    } else {
                        val layout =
                            classLayouts[objectId]
                                ?: throw NrbfFormatException("no layout for ${recordType.name} $objectId")
                        val (regenerated, newRecs) =
                            regenerateClassDef(recordType, objectId, layout, dirtyFiltered, row)
                        out.write(regenerated)
                        newStrings = newStrings + newRecs
                    }
                }

                recordType == RecordType.ClassWithId && objectId != NO_VALUE -> {
                    val dirtyFiltered = dirtyValues.filterKeys { (oid, _) -> oid == objectId }
                    if (dirtyFiltered.isEmpty()) {
                        copyRaw(data, offset, size, out)
                    } else {
                        val metadataId = index[row + IX_METADATA_ID]
                        val layout =
                            classLayouts[metadataId]
                                ?: throw NrbfFormatException("ClassWithId $objectId refs unknown metadata $metadataId")
                        val (regenerated, newRecs) = regenerateClassWithId(objectId, metadataId, layout, dirtyFiltered)
                        out.write(regenerated)
                        newStrings = newStrings + newRecs
                    }
                }

                else -> {
                    copyRaw(data, offset, size, out)
                }
            }
        }

        for ((id, str) in newStrings) {
            val encoded = encodeLengthPrefixedString(str)
            out.write(RecordType.BinaryObjectString.id)
            writeInt32Le(id).forEach { out.write(it.toInt() and BYTE_MASK) }
            encoded.forEach { out.write(it.toInt() and BYTE_MASK) }
        }

        out.write(RecordType.MessageEnd.id)

        val newBytes = out.toByteArray()
        outputFile.writeBytes(newBytes)
        val scan = scanRecords(newBytes)
        data = newBytes
        index = scan.index
        objectIdToRow = scan.objectIdToRow
        classLayouts.clear()
        classLayouts.putAll(scan.classLayouts)
        decodedObjects.clear()
        dirtyValues.clear()
    }

    // endregion

    // region Lifecycle ---------------------------------------------------------------

    override fun close() {
        closed = true
        decodedObjects.clear()
        dirtyValues.clear()
    }

    internal fun checkNotClosed() {
        if (closed) throw NrbfException("document is closed")
    }

    // endregion

    // region Internal decode helpers ------------------------------------------------

    private fun decodeObject(
        data: ByteArray,
        objectId: Int,
        row: Int,
    ): ObjectNode {
        val typeId = data[index[row * INDEX_STRIDE + IX_OFFSET]].toInt() and BYTE_MASK
        return when (RecordType.fromId(typeId)) {
            RecordType.ClassWithId -> decodeClassWithId(data, objectId, row)

            RecordType.SystemClassWithMembersAndTypes,
            RecordType.ClassWithMembersAndTypes,
            -> decodeClassDef(data, objectId, row)

            else -> throw NrbfFormatException(
                "object $objectId is a ${RecordType.fromId(typeId).name}, not a class record",
            )
        }
    }

    private fun decodeClassWithId(
        data: ByteArray,
        objectId: Int,
        row: Int,
    ): ObjectNode {
        val metadataId = index[row * INDEX_STRIDE + IX_METADATA_ID]
        val layout =
            classLayouts[metadataId]
                ?: throw NrbfFormatException("ClassWithId $objectId references unknown metadata $metadataId")

        val membersOffset = index[row * INDEX_STRIDE + IX_OFFSET] + 9
        val memberNodes = decodeMemberValues(data, membersOffset, objectId, layout)
        return ObjectNode(this, objectId, metadataId, layout, memberNodes.toMutableList())
    }

    private fun decodeClassDef(
        data: ByteArray,
        objectId: Int,
        row: Int,
    ): ObjectNode {
        val recordOffset = index[row * INDEX_STRIDE + IX_OFFSET]
        val typeId = data[recordOffset].toInt() and BYTE_MASK
        val recordType = RecordType.fromId(typeId)

        val layout =
            classLayouts[objectId]
                ?: throw NrbfFormatException("no layout for ${recordType.name} $objectId")

        var cursor = recordOffset + 1
        val classInfo = parseClassInfo(data, cursor)
        cursor = classInfo.endOffset
        val typeInfo = parseMemberTypeInfo(data, cursor, classInfo.memberNames.size)
        cursor = typeInfo.endOffset
        if (recordType == RecordType.ClassWithMembersAndTypes) {
            cursor += 4
        }

        val memberNodes = decodeMemberValues(data, cursor, objectId, layout)
        return ObjectNode(this, objectId, -1, layout, memberNodes.toMutableList())
    }

    private fun decodeMemberValues(
        data: ByteArray,
        offset: Int,
        objectId: Int,
        layout: ClassLayout,
    ): List<MemberNode> {
        val result = mutableListOf<MemberNode>()
        var cursor = offset
        var remainingNulls = 0

        for (i in layout.memberNames.indices) {
            if (remainingNulls > 0) {
                remainingNulls -= 1
                result.add(MemberNode(this, objectId, layout.memberNames[i], layout.binaryTypes[i], null))
                continue
            }

            val bt = layout.binaryTypes[i]
            when (bt) {
                BinaryType.Primitive -> {
                    val primType = layout.additionalInfos[i] as PrimitiveType
                    val (value, consumed) = decodePrimitiveValue(data, cursor, primType)
                    result.add(MemberNode(this, objectId, layout.memberNames[i], bt, value))
                    cursor += consumed
                }

                else -> {
                    val memberStart = cursor
                    val subType = RecordType.fromId(data[cursor].toInt() and BYTE_MASK)
                    val value = decodeSubRecord(data, cursor, bt, layout.additionalInfos[i])
                    result.add(MemberNode(this, objectId, layout.memberNames[i], bt, value))

                    val subResult = scanRecordEnd(data, cursor, mutableMapOf())
                    cursor = subResult.endOffset

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

        return result
    }

    private fun decodeSubRecord(
        data: ByteArray,
        offset: Int,
        bt: BinaryType,
        ai: Any?,
    ): Any? {
        val typeByte = data[offset].toInt() and BYTE_MASK
        val subType = RecordType.fromId(typeByte)

        return when {
            subType == RecordType.ObjectNull ||
                subType == RecordType.ObjectNullMultiple256 ||
                subType == RecordType.ObjectNullMultiple -> {
                null
            }

            bt == BinaryType.Primitive -> {
                val primType = ai as PrimitiveType
                decodePrimitiveValue(data, offset, primType).first
            }

            bt == BinaryType.String -> {
                when (subType) {
                    RecordType.BinaryObjectString -> decodeLengthPrefixedStringAt(data, offset + 5).first
                    else -> throw NrbfFormatException("unexpected string member type $typeByte at offset $offset")
                }
            }

            bt == BinaryType.Class || bt == BinaryType.Object || bt == BinaryType.SystemClass -> {
                when (subType) {
                    RecordType.MemberReference -> {
                        readInt32At(data, offset + 1)
                    }

                    RecordType.ClassWithId -> {
                        val objId = readInt32At(data, offset + 1)
                        objId
                    }

                    else -> {
                        throw NrbfFormatException(
                            "unexpected reference member type $typeByte at offset $offset",
                        )
                    }
                }
            }

            bt == BinaryType.ObjectArray || bt == BinaryType.StringArray ||
                bt == BinaryType.PrimitiveArray -> {
                when (subType) {
                    RecordType.MemberReference -> {
                        readInt32At(data, offset + 1)
                    }

                    RecordType.BinaryArray,
                    RecordType.ArraySingleObject,
                    RecordType.ArraySingleString,
                    RecordType.ArraySinglePrimitive,
                    -> {
                        val result = scanRecordEnd(data, offset, mutableMapOf())
                        result.objectId
                    }

                    else -> {
                        throw NrbfFormatException(
                            "unexpected array member type $typeByte at offset $offset",
                        )
                    }
                }
            }

            else -> {
                throw NrbfFormatException("cannot decode member of type $bt at offset $offset")
            }
        }
    }

    // endregion

    // region Rebuild helpers ----------------------------------------------------------

    private fun regenerateClassWithId(
        objectId: Int,
        metadataId: Int,
        layout: ClassLayout,
        dirtyFiltered: Map<Pair<Int, String>, Any?>,
    ): Pair<ByteArray, List<Pair<Int, String>>> {
        val out = ByteArrayOutputStream()
        out.write(RecordType.ClassWithId.id and BYTE_MASK)
        writeInt32LeTo(objectId, out)
        writeInt32LeTo(metadataId, out)
        val newStringRecs = mutableListOf<Pair<Int, String>>()

        for (i in layout.memberNames.indices) {
            val name = layout.memberNames[i]
            val bt = layout.binaryTypes[i]
            val dirty = dirtyFiltered[objectId to name]

            val value =
                if (dirtyFiltered.containsKey(objectId to name)) {
                    dirty
                } else {
                    val obj = decodedObjects[objectId]
                    obj?.memberNodes?.getOrNull(i)?.value
                }

            when {
                value == null -> {
                    out.write(RecordType.ObjectNull.id and BYTE_MASK)
                }

                bt == BinaryType.Primitive -> {
                    val primType = layout.additionalInfos[i] as PrimitiveType
                    out.write(encodePrimitiveValue(value, primType))
                }

                bt == BinaryType.String -> {
                    val str = value as? String ?: ""
                    val newId = allocateObjectId()
                    newStringRecs.add(newId to str)
                    out.write(RecordType.BinaryObjectString.id and BYTE_MASK)
                    writeInt32LeTo(newId, out)
                    encodeLengthPrefixedStringTo(str, out)
                }

                bt == BinaryType.Class || bt == BinaryType.Object || bt == BinaryType.SystemClass -> {
                    val refId =
                        value as? Int ?: throw NrbfException(
                            "expected reference value for member '$name', got $value",
                        )
                    out.write(RecordType.MemberReference.id and BYTE_MASK)
                    writeInt32LeTo(refId, out)
                }

                else -> {
                    val refId =
                        value as? Int ?: throw NrbfException(
                            "expected reference value for member '$name', got $value",
                        )
                    out.write(RecordType.MemberReference.id and BYTE_MASK)
                    writeInt32LeTo(refId, out)
                }
            }
        }

        return out.toByteArray() to newStringRecs
    }

    private fun regenerateClassDef(
        recordType: RecordType,
        objectId: Int,
        layout: ClassLayout,
        dirtyFiltered: Map<Pair<Int, String>, Any?>,
        row: Int,
    ): Pair<ByteArray, List<Pair<Int, String>>> {
        val out = ByteArrayOutputStream()
        out.write(recordType.id and BYTE_MASK)
        writeInt32LeTo(objectId, out)
        encodeLengthPrefixedStringTo(layout.className, out)
        writeInt32LeTo(layout.memberNames.size, out)

        for (memberName in layout.memberNames) {
            encodeLengthPrefixedStringTo(memberName, out)
        }
        for (bt in layout.binaryTypes) {
            out.write(bt.id and BYTE_MASK)
        }
        for (i in layout.additionalInfos.indices) {
            when (val ai = layout.additionalInfos[i]) {
                is PrimitiveType -> {
                    out.write(ai.id and BYTE_MASK)
                }

                is String -> {
                    encodeLengthPrefixedStringTo(ai, out)
                }

                is Map<*, *> -> {
                    val typeName = ai["type_name"] as String
                    val libId = ai["library_id"] as Int
                    encodeLengthPrefixedStringTo(typeName, out)
                    writeInt32LeTo(libId, out)
                }

                null -> {}
            }
        }

        if (recordType == RecordType.ClassWithMembersAndTypes) {
            val libraryId = index[row + IX_LIBRARY_ID]
            writeInt32LeTo(libraryId, out)
        }

        val newStringRecs = mutableListOf<Pair<Int, String>>()

        for (i in layout.memberNames.indices) {
            val name = layout.memberNames[i]
            val bt = layout.binaryTypes[i]
            val dirty = dirtyFiltered[objectId to name]

            val value =
                if (dirtyFiltered.containsKey(objectId to name)) {
                    dirty
                } else {
                    val obj = decodedObjects[objectId]
                    obj?.memberNodes?.getOrNull(i)?.value
                }

            when {
                value == null -> {
                    out.write(RecordType.ObjectNull.id and BYTE_MASK)
                }

                bt == BinaryType.Primitive -> {
                    val primType = layout.additionalInfos[i] as PrimitiveType
                    out.write(encodePrimitiveValue(value, primType))
                }

                bt == BinaryType.String -> {
                    val str = value as? String ?: ""
                    val newId = allocateObjectId()
                    newStringRecs.add(newId to str)
                    out.write(RecordType.BinaryObjectString.id and BYTE_MASK)
                    writeInt32LeTo(newId, out)
                    encodeLengthPrefixedStringTo(str, out)
                }

                else -> {
                    val refId =
                        value as? Int
                            ?: throw NrbfException(
                                "expected reference value for member '$name', got $value",
                            )
                    out.write(RecordType.MemberReference.id and BYTE_MASK)
                    writeInt32LeTo(refId, out)
                }
            }
        }

        return out.toByteArray() to newStringRecs
    }

    private fun encodePrimitiveValue(
        value: Any,
        primType: PrimitiveType,
    ): ByteArray {
        val buf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)

        when (primType) {
            PrimitiveType.Boolean -> buf.put(if (value as Boolean) 1 else 0)
            PrimitiveType.Byte -> buf.put((value as Int).toByte())
            PrimitiveType.SByte -> buf.put((value as Int).toByte())
            PrimitiveType.Char -> buf.putChar((value as Int).toChar())
            PrimitiveType.Int16 -> buf.putShort((value as Int).toShort())
            PrimitiveType.UInt16 -> buf.putChar((value as Int).toChar())
            PrimitiveType.Int32 -> buf.putInt(value as Int)
            PrimitiveType.UInt32 -> buf.putInt(value as Int)
            PrimitiveType.Int64 -> buf.putLong(value as Long)
            PrimitiveType.UInt64 -> buf.putLong(value as Long)
            PrimitiveType.Single -> buf.putFloat(value as Float)
            PrimitiveType.Double -> buf.putDouble(value as Double)
            PrimitiveType.TimeSpan -> buf.putLong(value as Long)
            PrimitiveType.DateTime -> buf.putLong(value as Long)
            else -> throw NrbfException("unsupported primitive type for encode: ${primType.name}")
        }

        buf.flip()
        return ByteArray(buf.remaining()).also { buf.get(it) }
    }

    // endregion

    // region Arrays -------------------------------------------------------------------

    /**
     * Decodes an array record into an [NrbfArray] for iteration.
     *
     * The array type is auto-detected from the record header.
     */
    fun decodeArray(objectId: Int): NrbfArray {
        checkNotClosed()
        val row =
            objectIdToRow[objectId]
                ?: throw ObjectNotFoundException("object $objectId not found")

        val offset = index[row * INDEX_STRIDE + IX_OFFSET]
        val typeByte = data[offset].toInt() and BYTE_MASK
        val recordType = RecordType.fromId(typeByte)

        return when (recordType) {
            RecordType.ArraySinglePrimitive -> decodeArraySinglePrimitive(data, offset)

            RecordType.ArraySingleObject -> decodeArraySingleObject(data, offset)

            RecordType.ArraySingleString -> decodeArraySingleString(data, offset)

            RecordType.BinaryArray -> decodeBinaryArray(data, offset)

            else -> throw NrbfFormatException(
                "object $objectId is not an array record (type=${recordType.name})",
            )
        }
    }

    // endregion
}

// region ObjectNode ------------------------------------------------------------------

/**
 * Represents a decoded NRBF class object (ClassWithId or class definition).
 *
 * Provides lazy access to members via [member], an iterator via [members],
 * and structural dirty-tracking through the owning [NrbfDocument].
 */
class ObjectNode(
    private val doc: NrbfDocument,
    /** Object identifier in the NRBF stream. */
    val objectId: Int,
    /** Metadata identifier for the class definition. */
    val metadataId: Int,
    /** Parsed class layout with member names, types, and additional info. */
    val layout: ClassLayout,
    internal val memberNodes: MutableList<MemberNode>,
) {
    /** Human-readable class name. */
    val className: String get() = layout.className

    /**
     * Returns the [MemberNode] for the given [name].
     *
     * @throws MemberNotFoundException if no member matches [name].
     */
    fun member(name: String): MemberNode {
        val idx = layout.memberNames.indexOf(name)
        if (idx < 0) {
            throw MemberNotFoundException(
                "member '$name' not found on $className (id=$objectId)",
            )
        }
        return memberNodes[idx]
    }

    /**
     * Returns all member nodes with their names and types.
     *
     * Useful for reflection-style iteration (e.g. age cooldown cascade).
     */
    fun members(): List<MemberNode> = memberNodes

    /**
     * Resolves a MemberReference member through the document to get the
     * referenced [ObjectNode].
     */
    fun derefObject(memberName: String): ObjectNode {
        val refId =
            member(memberName).value as? Int
                ?: throw NrbfException("member '$memberName' is null or not a reference")
        return doc.objectNode(refId)
    }
}

// endregion

// region MemberNode ------------------------------------------------------------------

/**
 * A single member on an [ObjectNode].
 *
 * Reading [value] returns the current value (possibly dirty from an edit).
 * Calling [set] registers a structural dirty entry in the document.
 */
class MemberNode(
    private val doc: NrbfDocument,
    /** Object ID of the parent record this member belongs to. */
    val objectId: Int,
    /** Member name as declared in the class layout. */
    val name: String,
    /** NRBF binary type indicating the member's value category. */
    val type: BinaryType,
    initialValue: Any?,
) {
    private var dirtyValue: Any? = null
    private var isDirty = false
    private val storedInitial = initialValue

    val value: Any?
        get() = if (isDirty) dirtyValue else storedInitial

    /** Sets the member value and marks this object structurally dirty. */
    fun set(newValue: Any?) {
        isDirty = true
        dirtyValue = newValue
        doc.putDirty(objectId, name, newValue)
    }

    /** De-references this member as an object ID, returning the [ObjectNode]. */
    fun derefObject(): ObjectNode {
        val refId =
            value as? Int
                ?: throw NrbfException("member '$name' is null or not a reference")
        return doc.objectNode(refId)
    }

    /** De-references this member as an array object ID, returning the [NrbfArray]. */
    fun derefArray(): NrbfArray {
        val arrayId =
            value as? Int
                ?: throw NrbfException("member '$name' is null or not a reference")
        return doc.decodeArray(arrayId)
    }
}

// endregion

// region NrbfArray -------------------------------------------------------------------

/**
 * Represents a decoded NRBF array value, providing typed accessors.
 *
 * Use [objectRefs] for object ID arrays, [stringValues] for string arrays,
 * [intValues]/[floatValues] for primitive numeric arrays.
 */
interface NrbfArray {
    /** Total number of elements across all dimensions. */
    val length: Int

    /** Returns the list of object ID references if this is an object reference array. */
    fun objectRefs(): List<Int>

    /** Returns the list of strings if this is a string array. */
    fun stringValues(): List<String>

    /** Returns the list of integer values if this is a numeric array. */
    fun intValues(): List<Int>

    /** Returns the list of float values if this is a single-precision array. */
    fun floatValues(): List<Float>
}

internal class SimpleNrbfArray(
    override val length: Int,
    private val objectRefs: List<Int> = emptyList(),
    private val strings: List<String> = emptyList(),
    private val primitives: List<Any> = emptyList(),
) : NrbfArray {
    override fun objectRefs(): List<Int> = objectRefs

    override fun stringValues(): List<String> = strings

    override fun intValues(): List<Int> =
        primitives.map {
            when (it) {
                is Int -> it
                is Long -> it.toInt()
                is Short -> it.toInt()
                is Number -> it.toInt()
                else -> throw NrbfException("cannot convert $it to Int")
            }
        }

    override fun floatValues(): List<Float> =
        primitives.map {
            when (it) {
                is Float -> it
                is Double -> it.toFloat()
                is Number -> it.toFloat()
                else -> throw NrbfException("cannot convert $it to Float")
            }
        }
}

// endregion
