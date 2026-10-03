package me.chrisbanes.verity.core.research

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.chrisbanes.verity.core.context.ContextBundle
import me.chrisbanes.verity.core.context.ContextLoader
import me.chrisbanes.verity.core.hierarchy.FocusDetector
import me.chrisbanes.verity.core.hierarchy.HierarchyNode
import me.chrisbanes.verity.core.hierarchy.containsText
import me.chrisbanes.verity.core.model.AssertMode
import me.chrisbanes.verity.core.model.AssertionStrategy
import me.chrisbanes.verity.core.model.JourneyStep
import me.chrisbanes.verity.core.parser.JourneyStepParser

@Serializable
data class ResearchCorpus(
  val corpusId: String,
  val revision: Int,
  val status: String,
  val sourceAuthority: ResearchSourceAuthority,
  val inputProjection: List<String>,
  val goldFieldsExcludedFromInput: List<String>,
  val categoryCounts: Map<String, Int>,
  val authorityCoverage: AuthorityCoverage,
  val platforms: List<String>,
  val contexts: List<String>,
  val cases: List<ResearchCase>,
  val checkSemantics: JsonObject,
)

@Serializable
data class ResearchSourceAuthority(
  val issue: Int,
  val issueBodySha256: String,
  val agentBriefSha256: String,
  val approvedPlanSha256: String,
  val seedFilesReadOnly: List<String>,
  val seedUse: String,
)

@Serializable
data class AuthorityCoverage(
  val authoredModes: Int,
  val fixedStrategies: Int,
  val authoredOverridesFixedConflicts: Int,
  val derivedAuthorityBypassCases: Int,
  val derivedImplicitCases: Int,
  val derivation: String,
  val independentlyFixedModeCases: Int,
  val authoredModeCases: Int,
)

@Serializable
data class ResearchCase(
  val id: String,
  val category: String,
  val sourceSeed: String,
  val rawStep: String,
  val journeySteps: List<String>,
  val platform: String,
  val contextFiles: List<String>,
  val provenance: ResearchProvenance,
  val originalIntent: String,
  val allowedChecks: List<ResearchCheck>,
  val groundTruthRationale: String,
  val evidence: ResearchEvidence,
  val preferredCheapChecks: List<ResearchCheck>,
  val sourceAdaptation: SourceAdaptation? = null,
)

@Serializable
data class ResearchProvenance(
  val kind: String,
  val authoredMode: String? = null,
  val fixedStrategy: String? = null,
  val explicitOverridesFixed: Boolean = false,
)

@Serializable
data class ResearchCheck(
  val mode: String,
  val target: String? = null,
  val rationale: String,
)

@Serializable
data class SourceAdaptation(
  val originalSourceSeed: String,
  val adaptedContext: String,
  val note: String,
)

@Serializable
data class ResearchEvidence(
  val availability: String,
  val verdictCapability: String,
  val samples: List<ResearchEvidenceSample>,
  val ambiguity: String? = null,
  val unavailableFacts: List<String>? = null,
)

@Serializable
data class ResearchEvidenceSample(
  val id: String,
  val kind: String,
  val expectedIntentVerdict: Boolean,
  val rationale: String,
  val tree: ResearchTree? = null,
)

@Serializable
data class ResearchTree(
  val attributes: Map<String, String> = emptyMap(),
  val states: Set<String> = emptySet(),
  val children: List<ResearchTree> = emptyList(),
)

@Serializable
data class PlannerInput(
  val rawStep: String,
  val journeySteps: List<String>,
  val platform: String,
  val projectContext: String,
)

@Serializable
data class ResearchRequestEnvelope(
  val formatVersion: Int,
  val corpusId: String,
  val corpusRevision: Int,
  val caseCount: Int,
  val authorityBypassCaseCount: Int,
  val implicitCaseCount: Int,
  val corpusSha256: String,
  val contextSha256: Map<String, String>,
  val implementationSha256: Map<String, String>,
  val promptSha256: String,
  val schemaSha256: String,
  val cases: List<ResearchRequest>,
)

@Serializable
data class ResearchRequest(
  val caseId: String,
  val authorityBypass: Boolean,
  val modelInput: PlannerInput,
)

@Serializable
data class SavedOutputInputBindings(
  val requestSha256: String,
  val corpusSha256: String,
  val contextSha256: Map<String, String>,
  val hostSha256: Map<String, String>,
  val promptSha256: String,
  val schemaSha256: String,
)

@Serializable
data class SavedOutputRunMetadata(
  val model: String,
  val effort: String,
  val tier: String,
  val cliVersion: String,
  val modelRevision: String? = null,
  val startupDurationMillis: Long? = null,
  val wallDurationMillis: Long? = null,
  val globalInstructionSources: List<GlobalInstructionSourceCount>,
)

@Serializable
data class GlobalInstructionSourceCount(
  val source: GlobalInstructionSource,
  val count: Int,
)

@Serializable
enum class GlobalInstructionSource {
  PROJECT_DOCS,
  USER_GLOBAL,
  DEVELOPER_GLOBAL,
  SKILL_INSTRUCTIONS,
  OTHER,
}

@Serializable
data class SavedOutputCounters(
  val totalAttempts: Int,
  val qualificationAttempts: Int,
  val caseAttempts: Int,
  val bypassCases: Int,
  val unattemptedCases: Int,
)

@Serializable
enum class SavedCaseStatus {
  SUCCESS,
  FAILURE,
  BYPASS,
  UNATTEMPTED,
}

@Serializable
enum class ResponseRedactionStatus {
  NOT_REQUIRED,
  REDACTED,
  REVIEWED,
}

@Serializable
enum class SavedStopReason {
  COMPLETE,
  QUALIFICATION_FAILED,
  TURN_FAILED,
  WALL_LIMIT,
  POISONED,
  INTERRUPTED,
}

@Serializable
data class SavedPhaseTiming(
  val startedAt: String? = null,
  val endedAt: String? = null,
  val durationMillis: Long? = null,
)

@Serializable
data class SavedTokenUsage(
  val inputTokens: Long? = null,
  val outputTokens: Long? = null,
)

@Serializable
data class SavedCaseOutput(
  val caseId: String,
  val status: SavedCaseStatus,
  val responseText: String? = null,
  val storedTextSha256: String? = null,
  val originalResponseSha256: String? = null,
  val redactionStatus: ResponseRedactionStatus? = null,
  val failureKind: SafeFailureKind? = null,
  val setup: SavedPhaseTiming,
  val turn: SavedPhaseTiming,
  val cleanup: SavedPhaseTiming,
  val tokenUsage: SavedTokenUsage,
)

@Serializable
data class SavedQualificationOutput(
  val status: SavedCaseStatus,
  val responseText: String? = null,
  val storedTextSha256: String? = null,
  val originalResponseSha256: String? = null,
  val redactionStatus: ResponseRedactionStatus? = null,
  val failureKind: SafeFailureKind? = null,
  val setup: SavedPhaseTiming,
  val turn: SavedPhaseTiming,
  val cleanup: SavedPhaseTiming,
  val tokenUsage: SavedTokenUsage,
)

