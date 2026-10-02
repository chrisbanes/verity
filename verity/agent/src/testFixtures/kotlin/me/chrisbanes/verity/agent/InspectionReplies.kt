package me.chrisbanes.verity.agent

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import kotlin.time.Instant

fun inspectionReply(text: String, finishReason: String? = null): Message.Assistant = Message.Assistant(
  content = text,
  metaInfo = ResponseMetaInfo(timestamp = Instant.parse("2026-10-02T00:00:00Z")),
  finishReason = finishReason,
)
