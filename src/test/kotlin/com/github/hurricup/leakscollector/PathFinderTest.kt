package com.github.hurricup.leakscollector

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.fail

class PathFinderTest {

    @Test
    fun `simple chain`() = runGraphTest("simple-chain.yaml")

    @Test
    fun `dead end with backtrack`() = runGraphTest("dead-end-with-backtrack.yaml")

    @Test
    fun `merge near root produces two paths`() = runGraphTest("merge-near-root.yaml")

    @Test
    fun `merge far from root skips redundant path`() = runGraphTest("merge-far-from-root-skip.yaml")

    @Test
    fun `merge far from root displaces longer prefix`() = runGraphTest("merge-far-displacement.yaml")

    @Test
    fun `cycle avoidance`() = runGraphTest("cycle-avoidance.yaml")

    @Test
    fun `array reference`() = runGraphTest("array-reference.yaml")

    @Test
    fun `target is root`() = runGraphTest("target-is-root.yaml")

    @Test
    fun `multiple targets`() = runGraphTest("multiple-targets.yaml")

    @Test
    fun `multiple roots`() = runGraphTest("multiple-roots.yaml")

    @Test
    fun `no path to target`() = runGraphTest("no-path.yaml")

    @Test
    fun `four targets diverge from shared prefix`() = runGraphTest("shared-prefix-four-targets.yaml")

    @Test
    fun `two pairs with separate shared paths`() = runGraphTest("two-pairs-shared-paths.yaml")

    @Test
    fun `cascading fallback discovers all unique paths`() = runGraphTest("cascading-fallback.yaml")

    @Test
    fun `cascading fallback with dependent target`() = runGraphTest("cascading-with-dependent.yaml")

    @Test
    fun `disposer anchor extends merge depth beyond default`() = runGraphTest("disposer-merge-depth.yaml")

    @Test
    fun `cross-target path is dead end`() = runGraphTest("cross-target-dead-end.yaml")

    @Test
    fun `cross-target filters only path through other target`() = runGraphTest("cross-target-partial.yaml")

    @Test
    fun `schema rejects invalid yaml`() {
        assertFails("Schema should reject unknown fields") {
            loadTestGraph("invalid-schema.yaml")
        }
    }

    private fun runGraphTest(fileName: String) {
        val testGraph = loadTestGraph(fileName)
        val (reverseIndex, rootObjectIds, objectIds, objectDefs) = buildTestData(testGraph)

        val targetIds = testGraph.targets.map { objectIds.getValue(it) }

        val defaultMergeDepth = 3
        val idToName = objectIds.entries.associate { (k, v) -> v to k }
        val classNameOf: (Long) -> String? = { id -> objectDefs[idToName[id]]?.`class` }
        val allTargetIds = targetIds.toHashSet()
        val claimedNodes = HashSet<Long>()
        val allPaths = mutableListOf<String>()
        val dependentTargets = mutableListOf<String>()
        for (targetId in targetIds) {
            val targetName = objectIds.entries.first { it.value == targetId }.key
            val targetClass = testGraph.objects?.get(targetName)?.`class`
                ?: testGraph.target_class
                ?: error("No class for target $targetName: define it in objects or set target_class")

            val records = findPathsForTarget(targetId, reverseIndex, rootObjectIds, allTargetIds, claimedNodes, defaultMergeDepth, classNameOf)

            if (records.isEmpty()) {
                dependentTargets.add(targetClass)
                continue
            }

            for (record in records) {
                val ids = record.idsFromTarget
                val stepsExcludingRoot = ids.size - 1
                val farFromRootCount = maxOf(0, stepsExcludingRoot - record.mergeDepth + 1)
                for (i in 0 until farFromRootCount) {
                    claimedNodes.add(ids[i])
                }
                val path = formatTestPath(record, targetId, targetClass, objectIds, objectDefs, testGraph.roots)
                allPaths.add(path)
            }
        }

        if (testGraph.expected_paths != null) {
            assertEquals(
                testGraph.expected_paths.sorted(),
                allPaths.sorted(),
                "Paths mismatch"
            )
        }
        if (testGraph.expected_path_count != null) {
            assertEquals(testGraph.expected_path_count, allPaths.size, "Path count mismatch")
        }
    }

    private val yamlMapper = ObjectMapper(YAMLFactory()).registerKotlinModule()
    private val jsonMapper = ObjectMapper()
    private val schema = run {
        val schemaStream = javaClass.classLoader.getResourceAsStream("test-graph-schema.json")
            ?: error("Schema not found: test-graph-schema.json")
        val schemaNode = jsonMapper.readTree(schemaStream)
        JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(schemaNode)
    }