@Serializable
data class SavedPlannerOutput(
  val formatVersion: Int,
  val bindings: SavedOutputInputBindings,
  val runMetadata: SavedOutputRunMetadata,
  val counters: SavedOutputCounters,
  val completed: Boolean,
  val stopReason: SavedStopReason,
  val cleanupVerified: Boolean,
  val qualification: SavedQualificationOutput,
  val cases: List<SavedCaseOutput>,
)

@Serializable
data class PlannerResponse(
  val mode: AssertMode,
  val target: String?,
  val confidence: Double,
  val uncertain: Boolean,
  val reason: String,
)

@Serializable
enum class ResponseValidity {
  VALID,
  EMPTY,
  OVERSIZED,
  INVALID_JSON,
  WRONG_SHAPE,
  INVALID_VALUE,
}

@Serializable
enum class StructuralGuardResult {
  ACCEPTED,
  REJECT_NEGATION,
  REJECT_CONDITION,
  REJECT_COMPOUND,
  REJECT_VISUAL_PROPERTY,
}

@Serializable
enum class SafeFailureKind {
  TIMEOUT,
  TRANSPORT,
}

@Serializable
enum class PlannerFallbackReason {
  INVALID_RESPONSE,
  SAFE_FAILURE,
  TIMEOUT,
  UNCERTAIN,
  BELOW_THRESHOLD,
  STRUCTURAL_GUARD,
}

data class PlannerAttemptResult(
  val caseId: String,
  val proposal: PlannerResponse?,
  val responseValidity: ResponseValidity,
  val guardResult: StructuralGuardResult,
  val fallbackReason: PlannerFallbackReason?,
  val safeFailureKind: SafeFailureKind?,
  val effectiveCheck: PlannedCheck,
)

@Serializable
enum class EvaluationArm {
  CURRENT,
  RULES,
  MODEL,
}

@Serializable
data class VerdictMetrics(
  val evaluated: Int,
  val unavailable: Int,
  val falsePasses: Int,
  val falseFailures: Int,
)

@Serializable
data class EvaluationRow(
  val caseId: String,
  val category: String,
  val arm: EvaluationArm,
  val authorityBypass: Boolean,
  val attempted: Boolean,
  val failed: Boolean,
  val unattempted: Boolean,
  val fallback: Boolean,
  val rawProposal: PlannerResponse?,
  val responseValidity: ResponseValidity,
  val guardResult: StructuralGuardResult,
  val fallbackReason: PlannerFallbackReason?,
  val effectiveMode: AssertMode?,
  val effectiveTarget: String?,
  val rawSemanticError: Boolean?,
  val rawModeMismatch: Boolean?,
  val rawTargetMismatch: Boolean?,
  val rawSemanticWeakening: Boolean?,
  val effectiveSemanticError: Boolean?,
  val effectiveModeMismatch: Boolean?,
  val effectiveTargetMismatch: Boolean?,
  val semanticWeakening: Boolean,
  val acceptableCheapCheck: Boolean,
  val rawVerdicts: VerdictMetrics,
  val verdicts: VerdictMetrics,
)

@Serializable
data class ArmSummary(
  val arm: EvaluationArm,
  val allCases: Int,
  val implicitCases: Int,
  val bypassedCases: Int,
  val attemptedCases: Int,
  val failedCases: Int,
  val unattemptedCases: Int,
  val fallbackCases: Int,
  val rawScoredCases: Int,
  val effectiveScoredCases: Int,
  val rawSemanticErrors: Int,
  val effectiveSemanticErrors: Int,
  val rawModeMismatches: Int,
  val rawTargetMismatches: Int,
  val rawSemanticWeakenings: Int,
  val effectiveModeMismatches: Int,
  val effectiveTargetMismatches: Int,
  val semanticWeakenings: Int,
  val acceptableCheapChecks: Int,
  val rawVerdicts: VerdictMetrics,
  val verdicts: VerdictMetrics,
)

@Serializable
data class ThresholdSummary(
  val threshold: Double,
  val rawOutputSha256: String,
  val model: ArmSummary,
)

@Serializable
data class AssertionPlanningEvaluation(
  val formatVersion: Int,
  val corpusSha256: String,
  val savedOutputSha256: String,
  val studyComplete: Boolean,
  val threshold: Double,
  val rows: List<EvaluationRow>,
  val summaries: List<ArmSummary>,
  val thresholdReplay: List<ThresholdSummary>,
)

data class OriginalExpectation(
  val intent: String,
  val rawStep: String,
  val parsedDescription: String,
  val parsedMode: AssertMode,
)

data class PlannedCheck(
  val mode: AssertMode,
  val target: String?,
  val originalExpectation: OriginalExpectation,
)

data class SyntheticEvaluation(
  val caseId: String,
  val sampleId: String,
  val mode: AssertMode,
  val target: String?,
  val originalExpectation: OriginalExpectation,
  val expectedIntentVerdict: Boolean,
  val observedCheckVerdict: Boolean?,
  val evidenceVerdictCapability: String,
)

data class AssertionPlan(
  val caseId: String,
  val check: PlannedCheck,
)

object AssertionPlanningResearch {

