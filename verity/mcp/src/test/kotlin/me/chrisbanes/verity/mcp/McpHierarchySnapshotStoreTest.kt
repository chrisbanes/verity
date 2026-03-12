package me.chrisbanes.verity.mcp

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import java.util.UUID
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import me.chrisbanes.verity.core.hierarchy.HierarchyNode

class McpHierarchySnapshotStoreTest {
  private val sessionId = UUID.randomUUID()
  private val store = McpHierarchySnapshotStore()

  private fun createNode(text: String) = HierarchyNode(attributes = mapOf("text" to text))

  @Test
  fun `add and retrieve snapshot`() = runTest {
    val node = createNode("Home")
    val id = store.add(sessionId, node)
    val snapshot = store.get(sessionId, id)
    assertThat(snapshot).isNotNull()
    assertThat(snapshot?.attributes?.get("text")).isEqualTo("Home")
  }

  @Test
  fun `get nonexistent snapshot returns null`() = runTest {
    assertThat(store.get(sessionId, UUID.randomUUID())).isNull()
  }

  @Test
  fun `latest returns most recent snapshot`() = runTest {
    store.add(sessionId, createNode("first"))
    store.add(sessionId, createNode("second"))
    assertThat(store.latest(sessionId)?.attributes?.get("text")).isEqualTo("second")
  }

  @Test
  fun `previous returns second-to-last snapshot`() = runTest {
    store.add(sessionId, createNode("first"))
    store.add(sessionId, createNode("second"))
    assertThat(store.previous(sessionId)?.attributes?.get("text")).isEqualTo("first")
  }

  @Test
  fun `evicts oldest when over capacity`() = runTest {
    val smallStore = McpHierarchySnapshotStore(maxPerSession = 3)
    val id1 = smallStore.add(sessionId, createNode("a"))
    smallStore.add(sessionId, createNode("b"))
    smallStore.add(sessionId, createNode("c"))
    smallStore.add(sessionId, createNode("d"))
    assertThat(smallStore.get(sessionId, id1)).isNull()
  }

  @Test
  fun `sessions are isolated`() = runTest {
    val session1 = UUID.randomUUID()
    val session2 = UUID.randomUUID()
    store.add(session1, createNode("session1 data"))
    store.add(session2, createNode("session2 data"))
    assertThat(store.latest(session1)?.attributes?.get("text")).isEqualTo("session1 data")
    assertThat(store.latest(session2)?.attributes?.get("text")).isEqualTo("session2 data")
  }

  @Test
  fun `clear removes all snapshots for session`() = runTest {
    store.add(sessionId, createNode("data"))
    store.clear(sessionId)
    assertThat(store.latest(sessionId)).isNull()
  }
}
