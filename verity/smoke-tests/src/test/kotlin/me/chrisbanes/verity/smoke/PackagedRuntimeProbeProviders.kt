package me.chrisbanes.verity.smoke

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.anthropic.AnthropicClientSettings
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.bedrock.BedrockClientSettings
import ai.koog.prompt.executor.clients.bedrock.BedrockLLMClient
import ai.koog.prompt.executor.clients.dashscope.DashscopeClientSettings
import ai.koog.prompt.executor.clients.dashscope.DashscopeLLMClient
import ai.koog.prompt.executor.clients.deepseek.DeepSeekClientSettings
import ai.koog.prompt.executor.clients.deepseek.DeepSeekLLMClient
import ai.koog.prompt.executor.clients.google.GoogleClientSettings
import ai.koog.prompt.executor.clients.google.GoogleLLMClient
import ai.koog.prompt.executor.clients.mistralai.MistralAIClientSettings
import ai.koog.prompt.executor.clients.mistralai.MistralAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openrouter.OpenRouterClientSettings
import ai.koog.prompt.executor.clients.openrouter.OpenRouterLLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.ollama.client.OllamaClient
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.message.MessagePart
import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.util.Base64
import kotlinx.coroutines.runBlocking
import me.chrisbanes.verity.cli.VerityProvider

/**
 * Creates every production provider client, then sends text (and image, where supported) prompts through
 * settings-redirected clients to a local fixture server. Only the base URL differs from production.
 *
 * Member signatures avoid :verity:cli types so JUnit discovery never links them on the test classpath.
 */
internal object PackagedRuntimeProbeProviders {
  private const val MARKER = "PROBE_USER_MARKER"

  // 1x1 transparent PNG.
  private val PNG = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==")

  private class Request(val path: String, val body: String)

  private fun reply(path: String, text: String, model: String): String = when {
    path.endsWith("/messages") || path.endsWith("/invoke") ->
      """{"id":"msg_probe","type":"message","role":"assistant","model":"$model","content":[{"type":"text","text":"$text"}],"stop_reason":"end_turn","usage":{"input_tokens":1,"output_tokens":1}}"""
    path.contains(":generateContent") ->
      """{"candidates":[{"content":{"role":"model","parts":[{"text":"$text"}]},"finishReason":"STOP","index":0}],"usageMetadata":{"promptTokenCount":1,"candidatesTokenCount":1,"totalTokenCount":2},"modelVersion":"$model"}"""
    path.endsWith("/api/chat") ->
      """{"model":"$model","created_at":"2026-01-01T00:00:00Z","message":{"role":"assistant","content":"$text"},"done":true,"done_reason":"stop","prompt_eval_count":1,"eval_count":1}"""
    path.endsWith("/converse") ->
      """{"output":{"message":{"role":"assistant","content":[{"text":"$text"}]}},"stopReason":"end_turn","usage":{"inputTokens":1,"outputTokens":1,"totalTokens":2},"metrics":{"latencyMs":1}}"""
    path.endsWith("/responses") ->
      """{"id":"resp_probe","object":"response","created_at":1,"status":"completed","model":"$model","output":[{"type":"message","id":"msg_probe","status":"completed","role":"assistant","content":[{"type":"output_text","text":"$text","annotations":[]}]}],"parallel_tool_calls":false,"tool_choice":"auto","tools":[],"usage":{"input_tokens":1,"output_tokens":1,"total_tokens":2,"input_tokens_details":{"cached_tokens":0},"output_tokens_details":{"reasoning_tokens":0}}}"""
    else ->
      """{"id":"chatcmpl_probe","object":"chat.completion","created":1,"model":"$model","system_fingerprint":"fp_probe","choices":[{"index":0,"message":{"role":"assistant","content":"$text"},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}"""
  }