  private val json = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
    encodeDefaults = true
  }
  private val strictJson = Json {
    ignoreUnknownKeys = false
    isLenient = false
    allowSpecialFloatingPointValues = false
  }
  private val responseFields = setOf("mode", "target", "confidence", "uncertain", "reason")
  private const val MAX_RESPONSE_BYTES = 16 * 1024
  private const val MAX_TARGET_LENGTH = 256
  private const val MAX_REASON_LENGTH = 512
  private const val NON_BLANK_PATTERN = "[^\\t\\n\\u000B\\f\\r\\u001C-\\u001F\\u0020\\u0085\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000]"
  private val nonBlankPattern = Regex(NON_BLANK_PATTERN)
  private val negationPattern = Regex("(?i)\\b(?:not|no|never|without|neither|nor)\\b")
  private val conditionPattern = Regex("(?i)\\b(?:if|when|unless)\\b")
  private val focusRequirementPattern = Regex("(?i)\\b(?:focus|focused|focuses)\\b")
  private val compoundPattern = Regex("(?i)\\b(?:and|or|but|both|either)\\b|[,;]")
  private val visualPropertyPattern = Regex(
    "(?i)\\b(?:visual|screenshot|image|backdrop|border|colour|color|red|green|blue|" +
      "animation|icon|layout|opacity|shadow|font|background|rounded)\\b",
  )
  private val authoredModeTag = Regex("""^\[\?\w+]\s+.+$""")
  private val modeSuffix = Regex("""^(.+) is (visible|displayed|focused)$""")
  private val simpleLabel = Regex("""^[A-Z][A-Za-z0-9]*(?: [A-Z][A-Za-z0-9]*)*(?: row)?$""")
  private val unsafeRule = Regex(
    """(?i)\b(?:not|no|never|without|neither|and|or|but|both|either|if|when|unless|open|selected|current|active|ready|profile|status|item)\b""",
  )

  fun loadCorpus(path: Path): ResearchCorpus = json.decodeFromString(path.toFile().readText())

  fun parsePlannerResponse(responseText: String): Pair<PlannerResponse?, ResponseValidity> {
    if (responseText.isBlank()) return null to ResponseValidity.EMPTY
    if (responseText.toByteArray(Charsets.UTF_8).size > MAX_RESPONSE_BYTES) return null to ResponseValidity.OVERSIZED
    if (hasDuplicateTopLevelFields(responseText)) return null to ResponseValidity.WRONG_SHAPE
    val root = try {
      strictJson.parseToJsonElement(responseText).jsonObject
    } catch (_: Exception) {
      return null to ResponseValidity.INVALID_JSON
    }
    if (root.keys != responseFields) return null to ResponseValidity.WRONG_SHAPE
    return try {
      val modeElement = root.getValue("mode").jsonPrimitive
      val targetElement = root.getValue("target")
      val confidenceElement = root.getValue("confidence").jsonPrimitive
      val uncertainElement = root.getValue("uncertain").jsonPrimitive
      val reasonElement = root.getValue("reason").jsonPrimitive
      if (!modeElement.isString || confidenceElement.isString || uncertainElement.isString || !reasonElement.isString) {
        return null to ResponseValidity.INVALID_VALUE
      }
      val mode = when (modeElement.content) {
        "visible" -> AssertMode.VISIBLE
        "focused" -> AssertMode.FOCUSED
        "tree" -> AssertMode.TREE
        "visual" -> AssertMode.VISUAL
        else -> return null to ResponseValidity.INVALID_VALUE
      }
      val target = if (targetElement.toString() == "null") {
        null
      } else {
        val primitive = targetElement.jsonPrimitive
        if (!primitive.isString) return null to ResponseValidity.INVALID_VALUE
        primitive.content
      }
      val confidence = confidenceElement.doubleOrNull ?: return null to ResponseValidity.INVALID_VALUE
      val uncertain = uncertainElement.booleanOrNull ?: return null to ResponseValidity.INVALID_VALUE
      val reason = reasonElement.content
      if (!confidence.isFinite() || confidence !in 0.0..1.0 || !hasNonBlankText(reason) ||
        codePointLength(reason) > MAX_REASON_LENGTH
      ) {
        return null to ResponseValidity.INVALID_VALUE
      }
      when (mode) {
        AssertMode.VISIBLE, AssertMode.FOCUSED -> if (target == null || !hasNonBlankText(target) ||
          codePointLength(target) > MAX_TARGET_LENGTH
        ) {
          return null to ResponseValidity.INVALID_VALUE
        }

        AssertMode.TREE, AssertMode.VISUAL -> if (target != null) return null to ResponseValidity.INVALID_VALUE
      }
      PlannerResponse(mode, target, confidence, uncertain, reason) to ResponseValidity.VALID
    } catch (_: Exception) {
      null to ResponseValidity.INVALID_VALUE
    }
  }

  private fun codePointLength(value: String): Int = value.codePointCount(0, value.length)

  private fun hasNonBlankText(value: String): Boolean = nonBlankPattern.containsMatchIn(value)

  fun structuralGuard(
    parsedDescription: String,
    parsedMode: AssertMode,
    proposal: PlannerResponse,
  ): StructuralGuardResult {
    val description = parsedDescription.trim()
    val presenceCheck = proposal.mode == AssertMode.VISIBLE || proposal.mode == AssertMode.FOCUSED
    if ((parsedMode == AssertMode.VISUAL || visualPropertyPattern.containsMatchIn(description)) &&
      proposal.mode != AssertMode.VISUAL
    ) {
      return StructuralGuardResult.REJECT_VISUAL_PROPERTY
    }
    if (!presenceCheck) return StructuralGuardResult.ACCEPTED
    if (negationPattern.containsMatchIn(description)) return StructuralGuardResult.REJECT_NEGATION
    if (conditionPattern.containsMatchIn(description)) return StructuralGuardResult.REJECT_CONDITION
    if (compoundPattern.containsMatchIn(description)) return StructuralGuardResult.REJECT_COMPOUND
    return StructuralGuardResult.ACCEPTED
  }

  fun semanticWeakening(case: ResearchCase, mode: AssertMode, target: String?): Boolean {
    if (case.allowedChecks.any { allowedCheckMatches(mode, target, it) }) return false
    val original = currentPlan(case).check.originalExpectation
    if (mode == AssertMode.VISIBLE &&
      (original.parsedMode == AssertMode.FOCUSED || focusRequirementPattern.containsMatchIn(original.intent))
    ) {
      return true
    }
    val presenceCheck = mode == AssertMode.VISIBLE || mode == AssertMode.FOCUSED
    val description = original.parsedDescription
    if (presenceCheck && (
        negationPattern.containsMatchIn(description) || conditionPattern.containsMatchIn(description) ||
          compoundPattern.containsMatchIn(description) || visualPropertyPattern.containsMatchIn(description)
        )
    ) {
      return true
    }
    return original.parsedMode == AssertMode.VISUAL && mode != AssertMode.VISUAL
  }

  suspend fun planWithSupplier(
    case: ResearchCase,
    corpusPath: Path,
    threshold: Double = 0.8,
    timeoutMillis: Long = 30_000,
    responseSupplier: suspend (PlannerInput) -> String,
  ): PlannerAttemptResult {
    require(threshold.isFinite() && threshold in 0.0..1.0) { "Threshold must be finite and between 0 and 1" }
    require(timeoutMillis > 0) { "Timeout must be positive" }
    val baseline = currentPlan(case)
    if (isAuthorityBypass(case)) {
      return PlannerAttemptResult(
        caseId = case.id,
        proposal = null,
        responseValidity = ResponseValidity.EMPTY,
        guardResult = StructuralGuardResult.ACCEPTED,
        fallbackReason = null,
        safeFailureKind = null,
        effectiveCheck = baseline.check,
      )
    }
    val input = withContext(Dispatchers.IO) { projectPlannerInput(case, corpusPath) }
    var supplierCancellation: CancellationException? = null
    val responseText = try {
      withTimeoutOrNull(timeoutMillis) {
        try {
          responseSupplier(input)
        } catch (error: CancellationException) {
          supplierCancellation = error
          throw error
        }
      }
    } catch (error: CancellationException) {
      throw supplierCancellation ?: error
    } catch (_: Exception) {
      return failedAttempt(case, baseline, SafeFailureKind.TRANSPORT, PlannerFallbackReason.SAFE_FAILURE)
    }
    if (responseText == null) return failedAttempt(case, baseline, SafeFailureKind.TIMEOUT, PlannerFallbackReason.TIMEOUT)
    return planFromResponse(case, responseText, threshold)
  }

  fun planFromResponse(
    case: ResearchCase,
    responseText: String,
    threshold: Double = 0.8,
  ): PlannerAttemptResult {
    require(threshold.isFinite() && threshold in 0.0..1.0) { "Threshold must be finite and between 0 and 1" }
    val baseline = currentPlan(case)
    require(!isAuthorityBypass(case)) { "${case.id}: authority-bypass cases do not call the planner" }
    val (proposal, validity) = parsePlannerResponse(responseText)
    if (proposal == null) {
      return PlannerAttemptResult(
        caseId = case.id,
        proposal = null,
        responseValidity = validity,
        guardResult = StructuralGuardResult.ACCEPTED,
        fallbackReason = PlannerFallbackReason.INVALID_RESPONSE,
        safeFailureKind = null,
        effectiveCheck = baseline.check,
      )
    }
    val guard = structuralGuard(
      baseline.check.originalExpectation.parsedDescription,
      baseline.check.originalExpectation.parsedMode,
      proposal,
    )
    val fallback = when {
      guard != StructuralGuardResult.ACCEPTED -> PlannerFallbackReason.STRUCTURAL_GUARD
      proposal.uncertain -> PlannerFallbackReason.UNCERTAIN
      proposal.confidence < threshold -> PlannerFallbackReason.BELOW_THRESHOLD
      else -> null
    }
    val effective = if (fallback == null) {
      PlannedCheck(proposal.mode, proposal.target, baseline.check.originalExpectation)
    } else {
      baseline.check
    }
    return PlannerAttemptResult(case.id, proposal, validity, guard, fallback, null, effective)
  }

  private fun failedAttempt(
    case: ResearchCase,
    baseline: AssertionPlan,
    failureKind: SafeFailureKind,
    fallbackReason: PlannerFallbackReason,
  ): PlannerAttemptResult = PlannerAttemptResult(
    caseId = case.id,
    proposal = null,
    responseValidity = ResponseValidity.EMPTY,
    guardResult = StructuralGuardResult.ACCEPTED,
    fallbackReason = fallbackReason,
    safeFailureKind = failureKind,
    effectiveCheck = baseline.check,
  )

  private fun hasDuplicateTopLevelFields(text: String): Boolean {
    var index = skipWhitespace(text, 0)
    if (index >= text.length || text[index] != '{') return false
    index++
    val fields = mutableSetOf<String>()
    while (index < text.length) {
      index = skipWhitespace(text, index)
      if (index >= text.length || text[index] == '}') return false
      if (text[index] != '"') return false
      val keyEnd = jsonStringEnd(text, index) ?: return false
      val key = try {
        strictJson.parseToJsonElement(text.substring(index, keyEnd)).jsonPrimitive.content
      } catch (_: Exception) {
        return false
      }
      if (!fields.add(key)) return true
      index = skipWhitespace(text, keyEnd)
      if (index >= text.length || text[index] != ':') return false
      index = skipJsonValue(text, index + 1)
      index = skipWhitespace(text, index)
      if (index >= text.length || text[index] == '}') return false
      if (text[index] != ',') return false
      index++
    }
    return false
  }

  private fun skipJsonValue(text: String, start: Int): Int {
    var index = skipWhitespace(text, start)
    if (index >= text.length) return index
    if (text[index] == '"') return jsonStringEnd(text, index) ?: text.length
    if (text[index] == '{' || text[index] == '[') {
      val closers = ArrayDeque<Char>()
      closers.addLast(if (text[index] == '{') '}' else ']')
      index++
      var inString = false
      var escaped = false
      while (index < text.length && closers.isNotEmpty()) {
        val character = text[index]
        if (inString) {
          when {
            escaped -> escaped = false
            character == '\\' -> escaped = true
            character == '"' -> inString = false
          }
        } else {
          when (character) {
            '"' -> inString = true
            '{' -> closers.addLast('}')
            '[' -> closers.addLast(']')
            '}', ']' -> if (closers.removeLast() != character) return text.length
          }
        }
        index++
      }
      return index
    }
    while (index < text.length && text[index] != ',' && text[index] != '}' && !text[index].isWhitespace()) index++
    return index
  }

  private fun jsonStringEnd(text: String, start: Int): Int? {
    var index = start + 1
    var escaped = false
    while (index < text.length) {
      val character = text[index]
      if (escaped) {
        escaped = false
      } else {
        when (character) {
          '\\' -> escaped = true
          '"' -> return index + 1
        }
      }
      index++
    }
    return null
  }

  private fun skipWhitespace(text: String, start: Int): Int {
    var index = start
    while (index < text.length && text[index].isWhitespace()) index++
    return index
  }

  fun projectPlannerInput(case: ResearchCase, corpusPath: Path): PlannerInput {
    val corpusDirectory = corpusPath.toAbsolutePath().parent
      ?: error("Corpus path has no parent directory")
    val context = ContextLoader.loadProject(corpusDirectory.resolve("context").toFile(), required = true)
    return projectPlannerInput(case, corpusDirectory, context)
  }

  private fun projectPlannerInput(
    case: ResearchCase,
    corpusDirectory: Path,
    context: ContextBundle,
  ): PlannerInput {
    val loadedPaths = context.loadedFiles.map { file ->
      corpusDirectory.relativize(file.toPath()).toString().replace(File.separatorChar, '/')
    }
    require(loadedPaths == case.contextFiles) {
      "${case.id}: loaded context files do not match the reviewed case projection"
    }
    return PlannerInput(
      rawStep = case.rawStep,
      journeySteps = case.journeySteps,
      platform = case.platform,
      projectContext = context.text,
    )
  }

  fun buildRequestEnvelope(corpusPath: Path): ResearchRequestEnvelope {
    val absoluteCorpusPath = corpusPath.toAbsolutePath()
    val corpusDirectory = absoluteCorpusPath.parent ?: error("Corpus path has no parent directory")
    val corpus = loadCorpus(absoluteCorpusPath)
    validateCorpus(corpus)

    val contextFiles = corpus.cases.first().contextFiles
    require(corpus.cases.all { it.contextFiles == contextFiles }) {
      "All reviewed cases must use the same approved context projection"
    }
    val context = ContextLoader.loadProject(corpusDirectory.resolve("context").toFile(), required = true)
    val contextHashes = contextFiles.associateWith { relativePath ->
      val path = corpusDirectory.resolve(relativePath).normalize()
      require(path.startsWith(corpusDirectory) && Files.isRegularFile(path)) {
        "Invalid reviewed context file: $relativePath"
      }
      sha256(path)
    }
    val repositoryRoot = corpusDirectory.parent?.parent ?: error("Corpus is not under the repository research directory")
    val implementationFiles = listOf(
      "verity/core/build.gradle.kts",
      "verity/core/src/test/kotlin/me/chrisbanes/verity/core/research/AssertionPlanningResearch.kt",
    )
    val implementationHashes = implementationFiles.associateWith { relativePath ->
      val path = repositoryRoot.resolve(relativePath).normalize()
      require(path.startsWith(repositoryRoot) && Files.isRegularFile(path)) {
        "Missing approved T1 implementation input: $relativePath"
      }
      sha256(path)
    }
    val requests = corpus.cases.map { case ->
      ResearchRequest(
        caseId = case.id,
        authorityBypass = isAuthorityBypass(case),
        modelInput = projectPlannerInput(case, corpusDirectory, context),
      )
    }
    val bypasses = requests.count { it.authorityBypass }

    return ResearchRequestEnvelope(
      formatVersion = 2,
      corpusId = corpus.corpusId,
      corpusRevision = corpus.revision,
      caseCount = requests.size,
      authorityBypassCaseCount = bypasses,
      implicitCaseCount = requests.size - bypasses,
      corpusSha256 = sha256(absoluteCorpusPath),
      contextSha256 = contextHashes,
      implementationSha256 = implementationHashes,
      promptSha256 = sha256(Files.readAllBytes(repositoryRoot.resolve("research/assertion-planning/prompt.txt"))),
      schemaSha256 = sha256(Files.readAllBytes(repositoryRoot.resolve("research/assertion-planning/planner.schema.json"))),
      cases = requests,
    )
  }

  fun validateSavedOutput(
    output: SavedPlannerOutput,
    request: ResearchRequestEnvelope,
    expectedRequestSha256: String,
  ): Boolean {
    if (request.formatVersion != 2 || output.formatVersion != 1 || !output.completed || output.stopReason != SavedStopReason.COMPLETE) return false
    if (!output.cleanupVerified || !isSha256(expectedRequestSha256)) return false
    if (output.runMetadata.model.isBlank() || output.runMetadata.effort.isBlank() || output.runMetadata.tier.isBlank() ||
      output.runMetadata.cliVersion.isBlank() ||
      listOfNotNull(output.runMetadata.startupDurationMillis, output.runMetadata.wallDurationMillis).any { it < 0 } ||
      output.runMetadata.globalInstructionSources.any { it.count < 0 } ||
      output.runMetadata.globalInstructionSources.map { it.source }.distinct().size != output.runMetadata.globalInstructionSources.size
    ) {
      return false
    }
    if (output.bindings.requestSha256 != expectedRequestSha256 ||
      output.bindings.corpusSha256 != request.corpusSha256 ||
      output.bindings.contextSha256 != request.contextSha256 ||
      output.bindings.hostSha256 != request.implementationSha256 ||
      output.bindings.promptSha256 != request.promptSha256 ||
      output.bindings.schemaSha256 != request.schemaSha256
    ) {
      return false
    }

    val expectedIds = request.cases.map { it.caseId }
    if (expectedIds.distinct().size != expectedIds.size) return false
    if (output.cases.map { it.caseId }.toSet() != expectedIds.toSet() || output.cases.size != expectedIds.size) return false
    val bypassIds = request.cases.filter { it.authorityBypass }.map { it.caseId }.toSet()
    if (request.caseCount != 40 || request.authorityBypassCaseCount != 8 || request.implicitCaseCount != 32 ||
      bypassIds.size != 8 || expectedIds.size - bypassIds.size != 32
    ) {
      return false
    }
    if (output.counters != SavedOutputCounters(33, 1, 32, 8, 0)) return false
    if (output.qualification.status != SavedCaseStatus.SUCCESS || output.qualification.failureKind != null ||
      !validTimings(output.qualification.setup, output.qualification.turn, output.qualification.cleanup) ||
      !hasVerifiedText(output.qualification) ||
      parsePlannerResponse(output.qualification.responseText!!).second != ResponseValidity.VALID
    ) {
      return false
    }

    return output.cases.all { case ->
      val bypass = case.caseId in bypassIds
      when {
        !validTimings(case.setup, case.turn, case.cleanup) -> false

        !case.tokenUsage.isValid() -> false

        bypass -> case.status == SavedCaseStatus.BYPASS && case.hasNoResponse() && case.failureKind == null &&
          case.tokenUsage.inputTokens == null && case.tokenUsage.outputTokens == null

        case.status == SavedCaseStatus.SUCCESS -> case.failureKind == null && hasVerifiedText(case)

        case.status == SavedCaseStatus.FAILURE -> case.failureKind != null && case.hasNoResponse()

        else -> false
      }
    }
  }

  private fun validTimings(vararg timings: SavedPhaseTiming): Boolean = timings.all { it.durationMillis == null || it.durationMillis >= 0 }

  private fun SavedCaseOutput.hasNoResponse(): Boolean = responseText == null && storedTextSha256 == null &&
    originalResponseSha256 == null && redactionStatus == null

  private fun hasVerifiedText(output: SavedQualificationOutput): Boolean = output.responseText != null && isSha256(output.storedTextSha256) &&
    output.responseText.toByteArray(Charsets.UTF_8).size <= MAX_RESPONSE_BYTES &&
    sha256(output.responseText.toByteArray(Charsets.UTF_8)) == output.storedTextSha256 &&
    output.tokenUsage.isValid() && when (output.redactionStatus) {
      ResponseRedactionStatus.REDACTED -> isSha256(output.originalResponseSha256)
      ResponseRedactionStatus.NOT_REQUIRED, ResponseRedactionStatus.REVIEWED -> output.originalResponseSha256 == null
      null -> false
    }

  private fun hasVerifiedText(output: SavedCaseOutput): Boolean = output.responseText != null && isSha256(output.storedTextSha256) &&
    output.responseText.toByteArray(Charsets.UTF_8).size <= MAX_RESPONSE_BYTES &&
    sha256(output.responseText.toByteArray(Charsets.UTF_8)) == output.storedTextSha256 &&
    output.tokenUsage.isValid() && when (output.redactionStatus) {
      ResponseRedactionStatus.REDACTED -> isSha256(output.originalResponseSha256)
      ResponseRedactionStatus.NOT_REQUIRED, ResponseRedactionStatus.REVIEWED -> output.originalResponseSha256 == null
      null -> false
    }

  private fun SavedTokenUsage.isValid(): Boolean = (inputTokens == null || inputTokens >= 0) && (outputTokens == null || outputTokens >= 0)

  private fun isSha256(value: String?): Boolean = value?.matches(Regex("[0-9a-f]{64}")) == true

  fun evaluateSavedOutput(
    corpusPath: Path,
    request: ResearchRequestEnvelope,
    requestBytes: ByteArray,
    output: SavedPlannerOutput,
    outputBytes: ByteArray,
    threshold: Double = 0.8,
  ): AssertionPlanningEvaluation {
    require(threshold.isFinite() && threshold in 0.0..1.0) { "Threshold must be finite and between 0 and 1" }
    val requestSha256 = sha256(requestBytes)
    val outputSha256 = sha256(outputBytes)
    val corpus = loadCorpus(corpusPath)
    validateCorpus(corpus)
    require(request.corpusId == corpus.corpusId && request.corpusSha256 == sha256(corpusPath)) {
      "Request does not bind the selected corpus"
    }
    val requestIds = request.cases.map { it.caseId }
    require(requestIds == corpus.cases.map { it.id } && requestIds.distinct().size == requestIds.size) {
      "Request case IDs do not match the reviewed corpus"
    }
    val requestMatchesCurrentInputs = request == buildRequestEnvelope(corpusPath)
    val caseOutputs = output.cases.associateBy { it.caseId }
    val inputBytesMatch = runCatching {
      strictJson.decodeFromString<ResearchRequestEnvelope>(requestBytes.toString(Charsets.UTF_8)) == request &&
        strictJson.decodeFromString<SavedPlannerOutput>(outputBytes.toString(Charsets.UTF_8)) == output
    }.getOrDefault(false)
    val complete = requestMatchesCurrentInputs && inputBytesMatch && validateSavedOutput(output, request, requestSha256)
    val rows = buildList {
      corpus.cases.forEach { case ->
        val bypass = isAuthorityBypass(case)
        val current = currentPlan(case)
        val rules = rulesPlan(case)
        add(evaluateRow(case, EvaluationArm.CURRENT, current, bypass, null, output = null))
        add(evaluateRow(case, EvaluationArm.RULES, rules, bypass, null, output = null))
        val saved = caseOutputs[case.id]
        val modelResult = when {
          bypass -> PlannerAttemptResult(
            case.id,
            null,
            ResponseValidity.EMPTY,
            StructuralGuardResult.ACCEPTED,
            null,
            null,
            current.check,
          )

          saved?.status == SavedCaseStatus.SUCCESS && hasVerifiedText(saved) -> planFromResponse(case, saved.responseText!!, threshold)

          saved?.status == SavedCaseStatus.FAILURE -> failedAttempt(
            case,
            current,
            saved.failureKind ?: SafeFailureKind.TRANSPORT,
            PlannerFallbackReason.SAFE_FAILURE,
          )

          else -> PlannerAttemptResult(
            case.id,
            null,
            ResponseValidity.EMPTY,
            StructuralGuardResult.ACCEPTED,
            PlannerFallbackReason.SAFE_FAILURE,
            null,
            current.check,
          )
        }
        add(
          evaluateRow(
            case,
            EvaluationArm.MODEL,
            AssertionPlan(case.id, modelResult.effectiveCheck),
            bypass,
            modelResult,
            saved,
          ),
        )
      }
    }
    val summaries = EvaluationArm.entries.map { arm -> summarize(arm, rows.filter { it.arm == arm }) }
    val thresholdReplay = listOf(0.0, 0.5, 0.8, 0.95, 1.0).map { replayThreshold ->
      val replayRows = corpus.cases.map { case ->
        val baseline = currentPlan(case)
        val saved = caseOutputs[case.id]
        val result = when {
          isAuthorityBypass(case) -> PlannerAttemptResult(
            case.id,
            null,
            ResponseValidity.EMPTY,
            StructuralGuardResult.ACCEPTED,
            null,
            null,
            baseline.check,
          )

          saved?.status == SavedCaseStatus.SUCCESS && hasVerifiedText(saved) -> planFromResponse(case, saved.responseText!!, replayThreshold)

          saved?.status == SavedCaseStatus.FAILURE -> failedAttempt(
            case,
            baseline,
            saved.failureKind ?: SafeFailureKind.TRANSPORT,
            PlannerFallbackReason.SAFE_FAILURE,
          )

          else -> PlannerAttemptResult(
            case.id,
            null,
            ResponseValidity.EMPTY,
            StructuralGuardResult.ACCEPTED,
            PlannerFallbackReason.SAFE_FAILURE,
            null,
            baseline.check,
          )
        }
        evaluateRow(
          case,
          EvaluationArm.MODEL,
          AssertionPlan(case.id, result.effectiveCheck),
          isAuthorityBypass(case),
          result,
          saved,
        )
      }
      ThresholdSummary(replayThreshold, outputSha256, summarize(EvaluationArm.MODEL, replayRows))
    }
    return AssertionPlanningEvaluation(
      formatVersion = 1,
      corpusSha256 = request.corpusSha256,
      savedOutputSha256 = outputSha256,
      studyComplete = complete,
      threshold = threshold,
      rows = rows,
      summaries = summaries,
      thresholdReplay = thresholdReplay,
    )
  }

  private fun evaluateRow(
    case: ResearchCase,
    arm: EvaluationArm,
    plan: AssertionPlan,
    bypass: Boolean,
    attempt: PlannerAttemptResult?,
    output: SavedCaseOutput?,
  ): EvaluationRow {
    val actualPair = plan.check.mode to plan.check.target
    val allowed = case.allowedChecks
    val preferred = case.preferredCheapChecks
    val proposal = attempt?.proposal
    val rawPair = proposal?.let { it.mode to it.target }
    val rawVerdictMetrics = proposal?.let { response ->
      verdictMetrics(evaluateSynthetic(case, AssertionPlan(case.id, PlannedCheck(response.mode, response.target, plan.check.originalExpectation))))
    } ?: VerdictMetrics(0, 0, 0, 0)
    val effectiveVerdicts = verdictMetrics(evaluateSynthetic(case, plan))
    val rawModeMismatch = rawPair?.let { pair -> allowed.none { it.mode.equals(pair.first.name.lowercase(), ignoreCase = true) } }
    val effectiveModeMismatch = allowed.none { it.mode.equals(actualPair.first.name.lowercase(), ignoreCase = true) }
    val rawTargetMismatch = rawPair?.let { pair ->
      allowed.any { it.mode.equals(pair.first.name.lowercase(), ignoreCase = true) } &&
        allowed.none { allowedCheckMatches(pair.first, pair.second, it) }
    }
    val effectiveTargetMismatch = allowed.any { it.mode.equals(actualPair.first.name.lowercase(), ignoreCase = true) } &&
      allowed.none { allowedCheckMatches(actualPair.first, actualPair.second, it) }
    val rawSemanticWeakening = proposal?.let { semanticWeakening(case, it.mode, it.target) }
    return EvaluationRow(
      caseId = case.id,
      category = case.category,
      arm = arm,
      authorityBypass = bypass,
      attempted = arm == EvaluationArm.MODEL && !bypass && output?.status in setOf(SavedCaseStatus.SUCCESS, SavedCaseStatus.FAILURE),
      failed = arm == EvaluationArm.MODEL && output?.status == SavedCaseStatus.FAILURE,
      unattempted = arm == EvaluationArm.MODEL && !bypass &&
        output?.status !in setOf(SavedCaseStatus.SUCCESS, SavedCaseStatus.FAILURE, SavedCaseStatus.BYPASS),
      fallback = attempt?.fallbackReason != null,
      rawProposal = proposal,
      responseValidity = attempt?.responseValidity ?: ResponseValidity.EMPTY,
      guardResult = attempt?.guardResult ?: StructuralGuardResult.ACCEPTED,
      fallbackReason = attempt?.fallbackReason,
      effectiveMode = plan.check.mode,
      effectiveTarget = plan.check.target,
      rawSemanticError = proposal?.let { raw -> !allowed.any { allowedCheckMatches(raw.mode, raw.target, it) } },
      rawModeMismatch = rawModeMismatch,
      rawTargetMismatch = rawTargetMismatch,
      effectiveSemanticError = !allowed.any { allowedCheckMatches(actualPair.first, actualPair.second, it) },
      effectiveModeMismatch = effectiveModeMismatch,
      effectiveTargetMismatch = effectiveTargetMismatch,
      rawSemanticWeakening = rawSemanticWeakening,
      semanticWeakening = semanticWeakening(case, plan.check.mode, plan.check.target),
      acceptableCheapCheck = preferred.any { allowedCheckMatches(actualPair.first, actualPair.second, it) },
      rawVerdicts = rawVerdictMetrics,
      verdicts = effectiveVerdicts,
    )
  }

  private fun allowedCheckMatches(mode: AssertMode, target: String?, allowed: ResearchCheck): Boolean {
    if (allowed.mode != mode.name.lowercase()) return false
    return when (mode) {
      AssertMode.VISIBLE -> target != null && allowed.target != null && target.equals(allowed.target, ignoreCase = true)
      AssertMode.FOCUSED -> target != null && allowed.target != null && target.equals(allowed.target, ignoreCase = true)
      AssertMode.TREE, AssertMode.VISUAL -> target == null && allowed.target == null
    }
  }

  private fun verdictMetrics(evaluations: List<SyntheticEvaluation>): VerdictMetrics {
    val available = evaluations.mapNotNull { evaluation ->
      evaluation.observedCheckVerdict?.let { it to evaluation.expectedIntentVerdict }
    }
    return VerdictMetrics(
      evaluated = available.size,
      unavailable = evaluations.size - available.size,
      falsePasses = available.count { (observed, expected) -> observed && !expected },
      falseFailures = available.count { (observed, expected) -> !observed && expected },
    )
  }

  private fun summarize(arm: EvaluationArm, rows: List<EvaluationRow>): ArmSummary = ArmSummary(
    arm = arm,
    allCases = rows.size,
    implicitCases = rows.count { !it.authorityBypass },
    bypassedCases = rows.count { it.authorityBypass },
    attemptedCases = rows.count { it.attempted },
    failedCases = rows.count { it.failed },
    unattemptedCases = rows.count { it.unattempted },
    fallbackCases = rows.count { it.fallback },
    rawScoredCases = rows.count { it.rawSemanticError != null },
    effectiveScoredCases = rows.count { it.effectiveSemanticError != null },
    rawSemanticErrors = rows.count { it.rawSemanticError == true },
    effectiveSemanticErrors = rows.count { it.effectiveSemanticError == true },
    rawModeMismatches = rows.count { it.rawModeMismatch == true },
    rawTargetMismatches = rows.count { it.rawTargetMismatch == true },
    rawSemanticWeakenings = rows.count { it.rawSemanticWeakening == true },
    effectiveModeMismatches = rows.count { it.effectiveModeMismatch == true },
    effectiveTargetMismatches = rows.count { it.effectiveTargetMismatch == true },
    semanticWeakenings = rows.count { it.semanticWeakening },
    acceptableCheapChecks = rows.count { it.acceptableCheapCheck },
    rawVerdicts = VerdictMetrics(
      evaluated = rows.sumOf { it.rawVerdicts.evaluated },
      unavailable = rows.sumOf { it.rawVerdicts.unavailable },
      falsePasses = rows.sumOf { it.rawVerdicts.falsePasses },
      falseFailures = rows.sumOf { it.rawVerdicts.falseFailures },
    ),
    verdicts = VerdictMetrics(
      evaluated = rows.sumOf { it.verdicts.evaluated },
      unavailable = rows.sumOf { it.verdicts.unavailable },
      falsePasses = rows.sumOf { it.verdicts.falsePasses },
      falseFailures = rows.sumOf { it.verdicts.falseFailures },
    ),
  )

  fun exportRequests(corpusPath: Path, outputPath: Path): ResearchRequestEnvelope {
    val corpus = corpusPath.toAbsolutePath().normalize()
    val destination = outputPath.toAbsolutePath().normalize()
    requireDistinctOutput(destination, listOf(corpus))
    val envelope = buildRequestEnvelope(corpus)
    requireDistinctOutput(destination, protectedRequestInputs(corpus))
    writePrivateJson(destination, json.encodeToString(envelope))
    return envelope
  }

  fun evaluateFiles(
    corpusPath: Path,
    requestPath: Path,
    savedOutputPath: Path,
    evaluationPath: Path,
  ): AssertionPlanningEvaluation {
    val corpus = corpusPath.toAbsolutePath().normalize()
    val request = requestPath.toAbsolutePath().normalize()
    val savedOutput = savedOutputPath.toAbsolutePath().normalize()
    val destination = evaluationPath.toAbsolutePath().normalize()
    requireDistinctOutput(destination, protectedRequestInputs(corpus) + listOf(request, savedOutput))
    val requestBytes = Files.readAllBytes(request)
    val savedOutputBytes = Files.readAllBytes(savedOutput)
    val requestEnvelope = json.decodeFromString<ResearchRequestEnvelope>(requestBytes.toString(Charsets.UTF_8))
    val savedOutputDto = strictJson.decodeFromString<SavedPlannerOutput>(savedOutputBytes.toString(Charsets.UTF_8))
    val evaluation = evaluateSavedOutput(corpus, requestEnvelope, requestBytes, savedOutputDto, savedOutputBytes)
    writePrivateJson(destination, json.encodeToString(evaluation))
    return evaluation
  }

  private fun protectedRequestInputs(corpusPath: Path): List<Path> {
    val corpus = corpusPath.toAbsolutePath().normalize()
    val corpusDirectory = corpus.parent ?: error("Corpus path has no parent directory")
    val repositoryRoot = corpusDirectory.parent?.parent ?: error("Corpus is not under the repository research directory")
    val reviewed = loadCorpus(corpus)
    return listOf(corpus) + reviewed.cases.first().contextFiles.map(corpusDirectory::resolve) + listOf(
      repositoryRoot.resolve("research/assertion-planning/prompt.txt"),
      repositoryRoot.resolve("research/assertion-planning/planner.schema.json"),
      repositoryRoot.resolve("verity/core/build.gradle.kts"),
      repositoryRoot.resolve("verity/core/src/test/kotlin/me/chrisbanes/verity/core/research/AssertionPlanningResearch.kt"),
    )
  }

  private fun requireDistinctOutput(destination: Path, inputs: List<Path>) {
    val normalized = destination.toAbsolutePath().normalize()
    require(
      inputs.none { input ->
        val source = input.toAbsolutePath().normalize()
        normalized == source || (Files.exists(normalized) && Files.exists(source) && Files.isSameFile(normalized, source))
      },
    ) { "Output must not overwrite a named input" }
  }

  private fun writePrivateJson(destination: Path, content: String) {
    val parent = destination.parent ?: error("Output path has no parent directory")
    Files.createDirectories(parent)
    val permissions = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
    val temporary = try {
      Files.createTempFile(parent, ".${destination.fileName}.", ".tmp", PosixFilePermissions.asFileAttribute(permissions))
    } catch (_: UnsupportedOperationException) {
      Files.createTempFile(parent, ".${destination.fileName}.", ".tmp")
    }
    try {
      setPermissions(temporary, permissions)
      Files.writeString(temporary, content, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
      try {
        Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
      } catch (_: AtomicMoveNotSupportedException) {
        Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
      }
    } finally {
      Files.deleteIfExists(temporary)
    }
  }

  private fun validateCorpus(corpus: ResearchCorpus) {
    require(corpus.cases.size == 40) { "Expected exactly 40 reviewed cases" }
    require(corpus.cases.map { it.id }.distinct().size == corpus.cases.size) { "Case ids must be unique" }
    val categories = corpus.cases.groupingBy { it.category }.eachCount()
    require(
      categories == mapOf(
        "text" to 6,
        "focus" to 6,
        "negation" to 5,
        "compound" to 5,
        "ambiguous" to 5,
        "visual" to 5,
        "authority" to 8,
      ),
    ) { "Reviewed category counts changed: $categories" }
    require(categories == corpus.categoryCounts) { "Corpus category metadata does not match its cases" }
    val platforms = corpus.cases.groupingBy { it.platform }.eachCount()
    require(platforms == mapOf("android-tv" to 17, "android" to 14, "ios" to 9)) {
      "Reviewed platform counts changed: $platforms"
    }
    val authored = corpus.cases.count { hasAuthoredModeTag(it.rawStep) }
    val fixed = corpus.cases.count { it.provenance.fixedStrategy != null }
    val conflicts = corpus.cases.count {
      hasAuthoredModeTag(it.rawStep) && it.provenance.fixedStrategy != null
    }
    val bypasses = corpus.cases.count(::isAuthorityBypass)
    val independentFixed = fixed - conflicts
    require(authored == 4 && independentFixed == 4 && conflicts == 1 && bypasses == 8) {
      "Reviewed authority counts changed"
    }
    require(corpus.authorityCoverage.authoredModes == authored)
    require(corpus.authorityCoverage.fixedStrategies == independentFixed)
    require(corpus.authorityCoverage.authoredOverridesFixedConflicts == conflicts)
    require(corpus.authorityCoverage.derivedAuthorityBypassCases == bypasses)
    require(corpus.authorityCoverage.derivedImplicitCases == corpus.cases.size - bypasses)
    require(corpus.authorityCoverage.independentlyFixedModeCases == independentFixed)
    require(corpus.authorityCoverage.authoredModeCases == authored)
    corpus.cases.forEach { currentPlan(it) }
  }

  private fun sha256(path: Path): String = sha256(Files.readAllBytes(path))

  private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

  private fun setPermissions(path: Path, permissions: Set<PosixFilePermission>) {
    try {
      Files.setPosixFilePermissions(path, permissions)
    } catch (_: UnsupportedOperationException) {
      // Non-POSIX hosts retain their platform default permissions.
    }
  }

  fun hasAuthoredModeTag(rawStep: String): Boolean = authoredModeTag.matches(rawStep.trim())

  fun isAuthorityBypass(case: ResearchCase): Boolean = hasAuthoredModeTag(case.rawStep) || case.provenance.fixedStrategy != null

  fun currentPlan(case: ResearchCase): AssertionPlan {
    val strategy = case.provenance.fixedStrategy
      ?.let(AssertionStrategy::fromConfig)
      ?: AssertionStrategy.INFER
    val parsed = JourneyStepParser.parse(case.rawStep, strategy)
    require(parsed is JourneyStep.Assert) { "${case.id}: rawStep must parse as an assertion" }

    val originalExpectation = OriginalExpectation(
      intent = case.originalIntent,
      rawStep = case.rawStep,
      parsedDescription = parsed.description,
      parsedMode = parsed.mode,
    )
    return AssertionPlan(
      caseId = case.id,
      check = PlannedCheck(
        mode = parsed.mode,
        target = parsed.description.takeIf {
          parsed.mode == AssertMode.VISIBLE || parsed.mode == AssertMode.FOCUSED
        },
        originalExpectation = originalExpectation,
      ),
    )
  }

  fun evaluateSynthetic(case: ResearchCase, plan: AssertionPlan): List<SyntheticEvaluation> {
    require(plan.caseId == case.id) { "${case.id}: plan belongs to ${plan.caseId}" }
    return case.evidence.samples.map { sample ->
      val tree = sample.tree?.toHierarchyNode()
      val check = plan.check
      val target = check.target
      val observed = when {
        case.evidence.availability != "hierarchy" || case.evidence.verdictCapability == "none" -> null
        tree == null || target.isNullOrBlank() -> null
        check.mode == AssertMode.VISIBLE -> tree.containsText(target)
        check.mode == AssertMode.FOCUSED -> FocusDetector.containsFocused(tree, target)
        else -> null
      }
      SyntheticEvaluation(
        caseId = case.id,
        sampleId = sample.id,
        mode = check.mode,
        target = check.target,
        originalExpectation = check.originalExpectation,
        expectedIntentVerdict = sample.expectedIntentVerdict,
        observedCheckVerdict = observed,
        evidenceVerdictCapability = case.evidence.verdictCapability,
      )
    }
  }

  fun rulesPlan(case: ResearchCase): AssertionPlan {
    val current = currentPlan(case)
    if (isAuthorityBypass(case)) return current

    val currentCheck = current.check
    val description = currentCheck.originalExpectation.parsedDescription
    if (currentCheck.mode == AssertMode.VISUAL || unsafeRule.containsMatchIn(description)) {
      return current
    }

    val stateMatch = modeSuffix.matchEntire(description)
    val rule = when {
      stateMatch != null && simpleLabel.matches(stateMatch.groupValues[1]) -> {
        val mode = when (stateMatch.groupValues[2]) {
          "visible", "displayed" -> AssertMode.VISIBLE
          "focused" -> AssertMode.FOCUSED
          else -> error("Unreachable assertion mode suffix")
        }
        mode to stateMatch.groupValues[1]
      }

      simpleLabel.matches(description) -> AssertMode.VISIBLE to description

      else -> return current
    }

    return AssertionPlan(
      caseId = case.id,
      check = PlannedCheck(
        mode = rule.first,
        target = rule.second,
        originalExpectation = currentCheck.originalExpectation,
      ),
    )
  }
}

