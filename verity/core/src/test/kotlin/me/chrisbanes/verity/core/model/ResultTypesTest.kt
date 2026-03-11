package me.chrisbanes.verity.core.model

import assertk.assertThat
import assertk.assertions.isTrue
import assertk.assertions.isFalse
import assertk.assertions.isEqualTo
import kotlin.test.Test

class ResultTypesTest {
    @Test
    fun `flow result success`() {
        val result = FlowResult(success = true, output = "Flow completed")
        assertThat(result.success).isTrue()
    }

    @Test
    fun `flow result failure`() {
        val result = FlowResult(success = false, output = "Element not found")
        assertThat(result.success).isFalse()
    }

    @Test
    fun `inspection verdict passed`() {
        val verdict = InspectionVerdict(passed = true, reasoning = "Home text found in hierarchy")
        assertThat(verdict.passed).isTrue()
        assertThat(verdict.reasoning).isEqualTo("Home text found in hierarchy")
    }

    @Test
    fun `inspection verdict failed`() {
        val verdict = InspectionVerdict(passed = false, reasoning = "Expected 'Movies' but not found")
        assertThat(verdict.passed).isFalse()
    }
}
