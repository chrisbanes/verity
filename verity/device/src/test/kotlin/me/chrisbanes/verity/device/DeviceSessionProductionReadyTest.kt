package me.chrisbanes.verity.device

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import assertk.assertions.messageContains
import java.util.concurrent.CountDownLatch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import maestro.DeviceInfo
import maestro.Driver
import maestro.KeyCode
import maestro.Maestro
import maestro.Point
import maestro.ScreenRecording
import maestro.SwipeDirection
import maestro.device.DeviceOrientation
import maestro.device.Platform as MaestroPlatform
import maestro.orchestra.Orchestra
import me.chrisbanes.verity.core.interaction.Interaction
import me.chrisbanes.verity.core.model.ActionFlow
import me.chrisbanes.verity.core.model.ActionFlowInvalidReason
import me.chrisbanes.verity.core.model.FlowResult
import me.chrisbanes.verity.core.model.InvalidActionFlowException
import me.chrisbanes.verity.core.model.Platform
import me.chrisbanes.verity.device.android.AndroidDeviceSession
import me.chrisbanes.verity.device.ios.IosDeviceSession

class DeviceSessionProductionReadyTest {

  @Test
  fun `Android raw and long presses use validated integer shell commands`() = runTest {
    val commands = mutableListOf<String>()
    val driver = RecordingDriver()
    val session = AndroidDeviceSession(Maestro(driver), Platform.ANDROID_TV) {
      commands += it
      ""
    }
    session.pressKey(174, false)
    session.pressKey(174, true)
    session.pressKey("Remote Dpad Up", true)
    session.pressKey("Remote Dpad Up", false)
    assertThat(commands).isEqualTo(listOf("input keyevent 174", "input keyevent --longpress 174", "input keyevent --longpress 19"))
    assertThat(driver.pressedKeys).isEqualTo(listOf(KeyCode.REMOTE_UP))
    assertFailure { session.pressKey(-1, false) }.messageContains("non-negative")
    assertFailure { session.pressKey("19; unwanted", true) }.messageContains("Unknown key name")
    assertThat(commands.size).isEqualTo(3)
  }

  @Test
  fun `named long press table preserves all thirty Maestro Android key mappings`() = runTest {
    // Maestro 2.11.0 AndroidDriver numeric lowering, independently pinned from its bytecode.
    val expected = mapOf(
      KeyCode.ENTER to 66, KeyCode.BACKSPACE to 67, KeyCode.BACK to 4,
      KeyCode.HOME to 3, KeyCode.LOCK to 276, KeyCode.VOLUME_UP to 24, KeyCode.VOLUME_DOWN to 25,
      KeyCode.REMOTE_UP to 19, KeyCode.REMOTE_DOWN to 20, KeyCode.REMOTE_LEFT to 21,
      KeyCode.REMOTE_RIGHT to 22, KeyCode.REMOTE_CENTER to 23, KeyCode.REMOTE_PLAY_PAUSE to 85,
      KeyCode.REMOTE_STOP to 86, KeyCode.REMOTE_NEXT to 87, KeyCode.REMOTE_PREVIOUS to 88,
      KeyCode.REMOTE_REWIND to 89, KeyCode.REMOTE_FAST_FORWARD to 90, KeyCode.ESCAPE to 111,
      KeyCode.POWER to 26, KeyCode.TAB to 62, KeyCode.REMOTE_SYSTEM_NAVIGATION_UP to 280,
      KeyCode.REMOTE_SYSTEM_NAVIGATION_DOWN to 281, KeyCode.REMOTE_BUTTON_A to 96,
      KeyCode.REMOTE_BUTTON_B to 97, KeyCode.REMOTE_MENU to 82, KeyCode.TV_INPUT to 178,
      KeyCode.TV_INPUT_HDMI_1 to 243, KeyCode.TV_INPUT_HDMI_2 to 244, KeyCode.TV_INPUT_HDMI_3 to 245,
    )
    assertThat(expected.keys).isEqualTo(KeyCode.entries.toSet())
    val commands = mutableListOf<String>()
    val session = AndroidDeviceSession(Maestro(RecordingDriver()), Platform.ANDROID_TV) {
      commands += it
      ""
    }
    for ((key, code) in expected) {
      session.pressKey(key.description, true)
      assertThat(commands.last()).isEqualTo("input keyevent --longpress $code")
    }
  }

