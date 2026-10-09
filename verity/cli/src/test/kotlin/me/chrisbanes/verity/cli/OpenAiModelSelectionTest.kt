package me.chrisbanes.verity.cli

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isSameInstanceAs
import kotlin.test.Test
import kotlin.test.assertFailsWith

class OpenAiModelSelectionTest {
  @Test
  fun `auth parser accepts only the exact supported values`() {
    assertThat(OpenAiAuth.parse(null)).isEqualTo(OpenAiAuth.API_KEY)
    assertThat(OpenAiAuth.parse("api-key")).isEqualTo(OpenAiAuth.API_KEY)
    assertThat(OpenAiAuth.parse("chatgpt")).isEqualTo(OpenAiAuth.CHATGPT)
    listOf("", "API-KEY", "ChatGPT", " api-key", "openai").forEach { value ->
      assertFailsWith<IllegalArgumentException> { OpenAiAuth.parse(value) }
    }
  }

  @Test
  fun `API model selection uses CLI config and provider defaults in order`() {
    val provider = VerityProvider.OpenAI

    assertThat(
      selectRoleModel(provider, null, "gpt-4o", "gpt-4.1", ModelRole.NAVIGATOR),
    ).isEqualTo(SelectedRoleModel.Api(provider.findModel("gpt-4o")))
    assertThat(
      selectRoleModel(provider, OpenAiAuth.API_KEY, null, "gpt-4.1", ModelRole.NAVIGATOR),
    ).isEqualTo(SelectedRoleModel.Api(provider.findModel("gpt-4.1")))
    assertThat(
      selectRoleModel(provider, OpenAiAuth.API_KEY, null, null, ModelRole.NAVIGATOR),
    ).isEqualTo(SelectedRoleModel.Api(provider.defaultNavigatorModel))
    assertThat(
      selectRoleModel(provider, OpenAiAuth.API_KEY, null, null, ModelRole.INSPECTOR),
    ).isEqualTo(SelectedRoleModel.Api(provider.defaultInspectorModel))
  }

  @Test
  fun `ChatGPT selection retains exact IDs without API catalog lookup`() {
    val provider = VerityProvider.OpenAI

    assertThat(
      selectRoleModel(provider, OpenAiAuth.CHATGPT, "custom-exact-id", "yaml-id", ModelRole.NAVIGATOR),
    ).isEqualTo(SelectedRoleModel.Codex("custom-exact-id"))
    assertThat(
      selectRoleModel(provider, OpenAiAuth.CHATGPT, null, "yaml-id", ModelRole.INSPECTOR),
    ).isEqualTo(SelectedRoleModel.Codex("yaml-id"))
    assertThat(
      selectRoleModel(provider, OpenAiAuth.CHATGPT, null, null, ModelRole.NAVIGATOR),
    ).isEqualTo(SelectedRoleModel.Codex("gpt-6-luna"))
    assertThat(
      selectRoleModel(provider, OpenAiAuth.CHATGPT, null, null, ModelRole.INSPECTOR),
    ).isEqualTo(SelectedRoleModel.Codex("gpt-6-luna"))
  }

  @Test
  fun `unknown API IDs and explicit OpenAI auth with other providers are rejected`() {
    assertFailsWith<IllegalStateException> {
      selectRoleModel(VerityProvider.OpenAI, OpenAiAuth.API_KEY, "unknown", null, ModelRole.NAVIGATOR)
    }
    assertFailsWith<IllegalArgumentException> {
      selectRoleModel(VerityProvider.Anthropic, OpenAiAuth.API_KEY, null, null, ModelRole.NAVIGATOR)
    }
    assertFailsWith<IllegalArgumentException> {
      selectRoleModel(VerityProvider.Anthropic, OpenAiAuth.CHATGPT, null, null, ModelRole.NAVIGATOR)
    }
  }

  @Test
  fun `omitted OpenAI auth leaves non OpenAI provider defaults on API path`() {
    val selected = selectRoleModel(VerityProvider.Anthropic, null, null, null, ModelRole.NAVIGATOR)

    assertThat(selected).isInstanceOf(SelectedRoleModel.Api::class)
    assertThat((selected as SelectedRoleModel.Api).model).isSameInstanceAs(VerityProvider.Anthropic.defaultNavigatorModel)
  }
}
