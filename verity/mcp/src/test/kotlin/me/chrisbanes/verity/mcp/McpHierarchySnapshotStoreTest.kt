package me.chrisbanes.verity.mcp

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import java.util.UUID
import kotlin.test.Test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import me.chrisbanes.verity.core.hierarchy.HierarchyNode

class McpHierarchySnapshotStoreTest {
  private val sessionId = UUID.randomUUID()
  private val store = McpHierarchySnapshotStore()

  private fun createNode(text: String) = HierarchyNode(attributes = mapOf("text" to text))

  @Test
  fun `concurrent capture and resolution retain one complete store state`() = runTest {
    for (captureFirst in listOf(false, true)) {
      val bounded = McpHierarchySnapshotStore(maxPerSession = 2)
      val first = createNode("A")
      val second = createNode("B")
      val third = createNode("C")
      val a = bounded.add(sessionId, first)
      val b = bounded.add(sessionId, second)
      val start = CompletableDeferred<Unit>()
      suspend fun capture() = start.await().let { bounded.add(sessionId, third) }
      suspend fun resolve() = start.await().let { bounded.resolvePair(sessionId) }
      val captureJob = if (captureFirst) async(start = CoroutineStart.UNDISPATCHED) { capture() } else null
      val resolution = async(start = CoroutineStart.UNDISPATCHED) { resolve() }
      val laterCapture = if (!captureFirst) async(start = CoroutineStart.UNDISPATCHED) { capture() } else null
      start.complete(Unit)
      val c = (captureJob ?: laterCapture!!).await()
      val retained = resolution.await()
      val expected = if (captureFirst) {
        ResolvedHierarchySnapshotPair(b, second, c, third)
      } else {
        ResolvedHierarchySnapshotPair(a, first, b, second)
      }
      assertThat(retained).isEqualTo(expected)
      assertThat(bounded.get(sessionId, a)).isNull()
      assertThat(bounded.resolvePair(sessionId)).isEqualTo(ResolvedHierarchySnapshotPair(b, second, c, third))
      assertThat(retained).isEqualTo(expected)
    }
  }

  @Test
  fun `unavailable explicit IDs take precedence over defaults and preserve capture order`() = runTest {
    val missing = UUID.randomUUID()
    assertThat(store.resolvePair(sessionId, missing)).isEqualTo(
      UnavailableHierarchySnapshot(sessionId, "before_snapshot_id", missing),
    )
    assertThat(store.resolvePair(sessionId, afterSnapshotId = missing)).isEqualTo(
      UnavailableHierarchySnapshot(sessionId, "after_snapshot_id", missing),
    )
    val bounded = McpHierarchySnapshotStore(maxPerSession = 2)
    val a = bounded.add(sessionId, createNode("A"))
    val b = bounded.add(sessionId, createNode("B"))
    val c = bounded.add(sessionId, createNode("C"))
    val foreign = bounded.add(UUID.randomUUID(), createNode("foreign"))
    for (unavailable in listOf(missing, a, foreign)) {
      assertThat(bounded.resolvePair(sessionId, unavailable, missing)).isEqualTo(
        UnavailableHierarchySnapshot(sessionId, "before_snapshot_id", unavailable),
      )
      assertThat(bounded.resolvePair(sessionId, b, unavailable)).isEqualTo(
        UnavailableHierarchySnapshot(sessionId, "after_snapshot_id", unavailable),
      )
    }
    assertThat(bounded.resolvePair(sessionId)).isEqualTo(
      ResolvedHierarchySnapshotPair(b, createNode("B"), c, createNode("C")),
    )
  }

  @Test
  fun `default shortages report the unresolved parameter and capture count`() = runTest {
    assertThat(store.resolvePair(sessionId)).isEqualTo(InsufficientHierarchyCaptures(sessionId, "before_snapshot_id", 2, 0))
    val a = store.add(sessionId, createNode("A"))
    assertThat(store.resolvePair(sessionId)).isEqualTo(InsufficientHierarchyCaptures(sessionId, "before_snapshot_id", 2, 1))
    assertThat(store.resolvePair(sessionId, afterSnapshotId = a)).isEqualTo(InsufficientHierarchyCaptures(sessionId, "before_snapshot_id", 2, 1))
  }

  @Test
  fun `explicit IDs and independent defaults can select any retained pair`() = runTest {
    val first = createNode("A")
    val second = createNode("B")
    val a = store.add(sessionId, first)
    assertThat(store.resolvePair(sessionId, a)).isEqualTo(ResolvedHierarchySnapshotPair(a, first, a, first))
    val b = store.add(sessionId, second)
    for ((before, after) in listOf(a to b, null to b, a to null, null to null)) {
      assertThat(store.resolvePair(sessionId, before, after)).isEqualTo(ResolvedHierarchySnapshotPair(a, first, b, second))
    }
    assertThat(store.resolvePair(sessionId, b, a)).isEqualTo(ResolvedHierarchySnapshotPair(b, second, a, first))
    assertThat(store.resolvePair(sessionId, a, a)).isEqualTo(ResolvedHierarchySnapshotPair(a, first, a, first))
  }

  @Test
  fun `both defaults retain the previous and latest identities after reads`() = runTest {
    val first = createNode("A")
    val second = createNode("B")
    val a = store.add(sessionId, first)
    val b = store.add(sessionId, second)
    store.get(sessionId, a)
    assertThat(store.resolvePair(sessionId)).isEqualTo(
      ResolvedHierarchySnapshotPair(a, first, b, second),
    )
  }

  @Test
  fun `reading an older snapshot preserves capture order and eviction`() = runTest {
    val bounded = McpHierarchySnapshotStore(maxPerSession = 2)
    val first = createNode("A")
    val second = createNode("B")
    val a = bounded.add(sessionId, first)
    val b = bounded.add(sessionId, second)
    bounded.get(sessionId, a)
    assertThat(bounded.previous(sessionId)).isEqualTo(first)
    assertThat(bounded.latest(sessionId)).isEqualTo(second)
    bounded.add(sessionId, createNode("C"))
    assertThat(bounded.get(sessionId, a)).isNull()
    assertThat(bounded.get(sessionId, b)).isEqualTo(second)
  }

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