  @Test
  fun `raw and named long presses propagate shell cancellation without later input`() = runTest {
    for (raw in listOf(false, true)) {
      val cancellation = CancellationException("shell caller cancelled")
      var calls = 0
      val session = AndroidDeviceSession(Maestro(RecordingDriver()), Platform.ANDROID_TV) {
        calls++
        throw cancellation
      }
      val observed = assertFailsWith<CancellationException> {
        if (raw) session.pressKey(174, true) else session.pressKey("Remote Dpad Up", true)
      }
      assertThat(observed === cancellation || observed.cause === cancellation).isEqualTo(true)
      assertThat(calls).isEqualTo(1)
    }
  }

  @Test
  fun `both routes report actual SDK command indices before driver admission on both adapters`() = runTest {
    for (ios in listOf(false, true)) {
      for (typed in listOf(false, true)) {
        val driver = RecordingDriver()
        val starts = mutableListOf<Int>()
        driver.onInput = { assertThat(starts).isEqualTo(listOf(0, 1, 2)) }
        val observer: (Int) -> Unit = { starts += it }
        val session = if (ios) {
          IosDeviceSession(Maestro(driver), FakeIosDevice(), onCommandStart = observer)
        } else {
          AndroidDeviceSession(Maestro(driver), Platform.ANDROID_MOBILE, onCommandStart = observer) { "" }
        }
        val result = if (typed) {
          session.executeActions(ActionFlow("app", listOf(Interaction.LaunchApp(false), Interaction.InputText("fixture"), Interaction.KeyPress("HOME"))))
        } else {
          session.executeFlow("appId: app\n---\n- launchApp:\n    clearState: false\n- inputText: fixture\n- pressKey: HOME\n")
        }
        assertThat(result).isEqualTo(FlowResult(success = true))
        assertThat(starts).isEqualTo(listOf(0, 1, 2, 3))
        assertThat(driver.pressedKeys).isEqualTo(listOf(KeyCode.HOME))
      }
    }
  }

  @Test
  fun `SDK observer reports failing command admission but never subsequent sentinel`() = runTest {
    for (typed in listOf(false, true)) {
      val driver = RecordingDriver().apply { inputFailure = IllegalStateException("fixture failure") }
      val starts = mutableListOf<Int>()
      val session = AndroidDeviceSession(Maestro(driver), Platform.ANDROID_MOBILE, onCommandStart = { starts += it }) { "" }
      val result = if (typed) {
        session.executeActions(ActionFlow("app", listOf(Interaction.InputText("fixture"), Interaction.KeyPress("HOME"))))
      } else {
        session.executeFlow("appId: app\n---\n- inputText: fixture\n- pressKey: HOME\n")
      }
      assertThat(result.success).isEqualTo(false)
      assertThat(starts).isEqualTo(listOf(0, 1))
      assertThat(driver.pressedKeys).isEqualTo(emptyList())
    }
  }

