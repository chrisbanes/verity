package me.chrisbanes.verity.device

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isNotEmpty
import kotlin.test.Test
import maestro.KeyCode
import me.chrisbanes.verity.core.interaction.AndroidTvInteractionMapper

class AndroidTvKeyNamesTest {
  @Test fun everyFastPathKeyNameResolvesInMaestro() {
    assertThat(AndroidTvInteractionMapper.KEY_MAP.values.filter { KeyCode.getByName(it) == null }).isEmpty()
  }

  @Test fun everyKeyNameInTvControlsContextResolvesInMaestro() {
    val doc = checkNotNull(javaClass.getResource("/verity/context/tv-controls.md")).readText()
    // Second column of the D-pad mapping table: | Action | `Key Name` |
    val names = Regex("""^\|[^|]+\|\s*`([^`]+)`\s*\|$""", RegexOption.MULTILINE).findAll(doc).map { it.groupValues[1] }.toList()
    assertThat(names).isNotEmpty()
    assertThat(names.filter { KeyCode.getByName(it) == null }).isEmpty()
  }
}
