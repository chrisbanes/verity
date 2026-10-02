package me.chrisbanes.verity.mcp

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.chrisbanes.verity.core.hierarchy.HierarchyNode

sealed interface HierarchySnapshotPairResult

data class ResolvedHierarchySnapshotPair(
  val beforeSnapshotId: UUID,
  val before: HierarchyNode,
  val afterSnapshotId: UUID,
  val after: HierarchyNode,
) : HierarchySnapshotPairResult

data class UnavailableHierarchySnapshot(
  val sessionId: UUID,
  val parameter: String,
  val snapshotId: UUID,
) : HierarchySnapshotPairResult

data class InsufficientHierarchyCaptures(
  val sessionId: UUID,
  val parameter: String,
  val requiredCaptures: Int,
  val availableCaptures: Int,
) : HierarchySnapshotPairResult

class McpHierarchySnapshotStore(
  private val maxPerSession: Int = 10,
) {
  private class SessionSnapshots(maxSize: Int) {
    val mutex = Mutex()
    val entries: LinkedHashMap<UUID, HierarchyNode> =
      object : LinkedHashMap<UUID, HierarchyNode>(maxSize, 0.75f, false) {
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

  /** Resolves and retains both trees from one capture-ordered store state. */
  suspend fun resolvePair(
    sessionId: UUID,
    beforeSnapshotId: UUID? = null,
    afterSnapshotId: UUID? = null,
  ): HierarchySnapshotPairResult {
    val session = sessions[sessionId]
    if (session == null) {
      return when {
        beforeSnapshotId != null -> UnavailableHierarchySnapshot(sessionId, "before_snapshot_id", beforeSnapshotId)
        afterSnapshotId != null -> UnavailableHierarchySnapshot(sessionId, "after_snapshot_id", afterSnapshotId)
        else -> InsufficientHierarchyCaptures(sessionId, "before_snapshot_id", 2, 0)
      }
    }
    return session.mutex.withLock {
      val beforeTree = beforeSnapshotId?.let { session.entries[it] }
      if (beforeSnapshotId != null && beforeTree == null) {
        return@withLock UnavailableHierarchySnapshot(sessionId, "before_snapshot_id", beforeSnapshotId)
      }
      val afterTree = afterSnapshotId?.let { session.entries[it] }
      if (afterSnapshotId != null && afterTree == null) {
        return@withLock UnavailableHierarchySnapshot(sessionId, "after_snapshot_id", afterSnapshotId)
      }
      val entries = session.entries.entries.toList()
      val beforeId = beforeSnapshotId ?: entries.getOrNull(entries.size - 2)?.key
        ?: return@withLock InsufficientHierarchyCaptures(sessionId, "before_snapshot_id", 2, entries.size)
      val afterId = afterSnapshotId ?: entries.lastOrNull()?.key
        ?: return@withLock InsufficientHierarchyCaptures(sessionId, "after_snapshot_id", 1, entries.size)
      ResolvedHierarchySnapshotPair(beforeId, session.entries.getValue(beforeId), afterId, session.entries.getValue(afterId))
    }
  }

  fun clear(sessionId: UUID) {
    sessions.remove(sessionId)
  }
}
