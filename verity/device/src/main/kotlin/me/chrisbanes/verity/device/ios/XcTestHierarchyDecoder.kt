package me.chrisbanes.verity.device.ios

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.util.JsonParserDelegate
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.deser.DefaultDeserializationContext
import com.fasterxml.jackson.databind.deser.std.NumberDeserializers
import com.fasterxml.jackson.databind.deser.std.StringDeserializer
import java.io.Reader
import me.chrisbanes.verity.core.hierarchy.HierarchyNode

/** Only the XCTest hierarchy wire schema; no SDK model or bean deserializer is used. */
internal object XcTestHierarchyDecoder {
  private val mapper = ObjectMapper()
  private val integers = NumberDeserializers.IntegerDeserializer(Int::class.javaPrimitiveType, 0)
  private val longs = NumberDeserializers.LongDeserializer(Long::class.javaPrimitiveType, 0L)
  private val floats = NumberDeserializers.FloatDeserializer(Float::class.javaPrimitiveType, 0f)
  private val booleans = NumberDeserializers.BooleanDeserializer(Boolean::class.javaPrimitiveType, false)
  private val strings = StringDeserializer.instance

  fun prepare() {
    mapper.deserializationConfig
  }

  fun decode(reader: Reader, checkpoint: () -> Unit = {}): HierarchyNode {
    checkpoint()
    val parser = CheckedParser(mapper.factory.createParser(reader), checkpoint)
    try {
      val context = (mapper.deserializationContext as DefaultDeserializationContext)
        .createInstance(mapper.deserializationConfig, parser, null)
      val decoder = Decoder(parser, context, checkpoint)
      parser.nextToken()
      return decoder.root()
    } finally {
      parser.close()
    }
  }

  private class Decoder(
    private val parser: JsonParser,
    private val context: DefaultDeserializationContext,
    private val checkpoint: () -> Unit,
  ) {
    fun root(): HierarchyNode {
      var node: HierarchyNode? = null
      var constructed = false
      val seen = HashSet<String>()
      fields { field ->
        when (field) {
          "axElement" -> node = nullable { element() }
          "depth" -> scalar(integers)
          else -> unknown(field)
        }
        seen += field
        if (!constructed && seen.size == 2) {
          checkNotNull(node) { "Missing root axElement" }
          constructed = true
        }
      }
      checkpoint()
      return checkNotNull(node) { "Missing root axElement" }
    }

    private fun element(): HierarchyNode {
      var label: String? = null
      var identifier: String? = null
      var frame: Boolean? = null
      var children: List<HierarchyNode>? = null
      var value: String? = null
      var title: String? = null
      var focused = false
      var selected = false
      var enabled = false
      var constructed = false
      val seen = HashSet<String>()
      fun validateConstructor() {
        check(label != null && identifier != null && frame != null && children != null) { "Missing required accessibility field" }
      }
      fields { field ->
        when (field) {
          "label" -> label = scalar(strings)

          "identifier" -> identifier = scalar(strings)

          "value" -> value = scalar(strings)

          "title" -> title = scalar(strings)

          "placeholderValue" -> scalar(strings)

          "elementType", "horizontalSizeClass", "verticalSizeClass", "displayID" -> scalar(integers)

          "windowContextID" -> scalar(longs)

          "hasFocus" -> focused = scalar(booleans) ?: false

          "selected" -> selected = scalar(booleans) ?: false

          "enabled" -> enabled = scalar(booleans) ?: false

          "frame" -> frame = nullable {
            frame()
            true
          }

          "children" -> children = nullable { children() }

          else -> unknown(field)
        }
        seen += field
        if (!constructed && seen.size == 15) {
          validateConstructor()
          constructed = true
        }
      }
      if (!constructed) validateConstructor()
      checkpoint()
      val attributes = buildMap {
        checkNotNull(label).takeIf(String::isNotEmpty)?.let { put("text", it) }
        checkNotNull(identifier).takeIf(String::isNotEmpty)?.let { put("resource-id", it) }
        value?.takeIf(String::isNotEmpty)?.let { put("value", it) }
        title?.takeIf(String::isNotEmpty)?.let { put("title", it) }
      }
      val states = buildSet {
        if (focused) add("focused")
        if (selected) add("selected")
        if (!enabled) add("disabled")
      }
      return HierarchyNode(attributes, states, checkNotNull(children)).also { checkpoint() }
    }

    private fun frame() {
      fields { field ->
        when (field) {
          "X", "Y", "Width", "Height", "left", "right", "top", "bottom" -> scalar(floats)
          "boundsString" -> scalar(strings)
          else -> unknown(field)
        }
      }
    }

    private fun children(): List<HierarchyNode> {
      check(parser.currentToken() == JsonToken.START_ARRAY) { "Accessibility children must be an array" }
      val nodes = ArrayList<HierarchyNode>()
      while (parser.nextToken() != JsonToken.END_ARRAY) {
        checkpoint()
        check(parser.currentToken() != null && parser.currentToken() != JsonToken.VALUE_NULL) { "Missing accessibility child" }
        nodes += element()
        checkpoint()
      }
      return nodes
    }

    private fun fields(consume: (String) -> Unit) {
      check(parser.currentToken() == JsonToken.START_OBJECT) { "Hierarchy object required" }
      while (parser.nextToken() != JsonToken.END_OBJECT) {
        check(parser.currentToken() == JsonToken.FIELD_NAME) { "Hierarchy field required" }
        val field = parser.currentName()
        parser.nextToken()
        consume(field)
      }
    }

    private inline fun <T> nullable(read: () -> T): T? = if (parser.currentToken() == JsonToken.VALUE_NULL) null else read()

    private fun <T> scalar(deserializer: JsonDeserializer<T>): T? {
      checkpoint()
      val value = if (parser.currentToken() == JsonToken.VALUE_NULL) deserializer.getNullValue(context) else deserializer.deserialize(parser, context)
      checkpoint()
      return value
    }

    private fun unknown(field: String): Nothing = error("Unknown XCTest hierarchy field: $field")
  }

  /** Scalar adapters advance this wrapper too; their token/read work keeps the same checks. */
  private class CheckedParser(parser: JsonParser, private val checkpoint: () -> Unit) : JsonParserDelegate(parser) {
    private inline fun <T> checked(block: () -> T): T {
      checkpoint()
      return block().also { checkpoint() }
    }
    override fun nextToken(): JsonToken? = checked { super.nextToken() }
    override fun nextValue(): JsonToken? = nextToken().let { if (it == JsonToken.FIELD_NAME) nextToken() else it }
    override fun getText(): String = checked { super.getText() }
    override fun getIntValue(): Int = checked { super.getIntValue() }
    override fun getLongValue(): Long = checked { super.getLongValue() }
    override fun getFloatValue(): Float = checked { super.getFloatValue() }
    override fun getDoubleValue(): Double = checked { super.getDoubleValue() }
    override fun getValueAsString(defaultValue: String?): String? = checked { super.getValueAsString(defaultValue) }
  }
}
