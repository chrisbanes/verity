package me.chrisbanes.verity.cli

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.chrisbanes.verity.agent.InspectorAgent
import me.chrisbanes.verity.agent.ModelBackendFailure
import me.chrisbanes.verity.agent.ModelFailureException
import me.chrisbanes.verity.agent.NavigatorAgent
import me.chrisbanes.verity.core.context.ContextLoader
import me.chrisbanes.verity.core.model.Platform
import org.junit.jupiter.api.Tag

/**
 * Real-binary qualification of Verity's own Codex backend. Opt-in through `:verity:cli:codexQualification`;
 * it makes real model requests, so it is excluded from `test` and `check`.
 */
@Tag("qualification-codex")
class CodexQualificationTest {
  private val model = SelectedRoleModel.Codex("gpt-6-luna")
  private val settings = ModelRequestSettings.Codex("low")

  @Test
  fun `installed Codex passes production prepare role validation text image and cleanup`() = runTest(timeout = kotlin.time.Duration.parse("5m")) {
    withContext(Dispatchers.Default) {
      val binary = property("binary")
      val version = property("version")
      val priorRequests = property("priorRequests").toInt()
      check(priorRequests + MAX_REQUESTS_PER_RUN <= REQUEST_GRANT) { "request grant exhausted" }
      val steps = mutableListOf<JsonObject>()
      val methods = sortedMapOf<String, Int>()
      val processes = mutableListOf<Process>()
      val directories = mutableListOf<Path>()
      var requests = 0
      var versionLine: String? = null
      var exited = false
      var removed = false
      var passed = false
      var backendKind: String? = null
      var backend: CodexModelBackend? = null

      suspend fun <T> step(name: String, block: suspend () -> T): T {
        val started = System.nanoTime()
        backendKind = null
        try {
          return block().also { steps += stepRecord(name, true, null, started) }
        } catch (e: CancellationException) {
          throw e
        } catch (e: Throwable) {
          val kind = when (e) {
            is ModelBackendFailure -> e.kind.name
            is ModelFailureException -> e.failure.name
            else -> "ASSERTION"
          }
          steps += stepRecord(name, false, kind, started, (e as? ModelBackendFailure)?.kind?.name ?: backendKind)
          throw e
        }
      }

      suspend fun request(name: String, system: String, user: String, images: List<LabeledLocalImage>) = try {
        requests++
        backend!!.execute(ModelRequest(name, system, user, images, model, settings))
      } catch (e: ModelBackendFailure) {
        backendKind = e.kind.name
        throw e
      }

      try {
        step("host-and-version") {
          assertThat(System.getProperty("os.name")).isEqualTo("Mac OS X")
          val process = ProcessBuilder(binary, "--version").redirectErrorStream(true).start()
          versionLine = process.inputStream.bufferedReader().readText().trim()
          assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue()
          assertThat(versionLine).isEqualTo("codex-cli $version")
        }
        backend = step("prepare") {
          CodexModelBackend.prepare(executable = { Path.of(binary) }, launch = { command: List<String>, directory: Path, environment: Map<String, String> ->
            directories.add(directory)
            if ("--out" in command) directories.add(Path.of(command[command.indexOf("--out") + 1]))
            MethodRecordingProcess(CodexModelBackend.launchProcess(command, directory, environment), methods).also { processes.add(it) }
          })
        }
        step("validate-roles") { backend.validateRoles(listOf(model to "low", model to "low")) }
        step("navigator-text") {
          val flow = NavigatorAgent(ContextLoader.loadBundledActions(), executeRequest = { system, user -> request("navigator", system, user, emptyList()) })
            .generate(listOf("Launch the app", "Tap the Login button"), "com.example.qualification", Platform.ANDROID_MOBILE)
          assertThat(flow.actions.isNotEmpty()).isTrue()
        }
        step("inspector-image") {
          val screenshot = Files.createTempFile("verity-qualification-", ".png")
          try {
            writeScreenshot(screenshot)
            val inspector = InspectorAgent(
              evaluateTreeContent = { _, _, _ -> error("tree evaluation is not qualified here") },
              evaluateVisualContent = { system, user, path, references ->
                request("visual-eval", system, user, listOf(LabeledLocalImage("Current screenshot", path)) + references.mapIndexed { index, image -> LabeledLocalImage("Reference screenshot ${index + 1}", image) })
              },
            )
            assertThat(inspector.evaluateVisual(screenshot, "The upper-left region of the screenshot is blue.").passed).isFalse()
          } finally {
            Files.deleteIfExists(screenshot)
          }
        }
        step("close-and-cleanup") {
          closeModelBackend(backend)
          exited = processes.none { it.isAlive }
          removed = directories.none { Files.exists(it) }
          assertThat(exited).isTrue()
          assertThat(removed).isTrue()
        }
        passed = true
      } finally {
        if (!passed && steps.none { it["step"] == JsonPrimitive("close-and-cleanup") }) {
          try {
            backend?.let { closeModelBackend(it) }
          } catch (e: CancellationException) {
            throw e
          } catch (_: Exception) {
            // The receipt records the remaining process and directory state.
          }
          exited = processes.none { it.isAlive }
          removed = directories.none { Files.exists(it) }
        }
        writeReceipt(version, binary, versionLine, priorRequests, requests, steps, methods, exited, removed, passed)
      }
    }
  }

