package me.chrisbanes.verity.core.parser

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import me.chrisbanes.verity.core.model.JourneyStep
import kotlin.test.Test

class LoopStepInferrerTest {
    @Test
    fun `simple until pattern`() {
        val result = LoopStepInferrer.infer("Navigate down until TV Shows row")
        assertThat(result).isNotNull()
        assertThat(result!!.action).isEqualTo("Navigate down")
        assertThat(result.until).isEqualTo("TV Shows row")
        assertThat(result.max).isEqualTo(20)
    }

    @Test
    fun `press until pattern`() {
        val result = LoopStepInferrer.infer("Press D-pad down until Settings")
        assertThat(result).isNotNull()
        assertThat(result!!.action).isEqualTo("Press D-pad down")
        assertThat(result.until).isEqualTo("Settings")
    }

    @Test
    fun `scroll until pattern`() {
        val result = LoopStepInferrer.infer("Scroll down until end of list")
        assertThat(result).isNotNull()
        assertThat(result!!.action).isEqualTo("Scroll down")
        assertThat(result.until).isEqualTo("end of list")
    }

    @Test
    fun `with max iterations`() {
        val result = LoopStepInferrer.infer("Navigate down until TV Shows up to 10 times")
        assertThat(result).isNotNull()
        assertThat(result!!.action).isEqualTo("Navigate down")
        assertThat(result.until).isEqualTo("TV Shows")
        assertThat(result.max).isEqualTo(10)
    }

    @Test
    fun `non-loop action returns null`() {
        assertThat(LoopStepInferrer.infer("Launch the app")).isNull()
    }

    @Test
    fun `text without until returns null`() {
        assertThat(LoopStepInferrer.infer("Press D-pad down")).isNull()
    }

    @Test
    fun `verb not in allowed list returns null`() {
        assertThat(LoopStepInferrer.infer("Launch app until Home")).isNull()
    }

    @Test
    fun `case insensitive verb matching`() {
        val result = LoopStepInferrer.infer("NAVIGATE down until Home")
        assertThat(result).isNotNull()
    }

    @Test
    fun `move verb is recognized`() {
        val result = LoopStepInferrer.infer("Move right until Search")
        assertThat(result).isNotNull()
        assertThat(result!!.action).isEqualTo("Move right")
    }

    @Test
    fun `step verb is recognized`() {
        val result = LoopStepInferrer.infer("Step through until Complete")
        assertThat(result).isNotNull()
    }

    @Test
    fun `go verb is recognized`() {
        val result = LoopStepInferrer.infer("Go down until Footer")
        assertThat(result).isNotNull()
    }
}
