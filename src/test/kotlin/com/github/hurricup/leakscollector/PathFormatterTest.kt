package com.github.hurricup.leakscollector

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PathFormatterTest {

    @Test
    fun `single step`() {
        val path = listOf(PathStep.Target("com.example.Foo", 123))
        assertEquals("com.example.Foo@123", formatPath(path))
        assertEquals("com.example.Foo@123", formatPath(path, pretty = true))
    }

    @Test
    fun `field references inline`() {
        val path = listOf(
            PathStep.FieldReference("com.example.Root", "child"),
            PathStep.FieldReference("com.example.Child", "value"),
            PathStep.Target("com.example.Target", 42),
        )
        assertEquals(
            "com.example.Root.child -> com.example.Child.value -> com.example.Target@42",
            formatPath(path),
        )
    }

    @Test
    fun `field references pretty`() {
        val path = listOf(
            PathStep.FieldReference("com.example.Root", "child"),
            PathStep.FieldReference("com.example.Child", "value"),
            PathStep.Target("com.example.Target", 42),
        )
        assertEquals(
            "com.example.Root.child ->\n\tcom.example.Child.value ->\n\tcom.example.Target@42",
            formatPath(path, pretty = true),
        )
    }

    @Test
    fun `array reference inline`() {
        val path = listOf(
            PathStep.FieldReference("com.example.Holder", "data"),
            PathStep.ArrayReference("java.lang.Object[]", 7),
            PathStep.Target("com.example.Leak", 999),
        )
        assertEquals(
            "com.example.Holder.data -> java.lang.Object[][7] -> com.example.Leak@999",
            formatPath(path),
        )
    }

    @Test
    fun `array reference pretty`() {
        val path = listOf(
            PathStep.FieldReference("com.example.Holder", "data"),
            PathStep.ArrayReference("java.lang.Object[]", 7),
            PathStep.Target("com.example.Leak", 999),
        )
        assertEquals(
            "com.example.Holder.data ->\n\tjava.lang.Object[][7] ->\n\tcom.example.Leak@999",
            formatPath(path, pretty = true),
        )
    }
}