  @Test
  fun `both SDK routes cancel an admitted visible wait with the same caller exception and no sentinel`() = runTest {
    for (ios in listOf(false, true)) {
      for (typed in listOf(false, true)) {
        val driver = RecordingDriver()
        val starts = java.util.concurrent.ConcurrentLinkedQueue<Int>()
        val admitted = CompletableDeferred<Unit>()
        val observer: (Int) -> Unit = { index ->
          starts.add(index)
          if (index == 1) admitted.complete(Unit)
        }
        val session = if (ios) {
          IosDeviceSession(Maestro(driver), FakeIosDevice(), onCommandStart = observer)
        } else {
          AndroidDeviceSession(Maestro(driver), Platform.ANDROID_MOBILE, onCommandStart = observer) { "" }
        }
        val cancellation = CancellationException("cancel admitted wait")
        var observed: CancellationException? = null
        val worker = launch {
          observed = assertFailsWith<CancellationException> {
            if (typed) {
              session.executeActions(ActionFlow("app", listOf(Interaction.WaitUntilVisible(text = "absent fixture", timeoutMs = 3000), Interaction.KeyPress("HOME"))))
            } else {
              session.executeFlow("appId: app\n---\n- extendedWaitUntil:\n    visible: absent fixture\n    timeout: 3000\n- pressKey: HOME\n")
            }
          }
        }
        admitted.await()
        worker.cancel(cancellation)
        worker.join()
        // Virtual-time supplement only; tagged native qualification uses a real delay.
        delay(2000)
        assertThat(observed, "ios=$ios typed=$typed recoveredCauseIsCaller=${observed?.cause === cancellation}").isSameInstanceAs(cancellation)
        assertThat(worker.isCancelled && worker.isCompleted).isEqualTo(true)
        assertThat(starts.toList()).isEqualTo(listOf(0, 1))
        assertThat(driver.pressedKeys).isEqualTo(emptyList())
      }
    }
  }

  @Test
  fun `both platform adapters execute typed configuration and launch through Orchestra`() = runTest {
    val androidDriver = RecordingDriver()
    val iosDriver = RecordingDriver()
    val sessions = listOf(
      AndroidDeviceSession(Maestro(androidDriver), Platform.ANDROID_MOBILE) { "" },
      IosDeviceSession(Maestro(iosDriver), FakeIosDevice()),
    )
    for (session in sessions) {
      assertThat(session.executeActions(ActionFlow("com.example.app", listOf(Interaction.LaunchApp(false)))))
        .isEqualTo(FlowResult(success = true))
    }
    assertThat(androidDriver.launchedApps).isEqualTo(listOf("com.example.app"))
    assertThat(iosDriver.launchedApps).isEqualTo(listOf("com.example.app"))
  }

  @Test
  fun `invalid final key or selector prevents callbacks and every adapter effect`() = runTest {
    for (invalid in listOf(Interaction.KeyPress("PRIVATE_UNSUPPORTED_KEY"), Interaction.TapOnText("["))) {
      val flow = ActionFlow("com.example.app", listOf(Interaction.LaunchApp(false), invalid))
      var callbackRan = false
      assertFailsWith<InvalidActionFlowException> {
        executeMaestroActions(Maestro(FakeDriver()), flow, runCommands = {
          callbackRan = true
          true
        })
      }
      assertThat(callbackRan).isEqualTo(false)
      for (platform in listOf(Platform.ANDROID_TV, Platform.ANDROID_MOBILE, Platform.IOS)) {
        val driver = RecordingDriver()
        val session: DeviceSession = if (platform == Platform.IOS) {
          IosDeviceSession(Maestro(driver), FakeIosDevice())
        } else {
          AndroidDeviceSession(Maestro(driver), platform) { error("no shell effect permitted") }
        }
        assertFailsWith<InvalidActionFlowException> { session.executeActions(flow) }
        assertThat(driver.launchedApps).isEqualTo(emptyList())
        assertThat(driver.pressedKeys).isEqualTo(emptyList())
      }
    }
  }

  @Test
  fun `unexpected preparation fault is safe and remains outside runtime failure conversion`() = runTest {
    var callbackRan = false
    val error = assertFailsWith<ActionFlowPreparationException> {
      executeMaestroActions(
        Maestro(FakeDriver()),
        ActionFlow("app", emptyList()),
        runCommands = {
          callbackRan = true
          true
        },
        compile = { error("PRIVATE_INPUT_OR_CAUSE") },
      )
    }
    assertThat(error.message).isEqualTo("Action flow preparation failed: command compilation")
    assertThat(error.cause).isNull()
    assertThat(callbackRan).isEqualTo(false)
  }

