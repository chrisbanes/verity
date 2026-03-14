package me.chrisbanes.verity.core.context

import java.io.File

object ContextLoader {

  private val bundledCache: String by lazy { loadBundledFromClasspath() }

  fun loadBundled(): String = bundledCache

  internal val BUNDLED_FILES = listOf("maestro.md", "tv-controls.md")

  private fun loadBundledFromClasspath(): String {
    val resourceDir = "verity/context"
    return BUNDLED_FILES.mapNotNull { filename ->
      ContextLoader::class.java.classLoader
        ?.getResourceAsStream("$resourceDir/$filename")
        ?.bufferedReader()
        ?.use { it.readText().trim() }
    }.joinToString("\n\n")
  }

  fun load(directory: File): String {
    if (!directory.isDirectory) return ""

    return directory.listFiles()
      ?.filter { it.isFile && it.extension in setOf("md", "markdown") }
      ?.sortedBy { it.name }
      ?.joinToString("\n\n") { it.readText().trim() }
      ?: ""
  }
}
