package me.chrisbanes.verity.mcp

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import assertk.assertions.isIn
import assertk.assertions.isLessThanOrEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import java.util.UUID
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.chrisbanes.verity.core.hierarchy.HierarchyNode
import me.chrisbanes.verity.core.model.Platform
import me.chrisbanes.verity.core.preflight.PreflightReport
import me.chrisbanes.verity.device.DeviceSession
import me.chrisbanes.verity.device.FakeDeviceSession
import me.chrisbanes.verity.device.preflight.DevicePreflightChecker

class CaptureFocusedTreeToolTest {
  @Test
  fun `focused capture exposes only session and existing filter modes`() {
    val server = VerityMcpServer().create()
    assertThat(server.tools["capture_focused_tree"]).isNotNull()
    val schema = server.tools.getValue("capture_focused_tree").tool.inputSchema
    assertThat(schema.required).isEqualTo(listOf("session_id"))
    assertThat(schema.properties!!.keys).containsExactlyInAnyOrder("session_id", "filter")
    assertThat(schema.properties!!["session_id"]!!.jsonObject["type"]).isEqualTo(JsonPrimitive("string"))
    assertThat(schema.properties!!["filter"]!!.jsonObject["enum"]!!.jsonArray)
      .containsExactly(JsonPrimitive("focus"), JsonPrimitive("content"), JsonPrimitive("all"))
  }

  @Test
  fun `oversized capture stores every original node and attribute exactly once`() = runTest {
    val longLabel = "x".repeat(20_000)
    val deep = HierarchyNode(attributes = mapOf("text" to "outside-window"))
    val tree = HierarchyNode(
      attributes = mapOf("class" to "Root"),
      children = listOf(
        HierarchyNode(
          attributes = mapOf("text" to longLabel, "class" to "Button", "accessibilityText" to "spoken label"),
          states = setOf("focused"),
          children = listOf(HierarchyNode(children = listOf(HierarchyNode(children = listOf(deep))))),
        ),
      ) + List(101) { HierarchyNode(attributes = mapOf("text" to "focused-$it"), states = setOf("focused")) },
    )
    val fixture = fixture(tree)
    val result = fixture.capture("focus")
    assertThat(result.isError).isIn(null, false)
    assertThat(result.content.size).isEqualTo(1)
    val text = textOf(result)
    assertThat(text.length).isLessThanOrEqualTo(12_000)
    assertThat(text.lines().count { it.trimStart().startsWith("#") }).isEqualTo(100)
    assertThat(text).contains("node_limit=true")
    assertThat(text).contains("text=true")
    assertThat(text).doesNotContain("class=")
    assertThat(text).doesNotContain("spoken label")
    assertThat(text).doesNotContain("outside-window")
    assertThat(fixture.session.captureCount).isEqualTo(1)
    val stored = fixture.store.get(fixture.sessionId, snapshotId(text))!!
    assertThat(stored).isEqualTo(tree)
    assertThat(stored.children.first().attributes).isEqualTo(
      mapOf("text" to longLabel, "class" to "Button", "accessibilityText" to "spoken label"),
    )
    assertThat(stored.children.first().children.single().children.single().children.single()).isEqualTo(deep)
    assertThat(stored.children.size).isEqualTo(102)
  }

  @Test
  fun `all filter choices and CONTENT fallbacks preserve focused state and full snapshots`() = runTest {
    val tree = HierarchyNode(
      attributes = linkedMapOf("text" to "Home", "accessibilityText" to "spoken", "class" to "Button", "selected" to "true"),
      states = setOf("focused"),
    )
    val fixture = fixture(tree)
    val choices = listOf(
      "focus" to "#0 [text=Home, selected=true] (focused)",
      "content" to "#0 [text=Home, accessibilityText=spoken] (focused)",
      "all" to "#0 [text=Home, accessibilityText=spoken, class=Button, selected=true] (focused)",
      null to "#0 [text=Home, accessibilityText=spoken] (focused)",
      "unknown" to "#0 [text=Home, accessibilityText=spoken] (focused)",
    )
    for ((filter, row) in choices) {
      val result = fixture.capture(filter)
      assertThat(result.isError).isIn(null, false)
      val text = textOf(result)
      assertThat(text).contains("focus_status: present")
      assertThat(text).contains("focused_labels_filtered: 0")
      assertThat(text.lineSequence().last { it.isNotEmpty() }).isEqualTo(row)
      assertThat(fixture.store.get(fixture.sessionId, snapshotId(text))).isEqualTo(tree)
    }
    assertThat(fixture.session.captureCount).isEqualTo(5)
  }

