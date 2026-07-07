package me.chrisbanes.verity.core.context

import java.io.File

data class ContextBundle(
  val text: String,
  val loadedFiles: List<File>,
  val status: ContextStatus,
)

enum class ContextStatus {
  LOADED,
  NOT_CONFIGURED,
  MISSING_DIRECTORY,
  EMPTY_DIRECTORY,
}

class ContextValidationException(message: String) : IllegalArgumentException(message)

object ContextLoader {

  private val markdownExtensions = setOf("md", "markdown")
  private val bundledCache: String by lazy { loadBundledFromClasspath() }

  fun loadBundled(): String = bundledCache

  private fun loadBundledFromClasspath(): String {
    val resourceDir = "verity/context"
    val files = listOf("maestro.md", "tv-controls.md")
    return files.mapNotNull { filename ->
      ContextLoader::class.java.classLoader
        ?.getResourceAsStream("$resourceDir/$filename")
        ?.bufferedReader()
        ?.use { it.readText().trim() }
    }.joinToString("\n\n")
  }

  fun load(directory: File): String = loadProject(directory = directory, required = false).text

  fun loadProject(directory: File?, required: Boolean): ContextBundle {
    val bundle = when {
      directory == null -> ContextBundle(
        text = "",
        loadedFiles = emptyList(),
        status = ContextStatus.NOT_CONFIGURED,
      )

      !directory.isDirectory -> ContextBundle(
        text = "",
        loadedFiles = emptyList(),
        status = ContextStatus.MISSING_DIRECTORY,
      )

      else -> {
        val files = directory.listFiles()
          ?.filter { it.isFile && it.extension in markdownExtensions }
          ?.sortedBy { it.name }
          ?: emptyList()

        if (files.isEmpty()) {
          ContextBundle(
            text = "",
            loadedFiles = emptyList(),
            status = ContextStatus.EMPTY_DIRECTORY,
          )
        } else {
          ContextBundle(
            text = files.joinToString("\n\n") { it.readText().trim() },
            loadedFiles = files,
            status = ContextStatus.LOADED,
          )
        }
      }
    }

    if (required && bundle.status != ContextStatus.LOADED) {
      throw ContextValidationException(bundle.requiredFailureMessage(directory))
    }

    return bundle
  }

  private fun ContextBundle.requiredFailureMessage(directory: File?): String = when (status) {
    ContextStatus.LOADED -> error("Loaded context is not a validation failure")

    ContextStatus.NOT_CONFIGURED ->
      "Required project context is not configured. Set --context-path before using --require-context."

    ContextStatus.MISSING_DIRECTORY ->
      "Required project context directory does not exist or is not a directory: ${directory!!.absolutePath}"

    ContextStatus.EMPTY_DIRECTORY ->
      "Required project context directory contains no markdown files: ${directory!!.absolutePath}"
  }
}
