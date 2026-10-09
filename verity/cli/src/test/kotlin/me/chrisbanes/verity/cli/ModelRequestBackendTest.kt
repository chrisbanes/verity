package me.chrisbanes.verity.cli

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutorAPI
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.params.LLMParams
import ai.koog.prompt.streaming.StreamFrame
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isLessThan
import assertk.assertions.isSameInstanceAs
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ModelRequestBackendTest {
  @Test
  fun `API backend forwards the complete params and ordered text image prompt and original reply`() = runTest {
    val imagePaths = withContext(Dispatchers.IO) {
      List(2) { index ->
        Files.createTempFile("verity-model-request-$index", ".png").also { path ->
          Files.write(path, byteArrayOf((index + 1).toByte()))
        }
      }
    }
    try {
      val executor = CapturingPromptExecutor()
      val backend = ApiModelRequestBackend(executor)
      val params = LLMParams(
        additionalProperties = buildJsonObject {
          put("reasoning", buildJsonObject { put("effort", JsonPrimitive("high")) })
          put("custom_native_option", JsonPrimitive("preserve-me"))
        },
      )
      val model = VerityProvider.OpenAI.findModel("gpt-4o")
      val reply = Message.Assistant(
        content = "assistant response",
        metaInfo = ResponseMetaInfo(
          timestamp = Instant.parse("2026-10-02T00:00:00Z"),
          modelId = "provider-model-id",
          metadata = buildJsonObject { put("trace", JsonPrimitive("provider-metadata")) },
        ),
        finishReason = "stop",
        rawResponse = buildJsonObject { put("raw", JsonPrimitive("original")) },
        id = "completion-id",
      )
      executor.reply = reply
      val request = ModelRequest(
        promptName = "visual-eval",
        systemText = "system instructions",
        userText = "user question",
        images = listOf(
          LabeledLocalImage("Current screenshot", imagePaths[0]),
          LabeledLocalImage("Reference screenshot 1", imagePaths[1]),
        ),
        model = SelectedRoleModel.Api(model),
        settings = ModelRequestSettings.Api(params),
      )

      val result = backend.execute(request)

      assertThat(result).isSameInstanceAs(reply)
      assertThat(executor.capturedModel).isSameInstanceAs(model)
      val prompt = checkNotNull(executor.capturedPrompt)
      assertThat(prompt.id).isEqualTo("visual-eval")
      assertThat(prompt.params).isEqualTo(params)
      assertThat(prompt.params.additionalProperties).isEqualTo(params.additionalProperties)
      assertThat(prompt.messages[0].textContent()).isEqualTo("system instructions")
      val userMessage = prompt.messages[1]
      assertThat(userMessage.textContent()).contains("user question")
      assertThat(userMessage.textContent()).contains("Current screenshot")
      assertThat(userMessage.textContent()).contains("Reference screenshot 1")
      val parts = userMessage.parts
      assertThat(parts.size).isEqualTo(4)
      val firstText = (parts[0] as MessagePart.Text).text
      assertThat(firstText).contains("user question")
      assertThat(firstText).contains("Current screenshot")
      assertThat(firstText.indexOf("user question")).isLessThan(firstText.indexOf("Current screenshot"))
      val currentImage = (parts[1] as MessagePart.Attachment).source as AttachmentSource.Image
      assertThat(currentImage.content).isInstanceOf(AttachmentContent.Binary::class)
      assertThat((currentImage.content as AttachmentContent.Binary).asBytes().toList()).isEqualTo(listOf(1.toByte()))
      assertThat((parts[2] as MessagePart.Text).text).isEqualTo("Reference screenshot 1")
      val referenceImage = (parts[3] as MessagePart.Attachment).source as AttachmentSource.Image
      assertThat(referenceImage.content).isInstanceOf(AttachmentContent.Binary::class)
      assertThat((referenceImage.content as AttachmentContent.Binary).asBytes().toList()).isEqualTo(listOf(2.toByte()))

      backend.close()
      assertThat(executor.closed).isEqualTo(true)
    } finally {
      withContext(NonCancellable) {
        withContext(Dispatchers.IO) { imagePaths.forEach(Files::deleteIfExists) }
      }
    }
  }

  @Test
  fun `text-only request preserves prompt and empty native parameters`() = runTest {
    val executor = CapturingPromptExecutor()
    val backend = ApiModelRequestBackend(executor)
    val request = ModelRequest(
      promptName = "navigator",
      systemText = "system",
      userText = "user",
      images = emptyList(),
      model = SelectedRoleModel.Api(VerityProvider.OpenAI.defaultNavigatorModel),
      settings = ModelRequestSettings.Api(LLMParams()),
    )

    backend.execute(request)

    val prompt = checkNotNull(executor.capturedPrompt)
    assertThat(prompt.messages[0].textContent()).isEqualTo("system")
    assertThat(prompt.messages[1].textContent()).isEqualTo("user")
    assertThat(prompt.params).isEqualTo(LLMParams())
  }

  @Test
  fun `request rejects settings from another backend`() {
    assertFailsWith<IllegalArgumentException> {
      ModelRequest(
        promptName = "request",
        systemText = "system",
        userText = "user",
        images = emptyList(),
        model = SelectedRoleModel.Api(VerityProvider.OpenAI.defaultNavigatorModel),
        settings = ModelRequestSettings.Codex("high"),
      )
    }
  }

  private class CapturingPromptExecutor : PromptExecutorAPI {
    var capturedPrompt: Prompt? = null
    var capturedModel: LLModel? = null
    var reply: Message.Assistant = Message.Assistant(
      content = "default response",
      metaInfo = ResponseMetaInfo(Instant.parse("2026-10-02T00:00:00Z")),
    )
    var closed = false

    override suspend fun execute(
      prompt: Prompt,
      model: LLModel,
      tools: List<ToolDescriptor>,
    ): Message.Assistant {
      capturedPrompt = prompt
      capturedModel = model
      return reply
    }

    override fun executeStreaming(
      prompt: Prompt,
      model: LLModel,
      tools: List<ToolDescriptor>,
    ): Flow<StreamFrame> = emptyFlow()

    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult = error("Moderation is outside this test seam")

    override fun close() {
      closed = true
    }
  }
}
