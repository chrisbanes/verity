package me.chrisbanes.verity.core.context

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEmpty
import assertk.assertions.isFalse
import assertk.assertions.isNotEmpty
import java.io.File
import kotlin.test.Test

class ContextLoaderTest {

  @Test
  fun `loads markdown files from directory`() {
    val dir = createTempContextDir(
      "app.md" to "# App\nLauncher app context",
      "maestro.md" to "# Maestro\nMaestro commands",
    )
    try {
      val context = ContextLoader.load(dir)
      assertThat(context).contains("# App")
      assertThat(context).contains("# Maestro")
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun `ignores non-markdown files`() {
    val dir = createTempContextDir(
      "app.md" to "# App context",
      "notes.txt" to "Should be ignored",
    )
    try {
      val context = ContextLoader.load(dir)
      assertThat(context).contains("# App context")
      assertThat(context.contains("Should be ignored")).isFalse()
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun `returns empty string for empty directory`() {
    val dir = kotlin.io.path.createTempDirectory("empty-context").toFile()
    try {
      assertThat(ContextLoader.load(dir)).isEmpty()
    } finally {
      dir.delete()
    }
  }

  @Test
  fun `returns empty string for nonexistent directory`() {
    assertThat(ContextLoader.load(File("/nonexistent/path"))).isEmpty()
  }

  @Test
  fun `loads dot-markdown extension files`() {
    val dir = createTempContextDir(
      "app.md" to "# App context",
      "extra.markdown" to "# Extra context",
    )
    try {
      val context = ContextLoader.load(dir)
      assertThat(context).contains("# App context")
      assertThat(context).contains("# Extra context")
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun `files are separated by double newline`() {
    val dir = createTempContextDir(
      "a.md" to "Section A",
      "b.md" to "Section B",
    )
    try {
      val context = ContextLoader.load(dir)
      assertThat(context).contains("\n\n")
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun `loadBundled returns Maestro reference content`() {
    val bundled = ContextLoader.loadBundled()
    assertThat(bundled).isNotEmpty()
    assertThat(bundled).contains("Maestro")
    assertThat(bundled).contains("pressKey")
  }

  @Test
  fun `loadBundled returns TV controls content`() {
    val bundled = ContextLoader.loadBundled()
    assertThat(bundled).contains("Remote Dpad")
    assertThat(bundled).contains("D-pad")
  }

  @Test
  fun `loadBundled concatenates all resource files`() {
    val bundled = ContextLoader.loadBundled()
    // Both files should be present
    assertThat(bundled).contains("Maestro YAML Reference")
    assertThat(bundled).contains("TV Remote Controls")
  }

  private fun createTempContextDir(vararg files: Pair<String, String>): File {
    val dir = kotlin.io.path.createTempDirectory("context-test").toFile()
    for ((name, content) in files) {
      File(dir, name).writeText(content)
    }
    return dir
  }
}