  @Test
  fun `SDK runner initialization fault is a safe preparation failure before execution`() = runTest {
    val driver = RecordingDriver()
    val thrown = assertFailsWith<ActionFlowPreparationException> {
      executeMaestroActions(
        Maestro(driver),
        ActionFlow("com.example.app", listOf(Interaction.LaunchApp(false))),
        createOrchestra = { error("PRIVATE_RUNNER_INITIALIZATION_CAUSE") },
      )
    }
    assertThat(thrown.message).isEqualTo("Action flow preparation failed: runner initialization")
    assertThat(thrown.cause).isNull()
    assertThat(driver.launchedApps).isEqualTo(emptyList())
  }

  @Test
  fun `invalid list never initializes the SDK runner`() = runTest {
    var initialized = false
    assertFailsWith<InvalidActionFlowException> {
      executeMaestroActions(
        Maestro(FakeDriver()),
        ActionFlow("app", listOf(Interaction.KeyPress("UNSUPPORTED_KEY"))),
        createOrchestra = {
          initialized = true
          error("must not initialize")
        },
      )
    }
    assertThat(initialized).isEqualTo(false)
  }

  @Test
  fun `caller cancellation on SDK constructor return wins before any driver action`() = runTest {
    val cancellation = CancellationException("cancel constructor return")
    val driver = RecordingDriver()
    var observed: CancellationException? = null
    val job = launch {
      val owner = currentCoroutineContext().job
      observed = assertFailsWith<CancellationException> {
        executeMaestroActions(
          Maestro(driver),
          ActionFlow("app", listOf(Interaction.LaunchApp(false))),
          createOrchestra = {
            owner.cancel(cancellation)
            Orchestra(maestro = it)
          },
        )
      }
    }
    job.join()
    assertThat(observed).isSameInstanceAs(cancellation)
    assertThat(driver.launchedApps).isEqualTo(emptyList())
  }

  @Test
  fun `caller cancellation during compiler fault wins over preparation failure`() = runTest {
    val cancellation = CancellationException("cancel during compilation")
    var observed: CancellationException? = null
    val job = launch {
      val owner = currentCoroutineContext().job
      observed = assertFailsWith<CancellationException> {
        executeMaestroActions(Maestro(FakeDriver()), ActionFlow("app", emptyList()), compile = {
          owner.cancel(cancellation)
          error("PRIVATE_PREPARATION_FAULT")
        })
      }
    }
    job.join()
    assertThat(observed).isSameInstanceAs(cancellation)
  }

  @Test
  fun `Orchestra runtime failure interrupts remaining typed actions on both adapters`() = runTest {
    for (platform in listOf(Platform.ANDROID_MOBILE, Platform.IOS)) {
      val driver = RecordingDriver().apply { inputFailure = IllegalStateException("boom") }
      val session: DeviceSession = if (platform == Platform.IOS) {
        IosDeviceSession(Maestro(driver), FakeIosDevice())
      } else {
        AndroidDeviceSession(Maestro(driver), platform) { "" }
      }
      val result = session.executeActions(
        ActionFlow(
          "com.example.app",
          listOf(
            Interaction.LaunchApp(false),
            Interaction.InputText("test"),
            Interaction.KeyPress("BACK"),
          ),
        ),
      )
      assertThat(result.success).isEqualTo(false)
      assertThat(driver.inputCount).isEqualTo(1)
      assertThat(driver.pressedKeys).isEqualTo(emptyList())
    }
  }

