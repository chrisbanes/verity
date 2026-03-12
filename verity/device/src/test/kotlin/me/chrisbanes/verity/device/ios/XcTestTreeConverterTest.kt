package me.chrisbanes.verity.device.ios

import assertk.assertThat
import assertk.assertions.containsOnly
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import hierarchy.AXElement
import hierarchy.AXFrame
import kotlin.test.Test

class XcTestTreeConverterTest {

  private val defaultFrame = AXFrame(0f, 0f, 100f, 50f)

  private fun axElement(
    label: String = "",
    identifier: String = "",
    value: String = "",
    title: String = "",
    hasFocus: Boolean = false,
    selected: Boolean = false,
    enabled: Boolean = true,
    children: ArrayList<AXElement> = arrayListOf(),
  ) = AXElement(
    label,
    0, // elementType
    identifier,
    0, // horizontalSizeClass
    0L, // windowContextID
    0, // verticalSizeClass
    selected,
    0, // displayID
    hasFocus,
    "", // placeholderValue
    value,
    defaultFrame,
    enabled,
    title,
    children,
  )

  @Test
  fun `converts empty AXElement`() {
    val result = XcTestTreeConverter.convert(axElement())
    assertThat(result.attributes).isEmpty()
    assertThat(result.states).isEmpty()
    assertThat(result.children).isEmpty()
  }

  @Test
  fun `maps label to text attribute`() {
    val result = XcTestTreeConverter.convert(axElement(label = "Hello"))
    assertThat(result.attributes).isEqualTo(mapOf("text" to "Hello"))
  }

  @Test
  fun `maps identifier to resource-id attribute`() {
    val result = XcTestTreeConverter.convert(axElement(identifier = "btn_login"))
    assertThat(result.attributes).isEqualTo(mapOf("resource-id" to "btn_login"))
  }

  @Test
  fun `maps value and title attributes`() {
    val result = XcTestTreeConverter.convert(axElement(value = "50%", title = "Volume"))
    assertThat(result.attributes).isEqualTo(mapOf("value" to "50%", "title" to "Volume"))
  }

  @Test
  fun `filters empty attribute values`() {
    val result = XcTestTreeConverter.convert(
      axElement(label = "Visible", identifier = "", value = "", title = ""),
    )
    assertThat(result.attributes).isEqualTo(mapOf("text" to "Visible"))
  }

  @Test
  fun `converts focused and selected states`() {
    val result = XcTestTreeConverter.convert(axElement(hasFocus = true, selected = true))
    assertThat(result.states).containsOnly("focused", "selected")
  }

  @Test
  fun `converts disabled state`() {
    val result = XcTestTreeConverter.convert(axElement(enabled = false))
    assertThat(result.states).containsOnly("disabled")
  }

  @Test
  fun `enabled true does not add disabled state`() {
    val result = XcTestTreeConverter.convert(axElement(enabled = true))
    assertThat(result.states).isEmpty()
  }

  @Test
  fun `converts children recursively`() {
    val child = axElement(label = "Child")
    val parent = axElement(label = "Parent", children = arrayListOf(child))
    val result = XcTestTreeConverter.convert(parent)
    assertThat(result.children).hasSize(1)
    assertThat(result.children[0].attributes["text"]).isEqualTo("Child")
  }
}
