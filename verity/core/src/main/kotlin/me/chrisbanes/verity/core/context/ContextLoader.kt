package me.chrisbanes.verity.core.context

import java.io.File
import me.chrisbanes.verity.core.interaction.InteractionMapper
import me.chrisbanes.verity.core.model.Platform

data class ContextBundle(
  val text: String,
  val loadedFiles: List<File>,
  val status: ContextStatus,
  val keyActions: List<KeyAction> = emptyList(),
)

enum class ContextStatus {
  LOADED,
  NOT_CONFIGURED,
  MISSING_DIRECTORY,
  EMPTY_DIRECTORY,
}

/** Human-readable status lines for [this] bundle; [fileLine] renders each loaded file. */
fun ContextBundle.describe(contextDir: File?, required: Boolean, fileLine: (File) -> String): List<String> {
  val mode = if (required) "required" else "optional"
  return when (status) {
    ContextStatus.LOADED -> listOf("Project context: loaded ${loadedFiles.size} file(s)") + loadedFiles.map(fileLine) +
      listOfNotNull("Key actions: ${keyActions.size}".takeIf { keyActions.isNotEmpty() })

    ContextStatus.NOT_CONFIGURED -> listOf("Project context: $mode, not configured")

    ContextStatus.MISSING_DIRECTORY -> listOf("Project context: $mode, missing directory: ${contextDir!!.absolutePath}")

    ContextStatus.EMPTY_DIRECTORY -> listOf("Project context: $mode, no markdown files found in: ${contextDir!!.absolutePath}")
  }
}

class ContextValidationException(message: String) : IllegalArgumentException(message)

object ContextLoader {

  private val markdownExtensions = setOf("md", "markdown")
  private val bundledCache: String by lazy { loadBundledFromClasspath(listOf("maestro.md", "tv-controls.md")) }
  private val actionsCache: String by lazy { loadBundledFromClasspath(listOf("actions.md")) }

  fun loadBundled(): String = bundledCache

  fun loadBundledActions(): String = actionsCache

  private fun loadBundledFromClasspath(files: List<String>): String {
    val resourceDir = "verity/context"
    return files.mapNotNull { filename ->
      ContextLoader::class.java.classLoader
        ?.getResourceAsStream("$resourceDir/$filename")
        ?.bufferedReader()
        ?.use { it.readText().trim() }
    }.joinToString("\n\n")
  }

  fun load(directory: File): String = loadProject(directory = directory, required = false).text

  /**
   * Loads project context and parses its `verity-key-actions` blocks, which fail setup even when context is optional.
   * With a [platform], a key action whose name that platform's built-in mapper already maps is rejected.
   */
  fun loadProject(directory: File?, required: Boolean, platform: Platform? = null): ContextBundle {
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
          val texts = files.map { it.readText() }
          ContextBundle(
            text = texts.joinToString("\n\n") { it.trim() },
            loadedFiles = files,
            status = ContextStatus.LOADED,
            keyActions = validated(files.zip(texts).flatMap { (file, text) -> KeyActionParser.parse(file, text) }, platform),
          )
        }
      }
    }

    if (required && bundle.status != ContextStatus.LOADED) {
      throw ContextValidationException(bundle.requiredFailureMessage(directory))
    }

    return bundle
  }

  private fun validated(keyActions: List<KeyAction>, platform: Platform?): List<KeyAction> {
    val builtIn = platform?.let(InteractionMapper::forPlatform)
    val seen = mutableMapOf<String, KeyAction>()
    for (action in keyActions) {
      seen.put(action.name.lowercase(), action)?.let {
        throw ContextValidationException("Duplicate key action \"${action.name}\" in ${it.location} and ${action.location}")
      }
      if (builtIn?.map(action.name) != null) {
        throw ContextValidationException("Key action \"${action.name}\" in ${action.location} matches a built-in $platform instruction")
      }
    }
    return keyActions
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
