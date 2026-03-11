package me.chrisbanes.verity.core.hierarchy

import assertk.assertThat
import assertk.assertions.isTrue
import assertk.assertions.isFalse
import kotlin.test.Test

class FocusDetectorTest {

    @Test
    fun `text on focused node`() {
        val hierarchy = """
            [text=Home] (focused)
            [text=Settings]
        """.trimIndent()
        assertThat(FocusDetector.containsFocused(hierarchy, "Home")).isTrue()
    }

    @Test
    fun `text not on focused node`() {
        val hierarchy = """
            [text=Home] (focused)
            [text=Settings]
        """.trimIndent()
        assertThat(FocusDetector.containsFocused(hierarchy, "Settings")).isFalse()
    }

    @Test
    fun `text on descendant of focused node`() {
        val hierarchy = """
            [resource-id=card] (focused)
              [text=Movie Title]
              [text=2024]
        """.trimIndent()
        assertThat(FocusDetector.containsFocused(hierarchy, "Movie Title")).isTrue()
    }

    @Test
    fun `text on sibling of focused node`() {
        val hierarchy = """
            [resource-id=container]
              [resource-id=focus-indicator] (focused)
              [text=Movie Title]
        """.trimIndent()
        assertThat(FocusDetector.containsFocused(hierarchy, "Movie Title")).isTrue()
    }

    @Test
    fun `text on ancestor of focused node is not a match`() {
        val hierarchy = """
            [text=Row Title]
              [resource-id=item] (focused)
        """.trimIndent()
        assertThat(FocusDetector.containsFocused(hierarchy, "Row Title")).isTrue()
    }

    @Test
    fun `no focused node returns false`() {
        val hierarchy = """
            [text=Home]
            [text=Settings]
        """.trimIndent()
        assertThat(FocusDetector.containsFocused(hierarchy, "Home")).isFalse()
    }

    @Test
    fun `case insensitive text matching`() {
        val hierarchy = "[text=Home] (focused)"
        assertThat(FocusDetector.containsFocused(hierarchy, "home")).isTrue()
    }

    @Test
    fun `empty hierarchy returns false`() {
        assertThat(FocusDetector.containsFocused("", "anything")).isFalse()
    }

    @Test
    fun `deeply nested descendant of focused node`() {
        val hierarchy = """
            [resource-id=card] (focused)
              [resource-id=inner]
                [text=Deep Text]
        """.trimIndent()
        assertThat(FocusDetector.containsFocused(hierarchy, "Deep Text")).isTrue()
    }
}
