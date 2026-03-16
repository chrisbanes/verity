package me.chrisbanes.verity.cli

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import java.nio.file.Path
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import me.chrisbanes.verity.agent.InspectorAgent
import me.chrisbanes.verity.agent.NavigatorAgent
import me.chrisbanes.verity.agent.Orchestrator
import me.chrisbanes.verity.core.hierarchy.HierarchyNode
import me.chrisbanes.verity.core.journey.JourneyLoader
import me.chrisbanes.verity.core.model.FlowResult
import me.chrisbanes.verity.core.model.Platform
import me.chrisbanes.verity.device.DeviceSession

class RunCommandSmokeTest {

  @Test
  fun `smoke journey loads and passes with visible text`() = runTest {
    val journeyUrl = javaClass.classLoader.getResource("smoke/minimal.journey.yaml")!!
    val journey = JourneyLoader.fromFile(java.io.File(journeyUrl.toURI()))

    assertThat(journey.name).isEqualTo("Minimal smoke")
    assertThat(journey.app).isEqualTo("com.example.app")
    assertThat(journey.platform).isEqualTo(Platform.ANDROID_TV)

    // Create a fake device session that returns "Home" in the hierarchy
    val session = FakeDeviceSession(
      hierarchyNode = HierarchyNode(attributes = mapOf("text" to "Home")),
    )

    val orchestrator = Orchestrator(
      session = session,
      navigatorFactory = {
        NavigatorAgent("unused") { _ ->
          FakeTextAgent { error("Navigator should not be called for key-mapped actions") }
        }
      },
      inspectorFactory = {
        InspectorAgent(
          treeAgentFactory = { FakeTextAgent { error("Inspector tree agent should not be called for VISIBLE assertions") } },
          evaluateVisualContent = { _, _, _ -> error("Inspector visual should not be called") },
        )
      },
    )

    val result = orchestrator.run(journey)

    // The journey has: press d-pad down (fast path), then [?] Home (visible assertion)
    // "Home" is in the hierarchy, so it should pass
    assertThat(result.passed).isTrue()
    assertThat(result.segments.size).isEqualTo(1)
  }

  @Test
  fun `smoke journey fails when text not visible`() = runTest {
    val journeyUrl = javaClass.classLoader.getResource("smoke/minimal.journey.yaml")!!
    val journey = JourneyLoader.fromFile(java.io.File(journeyUrl.toURI()))

    // No "Home" text in hierarchy — assertion should fail
    val session = FakeDeviceSession(
      hierarchyNode = HierarchyNode(attributes = mapOf("text" to "Settings")),
    )

    val orchestrator = Orchestrator(
      session = session,
      navigatorFactory = {
        NavigatorAgent("unused") { _ ->
          FakeTextAgent { error("unused") }
        }
      },
      inspectorFactory = {
        InspectorAgent(
          treeAgentFactory = { FakeTextAgent { error("unused") } },
          evaluateVisualContent = { _, _, _ -> error("unused") },
        )
      },
    )

    val result = orchestrator.run(journey)

    assertThat(result.passed).isFalse()
  }

  @Test
  fun `smoke context fixture loads`() {
    val contextUrl = javaClass.classLoader.getResource("smoke/context/app.md")!!
    val content = java.io.File(contextUrl.toURI()).readText()
    assertThat(content).transform { it.contains("Smoke Test App Context") }.isTrue()
  }

  private class FakeDeviceSession(
    private val hierarchyNode: HierarchyNode = HierarchyNode(attributes = emptyMap()),
  ) : DeviceSession {
    override val platform: Platform = Platform.ANDROID_TV

    override suspend fun executeFlow(yaml: String): FlowResult = FlowResult(success = true)
    override suspend fun pressKey(keyName: String) = Unit
    override suspend fun captureHierarchyTree(): HierarchyNode = hierarchyNode
    override suspend fun captureScreenshot(output: Path) = Unit
    override suspend fun shell(command: String): String = ""
    override suspend fun waitForAnimationToEnd() = Unit
    override fun close() = Unit
  }
}