  @Test
  fun `caller cancellation during actual Orchestra input joins preserves identity and stops sentinel`() = runTest {
    for (platform in listOf(Platform.ANDROID_MOBILE, Platform.IOS)) {
      val cancellation = CancellationException("caller cancellation")
      val admitted = CompletableDeferred<Unit>()
      val terminated = CompletableDeferred<Unit>()
      val release = CountDownLatch(1)
      val driver = RecordingDriver().apply {
        onInput = {
          admitted.complete(Unit)
          try {
            release.await()
          } finally {
            terminated.complete(Unit)
          }
        }
      }
      val session: DeviceSession = if (platform == Platform.IOS) {
        IosDeviceSession(Maestro(driver), FakeIosDevice())
      } else {
        AndroidDeviceSession(Maestro(driver), platform) { "" }
      }
      var observed: CancellationException? = null
      val job = launch {
        observed = assertFailsWith<CancellationException> {
          session.executeActions(
            ActionFlow(
              "com.example.app",
              listOf(
                Interaction.LaunchApp(false),
                Interaction.InputText("test"),
                Interaction.KeyPress("BACK"),
              ),
            ),
          )
        }
      }
      try {
        admitted.await()
        job.cancel(cancellation)
        job.join()
        assertThat(terminated.isCompleted).isEqualTo(true)
        assertThat(driver.inputCount).isEqualTo(1)
        assertThat(observed).isSameInstanceAs(cancellation)
        assertThat(driver.pressedKeys).isEqualTo(emptyList())
      } finally {
        release.countDown()
        job.cancel()
        job.join()
      }
    }
  }

  @Test
  fun `caller cancellation before preparation performs no compilation or execution`() = runTest {
    val cancellation = CancellationException("cancel before preparation")
    var observed: CancellationException? = null
    var compiled = false
    var ran = false
    val job = launch {
      currentCoroutineContext().cancel(cancellation)
      observed = assertFailsWith<CancellationException> {
        executeMaestroActions(
          Maestro(FakeDriver()),
          ActionFlow("app", emptyList()),
          runCommands = {
            ran = true
            true
          },
          compile = {
            compiled = true
            emptyList()
          },
        )
      }
    }
    job.join()
    assertThat(observed).isSameInstanceAs(cancellation)
    assertThat(compiled).isEqualTo(false)
    assertThat(ran).isEqualTo(false)
  }

  @Test
  fun `caller cancellation during runner joins and preserves the same exception`() = runTest {
    val cancellation = CancellationException("cancel during runner")
    val admitted = CompletableDeferred<Unit>()
    var observed: CancellationException? = null
    val job = launch {
      observed = assertFailsWith<CancellationException> {
        executeMaestroActions(Maestro(FakeDriver()), ActionFlow("app", emptyList()), runCommands = {
          admitted.complete(Unit)
          awaitCancellation()
        })
      }
    }
    admitted.await()
    job.cancel(cancellation)
    job.join()
    assertThat(observed).isSameInstanceAs(cancellation)
    assertThat(job.isCompleted).isEqualTo(true)
  }

  @Test
  fun `cancellation immediately after SDK return cannot become a successful result`() = runTest {
    val cancellation = CancellationException("cancel at return")
    var observed: CancellationException? = null
    val job = launch {
      observed = assertFailsWith<CancellationException> {
        executeMaestroActions(Maestro(FakeDriver()), ActionFlow("app", emptyList()), runCommands = {
          currentCoroutineContext().cancel(cancellation)
          true
        })
      }
    }
    job.join()
    assertThat(observed).isSameInstanceAs(cancellation)
  }

  @Test
  fun `injected runner cancellation while caller is active preserves original exception`() = runTest {
    val cancellation = CancellationException("runner cancellation")
    val observed = assertFailsWith<CancellationException> {
      executeMaestroActions(
        Maestro(FakeDriver()),
        ActionFlow("app", emptyList()),
        runCommands = { throw cancellation },
      )
    }
    assertThat(observed).isSameInstanceAs(cancellation)
  }

