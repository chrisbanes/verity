package me.chrisbanes.verity.smoke

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.chrisbanes.verity.core.interaction.Interaction
import me.chrisbanes.verity.core.model.ActionFlow
import me.chrisbanes.verity.core.model.Platform
import me.chrisbanes.verity.device.DeviceSession
import me.chrisbanes.verity.device.DeviceSessionFactory
import me.chrisbanes.verity.device.QualificationSelectorEvidence
import me.chrisbanes.verity.device.QualificationSelectorProbe

internal class QualificationUnavailable(message: String, val outcome: String = "unavailable") : IllegalStateException(message)

internal data class FixtureProbeSummary(
  val outcome: String,
  val labelMatches: Int = 0,
  val textMatches: Int = 0,
  val idMatches: Int = 0,
  val resourceId: String? = null,
  val selectedPath: String? = null,
  val selectedBounds: String? = null,
  val elapsedMillis: Long = 0,
)

internal suspend fun awaitFixtureReadiness(
  capture: suspend () -> FixtureProbeSummary,
  onSample: (FixtureProbeSummary) -> Unit = {},
  clockMillis: () -> Long = { System.nanoTime() / 1_000_000 },
  pause: suspend (Long) -> Unit = { delay(it) },
): FixtureProbeSummary {
  val started = clockMillis()
  var last: FixtureProbeSummary
  while (true) {
    val captured = capture()
    val elapsed = (clockMillis() - started).coerceAtLeast(0)
    last = captured.copy(
      outcome = if (elapsed > 5_000) "readiness-timeout" else captured.outcome,
      elapsedMillis = elapsed,
    )
    onSample(last)
    if (elapsed > 5_000) throw QualificationUnavailable("Safe Settings fixture readiness exceeded 5000 ms")
    when (last.outcome) {
      "ready" -> return last
      "not-ready" -> Unit
      else -> throw QualificationUnavailable("Safe Settings fixture selector unavailable: ${last.outcome}")
    }
    if (elapsed >= 5_000) throw QualificationUnavailable("Safe Settings fixture did not become ready within 5000 ms")
    pause(minOf(250L, 5_000L - elapsed))
  }
}

private fun QualificationSelectorEvidence.toFixtureSummary() = FixtureProbeSummary(
  outcome = outcome,
  labelMatches = labelMatches,
  textMatches = textMatches,
  idMatches = idMatches,
  resourceId = resourceId,
  selectedPath = selectedPath,
  selectedBounds = selectedBounds,
  elapsedMillis = elapsedMillis,
)

private fun MutableMap<String, String>.recordFixtureEvidence(label: String, evidence: FixtureProbeSummary) {
  put("fixtureLabel", label)
  put("fixtureOutcome", evidence.outcome)
  put("fixtureLabelMatches", evidence.labelMatches.toString())
  put("fixtureTextMatches", evidence.textMatches.toString())
  put("fixtureIdMatches", evidence.idMatches.toString())
  put("fixtureProbeElapsedMillis", evidence.elapsedMillis.toString())
  evidence.resourceId?.let { put("fixtureResourceId", it) }
  evidence.selectedPath?.let { put("fixtureSelectedPath", it) }
  evidence.selectedBounds?.let { put("fixtureSelectedBounds", it) }
}

/** Metadata is checked before the injected connector can touch any device. */
internal suspend fun connectQualifiedTarget(
  platform: Platform,
  target: String,
  onCommandStart: ((Int) -> Unit)? = null,
  readReceipt: suspend () -> String = ::readQualificationReceipt,
  sessionFactory: suspend (Platform, String, ((Int) -> Unit)?) -> DeviceSession = { selectedPlatform, id, observer ->
    withContext(Dispatchers.IO) {
      DeviceSessionFactory.connect(selectedPlatform, deviceId = id, disableAnimations = false, onCommandStart = observer)
    }
  },
): DeviceSession {
  verifyQualificationTarget(platform, target, readReceipt)
  return sessionFactory(platform, target, onCommandStart)
}