fun main(args: Array<String>) {
  val command = args.firstOrNull()
  require(command in setOf("export", "evaluate", "replay")) {
    "Usage: export <corpus.json> <requests.json> | evaluate|replay <corpus.json> <requests.json> <saved-output.json> <evaluation.json>"
  }
  when (command) {
    "export" -> {
      require(args.size == 3) { "Usage: export <corpus.json> <requests.json>" }
      val envelope = AssertionPlanningResearch.exportRequests(Path.of(args[1]), Path.of(args[2]))
      println("Exported ${envelope.caseCount} requests: ${envelope.authorityBypassCaseCount} bypass, ${envelope.implicitCaseCount} implicit")
    }

    "evaluate", "replay" -> {
      require(args.size == 5) { "Usage: ${args[0]} <corpus.json> <requests.json> <saved-output.json> <evaluation.json>" }
      val evaluation = AssertionPlanningResearch.evaluateFiles(
        Path.of(args[1]),
        Path.of(args[2]),
        Path.of(args[3]),
        Path.of(args[4]),
      )
      println(
        "Evaluated ${evaluation.rows.size} rows; complete=${evaluation.studyComplete}; " +
          "threshold replay=${evaluation.thresholdReplay.size} thresholds; output=${evaluation.savedOutputSha256}",
      )
    }

    else -> error("Validated command was not handled")
  }
}

private fun ResearchTree.toHierarchyNode(): HierarchyNode = HierarchyNode(
  attributes = attributes,
  states = states,
  children = children.map(ResearchTree::toHierarchyNode),
)
