package me.chrisbanes.verity.core.journey

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import me.chrisbanes.verity.core.model.AssertMode
import me.chrisbanes.verity.core.model.JourneyStep
import me.chrisbanes.verity.core.model.JourneyStep.*
import kotlin.test.Test

class JourneySegmenterTest {
    @Test
    fun `single action becomes one segment`() {
        val steps = listOf(Action("Launch the app"))
        val segments = JourneySegmenter.segment(steps)
        assertThat(segments).hasSize(1)
        assertThat(segments[0].actions).hasSize(1)
        assertThat(segments[0].assertion).isNull()
        assertThat(segments[0].loop).isNull()
    }

    @Test
    fun `actions followed by assertion become one segment`() {
        val steps = listOf(
            Action("Launch the app"),
            Action("Press D-pad down"),
            Assert("Home", AssertMode.VISIBLE),
        )
        val segments = JourneySegmenter.segment(steps)
        assertThat(segments).hasSize(1)
        assertThat(segments[0].actions).hasSize(2)
        assertThat(segments[0].assertion).isNotNull()
        assertThat(segments[0].assertion!!.description).isEqualTo("Home")
    }

    @Test
    fun `loop flushes pending actions then becomes its own segment`() {
        val steps = listOf(
            Action("Launch the app"),
            Loop("Press D-pad down", "TV Shows"),
        )
        val segments = JourneySegmenter.segment(steps)
        assertThat(segments).hasSize(2)
        assertThat(segments[0].actions).hasSize(1)
        assertThat(segments[0].loop).isNull()
        assertThat(segments[1].actions).isEmpty()
        assertThat(segments[1].loop).isNotNull()
    }

    @Test
    fun `multiple assertion segments`() {
        val steps = listOf(
            Action("Launch the app"),
            Assert("Home", AssertMode.VISIBLE),
            Action("Press D-pad down"),
            Assert("Settings", AssertMode.TREE),
        )
        val segments = JourneySegmenter.segment(steps)
        assertThat(segments).hasSize(2)
        assertThat(segments[0].index).isEqualTo(0)
        assertThat(segments[1].index).isEqualTo(1)
    }

    @Test
    fun `consecutive assertions create separate segments`() {
        val steps = listOf(
            Assert("Home", AssertMode.VISIBLE),
            Assert("Movies", AssertMode.VISIBLE),
        )
        val segments = JourneySegmenter.segment(steps)
        assertThat(segments).hasSize(2)
        assertThat(segments[0].actions).isEmpty()
        assertThat(segments[0].assertion!!.description).isEqualTo("Home")
        assertThat(segments[1].actions).isEmpty()
        assertThat(segments[1].assertion!!.description).isEqualTo("Movies")
    }

    @Test
    fun `empty steps produce empty segments`() {
        val segments = JourneySegmenter.segment(emptyList())
        assertThat(segments).isEmpty()
    }

    @Test
    fun `segment indices are sequential`() {
        val steps = listOf(
            Action("A"),
            Assert("X", AssertMode.VISIBLE),
            Action("B"),
            Assert("Y", AssertMode.TREE),
            Loop("Press down", "Z"),
        )
        val segments = JourneySegmenter.segment(steps)
        segments.forEachIndexed { i, seg ->
            assertThat(seg.index).isEqualTo(i)
        }
    }
}