private suspend fun readQualificationReceipt(): String = withContext(Dispatchers.IO) {
  val path = System.getenv("VERITY_QUALIFICATION_TARGET_RECEIPT")?.takeIf { it.isNotBlank() }?.let(Path::of)
    ?: throw QualificationUnavailable("Explicit root target receipt required")
  if (!path.isAbsolute || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 16384) {
    throw QualificationUnavailable("Explicit root target receipt invalid")
  }
  Files.readString(path)
}

private suspend fun verifyQualificationTarget(platform: Platform, target: String, readReceipt: suspend () -> String): Map<String, String> {
  if (target.isBlank()) throw QualificationUnavailable("Explicit dedicated target required")
  if (platform != Platform.ANDROID_MOBILE && platform != Platform.IOS) throw QualificationUnavailable("Unsupported qualification platform")
  val metadata = try {
    val receipt = Json.parseToJsonElement(readReceipt()).jsonObject
    fun boolean(name: String) = receipt[name]?.jsonPrimitive?.takeIf { !it.isString }?.booleanOrNull
    fun limit(name: String) = receipt[name]?.jsonPrimitive?.takeIf { !it.isString }?.contentOrNull
    if (boolean("ready") != true || boolean("runtimeQualificationGrant") != true ||
      limit("issue") != "107" || limit("caseLimitMillis") != "20000" || limit("platformLimitMillis") != "120000"
    ) {
      throw QualificationUnavailable("Finite root runtime qualification grant required")
    }
    val forbidden = receipt["forbiddenTargets"]?.let { value ->
      value.jsonArray.map { it.jsonPrimitive.takeIf { field -> field.isString }?.contentOrNull ?: error("invalid forbidden target") }
    } ?: throw QualificationUnavailable("Explicit forbidden target receipt required")
    if (target in forbidden) throw QualificationUnavailable("Forbidden qualification target")
    val identity = receipt[if (platform == Platform.IOS) "ios" else "android"]?.jsonObject
      ?: throw QualificationUnavailable("Dedicated target identity missing")
    fun field(name: String) = identity[name]?.jsonPrimitive?.takeIf { it.isString }?.contentOrNull
    val expected = field(if (platform == Platform.IOS) "udid" else "serial")
    val ready = if (platform == Platform.IOS) {
      field("kind") == "dedicated-simulator" && field("state") == "Booted"
    } else {
      field("kind") == "dedicated-emulator" && field("bootCompleted") == "1" && target.matches(Regex("emulator-[0-9]+"))
    }
    if (expected.isNullOrBlank() || target != expected || !ready) throw QualificationUnavailable("Dedicated target is not ready or mismatched")
    identity.mapValues { (_, value) -> value.jsonPrimitive.content }
  } catch (error: CancellationException) {
    throw error
  } catch (error: QualificationUnavailable) {
    throw error
  } catch (_: Exception) {
    throw QualificationUnavailable("Target receipt invalid")
  }
  return metadata
}

