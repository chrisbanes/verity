package me.chrisbanes.verity.mcp

import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.shared.RequestOptions
import io.modelcontextprotocol.kotlin.sdk.types.CreateMessageRequest
import io.modelcontextprotocol.kotlin.sdk.types.CreateMessageResult
import io.modelcontextprotocol.kotlin.sdk.types.ElicitRequest
import io.modelcontextprotocol.kotlin.sdk.types.ElicitRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.ElicitResult
import io.modelcontextprotocol.kotlin.sdk.types.EmptyResult
import io.modelcontextprotocol.kotlin.sdk.types.ListRootsRequest
import io.modelcontextprotocol.kotlin.sdk.types.ListRootsResult
import io.modelcontextprotocol.kotlin.sdk.types.LoggingMessageNotification
import io.modelcontextprotocol.kotlin.sdk.types.PingRequest
import io.modelcontextprotocol.kotlin.sdk.types.RequestId
import io.modelcontextprotocol.kotlin.sdk.types.ResourceUpdatedNotification
import io.modelcontextprotocol.kotlin.sdk.types.ServerNotification

/**
 * A no-op [ClientConnection] stub for unit-testing tool handlers that
 * do not interact with the client connection.
 */
class StubClientConnection : ClientConnection {
  override val sessionId: String = "stub-session"

  override suspend fun notification(notification: ServerNotification, relatedRequestId: RequestId?) = Unit

  override suspend fun ping(request: PingRequest, options: RequestOptions?): EmptyResult = throw UnsupportedOperationException()

  override suspend fun createMessage(
    request: CreateMessageRequest,
    options: RequestOptions?,
  ): CreateMessageResult = throw UnsupportedOperationException()

  override suspend fun listRoots(request: ListRootsRequest, options: RequestOptions?): ListRootsResult = throw UnsupportedOperationException()

  override suspend fun createElicitation(
    message: String,
    requestedSchema: ElicitRequestParams.RequestedSchema,
    options: RequestOptions?,
  ): ElicitResult = throw UnsupportedOperationException()

  override suspend fun createElicitation(
    request: ElicitRequest,
    options: RequestOptions?,
  ): ElicitResult = throw UnsupportedOperationException()

  override suspend fun sendLoggingMessage(notification: LoggingMessageNotification) = Unit

  override suspend fun sendResourceUpdated(notification: ResourceUpdatedNotification) = Unit

  override suspend fun sendResourceListChanged() = Unit

  override suspend fun sendToolListChanged() = Unit

  override suspend fun sendPromptListChanged() = Unit
}
