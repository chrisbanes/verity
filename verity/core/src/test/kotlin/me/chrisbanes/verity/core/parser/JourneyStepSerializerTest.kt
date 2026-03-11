package me.chrisbanes.verity.core.parser

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import me.chrisbanes.verity.core.model.AssertMode
import me.chrisbanes.verity.core.model.JourneyStep
import kotlin.test.Test

class JourneyStepSerializerTest {
    @Test
    fun `parses explicit visual assertion`() {
        val step = JourneyStepParser.parse("[?visual] Backdrop image loads")
        assertThat(step).isInstanceOf<JourneyStep.Assert>()
        val assert = step as JourneyStep.Assert
        assertThat(assert.mode).isEqualTo(AssertMode.VISUAL)
        assertThat(assert.description).isEqualTo("Backdrop image loads")
    }

    @Test
    fun `parses explicit tree assertion`() {
        val step = JourneyStepParser.parse("[?tree] Synopsis has content")
        assertThat(step).isInstanceOf<JourneyStep.Assert>()
        assertThat((step as JourneyStep.Assert).mode).isEqualTo(AssertMode.TREE)
    }

    @Test
    fun `parses explicit visible assertion`() {
        val step = JourneyStepParser.parse("[?visible] Home")
        assertThat(step).isInstanceOf<JourneyStep.Assert>()
        assertThat((step as JourneyStep.Assert).mode).isEqualTo(AssertMode.VISIBLE)
    }

    @Test
    fun `parses explicit focused assertion`() {
        val step = JourneyStepParser.parse("[?focused] Settings")
        assertThat(step).isInstanceOf<JourneyStep.Assert>()
        assertThat((step as JourneyStep.Assert).mode).isEqualTo(AssertMode.FOCUSED)
    }

    @Test
    fun `parses generic assertion with inferred mode - short text`() {
        val step = JourneyStepParser.parse("[?] Home")
        assertThat(step).isInstanceOf<JourneyStep.Assert>()
        val assert = step as JourneyStep.Assert
        assertThat(assert.description).isEqualTo("Home")
        assertThat(assert.mode).isEqualTo(AssertMode.VISIBLE)
    }

    @Test
    fun `parses generic assertion with inferred mode - long text`() {
        val step = JourneyStepParser.parse("[?] Detail page shows title and synopsis")
        assertThat(step).isInstanceOf<JourneyStep.Assert>()
        assertThat((step as JourneyStep.Assert).mode).isEqualTo(AssertMode.TREE)
    }

    @Test
    fun `parses generic assertion with visual keywords`() {
        val step = JourneyStepParser.parse("[?] Backdrop image is visible")
        assertThat(step).isInstanceOf<JourneyStep.Assert>()
        assertThat((step as JourneyStep.Assert).mode).isEqualTo(AssertMode.VISUAL)
    }

    @Test
    fun `parses loop from NL`() {
        val step = JourneyStepParser.parse("Navigate down until TV Shows row")
        assertThat(step).isInstanceOf<JourneyStep.Loop>()
        val loop = step as JourneyStep.Loop
        assertThat(loop.action).isEqualTo("Navigate down")
        assertThat(loop.until).isEqualTo("TV Shows row")
    }

    @Test
    fun `parses NL assertion keyword`() {
        val step = JourneyStepParser.parse("Verify that the title is visible")
        assertThat(step).isInstanceOf<JourneyStep.Assert>()
        assertThat((step as JourneyStep.Assert).description).isEqualTo("the title is visible")
    }

    @Test
    fun `parses plain action`() {
        val step = JourneyStepParser.parse("Press D-pad down")
        assertThat(step).isInstanceOf<JourneyStep.Action>()
        assertThat((step as JourneyStep.Action).instruction).isEqualTo("Press D-pad down")
    }

    @Test
    fun `parses launch as action not assertion`() {
        val step = JourneyStepParser.parse("Launch the app")
        assertThat(step).isInstanceOf<JourneyStep.Action>()
    }

    @Test
    fun `explicit mode prefix takes priority over loop inference`() {
        val step = JourneyStepParser.parse("[?] Navigate down until TV Shows")
        assertThat(step).isInstanceOf<JourneyStep.Assert>()
    }

    @Test
    fun `loop inference takes priority over assertion inference`() {
        val step = JourneyStepParser.parse("Navigate down until TV Shows row")
        assertThat(step).isInstanceOf<JourneyStep.Loop>()
    }

    @Test
    fun `trims whitespace`() {
        val step = JourneyStepParser.parse("  [?]   Home   ")
        assertThat(step).isInstanceOf<JourneyStep.Assert>()
        assertThat((step as JourneyStep.Assert).description).isEqualTo("Home")
    }
}