  @Test
  fun `filtered labels remain present and differ from inherently empty focused labels`() = runTest {
    for ((attributes, count) in listOf(mapOf("accessibilityText" to "spoken focus") to 1, emptyMap<String, String>() to 0)) {
      val tree = HierarchyNode(attributes = attributes, states = setOf("focused"))
      val fixture = fixture(tree)
      val result = fixture.capture("focus")
      assertThat(result.isError).isIn(null, false)
      val text = textOf(result)
      assertThat(text).contains("focus_status: present")
      assertThat(text).contains("focused: total=1 included=1 omitted_node_limit=0 omitted_character_limit=0")
      assertThat(text).contains("focused_labels_filtered: $count")
      assertThat(text).contains("#0 [] (focused)")
      assertThat(text).doesNotContain("[text truncated]")
      assertThat(text).contains("text=false")
      assertThat(fixture.store.get(fixture.sessionId, snapshotId(text))).isEqualTo(tree)
      assertThat(fixture.session.captureCount).isEqualTo(1)
    }
  }

  @Test
  fun `attribute-only focus returns no-focus metadata and a complete retrievable snapshot`() = runTest {
    val tree = HierarchyNode(attributes = mapOf("text" to "Ghost", "focused" to "true", "class" to "Button"))
    val fixture = fixture(tree)
    suspend fun check(): String = textOf(
      fixture.call(
        "check_focused",
        buildJsonObject {
          put("session_id", fixture.sessionId.toString())
          put("text", "Ghost")
        },
      ),
    )
    assertThat(check()).isEqualTo("false")
    val before = fixture.session.captureCount
    val text = textOf(fixture.capture("all"))
    assertThat(fixture.session.captureCount - before).isEqualTo(1)
    assertThat(text.substringAfter('\n')).isEqualTo(
      "focus_status: no_focus\n" +
        "focused: total=0 included=0 omitted_node_limit=0 omitted_character_limit=0\n" +
        "nodes: included=0 omitted_context=0 outside_context=1 omitted_regions=0\n" +
        "focused_labels_filtered: 0\n" +
        "truncated: node_limit=false character_limit=false text=false\n",
    )
    assertThat(fixture.store.get(fixture.sessionId, snapshotId(text))).isEqualTo(tree)
    assertThat(check()).isEqualTo("false")
  }

  @Test
  fun `leaf container sibling and multiple focus captures preserve existing focus assertions`() = runTest {
    val leaf = HierarchyNode(attributes = mapOf("text" to "Home"), states = setOf("focused"))
    val container = HierarchyNode(
      states = setOf("focused"),
      children = listOf(HierarchyNode(attributes = mapOf("text" to "Details"), children = listOf(HierarchyNode(attributes = mapOf("text" to "Subtitle"))))),
    )
    val sibling = HierarchyNode(children = listOf(HierarchyNode(states = setOf("focused")), HierarchyNode(attributes = mapOf("text" to "Settings"))))
    val multiple = HierarchyNode(
      children = listOf(
        HierarchyNode(attributes = mapOf("text" to "First"), states = setOf("focused")),
        HierarchyNode(attributes = mapOf("text" to "Second"), states = setOf("focused")),
      ),
    )
    val cases = listOf(
      Triple(leaf, listOf("Home"), listOf("#0 [text=Home] (focused)")),
      Triple(container, listOf("Details", "Subtitle"), listOf("#0 [] (focused)", "  #1 [text=Details]", "    #2 [text=Subtitle]")),
      Triple(sibling, listOf("Settings"), listOf("#0 [context]", "  #1 [] (focused)", "  #2 [text=Settings]")),
      Triple(multiple, listOf("First", "Second"), listOf("#0 [context]", "  #1 [text=First] (focused)", "  #2 [text=Second] (focused)")),
    )
    for ((tree, labels, rows) in cases) {
      val fixture = fixture(tree)
      suspend fun check(text: String): String = textOf(
        fixture.call(
          "check_focused",
          buildJsonObject {
            put("session_id", fixture.sessionId.toString())
            put("text", text)
          },
        ),
      )
      for (label in labels) assertThat(check(label)).isEqualTo("true")
      assertThat(check("NotPresent")).isEqualTo("false")
      val before = fixture.session.captureCount
      val text = textOf(fixture.capture())
      assertThat(fixture.session.captureCount - before).isEqualTo(1)
      assertThat(text.lines().filter { it.trimStart().startsWith("#") }).isEqualTo(rows)
      assertThat(text).contains("focus_status: present")
      assertThat(fixture.store.get(fixture.sessionId, snapshotId(text))).isEqualTo(tree)
      for (label in labels) assertThat(check(label)).isEqualTo("true")
      assertThat(check("NotPresent")).isEqualTo("false")
    }
  }

