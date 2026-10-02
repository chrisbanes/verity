package me.chrisbanes.verity.device

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import maestro.orchestra.error.SyntaxError
import maestro.orchestra.yaml.FlowParseException
import maestro.orchestra.yaml.MaestroFlowParser
import maestro.utils.FileAccessScope
import org.junit.jupiter.api.Test

class MaestroFlowValidatorTest {
  @Test fun `valid flow passes the canonical device free validator`() = runTest {
    validateMaestroFlow("appId: com.example\n---\n- tapOn: Continue")
  }

  @Test fun `malformed later YAML document is an invalid model response`() = runTest {
    val failure = runCatching { validateMaestroFlow("appId: com.example\n---\n- tapOn: \"unfinished") }.exceptionOrNull()
    assertThat(failure).isNotNull().isInstanceOf<InvalidMaestroFlowResponseException>()
  }

  @Test fun `null command options are an invalid response rather than local setup`() = runTest {
    val failure = runCatching { validateMaestroFlow("appId: com.example\n---\n- tapOn:") }.exceptionOrNull()
    assertThat(failure).isNotNull().isInstanceOf<InvalidMaestroFlowResponseException>()
  }

  @Test fun `missing referenced flow is local validation infrastructure failure`() = runTest {
    val failure = runCatching { validateMaestroFlow("appId: com.example\n---\n- runFlow: /unavailable/verity-missing-flow.yaml") }.exceptionOrNull()
    assertThat(failure).isNotNull().isInstanceOf<MaestroFlowValidationInfrastructureException>()
  }

  @Test fun `lexical parser consumes every document and pins actual lexical exception`() = runTest {
    listOf("appId: \"unfinished\n---\n- launchApp", "appId: com.example\n---\n- tapOn: [bad", "appId: com.example\n---\n- launchApp\n---\n\"unfinished").forEach { yaml ->
      val lexical = runCatching { YAMLFactory().createParser(yaml).use { while (it.nextToken() != null) {} } }.exceptionOrNull()
      assertThat(lexical).isNotNull().isInstanceOf<JsonParseException>()
      assertSafe(runCatching { validateMaestroFlow(yaml) }.exceptionOrNull(), false)
    }
  }

  @Test fun `real public structured categories classify invalid config and commands`() = runTest {
    val fixtures = listOf(
      "- tapOn: Example" to "Config Section Required",
      "appId: com.example\n---" to "Commands Section Required",
      "name: Example\n---\n- launchApp" to "Config Field Required",
      "appId: com.example\n---\n- 123" to "Invalid Command",
      "appId: com.example\n---\n- tapOn" to "Missing Command Options",
      "appId: com.example\n---\n- bogus" to "Invalid Command: ",
      "appId: com.example\n---\n- tapOn: Example\n  index: 0" to "Invalid Command Format: ",
      "appId: com.example\n---\n- runScript: {}" to "Config Field Required: ",
      "appId: com.example\n---\n- tapOn:" to "Incorrect Command Format: ",
      "appId: com.example\n---\n- tapOn:\n    unknownOption: true" to "Unknown Property: ",
      "appId: com.example\n---\n- tapOn:\n    enabled: bogus" to "Incorrect Format: ",
    )
    val categories = fixtures.map { (yaml, title) ->
      val sdk = runCatching {
        MaestroFlowParser.parseConfigOnly(Path.of("fixture.yaml"), yaml, FileAccessScope.everything)
        MaestroFlowParser.checkSyntax(yaml, Path.of("fixture.yaml"))
      }.exceptionOrNull() as? FlowParseException
      if (sdk?.title?.startsWith(title) == true) "matched" else "expected $title actual ${sdk?.title ?: "accepted"}"
    }
    assertThat(categories).isEqualTo(List(fixtures.size) { "matched" })
    fixtures.forEach { (yaml, _) -> assertSafe(runCatching { validateMaestroFlow(yaml) }.exceptionOrNull(), false) }
  }

