# Test Graph YAML Schema

Defines an object graph for testing the path-finding algorithm without real heap dumps.

## Top-Level Keys

| Key | Required | Type | Description |
|-----|----------|------|-------------|
| `roots` | yes | list of [Root](#root) | GC root entries |
| `objects` | yes | map of [Object](#object) | Named objects in the graph, keyed by ID |
| `targets` | yes | list of string | Object IDs to find paths to |
| `target_class` | no | string | Default class name for targets not defined in `objects` |
| `expected_paths` | no | list of string | Expected output paths (for assertion) |
| `expected_path_count` | no | integer | Expected number of paths (when exact paths don't matter) |
| `description` | no | string | Human-readable description of the test case |

## Root

| Key | Required | Type | Description |
|-----|----------|------|-------------|
| `type` | yes | string | GC root type (see [Root Types](#root-types)) |
| `objects` | yes | list of string | Object IDs held by this root |

### Root Types

- `JniGlobal`
- `JniLocal`
- `JavaFrame`
- `NativeStack`
- `ThreadBlock`
- `MonitorUsed`
- `ThreadObject`
- `JniMonitor`
- `ReferenceCleanup`
- `VmInternal`

## Object

Each entry in `objects` is keyed by a unique string ID.

| Key | Required | Type | Description |
|-----|----------|------|-------------|
| `class` | yes | string | Fully qualified class name |
| `fields` | no | map of string → string | Named field references: `fieldName: targetObjectId` |
| `elements` | no | list of string | Array element references (ordered): `[objectId, ...]` |

An object must have either `fields`, `elements`, or neither (leaf node). Not both.

## Path Format

Paths in `expected_paths` follow the output format:

```
Root[<type>] -> <ClassName>.<field> -> ... -> <TargetClass>
```

Array references use indexed notation:

```
Root[<type>] -> <ClassName>.<field> -> <ArrayClass>[<index>] -> <TargetClass>
```

## Example

```yaml
description: Simple chain through service registry

roots:
  - type: JniGlobal
    objects: [app_registry]

objects:
  app_registry:
    class: com.example.AppRegistry
    fields:
      service: svc1
  svc1:
    class: com.example.ServiceA
    fields:
      handler: handler1
  handler1:
    class: com.example.Handler
    fields:
      project: target1

targets: [target1]
target_class: com.example.ProjectImpl

expected_paths:
  - "Root[JniGlobal] -> com.example.AppRegistry.service -> com.example.ServiceA.handler -> com.example.Handler.project -> com.example.ProjectImpl"
```

## Example: Array References

```yaml
description: Path through an array

roots:
  - type: JavaFrame
    objects: [holder]

objects:
  holder:
    class: com.example.Holder
    fields:
      items: item_array
  item_array:
    class: java.lang.Object[]
    elements: [null, null, target1]

targets: [target1]
target_class: com.example.ProjectImpl

expected_paths:
  - "Root[JavaFrame] -> com.example.Holder.items -> java.lang.Object[][2] -> com.example.ProjectImpl"
```

## Example: Branching with Dead End

```yaml
description: One branch reaches root, other is a dead end

roots:
  - type: VmInternal
    objects: [root_obj]

objects:
  root_obj:
    class: com.example.Root
    fields:
      child: middle
  middle:
    class: com.example.Middle
    fields:
      project: target1
  dead_end:
    class: com.example.DeadEnd
    fields:
      project: target1

targets: [target1]
target_class: com.example.ProjectImpl

expected_paths:
  - "Root[VmInternal] -> com.example.Root.child -> com.example.Middle.project -> com.example.ProjectImpl"
```

## Example: Multiple Roots, Merge Near Root

```yaml
description: Two roots share a path, merge within threshold

roots:
  - type: JniGlobal
    objects: [root_a, root_b]

objects:
  root_a:
    class: com.example.RootA
    fields:
      shared: shared_node
  root_b:
    class: com.example.RootB
    fields:
      shared: shared_node
  shared_node:
    class: com.example.Shared
    fields:
      ref: target1

targets: [target1]
target_class: com.example.ProjectImpl

expected_path_count: 2
```
