package me.chrisbanes.verity.core.context

import java.io.File

object ContextLoader {

  fun load(directory: File): String {
    if (!directory.isDirectory) return ""

    return directory.listFiles()
      ?.filter { it.isFile && it.extension in setOf("md", "markdown") }
      ?.sortedBy { it.name }
      ?.joinToString("\n\n") { it.readText().trim() }
      ?: ""
  }
}
