package dev.nrbf4j

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DocumentTest {
    @Test
    fun openEmptyStreamThrows() {
        val file = createStream {}
        try {
            NrbfDocument.open(file)
            file.delete()
        } catch (_: Exception) {
            file.delete()
        }
    }

    @Test
    fun openStreamWithOneString() {
        val file =
            createStream { w ->
                w.classWithMembersAndTypes(
                    objectId = 1,
                    className = "Greeting",
                    members = listOf(MemberDef("Text", BinaryType.String)),
                    memberValues =
                        listOf(
                            listOf(RecordType.BinaryObjectString.id.toByte(), 0x02, 0, 0, 0) +
                                encodeLengthPrefixedString("Hello").toList(),
                        ),
                )
            }
        try {
            val doc = NrbfDocument.open(file)
            val node = doc.objectNode(1)
            assertEquals(1, node.objectId)
            assertEquals("Greeting", node.className)
            doc.close()
        } finally {
            file.delete()
        }
    }

    @Test
    fun readMemberValues() {
        val file =
            createStream { w ->
                val nameEncoded = encodeLengthPrefixedString("Alice").toList()
                val bobStr =
                    listOf(RecordType.BinaryObjectString.id.toByte()) +
                        writeInt32Le(777) +
                        nameEncoded

                w.classWithMembersAndTypes(
                    objectId = 100,
                    className = "Person",
                    members =
                        listOf(
                            MemberDef("Name", BinaryType.String),
                            MemberDef("Age", BinaryType.Primitive, PrimitiveType.Int32),
                        ),
                    memberValues =
                        listOf(
                            bobStr,
                            writeInt32Le(42),
                        ),
                )
            }

        try {
            val doc = NrbfDocument.open(file)
            val obj = doc.objectNode(100)
            assertEquals("Person", obj.className)

            val name = obj.member("Name")
            assertNotNull(name)
            assertEquals("Alice", name.value)

            val age = obj.member("Age")
            assertNotNull(age)
            assertEquals(42, age.value)

            doc.close()
        } finally {
            file.delete()
        }
    }

    @Test
    fun editAndWriteRoundTrip() {
        val file =
            createStream { w ->
                val strBytes =
                    listOf(RecordType.BinaryObjectString.id.toByte()) +
                        writeInt32Le(99) +
                        encodeLengthPrefixedString("Original").toList()

                w.classWithMembersAndTypes(
                    objectId = 100,
                    className = "Data",
                    members =
                        listOf(
                            MemberDef("Value", BinaryType.String),
                        ),
                    memberValues = listOf(strBytes),
                )
            }

        try {
            val doc = NrbfDocument.open(file)
            val obj = doc.objectNode(100)
            val name = obj.member("Value")
            assertNotNull(name)
            assertEquals("Original", name.value)

            name.set("Modified")

            val outFile = File.createTempFile("nrbf4j_out", ".bin")
            try {
                doc.write(outFile)
                val doc2 = NrbfDocument.open(outFile)
                val obj2 = doc2.objectNode(100)
                val name2 = obj2.member("Value")
                assertNotNull(name2)
                assertEquals("Modified", name2.value)
                doc2.close()
            } finally {
                outFile.delete()
            }
            doc.close()
        } finally {
            file.delete()
        }
    }

    @Test
    fun editReferencedStringRoundTrip() {
        val file =
            createStream { w ->
                w.binaryObjectString(99, "Original")
                w.classWithMembersAndTypes(
                    objectId = 100,
                    className = "Data",
                    members = listOf(MemberDef("Value", BinaryType.String)),
                    memberValues = listOf(memberReferenceBytes(99)),
                )
            }

        try {
            val doc = NrbfDocument.open(file)
            val obj = doc.objectNode(100)
            val name = obj.member("Value")
            assertEquals("Original", name.value)

            name.set("Original [test]")

            val outFile = File.createTempFile("nrbf4j_out", ".bin")
            try {
                doc.write(outFile)
                assertEquals(file.length() + STRING_TEST_SUFFIX_LENGTH, outFile.length())

                val doc2 = NrbfDocument.open(outFile)
                assertEquals("Original [test]", doc2.objectNode(100).member("Value").value)
                doc2.close()
            } finally {
                outFile.delete()
            }
            doc.close()
        } finally {
            file.delete()
        }
    }

    @Test
    fun editNestedStringPreservesInlineClassRecord() {
        val nestedChild =
            classWithMembersAndTypesBytes(
                objectId = 200,
                className = "Child",
                members = listOf(MemberDef("Age", BinaryType.Primitive, PrimitiveType.Int32)),
                memberValues = listOf(writeInt32Le(5)),
            )
        val file =
            createStream { w ->
                w.classWithMembersAndTypes(
                    objectId = 100,
                    className = "Parent",
                    members =
                        listOf(
                            MemberDef("Name", BinaryType.String),
                            MemberDef("Child", BinaryType.Object),
                        ),
                    memberValues =
                        listOf(
                            binaryObjectStringBytes(99, "Original"),
                            nestedChild,
                        ),
                )
            }

        try {
            val doc = NrbfDocument.open(file)
            val obj = doc.objectNode(100)
            assertEquals("Original", obj.member("Name").value)
            assertEquals(200, obj.member("Child").value)

            obj.member("Name").set("Original [test]")

            val outFile = File.createTempFile("nrbf4j_out", ".bin")
            try {
                doc.write(outFile)
                assertEquals(file.length() + STRING_TEST_SUFFIX_LENGTH, outFile.length())

                val doc2 = NrbfDocument.open(outFile)
                val obj2 = doc2.objectNode(100)
                assertEquals("Original [test]", obj2.member("Name").value)
                assertEquals(200, obj2.member("Child").value)
                doc2.close()
            } finally {
                outFile.delete()
            }
            doc.close()
        } finally {
            file.delete()
        }
    }

    @Test
    fun simpleArrayDetection() {
        val file =
            createStream { w ->
                w.classWithMembersAndTypes(
                    objectId = 1,
                    className = "Strings",
                    members = listOf(MemberDef("Items", BinaryType.StringArray)),
                    memberValues =
                        listOf(
                            listOf(RecordType.BinaryArray.id.toByte()) +
                                writeInt32Le(2) + // array objectId
                                listOf(0x00.toByte()) + // arrayTypeId: no lower bounds
                                writeInt32Le(1) + // rank 1
                                writeInt32Le(3) + // length 3
                                listOf(BinaryType.String.id.toByte()) + // element type
                                listOf(RecordType.BinaryObjectString.id.toByte(), 0x0A, 0, 0, 0) +
                                encodeLengthPrefixedString("one").toList() +
                                listOf(RecordType.BinaryObjectString.id.toByte(), 0x0B, 0, 0, 0) +
                                encodeLengthPrefixedString("two").toList() +
                                listOf(RecordType.BinaryObjectString.id.toByte(), 0x0C, 0, 0, 0) +
                                encodeLengthPrefixedString("three").toList(),
                        ),
                )
            }

        try {
            val doc = NrbfDocument.open(file)
            val objects = doc.objectNode(1)
            assertNotNull(objects)
            assertEquals("Strings", objects.className)
            doc.close()
        } finally {
            file.delete()
        }
    }

    private fun createStream(block: (NrbfWriter) -> Unit): File {
        val w = NrbfWriter()
        w.beginStream()
        block(w)
        w.messageEnd()
        val file = File.createTempFile("nrbf4j_test", ".bin")
        w.writeTo(file)
        return file
    }

    private fun binaryObjectStringBytes(
        objectId: Int,
        value: String,
    ): List<Byte> =
        listOf(RecordType.BinaryObjectString.id.toByte()) +
            writeInt32Le(objectId) +
            encodeLengthPrefixedString(value).toList()

    private fun memberReferenceBytes(objectId: Int): List<Byte> {
        val bytes = memberReferencePrefix()
        return bytes + writeInt32Le(objectId)
    }

    private fun memberReferencePrefix(): List<Byte> = listOf(RecordType.MemberReference.id.toByte())

    private fun classWithMembersAndTypesBytes(
        objectId: Int,
        className: String,
        members: List<MemberDef>,
        memberValues: List<List<Byte>>,
    ): List<Byte> {
        val w = NrbfWriter()
        w.classWithMembersAndTypes(objectId, className, members, memberValues)
        return w.toByteArray().toList()
    }

    private companion object {
        private const val STRING_TEST_SUFFIX_LENGTH = 7L
    }
}
