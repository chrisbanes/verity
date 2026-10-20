package me.chrisbanes.verity.core.context

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasMessage
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import me.chrisbanes.verity.core.model.Platform

class KeyActionParserTest {

  private fun block(vararg entries: String) = "```verity-key-actions\n${entries.joinToString("\n")}\n```"

  private fun <T> withContext(vararg files: Pair<String, String>, body: (File) -> T): T {
    val dir = createTempDirectory("key-actions-test").toFile()
    try {
      files.forEach { (name, content) -> File(dir, name).writeText(content) }
      return body(dir)
    } finally {
      dir.deleteRecursively()
    }
  }

  private fun load(text: String, platform: Platform? = null, required: Boolean = false) = withContext("remote.md" to text) {
    ContextLoader.loadProject(it, required, platform)
  }

  private fun assertInvalid(text: String, vararg expected: String) {
    assertFailure { load(text) }.isInstanceOf(ContextValidationException::class).also { failure ->
      expected.forEach { failure.transform { e -> e.message.orEmpty() }.contains(it) }
    }
  }

  @Test
  fun `scalar entry is a short press at its file line`() {
    val bundle = load("# Remote\n\n${block("Open Quick Actions: 174")}")

    val action = bundle.keyActions.single()
    assertThat(action.name).isEqualTo("Open Quick Actions")
    assertThat(action.keycode).isEqualTo(174)
    assertThat(action.longPress).isEqualTo(false)
    assertThat(action.line).isEqualTo(4)
    assertThat(action.location).isEqualTo("remote.md:4")
  }

  @Test
  fun `map entry carries long-press and quoted keycode is accepted`() {
    val bundle = load(block("Hold Quick Actions: { keycode: 174, long-press: true }", "Quoted: \"175\"", "Block map:", "  keycode: 176", "  long-press: false"))

    assertThat(bundle.keyActions.map { Triple(it.keycode, it.longPress, it.line) })
      .isEqualTo(listOf(Triple(174, true, 2), Triple(175, false, 3), Triple(176, false, 4)))
  }

  @Test
  fun `several blocks in one file are all parsed with their own lines`() {
    val bundle = load("${block("A: 1")}\n\ntext\n\n${block("B: 2")}")

    assertThat(bundle.keyActions.map { it.name to it.line }).isEqualTo(listOf("A" to 2, "B" to 8))
  }

  @Test
  fun `blocks stay in the model text and blank blocks yield nothing`() {
    val text = "# Remote\n\n${block("Open Quick Actions: 174")}\n\n```verity-key-actions\n\n```"
    val bundle = load(text)

    assertThat(bundle.text).isEqualTo(text.trim())
    assertThat(bundle.keyActions.size).isEqualTo(1)
    assertThat(load("```verity-key-actions\n```").keyActions).isEmpty()
  }

  @Test
  fun `context without blocks has no key actions`() {
    assertThat(load("# Remote\n```yaml\na: 1\n```").keyActions).isEmpty()
  }

  @Test
  fun `malformed entries fail setup naming the file and line even when optional`() {
    val cases = mapOf(
      "invalid yaml" to (block("Open: [174") to "remote.md:"),
      "list value" to (block("A: 1", "Open: [1, 2]") to "remote.md:3"),
      "null value" to (block("A: 1", "Open:") to "remote.md:3"),
      "unknown key" to (block("A: 1", "Open: { keycode: 1, hold: true }") to "remote.md:3"),
      "missing keycode" to (block("A: 1", "Open: { long-press: true }") to "remote.md:3"),
      "negative keycode" to (block("A: 1", "Open: -1") to "remote.md:3"),
      "fractional keycode" to (block("A: 1", "Open: 1.5") to "remote.md:3"),
      "non-ascii digits" to (block("A: 1", "Open: \u0661\u0667\u0664") to "remote.md:3"),
      "hex keycode" to (block("A: 1", "Open: 0x1A") to "remote.md:3"),
      "overflowing keycode" to (block("A: 1", "Open: 99999999999") to "remote.md:3"),
      "bad long-press" to (block("A: 1", "Open: { keycode: 1, long-press: yes }") to "remote.md:3"),
      "keycode map" to (block("A: 1", "Open: { keycode: { a: 1 } }") to "remote.md:3"),
      "blank name" to (block("A: 1", "\"\": 5") to "remote.md:3"),
      "semicolon name" to (block("A: 1", "\"Open; Close\": 5") to "remote.md:3"),
      "non-map root" to (block("- 174") to "remote.md:2"),
    )
    cases.forEach { (label, case) ->
      val (text, location) = case
      assertFailure { load(text) }.transform(label) { it.message.orEmpty() }.also {
        it.contains("Invalid key action in $location")
      }
    }
  }

  @Test
  fun `unclosed fence is an error at the opening fence`() {
    assertInvalid("# Remote\n```verity-key-actions\nOpen: 174", "Unclosed verity-key-actions block in remote.md:2")
  }

  @Test
  fun `exact duplicate within a block names both lines`() {
    assertInvalid(block("Open: 1", "Other: 2", "Open: 3"), "Duplicate key action \"Open\" in remote.md:2 and remote.md:4")
  }

  @Test
  fun `case-insensitive duplicate across blocks names both lines`() {
    assertFailure { load("${block("Open Quick Actions: 1")}\n\n${block("  open quick ACTIONS : 2")}") }
      .hasMessage("Duplicate key action \"open quick ACTIONS\" in remote.md:2 and remote.md:6")
  }

  @Test
  fun `duplicate across files names both locations`() {
    withContext("a.md" to "x\n${block("Open: 1")}", "b.md" to block("OPEN: 2")) { dir ->
      assertFailure { ContextLoader.loadProject(dir, required = false) }
        .hasMessage("Duplicate key action \"OPEN\" in a.md:3 and b.md:2")
    }
  }

  @Test
  fun `built-in collisions are rejected for the run platform only`() {
    val back = block("Press back: 4")
    assertFailure { load(back, Platform.ANDROID_TV) }
      .hasMessage("Key action \"Press back\" in remote.md:2 matches a built-in ANDROID_TV instruction")
    assertFailure { load(block("press keycode 5: 5"), Platform.ANDROID_MOBILE) }
      .transform { it.message.orEmpty() }.contains("matches a built-in ANDROID_MOBILE instruction")
    assertThat(load(back, platform = null).keyActions.size).isEqualTo(1)
  }

  @Test
  fun `status lines report the key action count only when there are some`() {
    val describe = { b: ContextBundle -> b.describe(null, required = false) { "  - ${it.name}" } }

    assertThat(describe(load(block("A: 1", "B: 2")))).isEqualTo(listOf("Project context: loaded 1 file(s)", "  - remote.md", "Key actions: 2"))
    assertThat(describe(load("# Remote"))).isEqualTo(listOf("Project context: loaded 1 file(s)", "  - remote.md"))
  }
}
