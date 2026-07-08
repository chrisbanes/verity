package me.chrisbanes.verity.core.result

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import kotlin.test.Test
import kotlinx.serialization.json.Json
import me.chrisbanes.verity.core.model.AssertMode
import me.chrisbanes.verity.core.model.Platform

class RunResultContractTest {
  private val json = Json {
    prettyPrint = false
    encodeDefaults = true
    explicitNulls = false
  }

  @Test
  fun `journey result serializes stable lowercase values and camelCase fields`() {
    val result = JourneyArtifactResult(
      journey = JourneyArtifactIdentity(
        name = "Login",
        file = "journeys/login.journey.yaml",
        app = "com.example.app",
        platform = Platform.ANDROID_TV,
      ),
      passed = false,
      failedAt = 2,
      segments = listOf(
        SegmentArtifactResult(
          index = 2,
          passed = false,
          executionMode = SegmentExecutionMode.SLOW,
          actions = listOf("Navigate to Settings"),
          assertion = AssertionArtifact(description = "Settings is visible", mode = AssertMode.TREE),
          reasoning = "Expected Settings but saw Home",
          generatedFlows = listOf("flows/001-login/segment-002-actions.yaml"),
          evidence = listOf(
            EvidenceArtifact(type = EvidenceType.HIERARCHY, path = "evidence/001-login/segment-002-tree.txt"),
          ),
          error = ArtifactError(kind = ArtifactErrorKind.JOURNEY_FAILURE, message = "Expected Settings but saw Home"),
        ),
      ),
    )

    val encoded = json.encodeToString(JourneyArtifactResult.serializer(), result)

    assertThat(encoded).contains("\"platform\":\"android-tv\"")
    assertThat(encoded).contains("\"executionMode\":\"slow\"")
    assertThat(encoded).contains("\"mode\":\"tree\"")
    assertThat(encoded).contains("\"type\":\"hierarchy\"")
    assertThat(encoded).contains("\"kind\":\"journey_failure\"")
    assertThat(encoded).contains("\"generatedFlows\"")
  }

  @Test
  fun `suite summary serializes status counts and journey references`() {
    val summary = SuiteArtifactSummary(
      formatVersion = 1,
      timestamp = "2026-07-08T14:35:12Z",
      inputPath = "journeys",
      status = ArtifactStatus.FAILED,
      total = 2,
      passed = 1,
      failed = 1,
      journeys = listOf(
        SuiteJourneyArtifact(path = "journeys/001-login.json", name = "Login", status = ArtifactStatus.PASSED),
        SuiteJourneyArtifact(path = "journeys/002-browse.json", name = "Browse", status = ArtifactStatus.FAILED),
      ),
      error = ArtifactError(kind = ArtifactErrorKind.JOURNEY_FAILURE, message = "One or more journeys failed"),
    )

    val encoded = json.encodeToString(SuiteArtifactSummary.serializer(), summary)

    assertThat(encoded).contains("\"formatVersion\":1")
    assertThat(encoded).contains("\"status\":\"failed\"")
    assertThat(encoded).contains("\"passed\":1")
    assertThat(encoded).contains("\"failed\":1")
    assertThat(encoded).contains("\"path\":\"journeys/001-login.json\"")
  }

  @Test
  fun `platform and assert mode decode from stable wire values`() {
    val decoded = json.decodeFromString(
      JourneyArtifactResult.serializer(),
      """
      {
        "journey":{"name":"Login","file":"login.journey.yaml","app":"com.example.app","platform":"android"},
        "passed":true,
        "segments":[{"index":0,"passed":true,"executionMode":"assertion-only","assertion":{"description":"Home","mode":"visible"}}]
      }
      """.trimIndent(),
    )

    assertThat(decoded.journey.platform).isEqualTo(Platform.ANDROID_MOBILE)
    assertThat(decoded.segments.single().assertion?.mode).isEqualTo(AssertMode.VISIBLE)
    assertThat(decoded.segments.single().executionMode).isEqualTo(SegmentExecutionMode.ASSERTION_ONLY)
  }

  @Test
  fun `platform encodes and decodes all stable wire values`() {
    assertThat(json.encodeToString(PlatformWireSerializer, Platform.ANDROID_TV)).isEqualTo("\"android-tv\"")
    assertThat(json.encodeToString(PlatformWireSerializer, Platform.ANDROID_MOBILE)).isEqualTo("\"android\"")
    assertThat(json.encodeToString(PlatformWireSerializer, Platform.IOS)).isEqualTo("\"ios\"")

    assertThat(json.decodeFromString(PlatformWireSerializer, "\"android-tv\"")).isEqualTo(Platform.ANDROID_TV)
    assertThat(json.decodeFromString(PlatformWireSerializer, "\"android\"")).isEqualTo(Platform.ANDROID_MOBILE)
    assertThat(json.decodeFromString(PlatformWireSerializer, "\"ios\"")).isEqualTo(Platform.IOS)
  }

