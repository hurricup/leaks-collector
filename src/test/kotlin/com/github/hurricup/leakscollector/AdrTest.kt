package com.github.hurricup.leakscollector

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readLines

private val ADR_DIR: Path = Path.of("adr")

private val ID_BASE: Instant = Instant.parse("2026-11-18T05:20:25Z")
private const val ID_RADIX = 36
private const val ID_LENGTH = 6
private const val ID_PAD_CHAR = '0'
private const val NUMBER_LENGTH = 4
private const val LAST_NUMBER = 6
private const val CHAIN_DEPTH_LIMIT = 2

private val NUMBERED_FILE = Regex("""^(\d{$NUMBER_LENGTH})-.+\.md$""")
private val ID_FILE = Regex("""^([0-9A-Z]{$ID_LENGTH})-.+\.md$""")
private val UTC_DATE_LINE = Regex("""^Date: (\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z)$""")
private val RELATION_PHRASE = Regex("""\b(Amends|Supersedes|Amended by|Superseded by)\b""")
private val MARKDOWN_LINK_TARGET = Regex("""\[[^\]]*]\(([^)\s]+)\)""")
private val NEWER_TO_OLDER_PHRASES = setOf("Amends", "Supersedes")

private const val STATUS_HEADING = "## Status"
private const val SECTION_HEADING_PREFIX = "## "
private const val TITLE_PREFIX = "# "

/**
 * Checks the decision records in the `adr/` folder: file names, ids, titles, `Date:` lines and the depth of
 * amendment and supersession chains.
 *
 * The id rules follow ADR 0006GM.
 */
class AdrTest {

    @Test
    fun `every record file name starts with a number or an id`() {
        val misnamed = adrFiles().filter { idOf(it) == null }.map { it.name }
        assertTrue(misnamed.isEmpty(), "ADR files with neither a number nor an id: $misnamed")
    }

    @Test
    fun `no record takes a new number`() {
        val newlyNumbered = adrFiles().filter { file ->
            NUMBERED_FILE.find(file.name)?.groupValues?.get(1)?.toInt()?.let { it > LAST_NUMBER } == true
        }.map { it.name }
        assertTrue(newlyNumbered.isEmpty(), "ADR files numbered above $LAST_NUMBER: $newlyNumbered")
    }

    @Test
    fun `no two records hold one id`() {
        val sharedIds = adrFiles()
            .groupBy { idOf(it) }
            .filterKeys { it != null }
            .filterValues { it.size > 1 }
            .mapValues { (_, files) -> files.map { it.name } }
        assertTrue(sharedIds.isEmpty(), "ADR files that hold one id: $sharedIds")
    }

    @Test
    fun `id matches the title and the date of the record`() {
        val mismatches = adrFiles().mapNotNull { file ->
            val id = ID_FILE.find(file.name)?.groupValues?.get(1) ?: return@mapNotNull null
            idMismatch(id, file.readLines())?.let { "${file.name}: $it" }
        }
        assertTrue(mismatches.isEmpty(), "ADR ids that do not match their records:\n${mismatches.joinToString("\n")}")
    }

    @Test
    fun `no chain of amendments and supersessions exceeds the depth limit`() {
        val olderByNewer = newerToOlderEdges(adrFiles())
        val tooDeep = olderByNewer.keys
            .map { longestChainFrom(it, olderByNewer, emptyList()) }
            .filter { it.size > CHAIN_DEPTH_LIMIT }
            .map { it.joinToString(" -> ") }
        assertTrue(
            tooDeep.isEmpty(),
            "ADR chains longer than $CHAIN_DEPTH_LIMIT records:\n${tooDeep.joinToString("\n")}",
        )
    }
}

private fun adrFiles(): List<Path> = ADR_DIR.listDirectoryEntries("*.md").sortedBy { it.name }

/**
 * Returns the id that the record [file] holds, with a number `NNNN` read as `00NNNN`.
 *
 * @return the six-character id, or `null` when the file name has neither shape.
 */
