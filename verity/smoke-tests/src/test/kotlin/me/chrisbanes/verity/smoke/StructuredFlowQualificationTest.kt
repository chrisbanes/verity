package me.chrisbanes.verity.smoke

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isSameInstanceAs
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.chrisbanes.verity.core.hierarchy.HierarchyNode
import me.chrisbanes.verity.core.model.ActionFlow
import me.chrisbanes.verity.core.model.FlowResult
import me.chrisbanes.verity.core.model.Platform
import me.chrisbanes.verity.device.DeviceSession

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StructuredFlowQualificationTest {
  @Test
  fun `blank mismatched forbidden or unready targets fail before any session connection`() = runTest {
    val cases = listOf(
      Platform.ANDROID_TV to androidTarget,
      Platform.ANDROID_MOBILE to "",
      Platform.ANDROID_MOBILE to "emulator-5554",
      Platform.IOS to "",
      Platform.IOS to "foreign-simulator",
      Platform.IOS to "another-device",
    )
    for ((platform, target) in cases) {
      var connected = false
      assertFailsWith<QualificationUnavailable> {
        connectQualifiedTarget(platform, target, readReceipt = { readyReceipt }, sessionFactory = { _, _, _ ->
          connected = true
          error("must not connect")
        })
      }
      assertThat(connected).isEqualTo(false)
    }
    for (receipt in listOf(readyReceipt.replace("\"runtimeQualificationGrant\":true", "\"runtimeQualificationGrant\":false"), readyReceipt.replace("emulator-9876", "emulator-5554"), readyReceipt.replace("Booted", "Shutdown"))) {
      val platform = if (receipt.contains("Shutdown")) Platform.IOS else Platform.ANDROID_MOBILE
      var connected = false
      assertFailsWith<QualificationUnavailable> {
        connectQualifiedTarget(platform, if (platform == Platform.IOS) iosTarget else androidTarget, readReceipt = { receipt }, sessionFactory = { _, _, _ ->
          connected = true
          error("must not connect")
        })
      }
      assertThat(connected).isEqualTo(false)
    }
  }

  @Test
  fun `accepted receipt connects only explicit target and forwards observer`() = runTest {
    for (platform in listOf(Platform.ANDROID_MOBILE, Platform.IOS)) {
      val expected = if (platform == Platform.IOS) iosTarget else androidTarget
      val starts = mutableListOf<Int>()
      val session = OfflineSession(platform)
      val connected = connectQualifiedTarget(platform, expected, onCommandStart = { starts += it }, readReceipt = { readyReceipt }, sessionFactory = { selected, id, observer ->
        assertThat(selected).isEqualTo(platform)
        assertThat(id).isEqualTo(expected)
        observer?.invoke(1)
        session
      })
      assertThat(connected).isEqualTo(session)
      assertThat(starts).isEqualTo(listOf(1))
    }
  }

  @Test
  fun `safe fixture needs one exact label and one usable unique identifier`() {
    val source = HierarchyNode(mapOf("text" to "General", "resource-id" to "safe.id[1]"))
    assertThat(resolveFixtureSelector(source, "General")).isEqualTo(Regex.escape("safe.id[1]"))
    for (tree in listOf(
      HierarchyNode(mapOf("text" to "General")),
      HierarchyNode(children = listOf(source, source)),
      HierarchyNode(mapOf("text" to "Another", "resource-id" to "safe.id[1]")),
      HierarchyNode(children = listOf(source, HierarchyNode(mapOf("text" to "Unrelated", "resource-id" to "safe.id[1]")))),
    )) {
      assertFailsWith<QualificationUnavailable> { resolveFixtureSelector(tree, "General") }
    }
  }

  @Test
  fun `cleanup failure is unverified and can never produce a passing case`() = runTest {
    withContext(Dispatchers.Default) {
      val events = mutableListOf<Pair<String, String>>()
      val session = OfflineSession(Platform.IOS, closeFailure = true)
      assertFailsWith<QualificationUnavailable> {
        runFiniteQualificationCase(connect = { session }, emit = { event, outcome -> events += event to outcome }) { }
      }
      assertThat(session.closed).isEqualTo(true)
      assertThat(events).isEqualTo(listOf("case-start" to "running", "cleanup-start" to "running", "cleanup-end" to "unverified", "case-end" to "inconclusive"))
    }
  }

  @Test
  fun `cooperative deadline includes completed cleanup and cannot become pass`() = runTest {
    val events = mutableListOf<Pair<String, String>>()
    val session = OfflineSession(Platform.IOS)
    assertFailsWith<kotlin.coroutines.cancellation.CancellationException> {
      runFiniteQualificationCase(connect = { session }, clock = { testScheduler.currentTime * 1_000_000 }, emit = { event, outcome -> events += event to outcome }) {
        delay(21_000)
      }
    }
    assertThat(session.closed).isEqualTo(true)
    assertThat(events.last()).isEqualTo("case-end" to "inconclusive")
  }

  private class OfflineSession(override val platform: Platform, val closeFailure: Boolean = false, val onClose: () -> Unit = {}) : DeviceSession {
    var closed = false
    override suspend fun executeFlow(yaml: String) = FlowResult(true)
    override suspend fun executeActions(flow: ActionFlow) = FlowResult(true)
    override suspend fun pressKey(keyName: String) = Unit
    override suspend fun captureHierarchyTree() = HierarchyNode()
    override suspend fun captureScreenshot(output: Path) = Unit
    override suspend fun shell(command: String) = ""
    override suspend fun waitForAnimationToEnd() = Unit
    override fun close() {
      closed = true
      onClose()
      if (closeFailure) error("fixture cleanup failure")
    }
  }

  @Test
  fun `private flushed events bind run head target and actual test process identity`() = runTest {
    val output = withContext(Dispatchers.IO) { Files.createTempFile("verity-qualification-events-", ".jsonl") }
    try {
      val head = "a".repeat(40)
      val events = QualificationEvents(output, "offline_test", head, Platform.IOS, iosTarget)
      events.emit("case-start", "running", "cancellation", "TYPED")
      events.emit("case-end", "passed", "cancellation", "TYPED")
      val records = withContext(Dispatchers.IO) { Files.readAllLines(output).map { Json.parseToJsonElement(it).jsonObject } }
      assertThat(records.size).isEqualTo(2)
      for (record in records) {
        assertThat(record["runId"]?.jsonPrimitive?.content).isEqualTo("offline_test")
        assertThat(record["head"]?.jsonPrimitive?.content).isEqualTo(head)
        assertThat(record["target"]?.jsonPrimitive?.content).isEqualTo(iosTarget)
        assertThat(record["pid"]?.jsonPrimitive?.content).isEqualTo(ProcessHandle.current().pid().toString())
        assertThat(record["parentPid"]?.jsonPrimitive?.content).isEqualTo(ProcessHandle.current().parent().orElseThrow().pid().toString())
        assertThat(record["processStartEpochMillis"]?.jsonPrimitive?.content).isEqualTo(ProcessHandle.current().info().startInstant().orElseThrow().toEpochMilli().toString())
        assertThat(record["caseLimitMillis"]?.jsonPrimitive?.content).isEqualTo("20000")
        assertThat(record["platformLimitMillis"]?.jsonPrimitive?.content).isEqualTo("120000")
      }
      for ((runId, invalidHead) in listOf("" to head, "offline_test" to "invalid")) {
        assertFailsWith<QualificationUnavailable> { QualificationEvents(output, runId, invalidHead, Platform.IOS, iosTarget) }
      }
    } finally {
      withContext(NonCancellable + Dispatchers.IO) { Files.deleteIfExists(output) }
    }
  }

  @Test
  fun `false missing malformed or loosened runtime receipts reject before connection`() = runTest {
    for (receipt in listOf(
      readyReceipt.replace("\"ready\":true", "\"ready\":false"),
      readyReceipt.replace("\"runtimeQualificationGrant\":true,", ""),
      readyReceipt.replace("\"runtimeQualificationGrant\":true", "\"runtimeQualificationGrant\":\"true\""),
      readyReceipt.replace("20000", "20001"),
      readyReceipt.replace("120000", "120001"),
      readyReceipt.replace("dedicated-emulator", "personal-device"),
      readyReceipt.replace("\"forbiddenTargets\":[\"foreign-simulator\"],", ""),
      readyReceipt.replace("foreign-simulator", androidTarget),
      "malformed receipt",
    )) {
      var connected = false
      assertFailsWith<QualificationUnavailable> {
        connectQualifiedTarget(Platform.ANDROID_MOBILE, androidTarget, readReceipt = { receipt }, sessionFactory = { _, _, _ ->
          connected = true
          error("must not connect")
        })
      }
      assertThat(connected).isEqualTo(false)
    }
  }

  @Test
  fun `event failure during cleanup still closes session and prevents pass`() = runTest {
    withContext(Dispatchers.Default) {
      val session = OfflineSession(Platform.IOS)
      assertFailsWith<QualificationUnavailable> {
        runFiniteQualificationCase(connect = { session }, emit = { event, _ ->
          if (event == "cleanup-start") error("fixture event transport failure")
        }) { }
      }
      assertThat(session.closed).isEqualTo(true)
    }
  }

  @Test
  fun `cleanup that crosses the deadline cannot report a passing case`() = runTest {
    withContext(Dispatchers.Default) {
      val elapsed = java.util.concurrent.atomic.AtomicLong(0)
      val events = mutableListOf<Pair<String, String>>()
      val session = OfflineSession(Platform.IOS, onClose = { elapsed.set(21_000_000_000) })
      assertFailsWith<QualificationUnavailable> {
        runFiniteQualificationCase(connect = { session }, clock = elapsed::get, emit = { event, outcome -> events += event to outcome }) { }
      }
      assertThat(session.closed).isEqualTo(true)
      assertThat(events.last()).isEqualTo("case-end" to "inconclusive")
    }
  }

  @Test
  fun `unavailable safe fixture keeps its precise reason after verified cleanup`() = runTest {
    withContext(Dispatchers.Default) {
      val events = mutableListOf<Pair<String, String>>()
      val missing = QualificationUnavailable("Safe Settings source identifier unavailable")
      val observed = assertFailsWith<QualificationUnavailable> {
        runFiniteQualificationCase(connect = { OfflineSession(Platform.IOS) }, emit = { event, outcome -> events += event to outcome }) { throw missing }
      }
      assertThat(observed).isSameInstanceAs(missing)
      assertThat(events.last()).isEqualTo("case-end" to "unavailable")
    }
  }

  private val androidTarget = "emulator-9876"
  private val iosTarget = "11111111-1111-4111-8111-111111111111"

  @Test
  fun `caller cancellation identity survives cleanup failure without a passing event`() = runTest {
    withContext(Dispatchers.Default) {
      val cancellation = kotlin.coroutines.cancellation.CancellationException("test caller cancellation")
      val events = mutableListOf<Pair<String, String>>()
      val observed = assertFailsWith<kotlin.coroutines.cancellation.CancellationException> {
        runFiniteQualificationCase(connect = { OfflineSession(Platform.IOS, closeFailure = true) }, emit = { event, outcome -> events += event to outcome }) { throw cancellation }
      }
      assertThat(observed).isSameInstanceAs(cancellation)
      assertThat(events.last()).isEqualTo("case-end" to "inconclusive")
    }
  }

  private val readyReceipt = """
    {"issue":107,"ready":true,"runtimeQualificationGrant":true,"caseLimitMillis":20000,"platformLimitMillis":120000,"forbiddenTargets":["foreign-simulator"],"android":{"kind":"dedicated-emulator","serial":"emulator-9876","avd":"VerityDrainQualification","bootCompleted":"1","platform":"Android","sdk":"34","release":"14"},"ios":{"kind":"dedicated-simulator","udid":"11111111-1111-4111-8111-111111111111","name":"iPhone 17","runtime":"com.apple.CoreSimulator.SimRuntime.iOS-27-0","state":"Booted"}}
  """.trimIndent()
}
