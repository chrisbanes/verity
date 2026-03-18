package me.chrisbanes.verity.smoke

import assertk.assertThat
import assertk.assertions.isTrue
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import me.chrisbanes.verity.agent.FakeTextAgent
import me.chrisbanes.verity.agent.InspectorAgent
import me.chrisbanes.verity.agent.NavigatorAgent
import me.chrisbanes.verity.agent.Orchestrator
import me.chrisbanes.verity.core.journey.JourneyLoader
import me.chrisbanes.verity.device.DeviceSession
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag

@Tag("ios")
class IosSettingsSmoke {
  companion object {
    private lateinit var lifecycle: DeviceLifecycle
    private lateinit var session: DeviceSession

    @BeforeAll
    @JvmStatic
    fun boot() {
      lifecycle = runBlocking { DeviceLifecycle.discoverOrBootIos() }
      session = runBlocking { lifecycle.connect() }
    }

    @AfterAll
    @JvmStatic
    fun shutdown() {
      if (::session.isInitialized) session.close()
      if (::lifecycle.isInitialized) lifecycle.close()
    }
  }

  @Test
  fun `settings journey passes`() = runBlocking {
    val url = javaClass.classLoader.getResource("ios-settings.journey.yaml")!!
    val journey = JourneyLoader.fromFile(java.io.File(url.toURI()))

    val orchestrator = Orchestrator(
      session = session,
      navigatorFactory = {
        NavigatorAgent("unused") { _ ->
          FakeTextAgent { _ ->
            "appId: com.apple.Preferences\n---\n- tapOn: General"
          }
        }
      },
      inspectorFactory = {
        InspectorAgent(
          treeAgentFactory = { FakeTextAgent { error("VISIBLE mode: inspector should not be called") } },
          evaluateVisualContent = { _, _, _ -> error("inspector visual should not be called") },
        )
      },
    )

    val result = orchestrator.run(journey)
    assertThat(result.passed).isTrue()
  }
}
