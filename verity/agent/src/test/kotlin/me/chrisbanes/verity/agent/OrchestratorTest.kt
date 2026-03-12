package me.chrisbanes.verity.agent

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import kotlin.test.Test
import me.chrisbanes.verity.core.model.Platform

class OrchestratorTest {
  @Test
  fun `classifies all-key-mapped actions as fast path`() {
    val actions = listOf("press d-pad down", "press d-pad down", "press select")
    val isFastPath = Orchestrator.isFastPath(actions, Platform.ANDROID_TV)
    assertThat(isFastPath).isTrue()
  }

  @Test
  fun `classifies non-mappable actions as slow path`() {
    val actions = listOf("press d-pad down", "navigate to settings page")
    val isFastPath = Orchestrator.isFastPath(actions, Platform.ANDROID_TV)
    assertThat(isFastPath).isEqualTo(false)
  }
}
