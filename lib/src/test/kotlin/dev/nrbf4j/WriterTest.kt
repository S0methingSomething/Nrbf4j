package dev.nrbf4j

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WriterTest {
    @Test
    fun writeEmptyStream() {
        val w = NrbfWriter()
        w.beginStream()
        w.messageEnd()
        val bytes = w.toByteArray()

        assertEquals(RecordType.SerializedStreamHeader.id.toByte(), bytes[0])
        assertEquals(RecordType.MessageEnd.id.toByte(), bytes.last())

        val scan = scanRecords(bytes)
        assertTrue(scan.index.size >= 2 * INDEX_STRIDE)
    }

    @Test
    fun writeBinaryObjectString() {
        val w = NrbfWriter()
        w.beginStream()
        w.binaryObjectString(1, "Hello")
        w.messageEnd()

        val scan = scanRecords(w.toByteArray())
        val rows = scan.index.size / INDEX_STRIDE
        assertEquals(3, rows)
        assertEquals(1, scan.objectIdToRow[1])
    }

    @Test
    fun writeMemberPrimitiveTyped() {
        val w = NrbfWriter()
        w.memberPrimitiveTyped(PrimitiveType.Int32, 42)
        val bytes = w.toByteArray()

        assertEquals(RecordType.MemberPrimitiveTyped.id.toByte(), bytes[0])
        assertEquals(PrimitiveType.Int32.id.toByte(), bytes[1])
        val value = readInt32At(bytes, 2)
        assertEquals(42, value)
    }

    @Test
    fun writeMemberReference() {
        val w = NrbfWriter()
        w.memberReference(100)
        val bytes = w.toByteArray()

        assertEquals(RecordType.MemberReference.id.toByte(), bytes[0])
        val refId = readInt32At(bytes, 1)
        assertEquals(100, refId)
    }

    @Test
    fun writeObjectNull() {
        val w = NrbfWriter()
        w.objectNull()
        assertEquals(byteArrayOf(RecordType.ObjectNull.id.toByte()).toList(), w.toByteArray().toList())
    }

    @Test
    fun writeObjectNullMultiple() {
        val w = NrbfWriter()
        w.objectNullMultiple(5)
        val bytes = w.toByteArray()
        assertEquals(RecordType.ObjectNullMultiple.id.toByte(), bytes[0])
        assertEquals(5, readInt32At(bytes, 1))
    }

    @Test
    fun writeObjectNullMultiple256() {
        val w = NrbfWriter()
        w.objectNullMultiple256(10)
        val bytes = w.toByteArray()
        assertEquals(RecordType.ObjectNullMultiple256.id.toByte(), bytes[0])
        assertEquals(10, bytes[1])
    }

    @Test
    fun roundTripClassWithMembers() {
        val w = NrbfWriter()
        w.beginStream()

        w.classWithMembersAndTypes(
            objectId = 100,
            className = "TestClass",
            members =
                listOf(
                    MemberDef("Name", BinaryType.String),
                    MemberDef("Age", BinaryType.Primitive, PrimitiveType.Int32),
                ),
            memberValues =
                listOf(
                    listOf(RecordType.BinaryObjectString.id.toByte()) +
                        writeInt32Le(1) +
                        encodeLengthPrefixedString("Alice").toList(),
                    writeInt32Le(30),
                ),
        )

        w.messageEnd()
        val bytes = w.toByteArray()

        val scan = scanRecords(bytes)
        assertEquals(3, scan.index.size / INDEX_STRIDE)
        assertNotNull(scan.objectIdToRow[100])
        assertEquals("TestClass", scan.classLayouts[100]!!.className)
        assertEquals(listOf("Name", "Age"), scan.classLayouts[100]!!.memberNames)
    }

    @Test
    fun roundTripDocument() {
        val writer = NrbfWriter()
        writer.beginStream()

        val nameBytes =
            listOf(RecordType.BinaryObjectString.id.toByte()) +
                writeInt32Le(1) +
                encodeLengthPrefixedString("Bob").toList()

        writer.classWithMembersAndTypes(
            objectId = 100,
            className = "Person",
            members =
                listOf(
                    MemberDef("Name", BinaryType.String),
                ),
            memberValues = listOf(nameBytes),
        )

        writer.classWithId(
            objectId = 200,
            metadataId = 100,
            memberValues = listOf(nameBytes),
        )

        writer.messageEnd()

        val file = File.createTempFile("nrbf4j_test", ".bin")
        try {
            writer.writeTo(file)
            val doc = NrbfDocument.open(file)

            val person = doc.objectNode(200)
            assertEquals("Person", person.className)
            assertEquals(200, person.objectId)

            val name = person.member("Name")
            assertNotNull(name)
            assertEquals("Bob", name.value)
            doc.close()
        } finally {
            file.delete()
        }
    }

    @Test
    fun writeBinaryLibrary() {
        val w = NrbfWriter()
        w.binaryLibrary(5, "System.Runtime")
        val bytes = w.toByteArray()
        assertEquals(RecordType.BinaryLibrary.id.toByte(), bytes[0])
        assertEquals(5, readInt32At(bytes, 1))
    }

    @Test
    fun writeArraySinglePrimitive() {
        val w = NrbfWriter()
        w.arraySinglePrimitive(PrimitiveType.Int32, listOf(10, 20, 30))
        val bytes = w.toByteArray()
        assertEquals(RecordType.ArraySinglePrimitive.id.toByte(), bytes[0])
        assertEquals(3, readInt32At(bytes, 1))
        assertEquals(PrimitiveType.Int32.id.toByte(), bytes[5])
    }

    @Test
    fun writeArraySingleString() {
        val w = NrbfWriter()
        w.arraySingleString(listOf("a", "bb", "ccc"))
        val bytes = w.toByteArray()
        assertEquals(RecordType.ArraySingleString.id.toByte(), bytes[0])
        assertEquals(3, readInt32At(bytes, 1))
    }

    @Test
    fun writeClassWithIdRoundTrip() {
        val writer = NrbfWriter()
        writer.beginStream()

        writer.classWithMembersAndTypes(
            objectId = 50,
            className = "Base",
            members = listOf(MemberDef("Value", BinaryType.Primitive, PrimitiveType.Int32)),
            memberValues = listOf(writeInt32Le(10)),
        )

        writer.classWithId(
            objectId = 60,
            metadataId = 50,
            memberValues = listOf(writeInt32Le(99)),
        )

        writer.messageEnd()

        val scan = scanRecords(writer.toByteArray())
        assertEquals(4, scan.index.size / INDEX_STRIDE)
        assertNotNull(scan.objectIdToRow[50])
        assertNotNull(scan.objectIdToRow[60])
    }

    @Test
    fun writeSystemClassWithMembersAndTypes() {
        val w = NrbfWriter()
        w.systemClassWithMembersAndTypes(
            className = "SystemType",
            members =
                listOf(
                    MemberDef("Prop", BinaryType.Primitive, PrimitiveType.Boolean),
                ),
            memberValues = listOf(listOf<Byte>(1)),
        )
        val bytes = w.toByteArray()
        assertEquals(
            RecordType.SystemClassWithMembersAndTypes.id.toByte(),
            bytes[0],
        )
    }
}
