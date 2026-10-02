# Hierarchy snapshot diff

`diff_hierarchy` compares two captured accessibility trees from one MCP session. It uses the full trees retained by `capture_hierarchy`, so capture-output filters do not hide changes from the diff. It performs no device capture or LLM call. Both stdio and HTTP expose the same tool registration.

## Snapshot selection

| Parameter | Required | Meaning when omitted |
| --- | --- | --- |
| `session_id` | Yes | No default |
| `before_snapshot_id` | No | Previous capture in this session |
| `after_snapshot_id` | No | Latest capture in this session |

IDs must be canonical UUID strings. Blank, malformed, non-string and explicit JSON `null` values are `invalid_argument`; only an absent optional parameter selects its default. Uppercase UUID strings are accepted.

Each omitted ID resolves independently. One capture supports an explicit before ID with omitted after ID, including self-comparison. An omitted before ID requires two captures. Both explicit IDs may select any retained snapshots in the session, including the same snapshot or a reverse chronological pair.

The store retains at most ten full trees per session and evicts the oldest capture when another is added. Reads do not change capture order or extend retention. Pair selection resolves explicit IDs, defaults and both tree references atomically. Explicit before is checked first, then explicit after, before omitted defaults are resolved. Retained references remain usable if later captures evict their store entries.

## Structural matching

The root path is `/`. Children are `/0`, `/1`, and so on; a grandchild can be `/0/1`. Every node participates, including empty containers. Matching paths compare all attributes and states. Attribute insertion order and state ordering do not affect equality. Paths on only one side are added or removed; child-list changes alone do not mark their parent changed.

Paths identify positions, not stable semantic elements. For example, inserting X before `[A, B]` produces `[X, A, B]`: `/0` and `/1` change, and `/2` is added. Sibling reordering or reparenting can likewise produce several structural changes for one user-visible action.

Comparing a snapshot with itself returns zero added, removed and changed counts, while retaining resolved IDs and focus summaries.

## Success response

Success is one compact JSON `TextContent`, with these fields:

| Field | Meaning |
| --- | --- |
| `session_id` | Session used for both snapshots |
| `before_snapshot_id`, `after_snapshot_id` | Resolved snapshot IDs |
| `added`, `removed`, `changed` | Full category counts and bounded samples |
| `focus_before`, `focus_after` | Full directly focused-node counts and bounded samples |
| `rendered_truncated` | Whether the total response budget removed samples |

Every category and focus object contains `count`, `samples`, `omitted_count` and `truncated`. Each sample contains `text` and `truncated`. Text describes the path, attributes and states; changed samples show before followed by after. Paths appear in tree preorder, with sorted attribute keys and states for deterministic descriptions.

Focus means the node has the `focused` state. An attribute named `focused` does not establish focus. Summaries count directly focused nodes, rather than the ancestor/sibling/descendant relationships used by `check_focused`.

## Output budgets

- Each category and focus summary returns at most 20 samples.
- Each complete serialized sample object, including JSON escaping and its flag, is at most 500 Kotlin string characters.
- The complete serialized success text, including IDs, metadata and both focus summaries, is at most 16,000 Kotlin string characters.

Sample text is clipped at Unicode code-point boundaries and ends with an ellipsis when clipped. Clipping sets the sample and category flags but does not count the returned entry as omitted. `omitted_count` is always the full count minus the number of returned samples. Category/focus `truncated` is true for any clipping or omission; full counts are never reduced.

When the total budget requires further omissions, samples are retained in this priority: focus before, focus after, changed, added, removed. Whole samples are removed in reverse priority, preserving preorder among those retained. Both focus containers remain present even if their samples must be omitted. `rendered_truncated` records this additional global trimming; it remains false when only the per-category or per-sample budgets apply.

## Errors and session close

Expected failures return one JSON `TextContent` with `isError=true` and an `error` object. It contains `code`, `message`, `remediation`, and applicable `session_id`, `parameter`, `snapshot_id`, `required_captures` and `available_captures` fields.

| Code | Cause and recovery |
| --- | --- |
| `invalid_argument` | Missing required session ID or invalid ID value. Supply UUID strings, or omit optional IDs for defaults. |
| `snapshot_unavailable` | Explicit snapshot is missing, evicted or belongs to another session. Capture again or use an ID from this session. These causes intentionally share one code without disclosing another session's identity. |
| `insufficient_captures` | An omitted default cannot resolve. The error identifies the parameter and required/available capture counts; capture another hierarchy. |
| `session_unavailable` | Session is absent or has been removed by close before diff admission. Open a session and capture again. |

Diff admission checks the session registry after acquiring its session mutex. A queued diff rejects a session removed before admission. An admitted diff resolves and retains its pair, then finishes while close waits for that mutex; device disposal and snapshot clearing follow. `close_session` clears the session's snapshots. Cancellation propagates, and unexpected callback failures retain the existing MCP error behavior.

## Source and coverage

- [Snapshot store](../../verity/mcp/src/main/kotlin/me/chrisbanes/verity/mcp/McpHierarchySnapshotStore.kt) and [store tests](../../verity/mcp/src/test/kotlin/me/chrisbanes/verity/mcp/McpHierarchySnapshotStoreTest.kt): capture ordering, eviction, atomic selection and typed failures.
- [HierarchyDiff](../../verity/mcp/src/main/kotlin/me/chrisbanes/verity/mcp/HierarchyDiff.kt) and [diff tests](../../verity/mcp/src/test/kotlin/me/chrisbanes/verity/mcp/HierarchyDiffTest.kt): raw structural matching, focus and serialized output budgets.
- [FocusDetector](../../verity/core/src/main/kotlin/me/chrisbanes/verity/core/hierarchy/FocusDetector.kt) and [focus tests](../../verity/core/src/test/kotlin/me/chrisbanes/verity/core/hierarchy/FocusDetectorTest.kt): focused-state predicate and existing relationship semantics.
- [MCP server](../../verity/mcp/src/main/kotlin/me/chrisbanes/verity/mcp/VerityMcpServer.kt), [handler tests](../../verity/mcp/src/test/kotlin/me/chrisbanes/verity/mcp/VerityMcpHierarchyDiffTest.kt) and [catalog tests](../../verity/mcp/src/test/kotlin/me/chrisbanes/verity/mcp/VerityMcpServerTest.kt): schema, IDs, structured errors, unfiltered storage, no capture, cancellation and deterministic close races.
