package me.chrisbanes.verity.cli

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isLessThan
import assertk.assertions.isNotEqualTo
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class CodexLifecycleTest {
  private class CallerCancellation(val marker: Any) : CancellationException("caller-owned-cancellation")

  @Test
  fun `real child startup verifies both launch environments empty cwd and final policy then closes idempotently`() = runTest {
    withContext(Dispatchers.Default) {
      listOf("success", "newer", "delta-flood", "disabled-layer-first").forEach { scenario ->
        val fake = FakeCodexLauncher(scenario)
        val backend = fake.prepare()
        try {
          assertThat(fake.children.size).isEqualTo(4)
          assertThat(fake.children.take(3).all { !it.process.isAlive }).isTrue()
          assertOwnedHome(fake, sqliteHome = "/unused-codex-owned-database")
          val observations = backend.client.request("config/read").getValue("observations").jsonObject
          assertThat(observations["pid"]).isEqualTo(JsonPrimitive(fake.children.last().process.pid()))
          assertThat(observations["emptyCwd"]).isEqualTo(JsonPrimitive(true))
          assertThat(observations["removedEnvironmentAbsent"]).isEqualTo(JsonPrimitive(true))
          assertThat(observations["optOutNotificationMethods"]).isEqualTo(JsonArray(OPTED_OUT_NOTIFICATIONS.map(::JsonPrimitive)))
          assertThat(backend.isolation.names.values.all { it.size == 2 }).isTrue()
          val typed = backend.client.request("config/read").getValue("config").jsonObject
          CodexIsolation.groups.forEach { group ->
            backend.isolation.names.getValue(group).forEach { assertThat(typed.getValue(group).jsonObject.getValue(it).jsonObject["enabled"]).isEqualTo(JsonPrimitive(false)) }
          }
          val invalidThread = buildJsonObject { put("config", buildJsonObject { put("mcp_servers.\"punctuation.a\\\"b\\\\c\".enabled", false) }) }
          assertThat(assertFailsWith<CodexFailure> { backend.client.request("thread/start", invalidThread) }.kind).isEqualTo(CodexFailureKind.PROTOCOL)
        } finally {
          backend.close()
        }
        backend.close()
        fake.verifyCleanup()
      }
    }
  }

  @Test
  fun `children share an owned home linking only the source credential and pin the database home`() = runTest {
    withContext(Dispatchers.Default) {
      for ((viaHome, sqliteHomeSet) in listOf(false to false, true to true, true to false)) {
        val fake = FakeCodexLauncher(sqliteHomeSet = sqliteHomeSet, viaHome = viaHome)
        val backend = fake.prepare()
        try {
          assertOwnedHome(fake, sqliteHome = if (sqliteHomeSet) "/unused-codex-owned-database" else fake.sourceHome.toString())
        } finally {
          backend.close()
        }
        fake.verifyCleanup()
      }
    }
  }

  @Test
  fun `missing source credential or home fails auth`() = runTest {
    withContext(Dispatchers.Default) {
      val fake = FakeCodexLauncher("source-auth-missing")
      val backend = fake.prepare()
      try {
        assertThat(assertFailsWith<CodexFailure> { backend.validateRoles(listOf(SelectedRoleModel.Codex("gpt-6-luna") to "low")) }.kind).isEqualTo(CodexFailureKind.AUTH)
      } finally {
        backend.close()
      }
      fake.verifyCleanup()
      val unresolved = FakeCodexLauncher()
      val failure = assertFailsWith<CodexFailure> { CodexModelBackend.prepare(executable = { Path.of("injected-jvm-fake") }, launch = unresolved::launch, host = "Mac OS X", environment = mapOf("CODEX_HOME" to "", "HOME" to "")) }
      assertThat(failure.kind).isEqualTo(CodexFailureKind.AUTH)
      assertThat(unresolved.children.size).isEqualTo(0)
    }
  }

  private fun assertOwnedHome(fake: FakeCodexLauncher, sqliteHome: String) {
    assertThat(fake.children.size).isEqualTo(4)
    val home = Path.of(fake.children.first().environment.getValue("CODEX_HOME"))
    assertThat(home.fileName.toString().startsWith("verity-codex-")).isTrue()
    assertThat(home).isNotEqualTo(fake.sourceHome)
    assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(home, LinkOption.NOFOLLOW_LINKS))).isEqualTo("rwx------")
    // Codex adds runtime state later, so only the first launch sees what Verity created.
    assertThat(fake.children.first().homeEntries).isEqualTo(listOf("auth.json"))
    fake.children.forEach { child ->
      assertThat(child.environment.keys.intersect(REMOVED_ENVIRONMENT).isEmpty()).isTrue()
      assertThat(child.environment["CODEX_HOME"]).isEqualTo(home.toString())
      assertThat(child.authTarget).isEqualTo(fake.sourceHome.resolve("auth.json"))
      assertThat(child.environment["CODEX_SQLITE_HOME"]).isEqualTo(sqliteHome)
    }
  }

  @Test
  fun `dotted group overrides crash like real Codex instead of disabling a name`() = runTest {
    withContext(Dispatchers.IO) {
      listOf("mcp_servers.\"punctuation.a\\\"b\\\\c\".enabled=false", "mcp_servers.punctuation.a\"b\\c.enabled=false").forEach { override ->
        val directory = Files.createTempDirectory("verity-fake-dotted-")
        try {
          val process = FakeCodexLauncher("success").launch(listOf("codex", "app-server", "--listen", "stdio://", "-c", override), directory, emptyMap())
          val error = process.errorStream.bufferedReader().readText()
          assertThat(process.waitFor()).isEqualTo(1)
          assertThat(error.contains("invalid transport")).isTrue()
        } finally {
          Files.deleteIfExists(directory)
        }
      }
    }
  }

  @Test
  fun `old and malformed versions stop before schema bootstrap and final launch`() = runTest {
    withContext(Dispatchers.Default) {
      listOf("old", "malformed-version").forEach { scenario ->
        val fake = FakeCodexLauncher(scenario)
        val failure = assertFailsWith<CodexFailure> { fake.prepare() }
        assertThat(failure.kind).isEqualTo(CodexFailureKind.VERSION)
        assertThat(failure.cause).isEqualTo(null)
        assertThat(fake.children.size).isEqualTo(1)
        fake.verifyCleanup()
      }
    }
  }

  @Test
  fun `launch failure at each partial acquisition cleans exact earlier children and directories`() = runTest {
    withContext(Dispatchers.Default) {
      (1..4).forEach { failedLaunch ->
        val fake = FakeCodexLauncher(failLaunch = failedLaunch)
        val failure = assertFailsWith<CodexFailure> { fake.prepare() }
        assertThat(failure.kind).isEqualTo(CodexFailureKind.PROTOCOL)
        assertThat(failure.cause).isEqualTo(null)
        assertThat(failure.message?.contains("secret")).isEqualTo(false)
        assertThat(fake.children.size).isEqualTo(failedLaunch - 1)
        fake.verifyCleanup()
      }
    }
  }

  @Test
  fun `bootstrap final EOF and every server callback fail closed and clean actual children`() = runTest {
    withContext(Dispatchers.Default) {
      listOf("bootstrap-eof", "final-eof", "callback", "callback-unknown", "relevant-flood", "rerouted", "ignore-opt-out").forEach { scenario ->
        val fake = FakeCodexLauncher(scenario)
        assertThat(assertFailsWith<CodexFailure> { fake.prepare() }.kind).isEqualTo(CodexFailureKind.PROTOCOL)
        fake.verifyCleanup()
      }
    }
  }

  @Test
  fun `one startup deadline bounds hanging bootstrap and final children with owned exit cleanup`() = runTest {
    withContext(Dispatchers.Default) {
      listOf("timeout", "final-timeout", "version-timeout", "schema-timeout", "stubborn").forEach { scenario ->
        val fake = FakeCodexLauncher(scenario)
        val start = System.nanoTime()
        assertThat(assertFailsWith<CodexFailure> { fake.prepare(startupMillis = 1_500) }.kind).isEqualTo(CodexFailureKind.STARTUP_TIMEOUT)
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(6_500L)
        fake.verifyCleanup()
      }
    }
  }

  @Test
  fun `caller cancellation remains identical after bounded real process cleanup`() = runTest {
    withContext(Dispatchers.Default) {
      val fake = FakeCodexLauncher("timeout")
      val cancellation = CallerCancellation(Any())
      var observed: CancellationException? = null
      val task = launch {
        try {
          fake.prepare()
        } catch (e: CancellationException) {
          observed = e
          throw e
        }
      }
      while (fake.children.size < 3) delay(10)
      task.cancel(cancellation)
      task.join()
      assertThat(observed).isSameInstanceAs(cancellation)
      fake.verifyCleanup()
    }
  }

  @Test
  fun `blocked stdin writer is unblocked by exact owned termination inside cleanup budget`() = runTest {
    withContext(Dispatchers.Default) {
      val fake = FakeCodexLauncher("no-stdin")
      val backend = fake.prepare()
      val writer = launch {
        try {
          backend.client.send(buildJsonObject { put("payload", "x".repeat(4 * 1024 * 1024)) })
        } catch (e: CancellationException) {
          throw e
        } catch (_: Exception) { /* Exact owned termination unblocked the pipe. */ }
      }
      delay(150)
      assertThat(writer.isCompleted).isFalse()
      writer.cancel()
      val started = System.nanoTime()
      backend.close()
      writer.join()
      val cleanupMillis = (System.nanoTime() - started) / 1_000_000
      assertThat(cleanupMillis).isLessThan(5_000L)
      withContext(Dispatchers.IO) {
        val evidence = buildJsonObject {
          put("scenario", "blocked-writer-completion")
          put("writerJoined", writer.isCompleted)
          put("cleanupMillis", cleanupMillis)
          put("allExactChildrenExited", fake.children.all { !it.process.isAlive })
        }
        recordCodexTestEvidence(evidence)
      }
      fake.verifyCleanup()
    }
  }

  @Test
  fun `owned directory cleanup failure stays fixed and sticky after every exact process exited`() = runTest {
    withContext(Dispatchers.Default) {
      val fake = FakeCodexLauncher("cleanup-failure")
      var prepared: CodexModelBackend? = null
      try {
        val backend = fake.prepare().also { prepared = it }
        val failure = assertFailsWith<CodexFailure> { backend.close() }
        assertThat(failure.kind).isEqualTo(CodexFailureKind.CLEANUP)
        assertThat(failure.cause).isEqualTo(null)
        assertThat(fake.children.all { !it.process.isAlive }).isTrue()
        assertThat(backend.resources.cleanupFailed).isTrue()
        withContext(Dispatchers.IO) {
          val leftovers = fake.directories.filter { java.nio.file.Files.exists(it) }
          assertThat(leftovers.isNotEmpty()).isTrue()
          val evidence = buildJsonObject {
            put("scenario", "cleanup-failure-before-repair")
            put("fixedCleanupFailure", true)
            put("allChildrenExited", fake.children.all { !it.process.isAlive })
            put("ownedDirectoriesRemain", true)
          }
          recordCodexTestEvidence(evidence)
        }
      } finally {
        withContext(NonCancellable) {
          withContext(Dispatchers.IO) {
            fake.directories.forEach { directory ->
              val blocked = directory.resolve("blocked")
              if (java.nio.file.Files.exists(blocked)) java.nio.file.Files.setPosixFilePermissions(blocked, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))
            }
          }
          prepared?.let { backend -> assertThat(assertFailsWith<CodexFailure> { backend.close() }.kind).isEqualTo(CodexFailureKind.CLEANUP) }
          withContext(Dispatchers.IO) {
            assertThat(fake.children.all { !it.process.isAlive }).isTrue()
            fake.directories.filter { java.nio.file.Files.exists(it) }.forEach { path ->
              java.nio.file.Files.walk(path).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(java.nio.file.Files::delete) }
            }
          }
          fake.verifyCleanup()
        }
      }
    }
  }

  @Test
  fun `NDJSON rejects truncated invalid UTF8 and oversize frames without exposing raw causes`() {
    listOf(byteArrayOf(123, 125), byteArrayOf(0xc3.toByte(), 0x28, 10), ByteArray(CodexAppServerClient.MAX_FRAME_BYTES + 1) { 32 }).forEach { bytes ->
      assertFailsWith<Exception> { CodexAppServerClient.readFrame(ByteArrayInputStream(bytes)) }
    }
    assertThat(CodexAppServerClient.readFrame(ByteArrayInputStream(byteArrayOf()))).isEqualTo(null)
  }

  @Test
  fun `the reader releases an unsubscribed thread before its response is consumed`() = runTest {
    withContext(Dispatchers.Default) {
      // Status, unloads following the response, and the frame the consumer sees after the response.
      listOf(Triple("unsubscribed", 96, "warning"), Triple("notSubscribed", 1, "thread/closed")).forEach { (status, unloads, next) ->
        val process = QueuedStdoutProcess()
        val resources = CodexOwnedResources()
        try {
          val client = resources.client(process)
          val id = client.beginRequest("thread/unsubscribe", buildJsonObject { put("threadId", "released") })
          val unload = buildJsonObject {
            put("method", "thread/closed")
            put("params", buildJsonObject { put("threadId", "released") })
          }
          // Everything is readable before the consumer takes the response; more unloads than the frame queue holds.
          process.emit(
            listOf(
              buildJsonObject {
                put("id", id)
                put("result", buildJsonObject { put("status", status) })
              },
            ) +
              List(unloads) { unload } +
              buildJsonObject {
                put("method", "warning")
                put("params", JsonObject(emptyMap()))
              },
          )
          assertThat(client.receive()["id"], "status=$status").isEqualTo(JsonPrimitive(id))
          assertThat(client.receive()["method"], "status=$status").isEqualTo(JsonPrimitive(next))
          client.requireHealthy()
        } finally {
          resources.close()
        }
      }
    }
  }

  /** Stdout is fed by the test and stays open until closed, so the reader neither races ahead nor sees EOF. */
  private class QueuedStdoutProcess : Process() {
    private val bytes = LinkedBlockingQueue<Int>()
    private val stdout = object : InputStream() {
      override fun read(): Int = bytes.take()
      override fun close() = bytes.put(-1)
    }
    fun emit(frames: List<JsonObject>) = frames.joinToString("") { "$it\n" }.toByteArray().forEach { bytes.put(it.toInt() and 0xff) }
    override fun getInputStream(): InputStream = stdout
    override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
    override fun getErrorStream(): InputStream = InputStream.nullInputStream()
    override fun waitFor(): Int = 0
    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = true
    override fun exitValue(): Int = 0
    override fun destroy() = Unit
  }
}