  private fun property(name: String): String = System.getProperty("verity.codex.qualification.$name")?.takeIf { it.isNotBlank() } ?: error("missing -PcodexQualification${name.replaceFirstChar(Char::uppercase)}")

  private fun stepRecord(name: String, passed: Boolean, failureKind: String?, startedNanos: Long, backendFailureKind: String? = null) = buildJsonObject {
    put("step", name)
    put("passed", passed)
    failureKind?.let { put("failureKind", it) }
    backendFailureKind?.let { put("backendFailureKind", it) }
    put("elapsedMillis", (System.nanoTime() - startedNanos) / 1_000_000)
  }

  private fun writeScreenshot(path: Path) {
    val image = BufferedImage(640, 360, BufferedImage.TYPE_INT_RGB)
    val graphics = image.createGraphics()
    graphics.color = Color.RED
    graphics.fillRect(0, 0, 320, 180)
    graphics.color = Color.BLUE
    graphics.fillRect(320, 0, 320, 180)
    graphics.color = Color.GREEN
    graphics.fillRect(0, 180, 640, 180)
    graphics.dispose()
    ImageIO.write(image, "png", path.toFile())
  }

  /** Records only versions, counts, outcomes and timings: no reply text, account data, configuration or stderr. */
  private fun writeReceipt(version: String, binary: String, versionLine: String?, prior: Int, requests: Int, steps: List<JsonObject>, methods: Map<String, Int>, exited: Boolean, removed: Boolean, passed: Boolean) {
    val directory = Path.of(System.getProperty("verity.codex.qualification.reports"))
    Files.createDirectories(directory)
    val receipt = buildJsonObject {
      put("qualifiedAt", Instant.now().toString())
      put("passed", passed)
      put("versionLine", versionLine)
      put("binarySha256", MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Path.of(binary))).joinToString("") { "%02x".format(it) })
      put("osName", System.getProperty("os.name"))
      put("osVersion", System.getProperty("os.version"))
      put("osArch", System.getProperty("os.arch"))
      put("model", model.modelId)
      put("effort", settings.reasoningEffort)
      put("documentedMinimum", "0.159.0")
      put("documentedMinimumStatus", "source-verified, not runtime-qualified")
      put("modelRequestsThisRun", requests)
      put("modelRequestsTotal", prior + requests)
      put("modelRequestGrant", REQUEST_GRANT)
      put("steps", JsonArray(steps))
      put("observedMethods", JsonObject(methods.mapValues { JsonPrimitive(it.value) }))
      put("processesExited", exited)
      put("directoriesRemoved", removed)
    }
    Files.writeString(directory.resolve("codex-$version.json"), prettyJson.encodeToString(JsonObject.serializer(), receipt) + "\n")
  }

  /** Delegates every operation to the exact child; the stdout tee keeps only JSON-RPC method names. */
  private class MethodRecordingProcess(private val child: Process, methods: MutableMap<String, Int>) : Process() {
    private val input = MethodTee(child.inputStream, methods)
    override fun getOutputStream() = child.outputStream
    override fun getInputStream(): InputStream = input
    override fun getErrorStream() = child.errorStream
    override fun waitFor() = child.waitFor()
    override fun waitFor(timeout: Long, unit: TimeUnit) = child.waitFor(timeout, unit)
    override fun exitValue() = child.exitValue()
    override fun destroy() = child.destroy()
    override fun destroyForcibly(): Process {
      child.destroyForcibly()
      return this
    }
    override fun isAlive() = child.isAlive
    override fun pid() = child.pid()
    override fun toHandle() = child.toHandle()
  }

  private class MethodTee(input: InputStream, private val methods: MutableMap<String, Int>) : FilterInputStream(input) {
    private val line = ByteArrayOutputStream()

    override fun read(): Int = super.read().also(::observe)

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = super.read(buffer, offset, length).also { count -> for (index in 0 until count) observe(buffer[offset + index].toInt() and 0xff) }

    private fun observe(value: Int) {
      when {
        value == -1 -> Unit

        value == '\n'.code -> {
          val method = runCatching { (Json.parseToJsonElement(line.toString(Charsets.UTF_8)) as? JsonObject)?.get("method")?.jsonPrimitive?.contentOrNull }.getOrNull()
          if (method != null) synchronized(methods) { methods.merge(method, 1, Int::plus) }
          line.reset()
        }

        line.size() <= CodexAppServerClient.MAX_FRAME_BYTES -> line.write(value)
      }
    }
  }

  private companion object {
    const val REQUEST_GRANT = 8
    const val MAX_REQUESTS_PER_RUN = 2
    val prettyJson = Json { prettyPrint = true }
  }
}