/** Cooperative timeout plus honest cleanup accounting; root supplies the external hard backstop. */
internal suspend fun runFiniteQualificationCase(
  connect: suspend () -> DeviceSession,
  clock: () -> Long = System::nanoTime,
  emit: suspend (String, String) -> Unit,
  body: suspend (DeviceSession) -> Unit,
) {
  val started = clock()
  var session: DeviceSession? = null
  var failure: Throwable? = null
  var cleanup = "not-connected"
  suspend fun record(event: String, outcome: String) {
    try {
      emit(event, outcome)
    } catch (error: CancellationException) {
      if (failure !is CancellationException) failure = error
    } catch (error: Exception) {
      if (failure == null) failure = error
    }
  }
  emit("case-start", "running")
  try {
    withTimeout(20_000) {
      try {
        session = connect()
        body(checkNotNull(session))
      } catch (error: CancellationException) {
        failure = error
        throw error
      } catch (error: Throwable) {
        failure = error
        throw error
      } finally {
        withContext(NonCancellable) {
          record("cleanup-start", "running")
          cleanup = "unverified"
          try {
            withContext(Dispatchers.IO) { session?.close() }
            cleanup = if (session != null) "closed" else "unverified"
          } catch (error: CancellationException) {
            if (failure !is CancellationException) failure = error
          } catch (error: Exception) {
            if (failure == null) failure = error
          } finally {
            record("cleanup-end", cleanup)
          }
        }
      }
    }
  } catch (error: CancellationException) {
    if (failure !is CancellationException) failure = error
  } catch (error: Throwable) {
    if (failure == null) failure = error
  }
  val outcome = when {
    cleanup != "closed" || failure is CancellationException || clock() - started >= 20_000_000_000L -> "inconclusive"
    failure is QualificationUnavailable -> "unavailable"
    failure != null -> "failed"
    else -> "passed"
  }
  withContext(NonCancellable) { record("case-end", outcome) }
  val error = failure
  if (error is CancellationException) throw error
  if (outcome == "unavailable" && error is QualificationUnavailable) throw error
  if (outcome != "passed") throw QualificationUnavailable("Qualification case $outcome", outcome)
  if (error != null) throw QualificationUnavailable("Qualification case evidence incomplete", "inconclusive")
}

/** Private JSONL handshake for the root's exact test-process deadline monitor. */
internal class QualificationEvents(
  private val output: Path,
  private val runId: String,
  private val head: String,
  private val platform: Platform,
  private val target: String,
) {
  private val process = ProcessHandle.current()
  private val processStarted = process.info().startInstant().orElseThrow {
    QualificationUnavailable("Test process start identity unavailable")
  }.toEpochMilli()

  init {
    if (!output.isAbsolute ||
      !runId.matches(Regex("[A-Za-z0-9_-]{1,128}")) || !head.matches(Regex("[a-f0-9]{40}"))
    ) {
      throw QualificationUnavailable("Private qualification event handshake required")
    }
  }

  suspend fun emit(event: String, outcome: String, case: String = "", route: String = "", details: Map<String, String> = emptyMap()) = withContext(Dispatchers.IO) {
    val record = buildJsonObject {
      put("event", JsonPrimitive(event))
      put("at", JsonPrimitive(Instant.now().toString()))
      put("monotonicNanos", JsonPrimitive(System.nanoTime()))
      put("runId", JsonPrimitive(runId))
      put("head", JsonPrimitive(head))
      put("platform", JsonPrimitive(platform.name))
      put("target", JsonPrimitive(target))
      put("pid", JsonPrimitive(process.pid()))
      put("parentPid", JsonPrimitive(process.parent().orElseThrow().pid()))
      put("processStartEpochMillis", JsonPrimitive(processStarted))
      put("case", JsonPrimitive(case))
      put("route", JsonPrimitive(route))
      put("outcome", JsonPrimitive(outcome))
      put("caseLimitMillis", JsonPrimitive(20_000))
      put("platformLimitMillis", JsonPrimitive(120_000))
      put("maestroVersion", JsonPrimitive("2.11.0"))
      put(
        "details",
        buildJsonObject {
          details.forEach { (key, value) -> put(key, JsonPrimitive(value)) }
        },
      )
    }.toString() + "\n"
    check(record.toByteArray().size <= 8192) { "Qualification event exceeds private record bound" }
    val permissions = PosixFilePermissions.fromString("rw-------")
    check(Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS)) { "Root-created private event file required" }
    val options = setOf(StandardOpenOption.WRITE, StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS)
    FileChannel.open(output, options, PosixFilePermissions.asFileAttribute(permissions)).use { channel ->
      check(Files.getPosixFilePermissions(output, LinkOption.NOFOLLOW_LINKS) == permissions) {
        "Qualification events must be private"
      }
      val bytes = ByteBuffer.wrap(record.toByteArray(Charsets.UTF_8))
      while (bytes.hasRemaining()) channel.write(bytes)
      channel.force(false)
    }
  }
}

