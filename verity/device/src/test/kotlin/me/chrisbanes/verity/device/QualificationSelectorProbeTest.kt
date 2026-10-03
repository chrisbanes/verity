package me.chrisbanes.verity.device

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotEqualTo
import assertk.assertions.isTrue
import kotlin.test.Test
import maestro.DeviceInfo
import maestro.Driver
import maestro.KeyCode
import maestro.Maestro
import maestro.Point
import maestro.ScreenRecording
import maestro.SwipeDirection
import maestro.TreeNode
import maestro.device.DeviceOrientation
import maestro.device.Platform as MaestroPlatform
import me.chrisbanes.verity.core.model.Platform
import me.chrisbanes.verity.device.android.AndroidDeviceSession

class QualificationSelectorProbeTest {
  @Test
  fun `SDK selector proves the unique literal node despite a shared ancestor identifier`() {
    val approved = node("Network & internet", "pkg:id/title", clickable = true)
    val root = TreeNode(
      attributes = mutableMapOf("resource-id" to "pkg:id/title"),
      children = listOf(approved),
    )

    val result = QualificationSelectorProbe.inspect(root, "Network & internet")

    assertThat(result.outcome).isEqualTo("ready")
    assertThat(result.labelMatches).isEqualTo(1)
    assertThat(result.textMatches).isEqualTo(1)
    assertThat(result.idMatches).isEqualTo(2)
    assertThat(result.sameNode).isTrue()
    assertThat(result.resourceId).isEqualTo("pkg:id/title")
    assertThat(result.selectedPath).isEqualTo("0.0")
  }

  @Test
  fun `SDK deepest and clickable selection rejects a different row with the same identifier`() {
    val approved = node("Network & internet", "pkg:id/title", clickable = false)
    val other = node("Other row", "pkg:id/title", clickable = true)
    val root = TreeNode(children = listOf(approved, other))

    val result = QualificationSelectorProbe.inspect(root, "Network & internet")

    assertThat(result.outcome).isEqualTo("selection-mismatch")
    assertThat(result.labelMatches).isEqualTo(1)
    assertThat(result.sameNode).isFalse()
    assertThat(result.resourceId).isEqualTo(null)
  }

  @Test
  fun `case insensitive text selection that outranks the literal fixture is rejected`() {
    val caseVariant = node("NETWORK & INTERNET", "pkg:id/other", clickable = true)
    val approved = node("Network & internet", "pkg:id/title", clickable = false)
    val root = TreeNode(children = listOf(caseVariant, approved))

    val result = QualificationSelectorProbe.inspect(root, "Network & internet")

    assertThat(result.outcome).isEqualTo("selection-mismatch")
    assertThat(result.labelMatches).isEqualTo(1)
    assertThat(result.textMatches).isEqualTo(2)
  }

  @Test
  fun `duplicate exact labels fail closed while iOS accessible text follows SDK text mapping`() {
    val duplicate = TreeNode(
      children = listOf(
        node("General", "prefs:general", "General", clickable = true),
        node("General", "prefs:general-duplicate", clickable = true),
      ),
    )

    assertThat(QualificationSelectorProbe.inspect(duplicate, "General").outcome).isEqualTo("ambiguous-label")

    val iosStyle = TreeNode(children = listOf(node("About", "prefs:about", accessibilityText = "About", clickable = true)))
    val result = QualificationSelectorProbe.inspect(iosStyle, "About")
    assertThat(result.outcome).isEqualTo("ready")
    assertThat(result.sameNode).isTrue()

    val accessibilityOnly = TreeNode(children = listOf(node(null, "prefs:about", accessibilityText = "About", clickable = true)))
    assertThat(accessibilityOnly.children.single().attributes["text"]).isEqualTo(null)
    assertThat(QualificationSelectorProbe.inspect(accessibilityOnly, "About").outcome).isEqualTo("ready")
  }

