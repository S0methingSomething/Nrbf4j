package dev.nrbf4j

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ScannerTest {
    @Test
    fun scansEmptyStream() {
        val w = NrbfWriter()
        w.beginStream()
        w.messageEnd()
        val result = scanRecords(w.toByteArray())

        assertEquals(2, result.index.size / INDEX_STRIDE)
        assertEquals(RecordType.SerializedStreamHeader.id, result.index[0 * INDEX_STRIDE + IX_RECORD_TYPE])
        assertEquals(RecordType.MessageEnd.id, result.index[1 * INDEX_STRIDE + IX_RECORD_TYPE])
    }

    @Test
    fun scansBinaryObjectString() {
        val w = NrbfWriter()
        w.beginStream()
        w.binaryObjectString(5, "test")
        w.messageEnd()
        val result = scanRecords(w.toByteArray())

        val rows = result.index.size / INDEX_STRIDE
        assertEquals(3, rows)

        val bosRow = 1 * INDEX_STRIDE
        assertEquals(RecordType.BinaryObjectString.id, result.index[bosRow + IX_RECORD_TYPE])
        assertEquals(5.toLong(), result.index[bosRow + IX_OBJECT_ID].toLong())
        assertNotNull(result.objectIdToRow[5])

        assertEquals(1, result.objectIdToRow[5])
    }

    @Test
    fun scansObjectNull() {
        val w = NrbfWriter()
        w.beginStream()
        w.objectNull()
        w.objectNullMultiple256(3)
        w.objectNullMultiple(5)
        w.messageEnd()
        val result = scanRecords(w.toByteArray())

        val rows = result.index.size / INDEX_STRIDE
        assertEquals(5, rows)
    }

    @Test
    fun scansMemberReference() {
        val w = NrbfWriter()
        w.beginStream()
        w.memberReference(42)
        w.messageEnd()
        val result = scanRecords(w.toByteArray())

        val rows = result.index.size / INDEX_STRIDE
        assertEquals(3, rows)

        val refRow = 1 * INDEX_STRIDE
        assertEquals(RecordType.MemberReference.id, result.index[refRow + IX_RECORD_TYPE])
    }

    @Test
    fun scansArbitraryPrimitiveRecord() {
        val buf =
            byteArrayOf(
                RecordType.MemberPrimitiveTyped.id.toByte(),
                PrimitiveType.Int32.id.toByte(),
                0x78,
                0x56,
                0x34,
                0x12,
            )
        val result = scanRecords(buf)

        val rows = result.index.size / INDEX_STRIDE
        assertEquals(1, rows)

        val row = 0 * INDEX_STRIDE
        assertEquals(RecordType.MemberPrimitiveTyped.id, result.index[row + IX_RECORD_TYPE])
    }

    @Test
    fun scansClassLayout() {
        val w = NrbfWriter()
        w.beginStream()

        val nameEncoded = encodeLengthPrefixedString("Test").toList()
        val value =
            listOf(
                RecordType.BinaryObjectString.id.toByte(),
            ) + writeInt32Le(7) + nameEncoded

        w.classWithMembersAndTypes(
            objectId = 50,
            className = "MyClass",
            members =
                listOf(
                    MemberDef("Field", BinaryType.String),
                ),
            memberValues = listOf(value),
        )

        w.messageEnd()
        val result = scanRecords(w.toByteArray())

        val layout = result.classLayouts[50]
        assertNotNull(layout, "class layout for metadata 50 should exist")
        assertEquals("MyClass", layout.className)
        assertEquals(1, layout.memberNames.size)
        assertEquals("Field", layout.memberNames.first())

        assertNotNull(result.objectIdToRow[50])
    }
}
