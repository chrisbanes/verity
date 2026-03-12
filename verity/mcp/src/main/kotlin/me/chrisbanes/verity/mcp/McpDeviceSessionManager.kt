package me.chrisbanes.verity.mcp

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.chrisbanes.verity.core.model.Platform
import me.chrisbanes.verity.device.DeviceSession
import me.chrisbanes.verity.device.DeviceSessionFactory

data class SessionHandle(
  val sessionId: UUID,
  val deviceId: String,
)

data class SessionEntry(
  val deviceId: String,
  val session: DeviceSession,
  val mutex: Mutex = Mutex(),
  var lastUsedAt: Long = System.currentTimeMillis(),
)

class McpDeviceSessionManager {

  private val sessions = ConcurrentHashMap<UUID, SessionEntry>()

  suspend fun open(
    platform: Platform,
    deviceId: String? = null,
    disableAnimations: Boolean = false,
  ): SessionHandle {
    val session = DeviceSessionFactory.connect(platform, deviceId, disableAnimations)
    val id = UUID.randomUUID()
    val resolvedDeviceId = deviceId ?: "auto-discovered"
    sessions[id] = SessionEntry(
      deviceId = resolvedDeviceId,
      session = session,
    )
    return SessionHandle(sessionId = id, deviceId = resolvedDeviceId)
  }

  suspend fun <T> withSession(sessionId: UUID, block: suspend (DeviceSession) -> T): T {
    val entry = sessions[sessionId]
      ?: throw IllegalArgumentException("No session found with ID: $sessionId")
    return entry.mutex.withLock {
      entry.lastUsedAt = System.currentTimeMillis()
      block(entry.session)
    }
  }

  suspend fun close(sessionId: UUID) {
    val entry = sessions.remove(sessionId)
      ?: throw IllegalArgumentException("No session found with ID: $sessionId")
    entry.mutex.withLock {
      entry.session.close()
    }
  }

  fun isOpen(sessionId: UUID): Boolean = sessions.containsKey(sessionId)
}