  @Test
  fun `SDK origin cancellation without cancelling caller preserves legacy unsuccessful result`() = runTest {
    val typedDriver = RecordingDriver().apply { inputFailure = CancellationException("SDK origin") }
    val legacyDriver = RecordingDriver().apply { inputFailure = CancellationException("SDK origin") }
    val typedSession = AndroidDeviceSession(Maestro(typedDriver), Platform.ANDROID_MOBILE) { "" }
    val legacySession = AndroidDeviceSession(Maestro(legacyDriver), Platform.ANDROID_MOBILE) { "" }
    val typed = typedSession.executeActions(
      ActionFlow(
        "com.example.app",
        listOf(
          Interaction.LaunchApp(false),
          Interaction.InputText("test"),
          Interaction.KeyPress("BACK"),
        ),
      ),
    )
    val legacy = legacySession.executeFlow(
      """
      appId: com.example.app
      ---
      - launchApp:
          clearState: false
      - inputText: test
      - pressKey: BACK
      """.trimIndent(),
    )
    assertThat(typedDriver.inputCount).isEqualTo(1)
    assertThat(legacyDriver.inputCount).isEqualTo(1)
    assertThat(typed).isEqualTo(legacy)
    assertThat(typed.success).isEqualTo(false)
    assertThat(typedDriver.pressedKeys).isEqualTo(emptyList())
    assertThat(legacyDriver.pressedKeys).isEqualTo(emptyList())
  }

  @Test
  fun `preparation cancellation preserves identity and never invokes execution`() = runTest {
    val cancellation = CancellationException("cancel compiler")
    val observed = assertFailsWith<CancellationException> {
      executeMaestroActions(
        Maestro(FakeDriver()),
        ActionFlow("app", emptyList()),
        runCommands = { error("must not run") },
        compile = { throw cancellation },
      )
    }
    assertThat(observed).isSameInstanceAs(cancellation)
  }

  private class RecordingDriver : FakeDriver() {
    val launchedApps = mutableListOf<String>()
    val pressedKeys = mutableListOf<KeyCode>()
    var inputFailure: Exception? = null
    var inputCount = 0
    var onInput: () -> Unit = {}
    override fun contentDescriptor(excludeKeyboardElements: Boolean): maestro.TreeNode = maestro.TreeNode()
    override fun pressKey(code: KeyCode) {
      pressedKeys += code
    }
    override fun inputText(text: String) {
      inputCount++
      onInput()
      inputFailure?.let { throw it }
    }
    override fun launchApp(appId: String, launchArguments: Map<String, Any>) {
      launchedApps += appId
    }
  }

  @Test
  fun `android executeFlow returns success for valid flow`() = runTest {
    val session = AndroidDeviceSession(
      maestro = Maestro(FakeDriver()),
      platform = Platform.ANDROID_MOBILE,
      executeShell = { "" },
    )

    val result = session.executeFlow(
      """
      appId: com.example.app
      ---
      - launchApp
      """.trimIndent(),
    )

    assertThat(result).isEqualTo(FlowResult(success = true))
  }

  @Test
  fun `android executeFlow returns failure output for invalid flow`() = runTest {
    val session = AndroidDeviceSession(
      maestro = Maestro(FakeDriver()),
      platform = Platform.ANDROID_MOBILE,
      executeShell = { "" },
    )

    val result = session.executeFlow("- launchApp")

    assertThat(result.success).isEqualTo(false)
    assertThat(result.output).contains("Config Section Required")
  }

  @Test
  fun `android executeFlow returns failure output for runtime errors`() = runTest {
    val session = AndroidDeviceSession(
      maestro = Maestro(ThrowingDriver()),
      platform = Platform.ANDROID_MOBILE,
      executeShell = { "" },
    )

    val result = session.executeFlow(
      """
      appId: com.example.app
      ---
      - launchApp
      """.trimIndent(),
    )

    assertThat(result.success).isEqualTo(false)
    assertThat(result.output).contains("boom")
  }

