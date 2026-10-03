package me.chrisbanes.verity.core.research

import assertk.assertThat
import assertk.assertions.isEqualTo
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import me.chrisbanes.verity.core.model.AssertMode

class AssertionPlanningResearchTest {

  @Test
  fun `current baseline keeps the complete parser description as its target`() {
    val corpus = AssertionPlanningResearch.loadCorpus(corpusPath())
    val textCase = corpus.cases.single { it.id == "text-03" }

    val check = AssertionPlanningResearch.currentPlan(textCase).checks.single()

    assertThat(check.mode).isEqualTo(AssertMode.VISIBLE)
    assertThat(check.target).isEqualTo("Internet is visible")
    assertThat(check.originalExpectation.intent).isEqualTo("The Internet row is displayed.")
  }

  @Test
  fun `real parser preserves authored precedence and all fixed modes`() {
    val corpus = AssertionPlanningResearch.loadCorpus(corpusPath())
    val byId = corpus.cases.associateBy { it.id }
    val expectedModes = mapOf(
      "authority-01" to AssertMode.VISIBLE,
      "authority-02" to AssertMode.FOCUSED,
      "authority-03" to AssertMode.TREE,
      "authority-04" to AssertMode.VISUAL,
      "authority-05" to AssertMode.VISIBLE,
      "authority-06" to AssertMode.FOCUSED,
      "authority-07" to AssertMode.TREE,
      "authority-08" to AssertMode.VISUAL,
      "text-03" to AssertMode.VISIBLE,
    )

    for ((caseId, expectedMode) in expectedModes) {
      val check = AssertionPlanningResearch.currentPlan(byId.getValue(caseId)).checks.single()
      assertThat(check.mode).isEqualTo(expectedMode)
    }

    val authoredVisualConflict = AssertionPlanningResearch.currentPlan(byId.getValue("authority-04")).checks.single()
    assertThat(authoredVisualConflict.target == null).isEqualTo(true)
    assertThat(authoredVisualConflict.originalExpectation.parsedDescription)
      .isEqualTo("Backdrop image loads")
    val inferredVisible = AssertionPlanningResearch.currentPlan(byId.getValue("text-03")).checks.single()
    assertThat(inferredVisible.target).isEqualTo("Internet is visible")
  }

  @Test
  fun `host rejects missing and unsupported commands before filesystem work`() {
    assertFailsWith<IllegalArgumentException> { main(emptyArray()) }
    assertFailsWith<IllegalArgumentException> { main(arrayOf("compare", "ignored", "ignored")) }
  }

  @Test
  fun `corpus counts and bypasses come from cases raw syntax and fixed strategy`() {
    val corpus = AssertionPlanningResearch.loadCorpus(corpusPath())
    val categories = corpus.cases.groupingBy { it.category }.eachCount()
    val platforms = corpus.cases.groupingBy { it.platform }.eachCount()
    val authored = corpus.cases.count { AssertionPlanningResearch.hasAuthoredModeTag(it.rawStep) }
    val fixed = corpus.cases.count { it.provenance.fixedStrategy != null }
    val conflicts = corpus.cases.count {
      AssertionPlanningResearch.hasAuthoredModeTag(it.rawStep) && it.provenance.fixedStrategy != null
    }
    val bypasses = corpus.cases.count { AssertionPlanningResearch.isAuthorityBypass(it) }

    assertThat(corpus.cases.size).isEqualTo(40)
    assertThat(categories).isEqualTo(
      mapOf(
        "text" to 6,
        "focus" to 6,
        "negation" to 5,
        "compound" to 5,
        "ambiguous" to 5,
        "visual" to 5,
        "authority" to 8,
      ),
    )
    assertThat(corpus.categoryCounts).isEqualTo(categories)
    assertThat(platforms).isEqualTo(mapOf("android-tv" to 17, "android" to 14, "ios" to 9))
    assertThat(authored).isEqualTo(4)
    assertThat(fixed).isEqualTo(5)
    assertThat(conflicts).isEqualTo(1)
    assertThat(bypasses).isEqualTo(8)
    assertThat(corpus.cases.size - bypasses).isEqualTo(32)
    assertThat(corpus.authorityCoverage.authoredModes).isEqualTo(authored)
    assertThat(corpus.authorityCoverage.fixedStrategies).isEqualTo(fixed - conflicts)
    assertThat(corpus.authorityCoverage.authoredOverridesFixedConflicts).isEqualTo(conflicts)
    assertThat(corpus.authorityCoverage.derivedAuthorityBypassCases).isEqualTo(bypasses)
    assertThat(corpus.authorityCoverage.derivedImplicitCases).isEqualTo(corpus.cases.size - bypasses)
    assertThat(corpus.authorityCoverage.independentlyFixedModeCases).isEqualTo(fixed - conflicts)
    assertThat(corpus.authorityCoverage.authoredModeCases).isEqualTo(authored)
  }

