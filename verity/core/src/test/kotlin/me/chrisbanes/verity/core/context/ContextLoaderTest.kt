package me.chrisbanes.verity.core.context

import assertk.assertThat
import assertk.assertFailure
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.hasMessage
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
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
  fun `loadProject returns not configured for optional null directory`() {
    val bundle = ContextLoader.loadProject(directory = null, required = false)

    assertThat(bundle.status).isEqualTo(ContextStatus.NOT_CONFIGURED)
    assertThat(bundle.text).isEmpty()
    assertThat(bundle.loadedFiles).isEmpty()
  }

  @Test
  fun `loadProject returns missing directory for optional nonexistent path`() {
    val directory = File("/nonexistent/path")

    val bundle = ContextLoader.loadProject(directory = directory, required = false)

    assertThat(bundle.status).isEqualTo(ContextStatus.MISSING_DIRECTORY)
    assertThat(bundle.text).isEmpty()
    assertThat(bundle.loadedFiles).isEmpty()
  }

  @Test
  fun `loadProject returns empty directory for optional directory without markdown files`() {
    val dir = kotlin.io.path.createTempDirectory("empty-context").toFile()
    try {
      File(dir, "notes.txt").writeText("ignored")

      val bundle = ContextLoader.loadProject(directory = dir, required = false)

      assertThat(bundle.status).isEqualTo(ContextStatus.EMPTY_DIRECTORY)
      assertThat(bundle.text).isEmpty()
      assertThat(bundle.loadedFiles).isEmpty()
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun `loadProject reports loaded markdown files in deterministic order`() {
    val dir = createTempContextDir(
      "b.markdown" to "Section B",
      "a.md" to "Section A",
      "notes.txt" to "ignored",
    )
    try {
      val bundle = ContextLoader.loadProject(directory = dir, required = true)

      assertThat(bundle.status).isEqualTo(ContextStatus.LOADED)
      assertThat(bundle.text).isEqualTo("Section A\n\nSection B")
      assertThat(bundle.loadedFiles.map { it.name }).containsExactly("a.md", "b.markdown")
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun `loadProject throws clear error when required context is not configured`() {
    assertFailure {
      ContextLoader.loadProject(directory = null, required = true)
    }.hasMessage("Required project context is not configured. Set --context-path before using --require-context.")
  }

  @Test
  fun `loadProject throws clear error when required context directory is missing`() {
    val directory = File("/nonexistent/path")

    assertFailure {
      ContextLoader.loadProject(directory = directory, required = true)
    }.hasMessage(
      "Required project context directory does not exist or is not a directory: ${directory.absolutePath}",
    )
  }

  @Test
  fun `loadProject throws clear error when required context directory is empty`() {
    val dir = kotlin.io.path.createTempDirectory("required-empty-context").toFile()
    try {
      assertFailure {
        ContextLoader.loadProject(directory = dir, required = true)
      }.hasMessage(
        "Required project context directory contains no markdown files: ${dir.absolutePath}",
      )
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
