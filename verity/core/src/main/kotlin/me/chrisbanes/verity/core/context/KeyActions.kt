package me.chrisbanes.verity.core.context

import com.charleskorn.kaml.DuplicateKeyException
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlException
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlNull
import com.charleskorn.kaml.YamlScalar
import java.io.File

/** A named device key press declared in a `verity-key-actions` block of a project-context file. */
data class KeyAction(
  val name: String,
  val keycode: Int,
  val longPress: Boolean,
  val file: File,
  /** 1-based line of the entry in [file]. */
  val line: Int,
) {
  val location: String get() = "${file.name}:$line"
}

internal object KeyActionParser {

  private const val FENCE = "```verity-key-actions"
  private const val CLOSE = "```"
  private val allowedKeys = setOf("keycode", "long-press")

  /** Parses every `verity-key-actions` block in [text], throwing [ContextValidationException] on any problem. */
  fun parse(file: File, text: String): List<KeyAction> {
    val lines = text.lines()
    val actions = mutableListOf<KeyAction>()
    var index = 0
    while (index < lines.size) {
      if (lines[index].trim() != FENCE) {
        index++
        continue
      }
      val fenceLine = index + 1
      val close = (index + 1 until lines.size).firstOrNull { lines[it].trim() == CLOSE }
        ?: throw ContextValidationException("Unclosed verity-key-actions block in ${file.name}:$fenceLine")
      actions += parseBlock(file, fenceLine, lines.subList(index + 1, close).joinToString("\n"))
      index = close + 1
    }
    return actions
  }

  private fun parseBlock(file: File, fenceLine: Int, body: String): List<KeyAction> {
    if (body.isBlank()) return emptyList()
    fun fail(line: Int, reason: String): Nothing = throw ContextValidationException("Invalid key action in ${file.name}:${fenceLine + line}: $reason")

    val root = try {
      Yaml.default.parseToYamlNode(body)
    } catch (e: DuplicateKeyException) {
      throw ContextValidationException(
        "Duplicate key action \"${e.key.removeSurrounding("'")}\" in ${file.name}:${fenceLine + e.originalLocation.line} and ${file.name}:${fenceLine + e.duplicateLocation.line}",
      )
    } catch (e: YamlException) {
      fail(e.line, e.message.orEmpty().lineSequence().first())
    }
    if (root !is YamlMap) fail(root.location.line, "expected a map of names to key presses")

    return root.entries.map { (key, value) ->
      val name = key.content.trim()
      val line = key.location.line
      if (name.isEmpty()) fail(line, "name is empty")
      if (';' in name) fail(line, "name \"$name\" contains ';'")
      val (keycode, longPress) = when (value) {
        is YamlScalar -> keycode(value, ::fail) to false

        is YamlMap -> {
          value.entries.keys.firstOrNull { it.content !in allowedKeys }?.let { fail(it.location.line, "unknown key \"${it.content}\"") }
          val keycodeNode = value.get<YamlNode>("keycode") ?: fail(line, "missing required key \"keycode\"")
          val longPressNode = value.get<YamlNode>("long-press")
          val long = when (longPressNode) {
            null -> false

            is YamlScalar -> when (longPressNode.content) {
              "true" -> true
              "false" -> false
              else -> fail(longPressNode.location.line, "long-press must be true or false, got \"${longPressNode.content}\"")
            }

            else -> fail(longPressNode.location.line, "long-press must be true or false")
          }
          (keycodeNode as? YamlScalar)?.let { keycode(it, ::fail) to long } ?: fail(keycodeNode.location.line, "keycode must be a non-negative integer")
        }

        is YamlNull -> fail(line, "\"$name\" has no keycode")

        else -> fail(value.location.line, "\"$name\" must map to a keycode or a keycode/long-press map")
      }
      KeyAction(name, keycode, longPress, file, fenceLine + line)
    }
  }

  private fun keycode(node: YamlScalar, fail: (Int, String) -> Nothing): Int {
    val content = node.content
    return content.takeIf { it.all(Char::isDigit) }?.toIntOrNull()
      ?: fail(node.location.line, "keycode must be a non-negative integer, got \"$content\"")
  }
}
