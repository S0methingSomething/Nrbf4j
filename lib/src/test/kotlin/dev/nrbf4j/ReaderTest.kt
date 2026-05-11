package dev.nrbf4j

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ReaderTest {
    private fun createStream(block: (NrbfWriter) -> Unit): File {
        val w = NrbfWriter()
        w.beginStream()
        block(w)
        w.messageEnd()
        val file = File.createTempFile("nrbf4j_reader", ".bin")
        w.writeTo(file)
        return file
    }

    @Test
    fun recordByIdFindsBinaryObjectString() {
        val file =
            createStream { w ->
                w.binaryObjectString(5, "Hello")
            }
        val reader = NrbfReader.open(file)
        try {
            val rec = reader.recordById(5)
            assertEquals(RecordType.BinaryObjectString, rec.type)
        } finally {
            reader.close()
            file.delete()
        }
    }

    @Test
    fun recordByIdThrowsForMissingObject() {
        val file =
            createStream { w ->
                w.binaryObjectString(1, "test")
            }
        val reader = NrbfReader.open(file)
        try {
            assertFailsWith<ObjectNotFoundException> {
                reader.recordById(999)
            }
        } finally {
            reader.close()
            file.delete()
        }
    }

    @Test
    fun readMemberPrimitive() {
        val file =
            createStream { w ->
                w.classWithMembersAndTypes(
                    objectId = 50,
                    className = "Data",
                    members =
                        listOf(
                            MemberDef("Score", BinaryType.Primitive, PrimitiveType.Int32),
                        ),
                    memberValues = listOf(writeInt32Le(9001)),
                )
                w.classWithId(
                    objectId = 10,
                    metadataId = 50,
                    memberValues = listOf(writeInt32Le(9001)),
                )
            }
        val reader = NrbfReader.open(file)
        try {
            val score = reader.readMember(10, "Score")
            assertEquals(9001, score)
        } finally {
            reader.close()
            file.delete()
        }
    }

    @Test
    fun readMemberRef() {
        val file =
            createStream { w ->
                w.classWithMembersAndTypes(
                    objectId = 50,
                    className = "Container",
                    members =
                        listOf(
                            MemberDef("Child", BinaryType.Class, Pair("ChildType", 0)),
                        ),
                    memberValues =
                        listOf(
                            listOf(RecordType.MemberReference.id.toByte()) +
                                writeInt32Le(42),
                        ),
                )
                w.classWithId(
                    objectId = 10,
                    metadataId = 50,
                    memberValues =
                        listOf(
                            listOf(RecordType.MemberReference.id.toByte()) +
                                writeInt32Le(42),
                        ),
                )
            }
        val reader = NrbfReader.open(file)
        try {
            val ref = reader.readMemberRef(10, "Child")
            assertEquals(42, ref)
        } finally {
            reader.close()
            file.delete()
        }
    }

    @Test
    fun readPersonFullName() {
        val file =
            createStream { w ->
                val johnStr =
                    listOf(RecordType.BinaryObjectString.id.toByte()) +
                        writeInt32Le(10) +
                        encodeLengthPrefixedString("John").toList()
                val doeStr =
                    listOf(RecordType.BinaryObjectString.id.toByte()) +
                        writeInt32Le(11) +
                        encodeLengthPrefixedString("Doe").toList()

                w.classWithMembersAndTypes(
                    objectId = 50,
                    className = "PersonName",
                    members =
                        listOf(
                            MemberDef("FirstName", BinaryType.String),
                            MemberDef("LastName", BinaryType.String),
                        ),
                    memberValues = listOf(johnStr, doeStr),
                )
                w.classWithId(
                    objectId = 1,
                    metadataId = 50,
                    memberValues = listOf(johnStr, doeStr),
                )

                w.classWithMembersAndTypes(
                    objectId = 60,
                    className = "Person",
                    members =
                        listOf(
                            MemberDef("Name", BinaryType.Class, Pair("SimPersonName", 0)),
                        ),
                    memberValues =
                        listOf(
                            listOf(RecordType.MemberReference.id.toByte()) +
                                writeInt32Le(1),
                        ),
                )
                w.classWithId(
                    objectId = 2,
                    metadataId = 60,
                    memberValues =
                        listOf(
                            listOf(RecordType.MemberReference.id.toByte()) +
                                writeInt32Le(1),
                        ),
                )
            }
        val reader = NrbfReader.open(file)
        try {
            val name = reader.readPersonFullName(2)
            assertEquals("John Doe", name)
        } finally {
            reader.close()
            file.delete()
        }
    }

    @Test
    fun readMemberThrowsForNonClassRecord() {
        val file =
            createStream { w ->
                w.binaryObjectString(1, "not-a-class")
            }
        val reader = NrbfReader.open(file)
        try {
            assertFailsWith<NrbfFormatException> {
                reader.readMember(1, "anything")
            }
        } finally {
            reader.close()
            file.delete()
        }
    }
}
