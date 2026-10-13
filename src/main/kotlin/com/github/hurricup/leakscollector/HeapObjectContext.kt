package com.github.hurricup.leakscollector

// @spec ../../../../../../../spec/leak-targets.spec.md

import shark.HeapGraph
import shark.HeapObject

class HeapObjectContext(
    val heapObject: HeapObject,
    val graph: HeapGraph,
)