  @Test
  fun `iOS row with label on parent and child proves text descendant and ID row are same fixture`() = kotlinx.coroutines.test.runTest {
    val resourceId = "com.apple.settings.general"
    val child = node(null, "", clickable = false, bounds = "[30,305][131,333]", hintText = "General")
    val row = node(null, resourceId, clickable = true, bounds = "[16,293][386,345]", hintText = "General").copy(children = listOf(child))
    val root = TreeNode(children = listOf(row))
    val driver = ProbeDriver(root, DeviceInfo(MaestroPlatform.IOS, 402, 874, 402, 874))
    val session = AndroidDeviceSession(Maestro(driver), Platform.ANDROID_MOBILE, executeShell = { "" })

    val evidence = QualificationSelectorProbe.capture(session, "General")
    val textResult = session.executeFlow(
      """
      appId: com.apple.Preferences
      ---
      - tapOn: General
      """.trimIndent(),
    )
    val idResult = session.executeFlow(
      """
      appId: com.apple.Preferences
      ---
      - tapOn:
          id: com.apple.settings.general
      """.trimIndent(),
    )

    assertThat(evidence.outcome).isEqualTo("ready")
    assertThat(evidence.labelMatches).isEqualTo(2)
    assertThat(evidence.sameNode).isFalse()
    assertThat(evidence.resourceId).isEqualTo(resourceId)
    assertThat(evidence.selectedPath).isEqualTo("0.0")
    assertThat(textResult.success).isTrue()
    assertThat(idResult.success).isTrue()
    assertThat(driver.taps).isEqualTo(listOf(Point(80, 319), Point(201, 319)))
  }

  @Test
  fun `iOS fixture ancestry does not admit different IDs or a sibling General row`() {
    val rowId = "com.apple.settings.general"
    val differentId = TreeNode(
      children = listOf(
        node(null, rowId, clickable = true, hintText = "General").copy(
          children = listOf(node(null, "com.apple.settings.other", clickable = false, hintText = "General")),
        ),
      ),
    )
    val sibling = TreeNode(
      children = listOf(
        node(null, rowId, clickable = true, hintText = "General"),
        node(null, rowId, clickable = true, hintText = "General"),
      ),
    )

    assertThat(QualificationSelectorProbe.inspect(differentId, "General").outcome).isNotEqualTo("ready")
    assertThat(QualificationSelectorProbe.inspect(sibling, "General").outcome).isNotEqualTo("ready")
  }

  @Test
  fun `unselected repeated resource ID on another row does not invalidate the actual SDK choice`() {
    val resourceId = "com.apple.settings.general"
    val approved = node("General", resourceId, clickable = true, bounds = "[10,20][40,50]")
    val other = node("Other row", resourceId, clickable = false, bounds = "[50,20][90,50]")
    val root = TreeNode(children = listOf(approved, other))

    val evidence = QualificationSelectorProbe.inspect(root, "General")

    assertThat(evidence.outcome).isEqualTo("ready")
    assertThat(evidence.sameNode).isTrue()
    assertThat(evidence.idMatches).isEqualTo(2)
    assertThat(evidence.selectedPath).isEqualTo("0.0")
  }

  @Test
  fun `missing identifiers and SDK-filtered snapshots are unavailable`() {
    val missingId = TreeNode(children = listOf(node("General", "", clickable = true)))
    assertThat(QualificationSelectorProbe.inspect(missingId, "General").outcome).isEqualTo("missing-id")

    assertThat(QualificationSelectorProbe.inspect(TreeNode(children = listOf(node("General", "prefs:general", bounds = ""))), "General").outcome)
      .isEqualTo("missing-bounds")

    val sdkFiltered = TreeNode(children = listOf(node("Off screen", "prefs:offscreen", clickable = true)))
    assertThat(QualificationSelectorProbe.inspect(sdkFiltered, "General").outcome).isEqualTo("not-ready")
  }

  @Test
  fun `actual Orchestra text and ID taps select the same node as the fixture probe`() = kotlinx.coroutines.test.runTest {
    val approved = node("Network & internet", "pkg:id/title", clickable = true, bounds = "[10,20][30,40]")
    val root = TreeNode(
      attributes = mutableMapOf("resource-id" to "pkg:id/title", "bounds" to "[0,0][100,100]"),
      children = listOf(approved),
    )
    val driver = ProbeDriver(root)
    val session = AndroidDeviceSession(Maestro(driver), Platform.ANDROID_MOBILE, executeShell = { "" })
    val evidence = QualificationSelectorProbe.capture(session, "Network & internet")

    val textResult = session.executeFlow(
      """
      appId: com.example.app
      ---
      - tapOn: "Network & internet"
      """.trimIndent(),
    )
    val textCaseResult = session.executeFlow(
      """
      appId: com.example.app
      ---
      - tapOn: "network & INTERNET"
      """.trimIndent(),
    )
    val idResult = session.executeFlow(
      """
      appId: com.example.app
      ---
      - tapOn:
          id: "pkg:id/title"
      """.trimIndent(),
    )
    val idSuffixResult = session.executeFlow(
      """
      appId: com.example.app
      ---
      - tapOn:
          id: ".*title"
      """.trimIndent(),
    )

    assertThat(textResult.success).isTrue()
    assertThat(textCaseResult.success).isTrue()
    assertThat(idResult.success).isTrue()
    assertThat(idSuffixResult.success).isTrue()
    assertThat(evidence.outcome).isEqualTo("ready")
    assertThat(driver.taps).isEqualTo(listOf(Point(20, 30), Point(20, 30), Point(20, 30), Point(20, 30)))
  }

