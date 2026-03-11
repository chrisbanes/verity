package me.chrisbanes.verity.core.model

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.containsExactly
import kotlin.test.Test

class AssertModeTest {
    @Test
    fun `assert modes are ordered by cost`() {
        val modes = AssertMode.entries.sortedBy { it.ordinal }
        assertThat(modes).containsExactly(
            AssertMode.VISIBLE,
            AssertMode.FOCUSED,
            AssertMode.TREE,
            AssertMode.VISUAL,
        )
    }
}