  @Test
  fun `structural character omissions preserve present focus and the complete deep snapshot`() = runTest {
    var tree = HierarchyNode(states = setOf("focused"))
    repeat(499) { tree = HierarchyNode(states = setOf("focused"), children = listOf(tree)) }
    val fixture = fixture(tree)
    val result = fixture.capture("focus")
    assertThat(result.isError).isIn(null, false)
    val text = textOf(result)
    assertThat(text.length).isLessThanOrEqualTo(12_000)
    assertThat(text.lines().count { it.trimStart().startsWith("#") }).isLessThanOrEqualTo(100)
    assertThat(text).contains("focus_status: present")
    assertThat(text).contains("focused: total=500")
    assertThat(text).contains("character_limit=true")
    assertThat(Regex("omitted_character_limit=(\\d+)").find(text)!!.groupValues[1].toInt() > 0).isTrue()
    assertThat(text).doesNotContain("[text truncated]")
    assertThat(text).contains("text=false")
    assertThat(fixture.store.get(fixture.sessionId, snapshotId(text))).isEqualTo(tree)
    assertThat(fixture.session.captureCount).isEqualTo(1)
  }

  @Test
  fun `missing malformed nonexistent and closed sessions return errors without a new snapshot`() = runTest {
    val tree = HierarchyNode(attributes = mapOf("text" to "Home"), states = setOf("focused"))
    val fixture = fixture(tree)
    val invalid = listOf(
      null,
      buildJsonObject { },
      buildJsonObject { put("session_id", "invalid") },
      buildJsonObject { put("session_id", "00000000-0000-0000-0000-000000000001") },
    )
    for (arguments in invalid) {
      val result = fixture.call("capture_focused_tree", arguments)
      assertThat(result.isError).isEqualTo(true)
      assertThat(textOf(result)).contains("IllegalArgumentException")
      assertThat(fixture.store.latest(fixture.sessionId)).isEqualTo(null)
      assertThat(fixture.session.captureCount).isEqualTo(0)
    }
    val capture = textOf(fixture.capture())
    val id = snapshotId(capture)
    val closed = fixture.call("close_session", buildJsonObject { put("session_id", fixture.sessionId.toString()) })
    assertThat(closed.isError).isIn(null, false)
    assertThat(fixture.store.get(fixture.sessionId, id)).isEqualTo(null)
    val result = fixture.capture()
    assertThat(result.isError).isEqualTo(true)
    assertThat(textOf(result)).contains("No session found")
    assertThat(fixture.store.latest(fixture.sessionId)).isEqualTo(null)
    assertThat(fixture.session.captureCount).isEqualTo(1)
  }

  @Test
  fun `capture failures return errors and cancellation propagates without storing a snapshot`() = runTest {
    val tree = HierarchyNode(states = setOf("focused"))
    val ordinary = object : DeviceSession by FakeDeviceSession() {
      override suspend fun captureHierarchyTree(): HierarchyNode = throw java.io.IOException("capture failed")
    }
    val failed = fixture(tree, ordinary)
    val result = failed.capture()
    assertThat(result.isError).isEqualTo(true)
    assertThat(textOf(result)).isEqualTo("IOException: capture failed")
    assertThat(failed.store.latest(failed.sessionId)).isEqualTo(null)
    val cancellation = kotlinx.coroutines.CancellationException("cancelled capture")
    val cancelling = object : DeviceSession by FakeDeviceSession() {
      override suspend fun captureHierarchyTree(): HierarchyNode = throw cancellation
    }
    val cancelled = fixture(tree, cancelling)
    val thrown = runCatching { cancelled.capture() }.exceptionOrNull()
    assertThat(thrown === cancellation).isTrue()
    assertThat(cancelled.store.latest(cancelled.sessionId)).isEqualTo(null)
  }