  fun run() = runBlocking {
    val requests = mutableListOf<Request>()
    var reply = ""
    var model = ""
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
      createContext("/") { exchange ->
        try {
          // Java APIs only: the shrunk archive keeps just the Kotlin stdlib members production uses.
          val body = String(exchange.requestBody.readAllBytes(), Charsets.UTF_8)
          synchronized(requests) { requests += Request(exchange.requestURI.path, body) }
          val bytes = Charsets.UTF_8.encode(reply(exchange.requestURI.path, reply, model)).let { buffer -> ByteArray(buffer.remaining()).also { buffer.get(it) } }
          exchange.responseHeaders.add("Content-Type", "application/json")
          exchange.sendResponseHeaders(200, bytes.size.toLong())
          exchange.responseBody.write(bytes)
        } catch (failure: Throwable) {
          failure.printStackTrace()
          throw failure
        } finally {
          exchange.close()
        }
      }
      start()
    }
    val image = File.createTempFile("packaged-probe", ".png").also { java.nio.file.Files.write(it.toPath(), PNG) }
    try {
      val url = "http://127.0.0.1:${server.address.port}"
      check(System.getenv("AWS_SECRET_ACCESS_KEY") == "fixture") { "Provider probe requires the fixture AWS secret" }
      for (provider in VerityProvider.all) {
        provider.createClient(if (provider is VerityProvider.Ollama) url else "fixture-key").close()
        val client = when (provider) {
          VerityProvider.Anthropic -> AnthropicLLMClient("fixture-key", AnthropicClientSettings(baseUrl = url))
          VerityProvider.OpenAI -> OpenAILLMClient("fixture-key", OpenAIClientSettings(baseUrl = url))
          VerityProvider.Google -> GoogleLLMClient("fixture-key", GoogleClientSettings(baseUrl = url))
          VerityProvider.OpenRouter -> OpenRouterLLMClient("fixture-key", OpenRouterClientSettings(baseUrl = url))
          VerityProvider.Bedrock -> BedrockLLMClient(
            identityProvider = StaticCredentialsProvider {
              accessKeyId = "fixture-key"
              secretAccessKey = "fixture"
            },
            settings = BedrockClientSettings(endpointUrl = url),
          )
          VerityProvider.DeepSeek -> DeepSeekLLMClient("fixture-key", DeepSeekClientSettings(baseUrl = url))
          VerityProvider.MistralAI -> MistralAILLMClient("fixture-key", MistralAIClientSettings(baseUrl = url))
          VerityProvider.Ollama -> OllamaClient(baseUrl = url)
          VerityProvider.DashScope -> DashscopeLLMClient("fixture-key", DashscopeClientSettings(baseUrl = url))
        }
        MultiLLMPromptExecutor(client).use { executor ->
          val results = mutableListOf<String>()
          for (withImage in listOf(false, true)) {
            val target = if (withImage) provider.defaultInspectorModel else provider.defaultNavigatorModel
            if (withImage && !target.supports(LLMCapability.Vision.Image)) {
              results += "image=unsupported"
              continue
            }
            synchronized(requests) { requests.clear() }
            reply = "PROBE_OK_${provider.name}"
            model = target.id
            val request = prompt("packaged-probe") {
              system("Packaged provider probe")
              if (withImage) {
                user {
                  text(MARKER)
                  image(kotlinx.io.files.Path(image.path))
                }
              } else {
                user(MARKER)
              }
            }
            val answer = try {
              executor.execute(request, target, emptyList())
            } catch (rejected: IllegalArgumentException) {
              // A client-side limitation both archives share (OpenRouter rejects user attachments); the line is diffed.
              if (!withImage) throw rejected
              results += "image=rejected:${rejected.javaClass.name}"
              continue
            }
            val seen = synchronized(requests) { requests.toList() }
            val text = answer.parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.text }
            check(seen.size == 1) { "${provider.name}: expected one request, saw ${seen.map { it.path }}" }
            val body = seen.single().body
            // Google and Bedrock carry the model ID in the request path.
            check((target.id in body || target.id in seen.single().path) && MARKER in body) { "${provider.name}: request lacks model or marker" }
            check(reply in text) { "${provider.name}: reply lacks marker" }
            if (withImage) check("iVBOR" in body) { "${provider.name}: image request lacks base64 PNG" }
            results += "${if (withImage) "image" else "text"}=${seen.single().path}"
          }
          println("PACKAGED_PROVIDER name=${provider.name} ${results.joinToString(" ")}")
        }
      }
      println("PACKAGED_PROVIDERS_OK count=${VerityProvider.all.size}")
    } finally {
      server.stop(0)
      image.delete()
    }
  }
}