private fun idOf(file: Path): String? =
    ID_FILE.find(file.name)?.groupValues?.get(1)
        ?: NUMBERED_FILE.find(file.name)?.groupValues?.get(1)?.padStart(ID_LENGTH, ID_PAD_CHAR)

/**
 * Returns the id that the creation time [created] gives: seconds from [ID_BASE], in uppercase base 36, padded.
 */
private fun idForTime(created: Instant): String =
    Duration.between(ID_BASE, created).seconds.toString(ID_RADIX).uppercase().padStart(ID_LENGTH, ID_PAD_CHAR)

/**
 * Checks the title and the `Date:` line of a record with the six-character [id].
 *
 * @param lines the lines of the record.
 * @return a description of the first mismatch, or `null` when the record matches its id.
 */
private fun idMismatch(id: String, lines: List<String>): String? {
    val title = lines.firstOrNull { it.startsWith(TITLE_PREFIX) }
    if (title == null || !title.startsWith("$TITLE_PREFIX$id. ")) return "title does not start with '# $id. ': $title"
    val dateText = lines.firstNotNullOfOrNull { UTC_DATE_LINE.find(it)?.groupValues?.get(1) }
        ?: return "no 'Date: YYYY-MM-DDThh:mm:ssZ' line"
    val created = try {
        Instant.parse(dateText)
    } catch (e: DateTimeParseException) {
        return "date '$dateText' is not a valid time: ${e.message}"
    }
    val expectedId = idForTime(created)
    return if (expectedId == id) null else "date $dateText gives the id $expectedId"
}

/**
 * Collects the relations from the `## Status` section of each record in [files].
 *
 * `A Amends B` and `B Amended by A` give the same edge from A to B. Only links to files of [files] count.
 *
 * @return the older record file names by each newer record file name.
 */
private fun newerToOlderEdges(files: List<Path>): Map<String, Set<String>> {
    val fileNames = files.map { it.name }.toSet()
    val edges = mutableMapOf<String, MutableSet<String>>()
    for (file in files) {
        val status = statusSection(file.readLines())
        val phrases = RELATION_PHRASE.findAll(status).toList()
        phrases.forEachIndexed { index, phrase ->
            val linksEnd = phrases.getOrNull(index + 1)?.range?.first ?: status.length
            val linkedNames = MARKDOWN_LINK_TARGET.findAll(status.substring(phrase.range.last + 1, linksEnd))
                .map { it.groupValues[1] }
                .filter { it in fileNames }
            for (linked in linkedNames) {
                val isNewerToOlder = phrase.value in NEWER_TO_OLDER_PHRASES
                val (newer, older) = if (isNewerToOlder) file.name to linked else linked to file.name
                edges.getOrPut(newer) { mutableSetOf() } += older
            }
        }
    }
    return edges
}

private fun statusSection(lines: List<String>): String =
    lines.dropWhile { it.trim() != STATUS_HEADING }
        .drop(1)
        .takeWhile { !it.startsWith(SECTION_HEADING_PREFIX) }
        .joinToString("\n")

/**
 * Returns [chainSoFar] extended by the longest chain of records that starts at [record] and follows
 * [olderByNewer].
 *
 * Fails the test when the chain comes back to a record in [chainSoFar].
 *
 * @param chainSoFar the records from the start of the chain up to, not including, [record].
 */
private fun longestChainFrom(
    record: String,
    olderByNewer: Map<String, Set<String>>,
    chainSoFar: List<String>,
): List<String> {
    if (record in chainSoFar) fail<Unit>("ADR relation cycle: ${(chainSoFar + record).joinToString(" -> ")}")
    val chain = chainSoFar + record
    val longestTail = olderByNewer[record].orEmpty()
        .map { longestChainFrom(it, olderByNewer, chain) }
        .maxByOrNull { it.size }
    return longestTail ?: chain
}