  @Test
  fun `assert mode encodes and decodes all stable wire values`() {
    assertThat(json.encodeToString(AssertModeWireSerializer, AssertMode.VISIBLE)).isEqualTo("\"visible\"")
    assertThat(json.encodeToString(AssertModeWireSerializer, AssertMode.FOCUSED)).isEqualTo("\"focused\"")
    assertThat(json.encodeToString(AssertModeWireSerializer, AssertMode.TREE)).isEqualTo("\"tree\"")
    assertThat(json.encodeToString(AssertModeWireSerializer, AssertMode.VISUAL)).isEqualTo("\"visual\"")

    assertThat(json.decodeFromString(AssertModeWireSerializer, "\"visible\"")).isEqualTo(AssertMode.VISIBLE)
    assertThat(json.decodeFromString(AssertModeWireSerializer, "\"focused\"")).isEqualTo(AssertMode.FOCUSED)
    assertThat(json.decodeFromString(AssertModeWireSerializer, "\"tree\"")).isEqualTo(AssertMode.TREE)
    assertThat(json.decodeFromString(AssertModeWireSerializer, "\"visual\"")).isEqualTo(AssertMode.VISUAL)
  }

  @Test
  fun `artifact status encodes and decodes all stable wire values`() {
    assertThat(json.encodeToString(ArtifactStatus.serializer(), ArtifactStatus.PASSED)).isEqualTo("\"passed\"")
    assertThat(json.encodeToString(ArtifactStatus.serializer(), ArtifactStatus.FAILED)).isEqualTo("\"failed\"")

    assertThat(json.decodeFromString(ArtifactStatus.serializer(), "\"passed\"")).isEqualTo(ArtifactStatus.PASSED)
    assertThat(json.decodeFromString(ArtifactStatus.serializer(), "\"failed\"")).isEqualTo(ArtifactStatus.FAILED)
  }

  @Test
  fun `segment execution mode encodes and decodes all stable wire values`() {
    assertThat(json.encodeToString(SegmentExecutionMode.serializer(), SegmentExecutionMode.FAST)).isEqualTo("\"fast\"")
    assertThat(json.encodeToString(SegmentExecutionMode.serializer(), SegmentExecutionMode.SLOW)).isEqualTo("\"slow\"")
    assertThat(json.encodeToString(SegmentExecutionMode.serializer(), SegmentExecutionMode.LOOP)).isEqualTo("\"loop\"")
    assertThat(json.encodeToString(SegmentExecutionMode.serializer(), SegmentExecutionMode.ASSERTION_ONLY))
      .isEqualTo("\"assertion-only\"")

    assertThat(json.decodeFromString(SegmentExecutionMode.serializer(), "\"fast\"")).isEqualTo(SegmentExecutionMode.FAST)
    assertThat(json.decodeFromString(SegmentExecutionMode.serializer(), "\"slow\"")).isEqualTo(SegmentExecutionMode.SLOW)
    assertThat(json.decodeFromString(SegmentExecutionMode.serializer(), "\"loop\"")).isEqualTo(SegmentExecutionMode.LOOP)
    assertThat(json.decodeFromString(SegmentExecutionMode.serializer(), "\"assertion-only\""))
      .isEqualTo(SegmentExecutionMode.ASSERTION_ONLY)
  }

  @Test
  fun `evidence type encodes and decodes all stable wire values`() {
    assertThat(json.encodeToString(EvidenceType.serializer(), EvidenceType.FLOW)).isEqualTo("\"flow\"")
    assertThat(json.encodeToString(EvidenceType.serializer(), EvidenceType.SCREENSHOT)).isEqualTo("\"screenshot\"")
    assertThat(json.encodeToString(EvidenceType.serializer(), EvidenceType.HIERARCHY)).isEqualTo("\"hierarchy\"")

    assertThat(json.decodeFromString(EvidenceType.serializer(), "\"flow\"")).isEqualTo(EvidenceType.FLOW)
    assertThat(json.decodeFromString(EvidenceType.serializer(), "\"screenshot\"")).isEqualTo(EvidenceType.SCREENSHOT)
    assertThat(json.decodeFromString(EvidenceType.serializer(), "\"hierarchy\"")).isEqualTo(EvidenceType.HIERARCHY)
  }

  @Test
  fun `artifact error kind encodes and decodes all stable wire values`() {
    assertThat(json.encodeToString(ArtifactErrorKind.serializer(), ArtifactErrorKind.PARSER_FAILURE))
      .isEqualTo("\"parser_failure\"")
    assertThat(json.encodeToString(ArtifactErrorKind.serializer(), ArtifactErrorKind.SETUP_FAILURE))
      .isEqualTo("\"setup_failure\"")
    assertThat(json.encodeToString(ArtifactErrorKind.serializer(), ArtifactErrorKind.JOURNEY_FAILURE))
      .isEqualTo("\"journey_failure\"")

    assertThat(json.decodeFromString(ArtifactErrorKind.serializer(), "\"parser_failure\""))
      .isEqualTo(ArtifactErrorKind.PARSER_FAILURE)
    assertThat(json.decodeFromString(ArtifactErrorKind.serializer(), "\"setup_failure\""))
      .isEqualTo(ArtifactErrorKind.SETUP_FAILURE)
    assertThat(json.decodeFromString(ArtifactErrorKind.serializer(), "\"journey_failure\""))
      .isEqualTo(ArtifactErrorKind.JOURNEY_FAILURE)
  }
}
