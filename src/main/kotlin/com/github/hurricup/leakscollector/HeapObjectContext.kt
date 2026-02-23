package com.github.hurricup.leakscollector

import shark.HeapGraph
import shark.HeapObject

class HeapObjectContext(
    val heapObject: HeapObject,
    val graph: HeapGraph,
)