  @Test
  fun `rules extract only simple positive visible and focused targets`() {
    val corpus = AssertionPlanningResearch.loadCorpus(corpusPath())
    val byId = corpus.cases.associateBy { it.id }

    val textCheck = AssertionPlanningResearch.rulesPlan(byId.getValue("text-03")).checks.single()
    assertThat(textCheck.mode).isEqualTo(AssertMode.VISIBLE)
    assertThat(textCheck.target).isEqualTo("Internet")
    assertThat(textCheck.originalExpectation.intent).isEqualTo("The Internet row is displayed.")

    val focusCheck = AssertionPlanningResearch.rulesPlan(byId.getValue("focus-06")).checks.single()
    assertThat(focusCheck.mode).isEqualTo(AssertMode.FOCUSED)
    assertThat(focusCheck.target).isEqualTo("Name")

    for (caseId in listOf("negation-01", "compound-01", "ambiguous-01", "visual-01", "text-02")) {
      val case = byId.getValue(caseId)
      val current = AssertionPlanningResearch.currentPlan(case).checks.single()
      val rules = AssertionPlanningResearch.rulesPlan(case).checks.single()
      assertThat(rules.mode).isEqualTo(current.mode)
      assertThat(rules.target).isEqualTo(current.target)
    }

    for (case in corpus.cases.filter(AssertionPlanningResearch::isAuthorityBypass)) {
      val current = AssertionPlanningResearch.currentPlan(case).checks.single()
      val rules = AssertionPlanningResearch.rulesPlan(case).checks.single()
      assertThat(rules.mode).isEqualTo(current.mode)
      assertThat(rules.target).isEqualTo(current.target)
    }
  }

  @Test
  fun `corpus loader retains source adaptation and preferred cheap check metadata`() {
    val corpus = AssertionPlanningResearch.loadCorpus(corpusPath())
    val tvDetail = corpus.cases.single { it.id == "text-02" }
    val internet = corpus.cases.single { it.id == "text-03" }

    assertThat(tvDetail.sourceSeed).isEqualTo("verity/core/src/test/resources/journeys/sample.journey.yaml#steps[4]")
    assertThat(tvDetail.sourceAdaptation?.originalSourceSeed)
      .isEqualTo("verity/core/src/test/resources/journeys/sample.journey.yaml#steps[4]")
    assertThat(tvDetail.sourceAdaptation?.adaptedContext).isEqualTo("synthetic:context/01-app.md#tv-detail-page")
    assertThat(tvDetail.preferredCheapChecks).isEqualTo(emptyList())
    assertThat(internet.preferredCheapChecks.single().mode).isEqualTo("visible")
    assertThat(internet.preferredCheapChecks.single().target).isEqualTo("Internet")
  }

  @Test
  fun `planner projection contains only the four approved fields`() {
    val corpusPath = corpusPath()
    val original = AssertionPlanningResearch.loadCorpus(corpusPath).cases.single { it.id == "text-03" }
    val sentinel = "GOLD_ONLY_SENTINEL"
    val polluted = original.copy(
      originalIntent = sentinel,
      allowedChecks = original.allowedChecks.map { it.copy(target = sentinel, rationale = sentinel) },
      groundTruthRationale = sentinel,
      evidence = original.evidence.copy(
        ambiguity = sentinel,
        samples = original.evidence.samples.map { sample ->
          sample.copy(
            rationale = sentinel,
            tree = sample.tree?.copy(attributes = sample.tree.attributes + ("sentinel" to sentinel)),
          )
        },
        unavailableFacts = listOf(sentinel),
      ),
      preferredCheapChecks = original.preferredCheapChecks.map { it.copy(target = sentinel, rationale = sentinel) },
      sourceAdaptation = original.sourceAdaptation?.copy(note = sentinel),
    )

    val input = AssertionPlanningResearch.projectPlannerInput(polluted, corpusPath)
    val encoded = Json.encodeToString(input)
    val keys = Json.parseToJsonElement(encoded).jsonObject.keys

    assertThat(keys).isEqualTo(setOf("rawStep", "journeySteps", "platform", "projectContext"))
    assertThat(encoded.contains(sentinel)).isEqualTo(false)
    assertThat(input.rawStep).isEqualTo(original.rawStep)
    assertThat(input.journeySteps).isEqualTo(original.journeySteps)
    assertThat(input.platform).isEqualTo(original.platform)
    assertThat(input.projectContext.startsWith("# Synthetic application context")).isEqualTo(true)
    assertThat(input.projectContext.indexOf("# Synthetic controls and terminology") > 0).isEqualTo(true)
  }