  @Test
  fun `iOS placeholder only fixture proves escaped text and ID selection`() = kotlinx.coroutines.test.runTest {
    val label = "New [About]?"
    val resourceId = "prefs:id/placeholder[1]"
    val approved = node(null, resourceId, clickable = true, bounds = "[10,20][30,40]", hintText = label)
    val root = TreeNode(
      attributes = mutableMapOf("bounds" to "[0,0][100,100]"),
      children = listOf(approved),
    )
    val driver = ProbeDriver(root)
    val session = AndroidDeviceSession(Maestro(driver), Platform.ANDROID_MOBILE, executeShell = { "" })

    assertThat(approved.attributes["text"]).isEqualTo(null)
    assertThat(approved.attributes["accessibilityText"]).isEqualTo(null)
    val evidence = QualificationSelectorProbe.capture(session, label)
    val textResult = session.executeFlow(
      """
      appId: com.example.app
      ---
      - tapOn: '${Regex.escape(label)}'
      """.trimIndent(),
    )
    val idResult = session.executeFlow(
      """
      appId: com.example.app
      ---
      - tapOn:
          id: '${Regex.escape(resourceId)}'
      """.trimIndent(),
    )

    assertThat(evidence.outcome).isEqualTo("ready")
    assertThat(evidence.resourceId).isEqualTo(resourceId)
    assertThat(evidence.selectedPath).isEqualTo("0.0")
    assertThat(textResult.success).isTrue()
    assertThat(idResult.success).isTrue()
    assertThat(driver.taps).isEqualTo(listOf(Point(20, 30), Point(20, 30)))

    val unsafeRow = node("NEW [ABOUT]?", "prefs:id/other", clickable = true)
    val unsafeTree = TreeNode(children = listOf(node(null, resourceId, clickable = false, hintText = label), unsafeRow))
    assertThat(QualificationSelectorProbe.inspect(unsafeTree, label).outcome).isEqualTo("selection-mismatch")
  }

  @Test
  fun `actual Orchestra ID selection keeps stable order for equally clickable repeated IDs`() = kotlinx.coroutines.test.runTest {
    val first = node("First row", "pkg:id/title", clickable = true, bounds = "[10,20][30,40]")
    val second = node("Second row", "pkg:id/title", clickable = true, bounds = "[60,20][80,40]")
    val driver = ProbeDriver(TreeNode(children = listOf(first, second)))
    val session = AndroidDeviceSession(Maestro(driver), Platform.ANDROID_MOBILE, executeShell = { "" })

    val result = session.executeFlow(
      """
      appId: com.example.app
      ---
      - tapOn:
          id: ".*title"
      """.trimIndent(),
    )

    assertThat(result.success).isTrue()
    assertThat(driver.taps).isEqualTo(listOf(Point(20, 30)))
  }

  @Test
  fun `probe rejects the all filtered SDK fallback that Orchestra still selects`() = kotlinx.coroutines.test.runTest {
    val offscreen = node("Network & internet", "pkg:id/title", clickable = true, bounds = "[200,200][300,300]")
    val driver = ProbeDriver(TreeNode(children = listOf(offscreen)))
    val session = AndroidDeviceSession(Maestro(driver), Platform.ANDROID_MOBILE, executeShell = { "" })

    val evidence = QualificationSelectorProbe.capture(session, "Network & internet")
    val result = session.executeFlow(
      """
      appId: com.example.app
      ---
      - tapOn: "Network & internet"
      """.trimIndent(),
    )

    assertThat(evidence.outcome).isEqualTo("outside-viewport")
    assertThat(result.success).isTrue()
    assertThat(driver.taps).isEqualTo(listOf(Point(250, 250)))
  }