    private fun loadTestGraph(fileName: String): TestGraph {
        val stream = javaClass.classLoader.getResourceAsStream("graphs/$fileName")
            ?: error("Test graph not found: graphs/$fileName")
        val yamlNode: JsonNode = yamlMapper.readTree(stream)

        val errors = schema.validate(yamlNode)
        if (errors.isNotEmpty()) {
            fail("Schema validation failed for $fileName:\n${errors.joinToString("\n") { "  - ${it.message}" }}")
        }

        return yamlMapper.treeToValue(yamlNode, TestGraph::class.java)
    }

    private data class TestData(
        val reverseIndex: Map<Long, LongArray>,
        val rootObjectIds: Set<Long>,
        val objectIds: Map<String, Long>,
        val objectDefs: Map<String, TestObject>,
    )

    private fun buildTestData(graph: TestGraph): TestData {
        val objectDefs = graph.objects ?: emptyMap()

        // Assign stable Long IDs to all named objects
        val allNames = linkedSetOf<String>()
        for (root in graph.roots) {
            allNames.addAll(root.objects)
        }
        for ((name, obj) in objectDefs) {
            allNames.add(name)
            obj.fields?.values?.let { allNames.addAll(it) }
            obj.elements?.filterNotNull()?.let { allNames.addAll(it) }
        }
        allNames.addAll(graph.targets)

        val objectIds = LinkedHashMap<String, Long>()
        var nextId = 1000L
        for (name in allNames) {
            objectIds.getOrPut(name) { nextId++ }
        }

        // Build reverse index: childId -> parentIds
        val forwardRefs = HashMap<Long, ArrayList<Long>>()
        for ((name, obj) in objectDefs) {
            val parentId = objectIds.getValue(name)
            obj.fields?.values?.forEach { childName ->
                val childId = objectIds.getValue(childName)
                forwardRefs.getOrPut(childId) { ArrayList() }.add(parentId)
            }
            obj.elements?.forEachIndexed { _, childName ->
                if (childName != null) {
                    val childId = objectIds.getValue(childName)
                    forwardRefs.getOrPut(childId) { ArrayList() }.add(parentId)
                }
            }
        }
        val reverseIndex = forwardRefs.mapValues { (_, list) -> list.toLongArray() }

        val rootObjectIds = graph.roots
            .flatMap { it.objects }
            .map { objectIds.getValue(it) }
            .toSet()

        return TestData(reverseIndex, rootObjectIds, objectIds, objectDefs)
    }

    private fun formatTestPath(
        record: PathRecord,
        targetId: Long,
        targetClass: String,
        objectIds: Map<String, Long>,
        objectDefs: Map<String, TestObject>,
        roots: List<TestRoot>,
    ): String {
        val idToName = objectIds.entries.associate { (k, v) -> v to k }

        // Build path as list of IDs: root, intermediate (reversed), target
        val ids = ArrayList<Long>()
        ids.add(record.rootObjectId)
        for (i in record.idsFromTarget.indices.reversed()) {
            ids.add(record.idsFromTarget[i])
        }
        ids.add(targetId)

        val parts = ArrayList<String>()

        // Root step
        val rootName = idToName.getValue(record.rootObjectId)
        val rootType = roots.first { rootName in it.objects }.type
        parts.add("Root[$rootType]")

        // Edge steps
        for (i in 0 until ids.size - 1) {
            val parentId = ids[i]
            val childId = ids[i + 1]
            if (parentId == childId) continue
            val parentName = idToName.getValue(parentId)
            val parentDef = objectDefs[parentName]
            if (parentDef != null) {
                val fieldEntry = parentDef.fields?.entries?.firstOrNull { objectIds[it.value] == childId }
                if (fieldEntry != null) {
                    parts.add("${parentDef.`class`}.${fieldEntry.key}")
                    continue
                }
                val elementIndex = parentDef.elements?.indexOfFirst { it != null && objectIds[it] == childId }
                if (elementIndex != null && elementIndex >= 0) {
                    parts.add("${parentDef.`class`}[$elementIndex]")
                    continue
                }
            }
            parts.add("${parentDef?.`class` ?: parentName}.?")
        }

        // Target step
        parts.add(targetClass)

        return parts.joinToString(" -> ")
    }

    // YAML data classes

    private data class TestGraph(
        val description: String? = null,
        val roots: List<TestRoot>,
        val objects: Map<String, TestObject>? = null,
        val targets: List<String>,
        val target_class: String? = null,
        val expected_paths: List<String>? = null,
        val expected_path_count: Int? = null,
    )

    private data class TestRoot(
        val type: String,
        val objects: List<String>,
    )

    private data class TestObject(
        val `class`: String,
        val fields: Map<String, String>? = null,
        val elements: List<String?>? = null,
    )
}
