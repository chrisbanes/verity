package me.chrisbanes.verity.cli

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class CodexIsolationTest {
  @Test
  fun `pinned policy includes every required execution disable and uses quoted names`() {
    val isolation = CodexIsolation(mapOf("mcp_servers" to setOf("a.\"b\\c"), "plugins" to setOf("with spaces"), "apps" to setOf("[app]")))
    assertThat(isolation.policy["model_provider"]).isEqualTo(JsonPrimitive("openai"))
    assertThat(isolation.policy["chatgpt_base_url"]).isEqualTo(JsonPrimitive("https://chatgpt.com/backend-api/"))
    assertThat(isolation.policy["openai_base_url"]).isEqualTo(JsonPrimitive("https://chatgpt.com/backend-api/codex"))
    assertThat(isolation.arguments().contains("openai_base_url=\"https://chatgpt.com/backend-api/codex\"")).isTrue()
    assertThat(isolation.threadConfig()["chatgpt_base_url"]).isEqualTo(JsonPrimitive("https://chatgpt.com/backend-api/"))
    assertThat(isolation.policy["notify"]).isEqualTo(JsonArray(emptyList()))
    assertThat(isolation.policy["project_doc_max_bytes"]).isEqualTo(JsonPrimitive(0))
    assertThat(CodexIsolation.features.size).isEqualTo(37)
    assertThat(CodexIsolation.features.all { isolation.policy["features.$it"] == JsonPrimitive(false) }).isTrue()
    assertThat(isolation.arguments().contains("mcp_servers={ \"a.\\\"b\\\\c\" = { enabled = false } }")).isTrue()
    assertThat(isolation.arguments().contains("plugins={ \"with spaces\" = { enabled = false } }")).isTrue()
    assertThat(isolation.arguments().none { it.contains("sqlite_home") || it.contains("CODEX_HOME") }).isTrue()
  }

  @Test
  fun `each group is one prefix free inline table keyed by TOML basic strings`() {
    val names = setOf("homeassistant", "computer-use", "linear@openai-curated", "punctuation.a\"b\\c", "space and [brackets]")
    val isolation = CodexIsolation(mapOf("mcp_servers" to names, "plugins" to names, "apps" to emptySet()))
    val overrides = isolation.arguments().chunked(2).map { (flag, override) ->
      assertThat(flag).isEqualTo("-c")
      override.substringBefore('=') to override.substringAfter('=')
    }
    val tables = names.joinToString(", ", "{ ", " }") { "${CodexIsolation.tomlKey(it)} = { enabled = false }" }
    assertThat(overrides.filter { it.first in CodexIsolation.groups }).isEqualTo(listOf("mcp_servers" to tables, "plugins" to tables, "apps" to "{ \"_default\" = { enabled = false } }"))
    assertThat(overrides.none { (key, _) -> CodexIsolation.groups.any { key.startsWith("$it.") } }).isTrue()
    assertThat(isolation.threadConfig()["apps"]).isEqualTo(JsonObject(mapOf("_default" to JsonObject(mapOf("enabled" to JsonPrimitive(false))))))
    assertThat(isolation.threadConfig()["mcp_servers"]?.jsonObject?.keys).isEqualTo(names)
    listOf(overrides.map { it.first }, isolation.threadConfig().keys.toList()).forEach { keys ->
      assertThat(keys.distinct()).isEqualTo(keys)
      assertThat(keys.none { key -> keys.any { key.startsWith("$it.") } }).isTrue()
    }
    assertThat(CodexIsolation().arguments().chunked(2).count { it[1].substringBefore('=') in CodexIsolation.groups }).isEqualTo(1)
  }

  @Test
  fun `minimum version is strict and newer versions still need the schema gate`() {
    listOf("codex-cli 0.159.0", "codex-cli 0.200.0", "codex-cli 1.0.0").forEach { assertThat(CodexModelBackend.supportedVersion(it)).isTrue() }
    listOf(null, "codex-cli 0.158.9", "0.159.0", "codex-cli 0.159.0-extra", "codex-cli secret", "codex-cli 999999999999999999.0.0").forEach { assertThat(CodexModelBackend.supportedVersion(it)).isFalse() }
  }

  @Test
  fun `required experimental schema absent or root type changed fails closed with actual child cleanup`() = runTest {
    withContext(Dispatchers.Default) {
      listOf("missing-raw", "missing-roots", "wrong-root-type", "missing-raw-definition", "wrong-echo-type", "schema-symlink", "missing-gateway", "missing-opt-out").forEach { scenario ->
        val fake = FakeCodexLauncher(scenario)
        val failure = assertFailsWith<CodexFailure> { fake.prepare() }
        assertThat(failure.kind).isEqualTo(CodexFailureKind.PROTOCOL)
        assertThat(fake.children.size).isEqualTo(2)
        fake.verifyCleanup()
      }
    }
  }

  @Test
  fun `effective policy denial redirected origin layered tools drift and inherited name drift fail once with complete cleanup`() = runTest {
    withContext(Dispatchers.Default) {
      listOf("denied-policy", "redirected-chatgpt-origin", "redirected-openai-origin", "managed-tools-enabled", "managed-tools-table", "layers-missing", "origin-mismatch", "string-false", "typed-key-missing", "drift").forEach { scenario ->
        val fake = FakeCodexLauncher(scenario)
        assertThat(assertFailsWith<CodexFailure> { fake.prepare() }.kind).isEqualTo(CodexFailureKind.ISOLATION)
        assertThat(fake.children.size).isEqualTo(if (scenario == "drift") 4 else 3)
        fake.verifyCleanup()
      }
    }
  }

  @Test
  fun `non macOS and missing executable never launch children`() = runTest {
    withContext(Dispatchers.Default) {
      var launches = 0
      val factory: (List<String>, Path, Map<String, String>) -> Process = { _, _, _ ->
        launches++
        error("must not launch")
      }
      assertThat(assertFailsWith<CodexFailure> { CodexModelBackend.prepare(host = "Linux", environment = emptyMap(), executable = { Path.of("fake") }, launch = factory) }.kind).isEqualTo(CodexFailureKind.HOST)
      assertThat(assertFailsWith<CodexFailure> { CodexModelBackend.prepare(host = "Mac OS X", environment = emptyMap(), executable = { null }, launch = factory) }.kind).isEqualTo(CodexFailureKind.INSTALLATION)
      assertThat(launches).isEqualTo(0)
    }
  }
}
