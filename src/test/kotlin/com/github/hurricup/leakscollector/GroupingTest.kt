package com.github.hurricup.leakscollector

import kotlin.test.Test
import kotlin.test.assertEquals

class GroupingTest {

    /**
     * Declarative expected group: signature, the example path kept, and which target IDs collapsed into it.
     */
    private data class ExpectedGroup(
        val signature: String,
        val examplePath: String,
        val targetIds: List<Long>,
    )

    private fun runGroupingTest(
        entries: List<Triple<Long, String, String>>,
        expected: List<ExpectedGroup>,
    ) {
        val groups = groupPathsBySignature(entries)
        assertEquals(expected.size, groups.size, "Group count mismatch")
        for ((i, exp) in expected.withIndex()) {
            val actual = groups[i]
            assertEquals(exp.signature, actual.signature, "Signature mismatch at group $i")
            assertEquals(exp.examplePath, actual.examplePath, "Example path mismatch at group $i")
            assertEquals(exp.targetIds, actual.targetIds, "Target IDs mismatch at group $i")
        }
    }

    @Test
    fun `same signature collapses targets`() = runGroupingTest(
        entries = listOf(
            Triple(1L, "Root -> A.field -> Target", "path1"),
            Triple(2L, "Root -> A.field -> Target", "path2"),
            Triple(3L, "Root -> B.field -> Target", "path3"),
        ),
        expected = listOf(
            ExpectedGroup("Root -> A.field -> Target", "path1", listOf(1L, 2L)),
            ExpectedGroup("Root -> B.field -> Target", "path3", listOf(3L)),
        ),
    )

    @Test
    fun `all unique signatures`() = runGroupingTest(
        entries = listOf(
            Triple(1L, "sig1", "p1"),
            Triple(2L, "sig2", "p2"),
            Triple(3L, "sig3", "p3"),
        ),
        expected = listOf(
            ExpectedGroup("sig1", "p1", listOf(1L)),
            ExpectedGroup("sig2", "p2", listOf(2L)),
            ExpectedGroup("sig3", "p3", listOf(3L)),
        ),
    )

    @Test
    fun `all same signature`() = runGroupingTest(
        entries = (1L..5L).map { Triple(it, "same", "path") },
        expected = listOf(
            ExpectedGroup("same", "path", (1L..5L).toList()),
        ),
    )

    @Test
    fun `empty input`() = runGroupingTest(
        entries = emptyList(),
        expected = emptyList(),
    )
}