  @Test fun `actual supported nested flow script and media references remain supported`() = runTest {
    withContext(Dispatchers.IO) {
      val directory = Files.createTempDirectory("verity-supported-resources-")
      try {
        val nested = directory.resolve("nested.yaml")
        val script = directory.resolve("script.js")
        val media = directory.resolve("photo.png")
        Files.writeString(nested, VALID)
        Files.writeString(script, "output.value = 1;")
        Files.write(media, byteArrayOf(1, 2, 3))
        validateMaestroFlow("appId: com.example\n---\n- runFlow: $nested\n- runScript: $script\n- addMedia:\n    - $media")
        validateMaestroFlow("appId: com.example.ios\n---\n- launchApp\n- tapOn: Example")
      } finally {
        Files.list(directory).use { files -> files.forEach { Files.delete(it) } }
        Files.delete(directory)
      }
    }
  }

  @Test fun `filesystem faults and ambiguous SDK faults are safe infrastructure`() = runTest {
    val unsafe = IOException(SENTINEL)
    assertSafe(runCatching { validateMaestroFlow(VALID, createTempFile = { throw unsafe }) }.exceptionOrNull(), true)
    assertSafe(runCatching { validateMaestroFlow(VALID, writeFlow = { _, _ -> throw unsafe }) }.exceptionOrNull(), true)
    assertSafe(runCatching { validateMaestroFlow(VALID, readFlow = { throw unsafe }) }.exceptionOrNull(), true)
    assertSafe(runCatching { validateMaestroFlow(VALID, readFlow = { throw SyntaxError(SENTINEL) }) }.exceptionOrNull(), true)
    assertSafe(runCatching { validateMaestroFlow(VALID, beforeResponseCheck = { throw IllegalStateException(SENTINEL) }) }.exceptionOrNull(), true)
    listOf("Parsing Failed", "New SDK category").forEach { title ->
      assertSafe(runCatching { validateMaestroFlow(VALID, beforeResponseCheck = { throw FlowParseException(com.fasterxml.jackson.core.JsonLocation.NA, Path.of("fixture"), SENTINEL, title, SENTINEL) }) }.exceptionOrNull(), true)
    }
    withContext(Dispatchers.IO) {
      var created: Path? = null
      val failure = runCatching { validateMaestroFlow(VALID, createTempFile = { Files.createTempFile("verity-delete-fault-", ".yaml").also { created = it } }, deleteFlow = { throw unsafe }) }.exceptionOrNull()
      assertSafe(failure, true)
      assertThat((failure as MaestroFlowValidationInfrastructureException).phase).isEqualTo(MaestroFlowValidationPhase.CLEANUP)
      Files.delete(created!!)
    }
  }

  @Test fun `temporary files are deleted after success and reader failure`() = runTest {
    withContext(Dispatchers.IO) {
      listOf(false, true).forEach { fail ->
        var created: Path? = null
        runCatching { validateMaestroFlow(VALID, createTempFile = { Files.createTempFile("verity-lifecycle-", ".yaml").also { created = it } }, readFlow = { if (fail) throw IOException(SENTINEL) else maestro.orchestra.yaml.YamlCommandReader.readCommands(it) }) }
        assertThat(Files.exists(created!!)).isFalse()
      }
      var created = false
      runCatching {
        validateMaestroFlow("appId: com.example\n---\n- tapOn:", createTempFile = {
          created = true
          Files.createTempFile("verity-invalid-", ".yaml")
        })
      }
      assertThat(created).isFalse()
    }
  }