  @Test
  fun `ios executeFlow returns success for valid flow`() = runTest {
    val session = IosDeviceSession(
      maestro = Maestro(FakeDriver()),
      iosDevice = FakeIosDevice(),
    )

    val result = session.executeFlow(
      """
      appId: com.example.app
      ---
      - launchApp
      """.trimIndent(),
    )

    assertThat(result).isEqualTo(FlowResult(success = true))
  }

  @Test
  fun `resolveIosDeviceId returns explicit id when provided`() = runTest {
    assertThat(DeviceSessionFactory.resolveIosDeviceId("sim-123") { error("unused") })
      .isEqualTo("sim-123")
  }

  @Test
  fun `resolveIosDeviceId falls back to discovered simulator id`() = runTest {
    assertThat(DeviceSessionFactory.resolveIosDeviceId(null) { listOf("booted-sim") })
      .isEqualTo("booted-sim")
  }

  @Test
  fun `resolveIosDeviceId fails when discovery yields nothing`() = runTest {
    assertFailure {
      DeviceSessionFactory.resolveIosDeviceId(null) { emptyList() }
    }.messageContains("No iOS simulator found")
  }

  @Test
  fun `resolveIosDeviceId fails when multiple simulators are booted`() = runTest {
    assertFailure {
      DeviceSessionFactory.resolveIosDeviceId(null) { listOf("sim-1", "sim-2") }
    }.messageContains("Multiple booted iOS simulators found")
  }

  @Test
  fun `resolveAndroidConnection uses explicit serial`() {
    val expected = Any()
    val connection = DeviceSessionFactory.resolveAndroidConnection(
      deviceId = "emulator-5554",
      createWithId = { id ->
        assertThat(id).isEqualTo("emulator-5554")
        expected
      },
      discover = { error("unused") },
    )

    assertThat(connection).isEqualTo(expected)
  }

  @Test
  fun `resolveAndroidConnection falls back to discovery when serial omitted`() {
    val discovered = Any()

    val connection = DeviceSessionFactory.resolveAndroidConnection(
      deviceId = null,
      createWithId = { error("unused") },
      discover = { discovered },
    )

    assertThat(connection).isEqualTo(discovered)
  }

  @Test
  fun `resolveAndroidConnection rejects ip address device id`() {
    assertFailure {
      DeviceSessionFactory.resolveAndroidConnection(
        deviceId = "192.168.1.20",
        createWithId = { error("unused") },
        discover = { error("unused") },
      )
    }.messageContains("Expected an ADB serial")
  }

  private class FakeIosDevice : device.IOSDevice {
    override val deviceId: String = "sim-123"
    override fun open() = Unit
    override fun deviceInfo(): xcuitest.api.DeviceInfo {
      error("unused in test")
    }

    override fun viewHierarchy(excludeKeyboardElements: Boolean): hierarchy.ViewHierarchy {
      error("unused in test")
    }

    override fun tap(x: Int, y: Int) = Unit
    override fun longPress(x: Int, y: Int, durationMs: Long) = Unit
    override fun scroll(xStart: Double, yStart: Double, xEnd: Double, yEnd: Double, duration: Double) = Unit
    override fun input(text: String) = Unit
    override fun install(stream: java.io.InputStream) = Unit
    override fun uninstall(id: String) = Unit
    override fun clearAppState(id: String) = Unit
    override fun clearKeychain(): com.github.michaelbull.result.Result<Unit, Throwable> {
      error("unused in test")
    }

    override fun launch(id: String, launchArguments: Map<String, Any>) = Unit
    override fun stop(id: String) = Unit
    override fun isKeyboardVisible(): Boolean = false
    override fun openLink(link: String): com.github.michaelbull.result.Result<Unit, Throwable> {
      error("unused in test")
    }

