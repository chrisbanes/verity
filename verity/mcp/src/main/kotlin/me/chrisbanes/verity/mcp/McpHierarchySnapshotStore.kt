package me.chrisbanes.verity.mcp

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.chrisbanes.verity.core.hierarchy.HierarchyNode

class McpHierarchySnapshotStore(
  private val maxPerSession: Int = 10,
) {
  private class SessionSnapshots(maxSize: Int) {
    val mutex = Mutex()
    val entries: LinkedHashMap<UUID, HierarchyNode> =
      object : LinkedHashMap<UUID, HierarchyNode>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<UUID, HierarchyNode>?): Boolean = size > maxSize
      }
  }

  private val sessions = ConcurrentHashMap<UUID, SessionSnapshots>()

  suspend fun add(sessionId: UUID, hierarchy: HierarchyNode): UUID {
    val session = sessions.computeIfAbsent(sessionId) { SessionSnapshots(maxPerSession) }
    val snapshotId = UUID.randomUUID()
    session.mutex.withLock {
      session.entries[snapshotId] = hierarchy
    }
    return snapshotId
  }

  suspend fun get(sessionId: UUID, snapshotId: UUID): HierarchyNode? {
    val session = sessions[sessionId] ?: return null
    return session.mutex.withLock { session.entries[snapshotId] }
  }

  suspend fun latest(sessionId: UUID): HierarchyNode? {
    val session = sessions[sessionId] ?: return null
    return session.mutex.withLock { session.entries.values.lastOrNull() }
  }

  suspend fun previous(sessionId: UUID): HierarchyNode? {
    val session = sessions[sessionId] ?: return null
    return session.mutex.withLock {
      val values = session.entries.values.toList()
      if (values.size >= 2) values[values.size - 2] else null
    }
  }

  fun clear(sessionId: UUID) {
    sessions.remove(sessionId)
  }
}
