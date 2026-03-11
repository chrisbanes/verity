package me.chrisbanes.verity.core.model

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import kotlin.test.Test

class JourneySegmentTest {
  @Test
  fun `segment with actions and assertion`() {
    val segment = JourneySegment(
      index = 0,
      actions = listOf(JourneyStep.Action("Launch the app")),
      assertion = JourneyStep.Assert("Home", AssertMode.VISIBLE),
    )
    assertThat(segment.actions.size).isEqualTo(1)
    assertThat(segment.assertion?.description).isEqualTo("Home")
    assertThat(segment.loop).isNull()
  }

  @Test
  fun `segment with loop and no assertion`() {
    val segment = JourneySegment(
      index = 1,
      actions = emptyList(),
      loop = JourneyStep.Loop("Press D-pad down", "TV Shows"),
    )
    assertThat(segment.assertion).isNull()
    assertThat(segment.loop?.until).isEqualTo("TV Shows")
  }
}