private enum class QualificationRoute {
  TYPED,
  SUPPLIED_YAML,
  ;

  suspend fun execute(session: DeviceSession, flow: ActionFlow, yaml: String) = when (this) {
    TYPED -> session.executeActions(flow)
    SUPPLIED_YAML -> session.executeFlow(yaml)
  }
}

/** Invoked only by explicitly included qualification tags, after a separate root runtime grant. */
internal suspend fun qualifyStructuredFlows(platform: Platform) {
  val targetName = if (platform == Platform.IOS) "VERITY_QUALIFICATION_IOS_UDID" else "VERITY_QUALIFICATION_ANDROID_SERIAL"
  val target = System.getenv(targetName).orEmpty()
  val targetMetadata = verifyQualificationTarget(platform, target, ::readQualificationReceipt)
  val events = QualificationEvents(
    Path.of(System.getenv("VERITY_QUALIFICATION_EVENTS") ?: throw QualificationUnavailable("Private event output required")),
    System.getenv("VERITY_QUALIFICATION_RUN_ID").orEmpty(),
    System.getenv("VERITY_QUALIFICATION_HEAD").orEmpty(),
    platform,
    target,
  )
  val appId = if (platform == Platform.IOS) "com.apple.Preferences" else "com.android.settings"
  val source = if (platform == Platform.IOS) "General" else "Network & internet"
  val destination = if (platform == Platform.IOS) "About" else "Internet"
  val started = System.nanoTime()
  var outcome = "inconclusive"
  events.emit("platform-start", "running", details = targetMetadata)
  try {
    withTimeout(120_000) {
      for (route in QualificationRoute.entries) {
        for (case in listOf("text", "id", "negative", "cancellation")) {
          val starts = ConcurrentLinkedQueue<Int>()
          val admitted = CompletableDeferred<Unit>()
          val watchWait = AtomicBoolean(false)
          val details = mutableMapOf("appId" to appId, "sourceText" to source, "destinationText" to destination, "clearState" to "false")
          runFiniteQualificationCase(
            connect = {
              connectQualifiedTarget(platform, target, onCommandStart = { index ->
                starts.add(index)
                // Offline real-Orchestra tests prove config=0, first action=1 on both routes.
                if (watchWait.get() && index == 1) admitted.complete(Unit)
              })
            },
            emit = { event, status ->
              if (event == "case-end") details["commandIndices"] = starts.joinToString(",")
              events.emit(event, status, case, route.name, details)
            },
          ) { session ->
            val reset = ActionFlow(appId, listOf(Interaction.LaunchApp(false)))
            val resetYaml = "appId: ${yamlScalar(appId)}\n---\n- launchApp:\n    clearState: false\n"
            check(route.execute(session, reset, resetYaml).success) { "Settings reset failed" }
            details["resetInputSha256"] = sha256(if (route == QualificationRoute.TYPED) encodeFlow(reset) else resetYaml)
            val fixture = awaitFixtureReadiness(
              capture = { QualificationSelectorProbe.capture(session, source).toFixtureSummary() },
              onSample = { details.recordFixtureEvidence(source, it) },
            )
            val resourceRegex = Regex.escape(checkNotNull(fixture.resourceId))
            starts.clear()
            if (case == "text" || case == "id") {
              val selector = if (case == "text") Regex.escape(source) else resourceRegex
              details["selector"] = selector
              val sourceWait = if (case == "text") {
                Interaction.WaitUntilVisible(text = selector, timeoutMs = 3000)
              } else {
                Interaction.WaitUntilVisible(resourceId = selector, timeoutMs = 3000)
              }
              val tap = if (case == "text") Interaction.TapOnText(selector) else Interaction.TapOnId(selector)
              val flow = ActionFlow(appId, listOf(sourceWait, tap, Interaction.WaitForAnimation(3000), Interaction.WaitUntilVisible(text = Regex.escape(destination), timeoutMs = 3000)))
              val selectorYaml = "${if (case == "text") "text" else "id"}: ${yamlScalar(selector)}"
              val tapYaml = if (case == "text") "- tapOn: ${yamlScalar(selector)}" else "- tapOn:\n    id: ${yamlScalar(selector)}"
              val yaml = "appId: ${yamlScalar(appId)}\n---\n- extendedWaitUntil:\n    visible:\n      $selectorYaml\n    timeout: 3000\n$tapYaml\n- waitForAnimationToEnd:\n    timeout: 3000\n- extendedWaitUntil:\n    visible:\n      text: ${yamlScalar(Regex.escape(destination))}\n    timeout: 3000\n"
              recordInputs(details, route, flow, yaml)
              check(route.execute(session, flow, yaml).success) { "Safe selector destination flow failed" }
              check(session.containsText(destination, ignoreCase = false)) { "Safe destination not observed" }
              details["destinationVisible"] = "true"
            } else {
              val absent = Regex.escape("VERITY_MISSING_SETTINGS_FIXTURE_107")
              check(!session.containsText("VERITY_MISSING_SETTINGS_FIXTURE_107", ignoreCase = false)) { "Missing fixture unexpectedly visible" }
              val timeout = if (case == "negative") 1000 else 3000
              val flow = ActionFlow(appId, listOf(Interaction.WaitUntilVisible(text = absent, timeoutMs = timeout), Interaction.KeyPress("HOME")))
              val yaml = "appId: ${yamlScalar(appId)}\n---\n- extendedWaitUntil:\n    visible:\n      text: ${yamlScalar(absent)}\n    timeout: $timeout\n- pressKey: HOME\n"
              recordInputs(details, route, flow, yaml)
              if (case == "negative") {
                check(!route.execute(session, flow, yaml).success) { "Missing visible wait unexpectedly passed" }
              } else {
                watchWait.set(true)
                val cancellation = CancellationException("qualification caller cancellation")
                var observed: CancellationException? = null
                coroutineScope {
                  val worker = launch {
                    try {
                      route.execute(session, flow, yaml)
                    } catch (error: CancellationException) {
                      observed = error
                      throw error
                    }
                  }
                  admitted.await()
                  worker.cancel(cancellation)
                  worker.join()
                  check(worker.isCancelled && worker.isCompleted && observed === cancellation) { "Caller cancellation identity or join failed" }
                  details["cancellationIdentity"] = "true"
                  details["workerJoined"] = "true"
                  details["owningJobCancelled"] = "true"
                  delay(2000)
                  details["postJoinObservationMillis"] = "2000"
                }
              }
              check(starts.toList() == listOf(0, 1)) { "Sentinel or unexpected command admitted" }
              check(session.containsText(source, ignoreCase = false)) { "Settings source changed after missing wait" }
              details["sentinelAdmitted"] = "false"
              details["sourceVisibleAfterTermination"] = "true"
            }
          }
        }
      }
    }
    check(System.nanoTime() - started < 120_000_000_000L) { "Platform execution deadline exceeded" }
    outcome = "passed"
  } catch (error: CancellationException) {
    throw error
  } catch (error: Exception) {
    outcome = if (error is QualificationUnavailable) error.outcome else "failed"
    throw error
  } finally {
    withContext(NonCancellable) { events.emit("platform-end", outcome) }
  }
}

private fun yamlScalar(value: String): String = Json.encodeToString(kotlinx.serialization.serializer<String>(), value)
private fun encodeFlow(flow: ActionFlow): String = Json.encodeToString(ActionFlow.serializer(), flow)
private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
private fun recordInputs(details: MutableMap<String, String>, route: QualificationRoute, flow: ActionFlow, yaml: String) {
  details["listSha256"] = sha256(encodeFlow(flow))
  details["inputSha256"] = sha256(if (route == QualificationRoute.TYPED) encodeFlow(flow) else yaml)
}
