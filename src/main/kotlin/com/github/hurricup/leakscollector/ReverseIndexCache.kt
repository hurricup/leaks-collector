package com.github.hurricup.leakscollector

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.*
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

private val logger = KotlinLogging.logger {}

private const val MAGIC = 0x52494458 // "RIDX"
private const val VERSION = 3
private const val FINGERPRINT_BYTES = 64 * 1024 // hash first 64KB of hprof

/**
 * Computes a fingerprint of the hprof file: SHA-256 of the first 64KB.
 */
private fun computeFingerprint(hprofFile: File): ByteArray {
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(hprofFile).use { fis ->
        val buf = ByteArray(FINGERPRINT_BYTES)
        var remaining = FINGERPRINT_BYTES
        while (remaining > 0) {
            val read = fis.read(buf, FINGERPRINT_BYTES - remaining, remaining)
            if (read < 0) break
            remaining -= read
        }
        digest.update(buf, 0, FINGERPRINT_BYTES - remaining)
    }
    return digest.digest()
}

/**
 * Saves the reverse index to a gzip-compressed binary file.
 *
 * Format (after gzip decompression):
 * - 4 bytes: magic
 * - 4 bytes: version
 * - 8 bytes: hprof file size
 * - 4 bytes: fingerprint length
 * - N bytes: fingerprint (SHA-256 of first 64KB)
 * - 4 bytes: number of child entries
 * - for each child entry:
 *   - 8 bytes: childId
 *   - 4 bytes: number of parents
 *   - 8 bytes * numParents: parentIds
 */
internal fun saveReverseIndex(index: Map<Long, LongArray>, cacheFile: File, hprofFile: File) {
    val fingerprint = computeFingerprint(hprofFile)
    DataOutputStream(BufferedOutputStream(GZIPOutputStream(FileOutputStream(cacheFile)))).use { out ->
        out.writeInt(MAGIC)
        out.writeInt(VERSION)
        out.writeLong(hprofFile.length())
        out.writeInt(fingerprint.size)
        out.write(fingerprint)
        out.writeInt(index.size)
        for ((childId, parentIds) in index) {
            out.writeLong(childId)
            out.writeInt(parentIds.size)
            for (parentId in parentIds) {
                out.writeLong(parentId)
            }
        }
    }
    logger.info { "Reverse index saved to ${cacheFile.name} (${cacheFile.length() / 1024 / 1024}MB)" }
}

/**
 * Loads the reverse index from a gzip-compressed binary cache file.
 * Returns null if the cache is stale (hprof file changed) or corrupt.
 */
internal fun loadReverseIndex(cacheFile: File, hprofFile: File): Map<Long, LongArray>? {
    try {
        DataInputStream(BufferedInputStream(GZIPInputStream(FileInputStream(cacheFile)))).use { inp ->
            val magic = inp.readInt()
            if (magic != MAGIC) {
                logger.warn { "Invalid cache magic: $magic, rebuilding" }
                return null
            }
            val version = inp.readInt()
            if (version != VERSION) {
                logger.warn { "Unsupported cache version: $version (expected $VERSION), rebuilding" }
                return null
            }

            val cachedSize = inp.readLong()
            val fingerprintLen = inp.readInt()
            val cachedFingerprint = ByteArray(fingerprintLen)
            inp.readFully(cachedFingerprint)

            if (cachedSize != hprofFile.length()) {
                logger.warn { "Cache stale: hprof size changed (cached=$cachedSize, actual=${hprofFile.length()}), rebuilding" }
                return null
            }
            val currentFingerprint = computeFingerprint(hprofFile)
            if (!cachedFingerprint.contentEquals(currentFingerprint)) {
                logger.warn { "Cache stale: hprof fingerprint mismatch, rebuilding" }
                return null
            }

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
    } catch (e: Exception) {
        logger.warn(e) { "Failed to load cache, rebuilding" }
        return null
    }
}