  @Test fun `caller cancellation during creation dispatcher handoff or read retains identity and cleanup`() = runTest {
    listOf("create", "read", "return").forEach { point ->
      val cancellation = CallerCancellation()
      val caller = Job()
      var created: Path? = null
      val delegate = StandardTestDispatcher(testScheduler)
      val dispatcher = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) {
          delegate.dispatch(
            context,
            Runnable {
              block.run()
              caller.cancel(cancellation)
            },
          )
        }
      }
      val failure = runCatching {
        withContext(caller) {
          validateMaestroFlow(
            VALID,
            createTempFile = {
              Files.createTempFile("verity-cancel-", ".yaml").also {
                created = it
                if (point == "create") caller.cancel(cancellation)
              }
            },
            readFlow = { if (point == "read") caller.cancel(cancellation) },
            ioDispatcher = if (point == "return") dispatcher else Dispatchers.IO,
          )
        }
      }.exceptionOrNull()
      assertThat(failure).isSameInstanceAs(cancellation)
      withContext(Dispatchers.IO) { assertThat(Files.exists(created!!)).isFalse() }
    }
  }

  @Test fun `caller cancellation wins over cleanup failure without raw suppressed cause`() = runTest {
    val cancellation = CallerCancellation()
    var created: Path? = null
    val failure = runCatching {
      validateMaestroFlow(
        VALID,
        createTempFile = { Files.createTempFile("verity-cancel-cleanup-", ".yaml").also { created = it } },
        readFlow = { throw cancellation },
        deleteFlow = { throw IOException(SENTINEL) },
      )
    }.exceptionOrNull()
    assertThat(failure).isSameInstanceAs(cancellation)
    assertThat(cancellation.suppressed).isEmpty()
    withContext(NonCancellable + Dispatchers.IO) { Files.delete(created!!) }
  }

  @Test fun `SDK faults cannot enter lexical invalid classification merely by exception type`() = runTest {
    val lexical = runCatching { YAMLFactory().createParser("\"unfinished").use { while (it.nextToken() != null) {} } }.exceptionOrNull()!!
    assertSafe(runCatching { validateMaestroFlow(VALID, beforeResponseCheck = { throw lexical }) }.exceptionOrNull(), true)
  }

  @Test fun `scalar object and invalid nesting are rejected with safe response diagnostics`() = runTest {
    for (yaml in listOf("scalar", "{}", "appId: com.example\n---\n- [launchApp]", "appId: com.example\n---\n- tapOn: [unfinished")) assertSafe(runCatching { validateMaestroFlow(yaml) }.exceptionOrNull(), false)
  }

  @Test fun `SDK erased cancellation cause cannot become a completed infrastructure failure`() = runTest {
    val cancellation = CallerCancellation()
    val caller = Job()
    var created: Path? = null
    val failure = runCatching {
      withContext(caller) {
        validateMaestroFlow(VALID, createTempFile = { Files.createTempFile("verity-erased-cancel-", ".yaml").also { created = it } }, readFlow = {
          caller.cancel(cancellation)
          throw SyntaxError(SENTINEL)
        })
      }
    }.exceptionOrNull()
    assertThat(failure).isSameInstanceAs(cancellation)
    withContext(Dispatchers.IO) { assertThat(Files.exists(created!!)).isFalse() }
  }

  private fun assertSafe(failure: Throwable?, infrastructure: Boolean) {
    assertThat(failure).isNotNull()
    if (infrastructure) {
      assertThat(failure).isNotNull().isInstanceOf<MaestroFlowValidationInfrastructureException>()
    } else {
      assertThat(failure).isNotNull().isInstanceOf<InvalidMaestroFlowResponseException>()
    }
    assertThat(failure!!.cause).isNull()
    assertThat(failure.suppressed).isEmpty()
    assertThat(failure.toString().contains(SENTINEL)).isFalse()
  }

  private class CallerCancellation : CancellationException("caller cancellation") {
    val marker = Any()
  }

  companion object {
    const val VALID = "appId: com.example\n---\n- tapOn: Example"
    const val SENTINEL = "HTTP-RAW-BODY Authorization Bearer danger-token sk-secret eyJheader.payload.signature"
  }
}