    override fun takeScreenshot(out: okio.Sink, compressed: Boolean) = Unit
    override fun startScreenRecording(out: okio.Sink): device.IOSScreenRecording = error("unused in test")

    override fun addMedia(path: String) = Unit
    override fun setLocation(latitude: Double, longitude: Double): com.github.michaelbull.result.Result<Unit, Throwable> {
      error("unused in test")
    }

    override fun setOrientation(orientation: String) = Unit
    override fun isDarkModeEnabled(): Boolean = false
    override fun setAppearance(appearance: String) = Unit
    override fun isShutdown(): Boolean = false
    override fun isScreenStatic(): Boolean = true
    override fun setPermissions(id: String, permissions: Map<String, String>) = Unit
    override fun eraseText(charactersToErase: Int) = Unit
    override fun pressKey(name: String) = Unit
    override fun pressButton(name: String) = Unit
    override fun close() = Unit
  }

  private open class FakeDriver : Driver {
    override fun name(): String = "fake"
    override fun open() = Unit
    override fun close() = Unit
    override fun deviceInfo(): DeviceInfo = DeviceInfo(MaestroPlatform.ANDROID, 1080, 1920, 1080, 1920)
    override fun launchApp(appId: String, launchArguments: Map<String, Any>) = Unit
    override fun stopApp(appId: String) = Unit
    override fun killApp(appId: String) = Unit
    override fun clearAppState(appId: String) = Unit
    override fun clearKeychain() = Unit
    override fun tap(point: Point) = Unit
    override fun longPress(point: Point) = Unit
    override fun pressKey(code: KeyCode) = Unit
    override fun contentDescriptor(excludeKeyboardElements: Boolean): maestro.TreeNode {
      error("unused in test")
    }

    override fun scrollVertical() = Unit
    override fun isKeyboardVisible(): Boolean = false
    override fun swipe(start: Point, end: Point, durationMs: Long) = Unit
    override fun swipe(swipeDirection: SwipeDirection, durationMs: Long) = Unit
    override fun swipe(elementPoint: Point, direction: SwipeDirection, durationMs: Long) = Unit
    override fun backPress() = Unit
    override fun inputText(text: String) = Unit
    override fun openLink(link: String, appId: String?, autoVerify: Boolean, browser: Boolean) = Unit
    override fun hideKeyboard() = Unit
    override fun takeScreenshot(out: okio.Sink, compressed: Boolean) = Unit
    override fun startScreenRecording(out: okio.Sink): ScreenRecording = object : ScreenRecording {
      override val startedAt: java.time.Instant = java.time.Instant.EPOCH
      override fun close() = Unit
    }
    override fun setLocation(latitude: Double, longitude: Double) = Unit
    override fun setOrientation(orientation: DeviceOrientation) = Unit
    override fun eraseText(charactersToErase: Int) = Unit
    override fun setProxy(host: String, port: Int) = Unit
    override fun resetProxy() = Unit
    override fun isShutdown(): Boolean = false
    override fun waitUntilScreenIsStatic(timeoutMs: Long): Boolean = true
    override fun waitForAppToSettle(
      initialHierarchy: maestro.ViewHierarchy?,
      appId: String?,
      timeoutMs: Int?,
    ): maestro.ViewHierarchy? = null

    override fun capabilities(): List<maestro.Capability> = emptyList()
    override fun setPermissions(appId: String, permissions: Map<String, String>) = Unit
    override fun addMedia(mediaFiles: List<java.io.File>) = Unit
    override fun isAirplaneModeEnabled(): Boolean = false
    override fun setAirplaneMode(enabled: Boolean) = Unit
    override fun isDarkModeEnabled(): Boolean = false
    override fun setDarkMode(enabled: Boolean) = Unit
  }

  private class ThrowingDriver : FakeDriver() {
    override fun stopApp(appId: String) {
      error("boom")
    }
  }
}