  @Test
  fun `ordinary and focused captures retain full snapshots for existing diffing`() = runTest {
    val tree = HierarchyNode(
      attributes = mapOf("class" to "Root"),
      children = listOf(HierarchyNode(attributes = linkedMapOf("text" to "Home", "class" to "Button", "accessibilityText" to "spoken"), states = setOf("focused"))),
    )
    val fixture = fixture(tree)
    val ordinary = textOf(
      fixture.call(
        "capture_hierarchy",
        buildJsonObject {
          put("session_id", fixture.sessionId.toString())
          put("filter", "all")
        },
      ),
    )
    assertThat(ordinary.substringAfter("\n\n")).isEqualTo("[class=Root]\n  [text=Home, class=Button, accessibilityText=spoken] (focused)\n")
    val before = snapshotId(ordinary)
    val focused = textOf(fixture.capture("focus"))
    val after = snapshotId(focused)
    assertThat(before == after).isEqualTo(false)
    assertThat(fixture.store.get(fixture.sessionId, before)).isEqualTo(tree)
    assertThat(fixture.store.get(fixture.sessionId, after)).isEqualTo(tree)
    assertThat(focused).doesNotContain("class=")
    assertThat(focused).doesNotContain("spoken")
    val diff = fixture.call(
      "diff_hierarchy",
      buildJsonObject {
        put("session_id", fixture.sessionId.toString())
        put("before_snapshot_id", before.toString())
        put("after_snapshot_id", after.toString())
      },
    )
    assertThat(diff.isError).isIn(null, false)
    val json = kotlinx.serialization.json.Json.parseToJsonElement(textOf(diff)).jsonObject
    assertThat(json["before_snapshot_id"]).isEqualTo(JsonPrimitive(before.toString()))
    assertThat(json["after_snapshot_id"]).isEqualTo(JsonPrimitive(after.toString()))
    assertThat(json["added"]!!.jsonObject["count"]).isEqualTo(JsonPrimitive(0))
    assertThat(json["removed"]!!.jsonObject["count"]).isEqualTo(JsonPrimitive(0))
    assertThat(json["changed"]!!.jsonObject["count"]).isEqualTo(JsonPrimitive(0))
    assertThat(fixture.session.captureCount).isEqualTo(2)
  }

  private class CountingSession(private val delegate: DeviceSession) : DeviceSession by delegate {
    var captureCount = 0
    override suspend fun captureHierarchyTree(): HierarchyNode {
      captureCount++
      return delegate.captureHierarchyTree()
    }
  }

  private class Fixture(
    val server: Server,
    val session: CountingSession,
    val sessionId: UUID,
    val store: McpHierarchySnapshotStore,
  ) {
    suspend fun call(name: String, arguments: JsonObject? = null): CallToolResult = server.tools.getValue(name).handler.invoke(
      StubClientConnection(),
      CallToolRequest(CallToolRequestParams(name = name, arguments = arguments)),
    )
    suspend fun capture(filter: String? = null): CallToolResult = call(
      "capture_focused_tree",
      buildJsonObject {
        put("session_id", sessionId.toString())
        filter?.let { put("filter", it) }
      },
    )
  }

  private suspend fun fixture(tree: HierarchyNode, device: DeviceSession = FakeDeviceSession(hierarchyNode = tree)): Fixture {
    val session = CountingSession(device)
    val manager = McpDeviceSessionManager { _, _, _ -> session }
    val store = McpHierarchySnapshotStore()
    val id = manager.open(Platform.ANDROID_TV, "fake").sessionId
    val server = VerityMcpServer(
      sessionManager = manager,
      snapshotStore = store,
      devicePreflightChecker = DevicePreflightChecker { _, _ -> PreflightReport() },
    ).create()
    return Fixture(server, session, id, store)
  }

  private fun textOf(result: CallToolResult): String = (result.content.single() as TextContent).text
  private fun snapshotId(text: String): UUID = UUID.fromString(text.lineSequence().first().substringAfter("snapshot_id: "))
}
