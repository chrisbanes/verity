package me.chrisbanes.verity.core.research

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import kotlin.test.Test
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.chrisbanes.verity.core.model.AssertMode

class AssertionPlanningResearchTest {

  @Test
  fun `current baseline keeps the complete parser description as its target`() {
    val corpus = AssertionPlanningResearch.loadCorpus(corpusPath())
    val textCase = corpus.cases.single { it.id == "text-03" }

    val check = AssertionPlanningResearch.currentPlan(textCase).check

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
      "text-01" to AssertMode.VISIBLE,
      "text-03" to AssertMode.VISIBLE,
      "visual-01" to AssertMode.VISUAL,
      "negation-04" to AssertMode.VISUAL,
    )

    for ((caseId, expectedMode) in expectedModes) {
      val check = AssertionPlanningResearch.currentPlan(byId.getValue(caseId)).check
      assertThat(check.mode).isEqualTo(expectedMode)
    }

    val inferredHome = AssertionPlanningResearch.currentPlan(byId.getValue("text-01")).check
    assertThat(inferredHome.originalExpectation.parsedDescription).isEqualTo("Home")
    assertThat(inferredHome.target).isEqualTo("Home")
    assertThat(byId.getValue("text-01").provenance.fixedStrategy).isEqualTo(null)
    val authoredVisualConflict = AssertionPlanningResearch.currentPlan(byId.getValue("authority-04")).check
    assertThat(authoredVisualConflict.target == null).isEqualTo(true)
    assertThat(authoredVisualConflict.originalExpectation.parsedDescription)
      .isEqualTo("Backdrop image loads")
    val inferredVisual = AssertionPlanningResearch.currentPlan(byId.getValue("visual-01")).check
    assertThat(inferredVisual.target).isEqualTo(null)
    assertThat(inferredVisual.originalExpectation.parsedDescription).isEqualTo("Backdrop image loads")
    assertThat(AssertionPlanningResearch.currentPlan(byId.getValue("negation-04")).check.mode)
      .isEqualTo(AssertMode.VISUAL)
    val inferredVisible = AssertionPlanningResearch.currentPlan(byId.getValue("text-03")).check
    assertThat(inferredVisible.target).isEqualTo("Internet is visible")
  }

  @Test
  fun `host rejects missing and unsupported commands before filesystem work`() {
    assertFailure { main(emptyArray()) }.isInstanceOf<IllegalArgumentException>()
    assertFailure { main(arrayOf("compare", "ignored", "ignored")) }
      .isInstanceOf<IllegalArgumentException>()
    assertFailure { main(arrayOf("evaluate", "missing-corpus.json")) }
      .isInstanceOf<IllegalArgumentException>()
  }

  @Test
  fun `strict response validation retains literal response fields and rejects extra properties`() {
    val (proposal, validity) = AssertionPlanningResearch.parsePlannerResponse(
      """{"mode":"visible","target":"Home","confidence":0.9,"uncertain":false,"reason":"preserves literal label"}""",
    )
    assertThat(validity).isEqualTo(ResponseValidity.VALID)
    assertThat(proposal?.mode).isEqualTo(AssertMode.VISIBLE)
    assertThat(proposal?.target).isEqualTo("Home")
    assertThat(proposal?.confidence).isEqualTo(0.9)
    assertThat(proposal?.uncertain).isEqualTo(false)
    assertThat(proposal?.reason).isEqualTo("preserves literal label")

    val (invalid, invalidity) = AssertionPlanningResearch.parsePlannerResponse(
      """{"mode":"visible","target":"Home","confidence":0.9,"uncertain":false,"reason":"ok","extra":true}""",
    )
    assertThat(invalid).isEqualTo(null)
    assertThat(invalidity).isEqualTo(ResponseValidity.WRONG_SHAPE)
  }

  @Test
  fun `strict response validation rejects duplicate top-level fields`() {
    val duplicate = """{"mode":"visible","mode":"visible","target":"Home","confidence":0.9,"uncertain":false,"reason":"ok"}"""
    val escapedDuplicate = """{"mode":"visible","m\u006fde":"visible","target":"Home","confidence":0.9,"uncertain":false,"reason":"ok"}"""

    assertThat(AssertionPlanningResearch.parsePlannerResponse(duplicate).second)
      .isEqualTo(ResponseValidity.WRONG_SHAPE)
    assertThat(AssertionPlanningResearch.parsePlannerResponse(escapedDuplicate).second)
      .isEqualTo(ResponseValidity.WRONG_SHAPE)
  }

  @Test
  fun `strict response contract enforces all mode shapes types and bounds`() {
    val validModes = listOf(
      responseJson("visible", "Home"),
      responseJson("focused", "Home"),
      responseJson("tree", null),
      responseJson("visual", null),
    )
    validModes.forEach { response ->
      assertThat(AssertionPlanningResearch.parsePlannerResponse(response).second).isEqualTo(ResponseValidity.VALID)
    }

    val invalidValues = listOf(
      "" to ResponseValidity.EMPTY,
      "  \n" to ResponseValidity.EMPTY,
      "{" to ResponseValidity.INVALID_JSON,
      "[]" to ResponseValidity.INVALID_JSON,
      """{"mode":"visible","confidence":0.9,"uncertain":false,"reason":"missing target"}""" to ResponseValidity.WRONG_SHAPE,
      responseJson("unknown", "Home") to ResponseValidity.INVALID_VALUE,
      responseJson("visible", null) to ResponseValidity.INVALID_VALUE,
      responseJson("focused", " ") to ResponseValidity.INVALID_VALUE,
      responseJson("visible", "\u00a0") to ResponseValidity.INVALID_VALUE,
      """{"mode":"visible","target":"\u001c","confidence":0.9,"uncertain":false,"reason":"ok"}""" to ResponseValidity.INVALID_VALUE,
      """{"mode":"tree","target":null,"confidence":0.9,"uncertain":false,"reason":" \t\u00a0"}""" to ResponseValidity.INVALID_VALUE,
      """{"mode":"tree","target":null,"confidence":0.9,"uncertain":false,"reason":"\u001c"}""" to ResponseValidity.INVALID_VALUE,
      """{"mode":"visible","target":7,"confidence":0.9,"uncertain":false,"reason":"bad target"}""" to ResponseValidity.INVALID_VALUE,
      """{"mode":"tree","target":"Home","confidence":0.9,"uncertain":false,"reason":"bad tree target"}""" to ResponseValidity.INVALID_VALUE,
      responseJson("visual", "Backdrop") to ResponseValidity.INVALID_VALUE,
      """{"mode":"visible","target":"Home","confidence":"0.9","uncertain":false,"reason":"bad confidence type"}""" to ResponseValidity.INVALID_VALUE,
      responseJson("tree", null, confidence = "-0.01") to ResponseValidity.INVALID_VALUE,
      responseJson("tree", null, confidence = "1.01") to ResponseValidity.INVALID_VALUE,
      responseJson("tree", null, confidence = "1e9999") to ResponseValidity.INVALID_VALUE,
      """{"mode":"tree","target":null,"confidence":0.9,"uncertain":"false","reason":"bad uncertainty type"}""" to ResponseValidity.INVALID_VALUE,
      """{"mode":"tree","target":null,"confidence":0.9,"uncertain":false,"reason":1}""" to ResponseValidity.INVALID_VALUE,
      responseJson("tree", null, reason = "r".repeat(513)) to ResponseValidity.INVALID_VALUE,
    )
    invalidValues.forEach { (response, expected) ->
      assertThat(AssertionPlanningResearch.parsePlannerResponse(response).second).isEqualTo(expected)
    }
    assertThat(AssertionPlanningResearch.parsePlannerResponse(responseJson("visible", "H".repeat(257))).second)
      .isEqualTo(ResponseValidity.INVALID_VALUE)
    assertThat(AssertionPlanningResearch.parsePlannerResponse("x".repeat(16 * 1024 + 1)).second)
      .isEqualTo(ResponseValidity.OVERSIZED)
    val escapedContent = """{"mode":"visible","target":"Home","confidence":0.9,"uncertain":false,"reason":"literal } and , and \"mode\" text"}"""
    assertThat(AssertionPlanningResearch.parsePlannerResponse(escapedContent).second).isEqualTo(ResponseValidity.VALID)
    assertThat(AssertionPlanningResearch.parsePlannerResponse(responseJson("visible", "\ufeff")).second)
      .isEqualTo(ResponseValidity.VALID)
  }

  @Test
  fun `runtime guard rejects presence reductions using only parsed description and mode`() {
    val corpus = AssertionPlanningResearch.loadCorpus(corpusPath())
    val ordinary = AssertionPlanningResearch.currentPlan(corpus.cases.single { it.id == "text-01" }).check.originalExpectation
    val positive = PlannerResponse(AssertMode.VISIBLE, "Home", 0.9, false, "literal")
    assertThat(AssertionPlanningResearch.structuralGuard(ordinary.parsedDescription, ordinary.parsedMode, positive))
      .isEqualTo(StructuralGuardResult.ACCEPTED)

    val negation = AssertionPlanningResearch.currentPlan(corpus.cases.single { it.id == "negation-01" }).check.originalExpectation
    val compound = AssertionPlanningResearch.currentPlan(corpus.cases.single { it.id == "compound-01" }).check.originalExpectation
    val visual = AssertionPlanningResearch.currentPlan(corpus.cases.single { it.id == "visual-01" }).check.originalExpectation
    assertThat(AssertionPlanningResearch.structuralGuard(negation.parsedDescription, negation.parsedMode, positive))
      .isEqualTo(StructuralGuardResult.REJECT_NEGATION)
    assertThat(AssertionPlanningResearch.structuralGuard(compound.parsedDescription, compound.parsedMode, positive.copy(mode = AssertMode.FOCUSED)))
      .isEqualTo(StructuralGuardResult.REJECT_COMPOUND)
    val conditional = AssertionPlanningResearch.currentPlan(
      corpus.cases.single { it.id == "text-01" }.copy(rawStep = "[?] If Connected Home is visible"),
    ).check.originalExpectation
    assertThat(AssertionPlanningResearch.structuralGuard(conditional.parsedDescription, conditional.parsedMode, positive))
      .isEqualTo(StructuralGuardResult.REJECT_CONDITION)
    assertThat(AssertionPlanningResearch.structuralGuard(visual.parsedDescription, visual.parsedMode, positive))
      .isEqualTo(StructuralGuardResult.REJECT_VISUAL_PROPERTY)
    assertThat(AssertionPlanningResearch.structuralGuard(visual.parsedDescription, visual.parsedMode, positive.copy(mode = AssertMode.VISUAL, target = null)))
      .isEqualTo(StructuralGuardResult.ACCEPTED)
  }

  @Test
  fun `evaluator separates genuine semantic weakening from guard rejection and literal errors`() {
    val corpus = AssertionPlanningResearch.loadCorpus(corpusPath())
    val focus = corpus.cases.single { it.id == "focus-01" }
    val negation = corpus.cases.single { it.id == "negation-01" }
    val compound = corpus.cases.single { it.id == "compound-01" }
    val visual = corpus.cases.single { it.id == "visual-01" }
    val ordinaryLiteralMismatch = corpus.cases.single { it.id == "text-03" }

    assertThat(AssertionPlanningResearch.semanticWeakening(focus, AssertMode.VISIBLE, "Home")).isEqualTo(true)
    assertThat(AssertionPlanningResearch.semanticWeakening(negation, AssertMode.VISIBLE, "Home")).isEqualTo(true)
    assertThat(AssertionPlanningResearch.semanticWeakening(negation, AssertMode.TREE, null)).isEqualTo(false)
    assertThat(AssertionPlanningResearch.semanticWeakening(compound, AssertMode.VISIBLE, "Home")).isEqualTo(true)
    assertThat(AssertionPlanningResearch.semanticWeakening(visual, AssertMode.VISIBLE, "Home")).isEqualTo(true)
    val conditional = compound.copy(rawStep = "[?] If the Home row is visible")
    assertThat(AssertionPlanningResearch.semanticWeakening(conditional, AssertMode.VISIBLE, "Home")).isEqualTo(true)
    assertThat(AssertionPlanningResearch.semanticWeakening(ordinaryLiteralMismatch, AssertMode.VISIBLE, "Home"))
      .isEqualTo(false)

    val guardedButAllowed = compound.copy(
      allowedChecks = listOf(ResearchCheck("visible", "Title", "the exact independent check preserves this test intent")),
    )
    val parsed = AssertionPlanningResearch.currentPlan(guardedButAllowed).check.originalExpectation
    val visibleTitle = PlannerResponse(AssertMode.VISIBLE, "Title", 0.9, false, "literal title")
    assertThat(AssertionPlanningResearch.structuralGuard(parsed.parsedDescription, parsed.parsedMode, visibleTitle))
      .isEqualTo(StructuralGuardResult.REJECT_COMPOUND)
    assertThat(AssertionPlanningResearch.semanticWeakening(guardedButAllowed, AssertMode.VISIBLE, "Title"))
      .isEqualTo(false)
  }

  @Test
  fun `planner retains raw guarded uncertain and low confidence proposals while using baseline fallback`() = runTest {
    val corpusPath = corpusPath()
    val corpus = AssertionPlanningResearch.loadCorpus(corpusPath)
    val case = corpus.cases.single { it.id == "negation-01" }
    val baseline = AssertionPlanningResearch.currentPlan(case).check
    val guardRejected = AssertionPlanningResearch.planFromResponse(case, responseJson("visible", "Home"))
    assertThat(guardRejected.proposal?.target).isEqualTo("Home")
    assertThat(guardRejected.guardResult).isEqualTo(StructuralGuardResult.REJECT_NEGATION)
    assertThat(guardRejected.fallbackReason).isEqualTo(PlannerFallbackReason.STRUCTURAL_GUARD)
    assertThat(guardRejected.effectiveCheck).isEqualTo(baseline)

    val ordinary = corpus.cases.single { it.id == "text-01" }
    val uncertain = AssertionPlanningResearch.planFromResponse(
      ordinary,
      responseJson("visible", "Home", uncertain = "true"),
    )
    assertThat(uncertain.proposal?.target).isEqualTo("Home")
    assertThat(uncertain.fallbackReason).isEqualTo(PlannerFallbackReason.UNCERTAIN)
    assertThat(uncertain.effectiveCheck).isEqualTo(AssertionPlanningResearch.currentPlan(ordinary).check)

    val lowConfidence = AssertionPlanningResearch.planFromResponse(
      ordinary,
      responseJson("visible", "Home", confidence = "0.49"),
      threshold = 0.5,
    )
    assertThat(lowConfidence.proposal?.confidence).isEqualTo(0.49)
    assertThat(lowConfidence.fallbackReason).isEqualTo(PlannerFallbackReason.BELOW_THRESHOLD)
    assertThat(lowConfidence.effectiveCheck).isEqualTo(AssertionPlanningResearch.currentPlan(ordinary).check)
  }

  @Test
  fun `supplier failure and owned timeout fall back while caller cancellation identity propagates`() = runTest {
    val corpusPath = corpusPath()
    val corpus = AssertionPlanningResearch.loadCorpus(corpusPath)
    val case = corpus.cases.single { it.id == "text-01" }
    val failed = AssertionPlanningResearch.planWithSupplier(case, corpusPath) {
      error("provider detail must not be retained")
    }
    assertThat(failed.safeFailureKind).isEqualTo(SafeFailureKind.TRANSPORT)
    assertThat(failed.fallbackReason).isEqualTo(PlannerFallbackReason.SAFE_FAILURE)
    assertThat(failed.effectiveCheck).isEqualTo(AssertionPlanningResearch.currentPlan(case).check)

    val timedOut = AssertionPlanningResearch.planWithSupplier(case, corpusPath, timeoutMillis = 10) {
      delay(100)
      responseJson("visible", "Home")
    }
    assertThat(timedOut.safeFailureKind).isEqualTo(SafeFailureKind.TIMEOUT)
    assertThat(timedOut.fallbackReason).isEqualTo(PlannerFallbackReason.TIMEOUT)

    val explicitCancellation = CancellationException("caller owns cancellation")
    val observed = try {
      AssertionPlanningResearch.planWithSupplier(case, corpusPath) { throw explicitCancellation }
      null
    } catch (error: CancellationException) {
      error
    }
    assertThat(observed === explicitCancellation).isEqualTo(true)

    val outerTimedOut = try {
      kotlinx.coroutines.withTimeout(1) {
        AssertionPlanningResearch.planWithSupplier(case, corpusPath, timeoutMillis = 100) {
          delay(1000)
          responseJson("visible", "Home")
        }
      }
      false
    } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
      true
    }
    assertThat(outerTimedOut).isEqualTo(true)

    var calls = 0
    val bypass = corpus.cases.single { it.id == "authority-01" }
    val bypassPlan = AssertionPlanningResearch.planWithSupplier(bypass, corpusPath) {
      calls++
      responseJson("visible", "Home")
    }
    assertThat(calls).isEqualTo(0)
    assertThat(bypassPlan.effectiveCheck).isEqualTo(AssertionPlanningResearch.currentPlan(bypass).check)
  }

  private fun responseJson(
    mode: String,
    target: String?,
    confidence: String = "0.9",
    uncertain: String = "false",
    reason: String = "ok",
  ): String = """{"mode":"$mode","target":${target?.let { "\"$it\"" } ?: "null"},"confidence":$confidence,"uncertain":$uncertain,"reason":"$reason"}"""

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

    val textCheck = AssertionPlanningResearch.rulesPlan(byId.getValue("text-03")).check
    assertThat(textCheck.mode).isEqualTo(AssertMode.VISIBLE)
    assertThat(textCheck.target).isEqualTo("Internet")
    assertThat(textCheck.originalExpectation.intent).isEqualTo("The Internet row is displayed.")

    val focusCheck = AssertionPlanningResearch.rulesPlan(byId.getValue("focus-06")).check
    assertThat(focusCheck.mode).isEqualTo(AssertMode.FOCUSED)
    assertThat(focusCheck.target).isEqualTo("Name")

    val displayedCheck = AssertionPlanningResearch.rulesPlan(
      byId.getValue("text-03").copy(rawStep = "Verify Home is displayed"),
    ).check
    assertThat(displayedCheck.mode).isEqualTo(AssertMode.VISIBLE)
    assertThat(displayedCheck.target).isEqualTo("Home")

    val conditionalSeed = byId.getValue("text-03")
    for (step in listOf(
      "[?] If Connected Home is visible",
      "[?] When Connected Home is visible",
      "[?] Unless Connected Home is visible",
    )) {
      val conditionalCase = conditionalSeed.copy(rawStep = step)
      val current = AssertionPlanningResearch.currentPlan(conditionalCase).check
      val rules = AssertionPlanningResearch.rulesPlan(conditionalCase).check
      assertThat(rules.mode).isEqualTo(current.mode)
      assertThat(rules.target).isEqualTo(current.target)
    }

    for (caseId in listOf("negation-01", "compound-01", "ambiguous-01", "visual-01", "text-02")) {
      val case = byId.getValue(caseId)
      val current = AssertionPlanningResearch.currentPlan(case).check
      val rules = AssertionPlanningResearch.rulesPlan(case).check
      assertThat(rules.mode).isEqualTo(current.mode)
      assertThat(rules.target).isEqualTo(current.target)
    }

    for (case in corpus.cases.filter(AssertionPlanningResearch::isAuthorityBypass)) {
      val current = AssertionPlanningResearch.currentPlan(case).check
      val rules = AssertionPlanningResearch.rulesPlan(case).check
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
  fun `evidence availability gates checks without hiding label-only false passes`() {
    val corpus = AssertionPlanningResearch.loadCorpus(corpusPath())
    val negationCase = corpus.cases.single { it.id == "negation-01" }
    val presentHome = negationCase.evidence.samples.single { it.id == "negation-01-present" }
    val wrongVisibleProposal = AssertionPlan(
      caseId = negationCase.id,
      check = PlannedCheck(
        AssertMode.VISIBLE,
        "Home",
        AssertionPlanningResearch.currentPlan(negationCase).check.originalExpectation,
      ),
    )
    val falsePass = AssertionPlanningResearch.evaluateSynthetic(negationCase, wrongVisibleProposal)
      .single { it.sampleId == "negation-01-present" }
    assertThat(negationCase.evidence.verdictCapability).isEqualTo("independent_intent_labels_only")
    assertThat(presentHome.expectedIntentVerdict).isEqualTo(false)
    assertThat(falsePass.expectedIntentVerdict).isEqualTo(false)
    assertThat(falsePass.observedCheckVerdict).isEqualTo(true)

    val visualCase = corpus.cases.single { it.id == "visual-01" }
    val hierarchySample = ResearchEvidenceSample(
      id = "visual-unavailable-with-tree",
      kind = "guard-boundary",
      expectedIntentVerdict = false,
      rationale = "A supplied hierarchy still cannot make unavailable visual evidence evaluable.",
      tree = ResearchTree(attributes = mapOf("text" to "Title")),
    )
    val unavailablePlan = AssertionPlan(
      caseId = visualCase.id,
      check = PlannedCheck(
        AssertMode.VISIBLE,
        "Title",
        AssertionPlanningResearch.currentPlan(visualCase).check.originalExpectation,
      ),
    )
    val noCapabilityCase = visualCase.copy(
      evidence = visualCase.evidence.copy(
        availability = "hierarchy",
        samples = listOf(hierarchySample),
      ),
    )
    assertThat(noCapabilityCase.evidence.verdictCapability).isEqualTo("none")
    assertThat(AssertionPlanningResearch.evaluateSynthetic(noCapabilityCase, unavailablePlan).single().observedCheckVerdict)
      .isEqualTo(null)

    val unavailableCase = visualCase.copy(
      evidence = visualCase.evidence.copy(
        verdictCapability = "visible_text_helper",
        samples = listOf(hierarchySample),
      ),
    )
    assertThat(unavailableCase.evidence.availability).isEqualTo("visual_unavailable")
    assertThat(AssertionPlanningResearch.evaluateSynthetic(unavailableCase, unavailablePlan).single().observedCheckVerdict)
      .isEqualTo(null)
  }

  @Test
  fun `request export preserves existing parent permissions and writes a private file`() {
    val parent = Files.createTempDirectory("verity-issue59-export")
    val output = parent.resolve("requests.json")
    val expectedPermissions = setOf(
      PosixFilePermission.OWNER_READ,
      PosixFilePermission.OWNER_WRITE,
      PosixFilePermission.OWNER_EXECUTE,
      PosixFilePermission.GROUP_READ,
      PosixFilePermission.GROUP_EXECUTE,
    )
    try {
      try {
        Files.setPosixFilePermissions(parent, expectedPermissions)
      } catch (_: UnsupportedOperationException) {
        return
      }
      AssertionPlanningResearch.exportRequests(corpusPath(), output)
      assertThat(Files.getPosixFilePermissions(parent)).isEqualTo(expectedPermissions)
      assertThat(Files.getPosixFilePermissions(output)).isEqualTo(
        setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
      )
    } finally {
      Files.deleteIfExists(output)
      Files.deleteIfExists(parent)
    }
  }

  @Test
  fun `request export refuses to overwrite its corpus input`() {
    val root = Files.createTempDirectory("verity-corpus-overwrite")
    val corpusDirectory = root.resolve("research/assertion-planning")
    val contextDirectory = corpusDirectory.resolve("context")
    val implementationDirectory = root.resolve(
      "verity/core/src/test/kotlin/me/chrisbanes/verity/core/research",
    )
    Files.createDirectories(contextDirectory)
    Files.createDirectories(implementationDirectory)
    val corpus = corpusDirectory.resolve("corpus.json")
    val sourceCorpus = corpusPath()
    Files.copy(sourceCorpus, corpus)
    Files.copy(sourceCorpus.parent.resolve("context/01-app.md"), contextDirectory.resolve("01-app.md"))
    Files.copy(
      sourceCorpus.parent.resolve("context/02-controls.markdown"),
      contextDirectory.resolve("02-controls.markdown"),
    )
    Files.writeString(root.resolve("verity/core/build.gradle.kts"), "test fixture")
    Files.writeString(implementationDirectory.resolve("AssertionPlanningResearch.kt"), "test fixture")
    val originalBytes = Files.readAllBytes(corpus)

    try {
      assertFailure {
        AssertionPlanningResearch.exportRequests(corpus, corpus)
      }.isInstanceOf<IllegalArgumentException>()

      assertThat(Files.readAllBytes(corpus).contentEquals(originalBytes)).isEqualTo(true)
      val alias = corpus.resolveSibling("corpus-hard-link.json")
      try {
        Files.createLink(alias, corpus)
      } catch (_: UnsupportedOperationException) {
        return
      }
      assertFailure {
        AssertionPlanningResearch.exportRequests(corpus, alias)
      }.isInstanceOf<IllegalArgumentException>()
      assertThat(Files.readAllBytes(corpus).contentEquals(originalBytes)).isEqualTo(true)
    } finally {
      root.toFile().deleteRecursively()
    }
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

    for (caseId in listOf("focus-01", "focus-03", "focus-04", "focus-05")) {
      val case = byId.getValue(caseId)
      val positive = AssertionPlanningResearch.evaluateSynthetic(
        case,
        AssertionPlanningResearch.rulesPlan(case),
      ).single { it.expectedIntentVerdict }
      assertThat(positive.observedCheckVerdict).isEqualTo(true)
    }

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
      val current = AssertionPlanningResearch.currentPlan(case).check
      val rules = AssertionPlanningResearch.rulesPlan(case).check
      assertThat(current.target == null).isEqualTo(true)
      assertThat(current.originalExpectation.parsedDescription).isEqualTo(description)
      assertThat(rules.target == null).isEqualTo(true)
      val outcomes = AssertionPlanningResearch.evaluateSynthetic(case, AssertionPlanningResearch.currentPlan(case))
      assertThat(outcomes.all { it.observedCheckVerdict == null }).isEqualTo(true)
    }
  }

  @Test
  fun `request envelope preserves all reviewed ids and bypass counts`() {
    val corpusPath = corpusPath()
    val corpus = AssertionPlanningResearch.loadCorpus(corpusPath)
    val envelope = AssertionPlanningResearch.buildRequestEnvelope(corpusPath)

    assertThat(envelope.caseCount).isEqualTo(40)
    assertThat(envelope.cases.size).isEqualTo(40)
    assertThat(envelope.cases.map { it.caseId }).isEqualTo(corpus.cases.map { it.id })
    assertThat(envelope.cases.map { it.caseId }.distinct().size).isEqualTo(40)
    assertThat(envelope.cases.count { it.authorityBypass }).isEqualTo(8)
    assertThat(envelope.cases.count { !it.authorityBypass }).isEqualTo(32)
    assertThat(envelope.promptSha256.length).isEqualTo(64)
    assertThat(envelope.schemaSha256.length).isEqualTo(64)
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

  @Test
  fun `request envelope binds exact prompt and strict schema bytes`() {
    val corpusPath = corpusPath()
    val envelope = AssertionPlanningResearch.buildRequestEnvelope(corpusPath)
    val researchDirectory = corpusPath.parent

    assertThat(envelope.promptSha256).isEqualTo(sha256(Files.readAllBytes(researchDirectory.resolve("prompt.txt"))))
    val schemaBytes = Files.readAllBytes(researchDirectory.resolve("planner.schema.json"))
    assertThat(envelope.schemaSha256).isEqualTo(sha256(schemaBytes))
    val schema = Json.parseToJsonElement(schemaBytes.toString(Charsets.UTF_8)).jsonObject
    assertThat(
      schema.getValue("\$defs").jsonObject.getValue("nonBlankString").jsonObject.getValue("pattern").jsonPrimitive.content,
    ).isEqualTo("[^\\t\\n\\u000B\\f\\r\\u001C-\\u001F\\u0020\\u0085\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000]")
  }

  @Test
  fun `saved output is complete only with exact bindings attempts and unique implicit cases`() {
    val request = AssertionPlanningResearch.buildRequestEnvelope(corpusPath())
    val requestBytes = Json.encodeToString(request).toByteArray()
    val requestDigest = sha256(requestBytes)
    val output = completeSavedOutput(request, requestDigest)

    assertThat(AssertionPlanningResearch.validateSavedOutput(output, request, requestDigest)).isEqualTo(true)
    assertThat(
      AssertionPlanningResearch.validateSavedOutput(
        output.copy(cases = output.cases.dropLast(1) + output.cases.first()),
        request,
        requestDigest,
      ),
    ).isEqualTo(false)
    assertThat(
      AssertionPlanningResearch.validateSavedOutput(
        output.copy(bindings = output.bindings.copy(promptSha256 = "0".repeat(64))),
        request,
        requestDigest,
      ),
    ).isEqualTo(false)
    val responseCorruption = output.copy(
      cases = output.cases.mapIndexed { index, case ->
        if (index == 0) case.copy(responseText = "different response") else case
      },
    )
    assertThat(AssertionPlanningResearch.validateSavedOutput(responseCorruption, request, requestDigest)).isEqualTo(false)
    val failedAttemptWithNegativeTokens = output.copy(
      cases = output.cases.map { case ->
        if (case.caseId == "text-01") {
          case.copy(
            status = SavedCaseStatus.FAILURE,
            responseText = null,
            storedTextSha256 = null,
            redactionStatus = null,
            failureKind = SafeFailureKind.TRANSPORT,
            tokenUsage = SavedTokenUsage(inputTokens = -1),
          )
        } else {
          case
        }
      },
    )
    assertThat(
      AssertionPlanningResearch.validateSavedOutput(failedAttemptWithNegativeTokens, request, requestDigest),
    ).isEqualTo(false)
  }

  @Test
  fun `saved output scoring separates literal mismatch weakening and unavailable verdicts`() {
    val corpusPath = corpusPath()
    val request = AssertionPlanningResearch.buildRequestEnvelope(corpusPath)
    val requestBytes = Json.encodeToString(request).toByteArray()
    val requestDigest = sha256(requestBytes)
    val output = completeSavedOutput(request, requestDigest)
    val outputBytes = Json.encodeToString(output).toByteArray()
    val outputDigest = sha256(outputBytes)

    val evaluation = AssertionPlanningResearch.evaluateSavedOutput(
      corpusPath = corpusPath,
      request = request,
      requestBytes = requestBytes,
      output = output,
      outputBytes = outputBytes,
    )

    assertThat(evaluation.studyComplete).isEqualTo(true)
    assertThat(evaluation.rows.size).isEqualTo(120)
    val modelText = evaluation.rows.single { it.caseId == "text-03" && it.arm == EvaluationArm.MODEL }
    assertThat(modelText.rawSemanticError).isEqualTo(true)
    assertThat(modelText.effectiveSemanticError).isEqualTo(true)
    assertThat(modelText.effectiveModeMismatch).isEqualTo(false)
    assertThat(modelText.effectiveTargetMismatch).isEqualTo(true)
    assertThat(modelText.semanticWeakening).isEqualTo(false)
    assertThat(modelText.verdicts.evaluated).isEqualTo(3)
    assertThat(modelText.verdicts.unavailable).isEqualTo(0)
    val caseOnlyDifference = evaluation.rows.single { it.caseId == "text-01" && it.arm == EvaluationArm.MODEL }
    assertThat(caseOnlyDifference.effectiveSemanticError).isEqualTo(false)
    assertThat(caseOnlyDifference.effectiveTargetMismatch).isEqualTo(false)
    val negation = evaluation.rows.single { it.caseId == "negation-01" && it.arm == EvaluationArm.MODEL }
    assertThat(negation.guardResult).isEqualTo(StructuralGuardResult.REJECT_NEGATION)
    assertThat(negation.semanticWeakening).isEqualTo(false)
    assertThat(negation.rawSemanticWeakening).isEqualTo(true)
    assertThat(negation.rawVerdicts.falsePasses > 0).isEqualTo(true)
    assertThat(negation.verdicts != negation.rawVerdicts).isEqualTo(true)
    assertThat(evaluation.thresholdReplay.map { it.threshold }).isEqualTo(listOf(0.0, 0.5, 0.8, 0.95, 1.0))
    assertThat(evaluation.thresholdReplay.all { it.rawOutputSha256 == outputDigest }).isEqualTo(true)

    val incomplete = output.copy(
      completed = false,
      stopReason = SavedStopReason.INTERRUPTED,
      counters = output.counters.copy(caseAttempts = 31, unattemptedCases = 1, totalAttempts = 32),
      cases = output.cases.filterNot { it.caseId == "text-01" },
    )
    val incompleteBytes = Json.encodeToString(incomplete).toByteArray()
    val incompleteEvaluation = AssertionPlanningResearch.evaluateSavedOutput(
      corpusPath = corpusPath,
      request = request,
      requestBytes = requestBytes,
      output = incomplete,
      outputBytes = incompleteBytes,
    )
    assertThat(incompleteEvaluation.studyComplete).isEqualTo(false)
    assertThat(incompleteEvaluation.summaries.single { it.arm == EvaluationArm.MODEL }.unattemptedCases).isEqualTo(1)
  }

  private fun completeSavedOutput(request: ResearchRequestEnvelope, requestDigest: String): SavedPlannerOutput {
    val response = responseJson("visible", "Home")
    val responseDigest = sha256(response.toByteArray())
    val implicitCases = request.cases.filterNot { it.authorityBypass }
    val successfulCase = { caseId: String ->
      val caseResponse = if (caseId == "text-01") responseJson("visible", "home") else response
      SavedCaseOutput(
        caseId = caseId,
        status = SavedCaseStatus.SUCCESS,
        responseText = caseResponse,
        storedTextSha256 = sha256(caseResponse.toByteArray()),
        redactionStatus = ResponseRedactionStatus.NOT_REQUIRED,
        setup = SavedPhaseTiming(durationMillis = 1),
        turn = SavedPhaseTiming(durationMillis = 1),
        cleanup = SavedPhaseTiming(durationMillis = 1),
        tokenUsage = SavedTokenUsage(),
      )
    }
    return SavedPlannerOutput(
      formatVersion = 1,
      bindings = SavedOutputInputBindings(
        requestSha256 = requestDigest,
        corpusSha256 = request.corpusSha256,
        contextSha256 = request.contextSha256,
        hostSha256 = request.implementationSha256,
        promptSha256 = request.promptSha256,
        schemaSha256 = request.schemaSha256,
      ),
      runMetadata = SavedOutputRunMetadata(
        model = "offline-fixture",
        effort = "high",
        tier = "Luna",
        cliVersion = "offline-fixture",
        globalInstructionSources = emptyList(),
      ),
      counters = SavedOutputCounters(33, 1, 32, 8, 0),
      completed = true,
      stopReason = SavedStopReason.COMPLETE,
      cleanupVerified = true,
      qualification = SavedQualificationOutput(
        status = SavedCaseStatus.SUCCESS,
        responseText = response,
        storedTextSha256 = responseDigest,
        redactionStatus = ResponseRedactionStatus.NOT_REQUIRED,
        setup = SavedPhaseTiming(durationMillis = 1),
        turn = SavedPhaseTiming(durationMillis = 1),
        cleanup = SavedPhaseTiming(durationMillis = 1),
        tokenUsage = SavedTokenUsage(),
      ),
      cases = request.cases.map { case ->
        if (case.authorityBypass) {
          SavedCaseOutput(
            caseId = case.caseId,
            status = SavedCaseStatus.BYPASS,
            setup = SavedPhaseTiming(),
            turn = SavedPhaseTiming(),
            cleanup = SavedPhaseTiming(),
            tokenUsage = SavedTokenUsage(),
          )
        } else {
          successfulCase(case.caseId)
        }
      },
    )
  }

  private fun corpusPath(): Path {
    var directory = Path.of("").toAbsolutePath()
    while (!Files.isRegularFile(directory.resolve("research/assertion-planning/corpus.json"))) {
      directory = directory.parent ?: error("Could not locate the repository corpus")
    }
    return directory.resolve("research/assertion-planning/corpus.json")
  }

  private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
