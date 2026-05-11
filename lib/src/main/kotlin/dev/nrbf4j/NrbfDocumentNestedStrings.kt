package dev.nrbf4j

import java.io.ByteArrayOutputStream

internal fun copyRawReplacingNestedStrings(
    data: ByteArray,
    offset: Int,
    size: Int,
    out: ByteArrayOutputStream,
    nestedDirtyStrings: Map<Int, String>,
) {
    if (nestedDirtyStrings.isEmpty()) {
        copyRaw(data, offset, size, out)
        return
    }

    val end = offset + size
    var cursor = offset
    var copyStart = offset
    while (cursor <= end - MIN_BINARY_OBJECT_STRING_SIZE) {
        val isStringRecord = (data[cursor].toInt() and BYTE_MASK) == RecordType.BinaryObjectString.id
        if (!isStringRecord) {
            cursor += 1
            continue
        }

        val stringObjectId = readInt32At(data, cursor + 1)
        val newValue = nestedDirtyStrings[stringObjectId]
        if (newValue == null) {
            cursor += 1
            continue
        }

        val originalSize = nestedStringRecordSize(data, cursor, end)
        if (originalSize == null) {
            cursor += 1
            continue
        }

        copyRaw(data, copyStart, cursor - copyStart, out)
        out.write(RecordType.BinaryObjectString.id and BYTE_MASK)
        writeInt32LeTo(stringObjectId, out)
        encodeLengthPrefixedStringTo(newValue, out)
        cursor += originalSize
        copyStart = cursor
    }

    copyRaw(data, copyStart, end - copyStart, out)
}

private fun nestedStringRecordSize(
    data: ByteArray,
    offset: Int,
    end: Int,
): Int? =
    try {
        val (_, consumed) = decodeLengthPrefixedStringAt(data, offset + 5)
        val recordSize = 5 + consumed
        recordSize.takeIf { offset + it <= end }
    } catch (_: NrbfException) {
        null
    }