  @Test
  fun `SDK hierarchy filtering and Orchestra exclude an offscreen repeated ID competitor`() = kotlinx.coroutines.test.runTest {
    val approved = node("Network & internet", "pkg:id/title", clickable = false, bounds = "[10,20][30,40]")
    val offscreen = node("Other row", "pkg:id/title", clickable = true, bounds = "[200,200][300,300]")
    val root = TreeNode(
      attributes = mutableMapOf("bounds" to "[0,0][100,100]"),
      children = listOf(approved, offscreen),
    )
    val driver = ProbeDriver(root)
    val session = AndroidDeviceSession(Maestro(driver), Platform.ANDROID_MOBILE, executeShell = { "" })

    val evidence = QualificationSelectorProbe.capture(session, "Network & internet")
    val result = session.executeFlow(
      """
      appId: com.example.app
      ---
      - tapOn:
          id: "pkg:id/title"
      """.trimIndent(),
    )

    assertThat(evidence.outcome).isEqualTo("ready")
    assertThat(evidence.idMatches).isEqualTo(1)
    assertThat(result.success).isTrue()
    assertThat(driver.taps).isEqualTo(listOf(Point(20, 30)))
  }

  private fun node(
    text: String?,
    id: String,
    accessibilityText: String? = null,
    clickable: Boolean? = null,
    bounds: String = "[1,1][50,20]",
    hintText: String? = null,
  ) = TreeNode(
    attributes = buildMap {
      text?.let { put("text", it) }
      put("resource-id", id)
      put("bounds", bounds)
      accessibilityText?.let { put("accessibilityText", it) }
      hintText?.let { put("hintText", it) }
    }.toMutableMap(),
    clickable = clickable,
  )

  private class ProbeDriver(
    private val root: TreeNode,
    private val info: DeviceInfo = DeviceInfo(MaestroPlatform.ANDROID, 100, 100, 100, 100),
  ) : Driver {
    val taps = mutableListOf<Point>()
    override fun name() = "qualification-probe"
    override fun open() = Unit
    override fun close() = Unit
    override fun deviceInfo() = info
    override fun launchApp(appId: String, launchArguments: Map<String, Any>) = Unit
    override fun stopApp(appId: String) = Unit
    override fun killApp(appId: String) = Unit
    override fun clearAppState(appId: String) = Unit
    override fun clearKeychain() = Unit
    override fun tap(point: Point) {
      taps += point
    }
    override fun longPress(point: Point) = Unit
    override fun pressKey(code: KeyCode) = Unit
    override fun contentDescriptor(excludeKeyboardElements: Boolean) = root
    override fun scrollVertical() = Unit
    override fun isKeyboardVisible() = false
    override fun swipe(start: Point, end: Point, durationMs: Long) = Unit
    override fun swipe(swipeDirection: SwipeDirection, durationMs: Long) = Unit
    override fun swipe(elementPoint: Point, direction: SwipeDirection, durationMs: Long) = Unit
    override fun backPress() = Unit
    override fun inputText(text: String) = Unit
    override fun openLink(link: String, appId: String?, autoVerify: Boolean, browser: Boolean) = Unit
    override fun hideKeyboard() = Unit
    override fun takeScreenshot(out: okio.Sink, compressed: Boolean) = Unit
    override fun startScreenRecording(out: okio.Sink): ScreenRecording = object : ScreenRecording {
      override val startedAt = java.time.Instant.EPOCH
      override fun close() = Unit
    }
    override fun setLocation(latitude: Double, longitude: Double) = Unit
    override fun setOrientation(orientation: DeviceOrientation) = Unit
    override fun eraseText(charactersToErase: Int) = Unit
    override fun setProxy(host: String, port: Int) = Unit
    override fun resetProxy() = Unit
    override fun isShutdown() = false
    override fun waitUntilScreenIsStatic(timeoutMs: Long) = true
    override fun waitForAppToSettle(initialHierarchy: maestro.ViewHierarchy?, appId: String?, timeoutMs: Int?) = null
    override fun capabilities() = emptyList<maestro.Capability>()
    override fun setPermissions(appId: String, permissions: Map<String, String>) = Unit
    override fun addMedia(mediaFiles: List<java.io.File>) = Unit
    override fun isAirplaneModeEnabled() = false
    override fun setAirplaneMode(enabled: Boolean) = Unit
    override fun isDarkModeEnabled() = false
    override fun setDarkMode(enabled: Boolean) = Unit
  }
}
