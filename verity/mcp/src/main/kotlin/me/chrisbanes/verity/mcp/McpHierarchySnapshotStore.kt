package me.chrisbanes.verity.mcp

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import me.chrisbanes.verity.core.hierarchy.HierarchyNode

class McpHierarchySnapshotStore(
  private val maxPerSession: Int = 10,
) {
  private val sessions = ConcurrentHashMap<UUID, LinkedHashMap<UUID, HierarchyNode>>()

  fun add(sessionId: UUID, hierarchy: HierarchyNode): UUID {
    val snapshots =
      sessions.computeIfAbsent(sessionId) {
        object : LinkedHashMap<UUID, HierarchyNode>(maxPerSession, 0.75f, true) {
          override fun removeEldestEntry(eldest: MutableMap.MutableEntry<UUID, HierarchyNode>?): Boolean = size > maxPerSession
        }
      }
    val snapshotId = UUID.randomUUID()
    synchronized(snapshots) {
      snapshots[snapshotId] = hierarchy
    }
    return snapshotId
  }

  fun get(sessionId: UUID, snapshotId: UUID): HierarchyNode? = sessions[sessionId]?.let { synchronized(it) { it[snapshotId] } }

  fun latest(sessionId: UUID): HierarchyNode? = sessions[sessionId]?.let { synchronized(it) { it.values.lastOrNull() } }

  fun previous(sessionId: UUID): HierarchyNode? = sessions[sessionId]?.let {
    synchronized(it) {
      val values = it.values.toList()
      if (values.size >= 2) values[values.size - 2] else null
    }
  }

  fun clear(sessionId: UUID) {
    sessions.remove(sessionId)
  }
}
