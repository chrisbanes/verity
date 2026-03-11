package me.chrisbanes.verity.core.parser

import assertk.assertThat
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isEqualTo
import me.chrisbanes.verity.core.model.AssertMode
import me.chrisbanes.verity.core.model.JourneyStep
import kotlin.test.Test

class AssertionStepInferrerTest {
    @Test
    fun `verify that pattern`() {
        val result = AssertionStepInferrer.infer("Verify that Home is visible")
        assertThat(result).isNotNull()
        assertThat(result!!.description).isEqualTo("Home is visible")
    }

    @Test
    fun `verify pattern without that`() {
        val result = AssertionStepInferrer.infer("Verify Home is visible")
        assertThat(result).isNotNull()
        assertThat(result!!.description).isEqualTo("Home is visible")
    }

    @Test
    fun `ensure pattern`() {
        val result = AssertionStepInferrer.infer("Ensure the title appears")
        assertThat(result).isNotNull()
        assertThat(result!!.description).isEqualTo("the title appears")
    }

    @Test
    fun `confirm pattern`() {
        val result = AssertionStepInferrer.infer("Confirm detail page loaded")
        assertThat(result).isNotNull()
        assertThat(result!!.description).isEqualTo("detail page loaded")
    }

    @Test
    fun `check pattern`() {
        val result = AssertionStepInferrer.infer("Check that Settings is focused")
        assertThat(result).isNotNull()
        assertThat(result!!.description).isEqualTo("Settings is focused")
    }

    @Test
    fun `case insensitive`() {
        val result = AssertionStepInferrer.infer("VERIFY home screen")
        assertThat(result).isNotNull()
    }

    @Test
    fun `inferred mode uses AssertModeInferrer`() {
        val result = AssertionStepInferrer.infer("Verify backdrop image is visible")
        assertThat(result).isNotNull()
        assertThat(result!!.mode).isEqualTo(AssertMode.VISUAL)
    }

    @Test
    fun `non-assertion returns null`() {
        assertThat(AssertionStepInferrer.infer("Press D-pad down")).isNull()
    }

    @Test
    fun `launch command is not an assertion`() {
        assertThat(AssertionStepInferrer.infer("Launch the app")).isNull()
    }
}