  @Test
  fun `synthetic helper outcomes remain separate from intent verdicts`() {
    val corpus = AssertionPlanningResearch.loadCorpus(corpusPath())
    val byId = corpus.cases.associateBy { it.id }

    val textCase = byId.getValue("text-01")
    val textOutcomes = AssertionPlanningResearch.evaluateSynthetic(
      textCase,
      AssertionPlanningResearch.rulesPlan(textCase),
    ).associateBy { it.sampleId }
    assertThat(textOutcomes.getValue("text-01-positive").expectedIntentVerdict).isEqualTo(true)
    assertThat(textOutcomes.getValue("text-01-positive").observedCheckVerdict).isEqualTo(true)
    assertThat(textOutcomes.getValue("text-01-negative").expectedIntentVerdict).isEqualTo(false)
    assertThat(textOutcomes.getValue("text-01-negative").observedCheckVerdict).isEqualTo(false)
    assertThat(textOutcomes.getValue("text-01-adversarial-resource-id").expectedIntentVerdict).isEqualTo(false)
    assertThat(textOutcomes.getValue("text-01-adversarial-resource-id").observedCheckVerdict).isEqualTo(true)

    val focusCase = byId.getValue("focus-04")
    val focusOutcomes = AssertionPlanningResearch.evaluateSynthetic(
      focusCase,
      AssertionPlanningResearch.rulesPlan(focusCase),
    ).associateBy { it.sampleId }
    assertThat(focusOutcomes.getValue("focus-04-positive").observedCheckVerdict).isEqualTo(true)
    assertThat(focusOutcomes.getValue("focus-04-negative").observedCheckVerdict).isEqualTo(false)
    assertThat(focusOutcomes.getValue("focus-04-adversarial-no-focus").observedCheckVerdict).isEqualTo(false)
    assertThat(focusOutcomes.getValue("focus-04-positive").target).isEqualTo("Movie Title")
    assertThat(focusOutcomes.getValue("focus-04-positive").originalExpectation.intent)
      .isEqualTo(focusCase.originalIntent)

    for ((caseId, description) in listOf(
      "text-02" to "Detail page shows title",
      "visual-01" to "Backdrop image loads",
    )) {
      val case = byId.getValue(caseId)
      val current = AssertionPlanningResearch.currentPlan(case).checks.single()
      val rules = AssertionPlanningResearch.rulesPlan(case).checks.single()
      assertThat(current.target == null).isEqualTo(true)
      assertThat(current.originalExpectation.parsedDescription).isEqualTo(description)
      assertThat(rules.target == null).isEqualTo(true)
      val outcomes = AssertionPlanningResearch.evaluateSynthetic(case, AssertionPlanningResearch.currentPlan(case))
      assertThat(outcomes.all { it.observedCheckVerdict == null }).isEqualTo(true)
    }
  }

  @Test
  fun `request envelope preserves all reviewed ids and bypass counts without model hashes`() {
    val corpusPath = corpusPath()
    val corpus = AssertionPlanningResearch.loadCorpus(corpusPath)
    val envelope = AssertionPlanningResearch.buildRequestEnvelope(corpusPath)

    assertThat(envelope.caseCount).isEqualTo(40)
    assertThat(envelope.cases.size).isEqualTo(40)
    assertThat(envelope.cases.map { it.caseId }).isEqualTo(corpus.cases.map { it.id })
    assertThat(envelope.cases.map { it.caseId }.distinct().size).isEqualTo(40)
    assertThat(envelope.cases.count { it.authorityBypass }).isEqualTo(8)
    assertThat(envelope.cases.count { !it.authorityBypass }).isEqualTo(32)
    assertThat(envelope.promptHashStatus).isEqualTo("unavailable_until_t2")
    assertThat(envelope.schemaHashStatus).isEqualTo("unavailable_until_t2")
    assertThat(envelope.contextSha256.keys).isEqualTo(setOf("context/01-app.md", "context/02-controls.markdown"))
    assertThat(envelope.implementationSha256.keys).isEqualTo(
      setOf(
        "verity/core/build.gradle.kts",
        "verity/core/src/test/kotlin/me/chrisbanes/verity/core/research/AssertionPlanningResearch.kt",
      ),
    )
    assertThat(envelope.implementationSha256.values.all { it.length == 64 }).isEqualTo(true)
    assertThat(
      envelope.cases.all { request ->
        Json.parseToJsonElement(Json.encodeToString(request.modelInput)).jsonObject.keys ==
          setOf("rawStep", "journeySteps", "platform", "projectContext")
      },
    ).isEqualTo(true)
    assertThat(envelope.corpusSha256.length).isEqualTo(64)
  }

  private fun corpusPath(): Path {
    var directory = Path.of("").toAbsolutePath()
    while (!Files.isRegularFile(directory.resolve("research/assertion-planning/corpus.json"))) {
      directory = directory.parent ?: error("Could not locate the repository corpus")
    }
    return directory.resolve("research/assertion-planning/corpus.json")
  }
}
