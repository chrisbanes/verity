package me.chrisbanes.verity.agent

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
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
    assertThat(isFastPath).isFalse()
  }

  @Test
  fun `JourneyResult passed is true when all segments pass`() {
    val result = JourneyResult(
      "test",
      listOf(SegmentResult(0, passed = true), SegmentResult(1, passed = true)),
    )
    assertThat(result.passed).isTrue()
  }

  @Test
  fun `JourneyResult passed is false when any segment fails`() {
    val result = JourneyResult(
      "test",
      listOf(SegmentResult(0, passed = true), SegmentResult(1, passed = false)),
    )
    assertThat(result.passed).isFalse()
  }

  @Test
  fun `JourneyResult failedAt returns first failed segment index`() {
    val result = JourneyResult(
      "test",
      listOf(
        SegmentResult(0, passed = true),
        SegmentResult(1, passed = false),
        SegmentResult(2, passed = false),
      ),
    )
    assertThat(result.failedAt).isEqualTo(1)
  }

  @Test
  fun `JourneyResult failedAt is null when all pass`() {
    val result = JourneyResult(
      "test",
      listOf(SegmentResult(0, passed = true)),
    )
    assertThat(result.failedAt).isNull()
  }
}
