package me.chrisbanes.verity.core.model

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import kotlin.test.Test
import kotlinx.serialization.json.Json
import me.chrisbanes.verity.core.interaction.Direction
import me.chrisbanes.verity.core.interaction.Interaction

class ActionFlowTest {
  @Test
  fun `response binds authoritative app context and preserves ordered actions`() {
    val flow = ActionFlow.decodeResponse(
      "com.example.app",
      """{"actions":[{"type":"keyPress","keyName":"BACK"},{"type":"tapOnText","text":"Settings.*"}]}""",
    )
    assertThat(flow).isEqualTo(
      ActionFlow("com.example.app", listOf(Interaction.KeyPress("BACK"), Interaction.TapOnText("Settings.*"))),
    )
  }

  @Test
  fun `malformed or unsupported final action rejects the whole response safely`() {
    val suffixes = listOf(
      """{"type":"tapOnText","text":" "}""",
      """{"type":"tapOnText","text":"["}""",
      """{"type":"tapOnText","text":123}""",
      """{"type":"tapOnId","resourceId":""}""",
      """{"type":"keyPress","keyName":"  "}""",
      """{"type":"swipe","direction":"DIAGONAL"}""",
      """{"type":"keyPress","keyName":"BACK","extra":true}""",
      """{"type":"screenshot","path":"private"}""",
      """{"type":"keyPress"}""",
    )
    for (suffix in suffixes) {
      assertFailure {
        ActionFlow.decodeResponse("com.example.app", """{"actions":[{"type":"keyPress","keyName":"BACK"},$suffix]}""")
      }.isInstanceOf<InvalidActionFlowException>()
    }
  }

  @Test
  fun `response owns actions only and requires a usable external app id`() {
    for (response in listOf(
      """{"appId":"override","actions":[]}""",
      """{"actions":{}}""",
      """{}""",
      """[]""",
      """```json {"actions":[]} ```""",
      """{"actions":[{"type":"keyPress","keyName":"BACK"}]} trailing""",
    )) {
      assertFailure { ActionFlow.decodeResponse("com.example.app", response) }.isInstanceOf<InvalidActionFlowException>()
    }
    assertFailure { ActionFlow.decodeResponse(" ", """{"actions":[]}""") }.isInstanceOf<InvalidActionFlowException>()
  }

  @Test
  fun `current navigation vocabulary preserves contents defaults and serialization`() {
    val flow = ActionFlow.decodeResponse(
      "app",
      """{"actions":[
      {"type":"launchApp"},{"type":"launchApp","clearState":false},
      {"type":"inputText","text":"  café: # \n雪  "},{"type":"inputText","text":""},
      {"type":"defaultScroll"},{"type":"scroll","direction":"DOWN"},
      {"type":"swipe","direction":"LEFT"},{"type":"longPressOnFocused"},
      {"type":"longPressOnText","text":"Settings.*"},{"type":"pullToRefresh"},
      {"type":"waitForAnimation"},{"type":"waitForAnimation","timeoutMs":500},
      {"type":"waitUntilVisible","text":"Next.*","timeoutMs":1000},
      {"type":"waitUntilVisible","resourceId":"example:id/item","timeoutMs":2000}
    ]}""",
    )
    assertThat(flow.actions).isEqualTo(
      listOf(
        Interaction.LaunchApp(), Interaction.LaunchApp(false),
        Interaction.InputText("  café: # \n雪  "), Interaction.InputText(""),
        Interaction.DefaultScroll, Interaction.Scroll(Direction.DOWN),
        Interaction.Swipe(Direction.LEFT), Interaction.LongPressOnFocused,
        Interaction.LongPressOnText("Settings.*"), Interaction.PullToRefresh,
        Interaction.WaitForAnimation(), Interaction.WaitForAnimation(500),
        Interaction.WaitUntilVisible(text = "Next.*", timeoutMs = 1000),
        Interaction.WaitUntilVisible(resourceId = "example:id/item", timeoutMs = 2000),
      ),
    )
    val json = Json { classDiscriminator = "type" }
    assertThat(json.decodeFromString<ActionFlow>(json.encodeToString(ActionFlow.serializer(), flow))).isEqualTo(flow)
  }

  @Test
  fun `visible selector and millisecond timeout are explicit and strict`() {
    val invalid = listOf(
      """{"type":"waitUntilVisible","timeoutMs":1}""",
      """{"type":"waitUntilVisible","text":"x","resourceId":"y","timeoutMs":1}""",
      """{"type":"waitUntilVisible","text":" ","timeoutMs":1}""",
      """{"type":"waitUntilVisible","text":"x"}""",
      """{"type":"waitUntilVisible","text":"x","timeoutMs":0}""",
      """{"type":"waitUntilVisible","text":"x","timeoutMs":-1}""",
      """{"type":"waitUntilVisible","text":"x","timeoutMs":1.5}""",
      """{"type":"waitUntilVisible","text":"x","timeoutMs":2147483648}""",
      """{"type":"waitUntilVisible","text":"x","timeoutMs":"100"}""",
      """{"type":"waitForAnimation","timeoutMs":0}""",
      """{"type":"launchApp","clearState":"false"}""",
      """{"type":"launchApp","appId":"override"}""",
      """{"type":"inputText","text":false}""",
    )
    for (action in invalid) {
      assertFailure { ActionFlow.decodeResponse("app", """{"actions":[$action]}""") }.isInstanceOf<InvalidActionFlowException>()
    }
  }
}
