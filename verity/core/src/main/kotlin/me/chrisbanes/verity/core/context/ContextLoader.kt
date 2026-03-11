package me.chrisbanes.verity.core.context

import java.io.File

object ContextLoader {

  fun load(directory: File): String {
    if (!directory.isDirectory) return ""

    return directory.listFiles()
      ?.filter { it.isFile && it.extension == "md" }
      ?.sortedBy { it.name }
      ?.joinToString("\n\n") { it.readText().trim() }
      ?: ""
  }
}
