package com.github.hurricup.leakscollector

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.*
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

private val logger = KotlinLogging.logger {}

private const val MAGIC = 0x52494458 // "RIDX"
private const val VERSION = 2

/**
 * Saves the reverse index to a gzip-compressed binary file.
 *
 * Format (after gzip decompression):
 * - 4 bytes: magic
 * - 4 bytes: version
 * - 4 bytes: number of child entries
 * - for each child entry:
 *   - 8 bytes: childId
 *   - 4 bytes: number of parents
 *   - 8 bytes * numParents: parentIds
 */
internal fun saveReverseIndex(index: Map<Long, LongArray>, file: File) {
    DataOutputStream(BufferedOutputStream(GZIPOutputStream(FileOutputStream(file)))).use { out ->
        out.writeInt(MAGIC)
        out.writeInt(VERSION)
        out.writeInt(index.size)
        for ((childId, parentIds) in index) {
            out.writeLong(childId)
            out.writeInt(parentIds.size)
            for (parentId in parentIds) {
                out.writeLong(parentId)
            }
        }
    }
    logger.info { "Reverse index saved to ${file.name} (${file.length() / 1024 / 1024}MB)" }
}

/**
 * Loads the reverse index from a gzip-compressed binary cache file.
 */
internal fun loadReverseIndex(file: File): Map<Long, LongArray> {
    DataInputStream(BufferedInputStream(GZIPInputStream(FileInputStream(file)))).use { inp ->
        val magic = inp.readInt()
        require(magic == MAGIC) { "Invalid cache file magic: $magic" }
        val version = inp.readInt()
        require(version == VERSION) { "Unsupported cache version: $version (expected $VERSION)" }

        val entryCount = inp.readInt()
        val index = HashMap<Long, LongArray>(entryCount)
        repeat(entryCount) {
            val childId = inp.readLong()
            val parentCount = inp.readInt()
            val parentIds = LongArray(parentCount)
            for (i in 0 until parentCount) {
                parentIds[i] = inp.readLong()
            }
            index[childId] = parentIds
        }
        return index
    }
}
