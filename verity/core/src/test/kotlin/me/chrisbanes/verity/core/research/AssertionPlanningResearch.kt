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
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
  val promptHashStatus: String,
  val schemaHashStatus: String,
  val cases: List<ResearchRequest>,
)

@Serializable
data class ResearchRequest(
  val caseId: String,
  val authorityBypass: Boolean,
  val modelInput: PlannerInput,
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
  private val authoredModeTag = Regex("""^\[\?\w+]\s+.+$""")
  private val modeSuffix = Regex("""^(.+) is (visible|displayed|focused)$""")
  private val simpleLabel = Regex("""^[A-Z][A-Za-z0-9]*(?: [A-Z][A-Za-z0-9]*)*(?: row)?$""")
  private val unsafeRule = Regex(
    """(?i)\b(?:not|no|never|without|neither|and|or|but|both|either|if|when|unless|open|selected|current|active|ready|profile|status|item)\b""",
  )

  fun loadCorpus(path: Path): ResearchCorpus = json.decodeFromString(path.toFile().readText())

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
      formatVersion = 1,
      corpusId = corpus.corpusId,
      corpusRevision = corpus.revision,
      caseCount = requests.size,
      authorityBypassCaseCount = bypasses,
      implicitCaseCount = requests.size - bypasses,
      corpusSha256 = sha256(absoluteCorpusPath),
      contextSha256 = contextHashes,
      implementationSha256 = implementationHashes,
      promptHashStatus = "unavailable_until_t2",
      schemaHashStatus = "unavailable_until_t2",
      cases = requests,
    )
  }

  fun exportRequests(corpusPath: Path, outputPath: Path): ResearchRequestEnvelope {
    val corpus = corpusPath.toAbsolutePath().normalize()
    val destination = outputPath.toAbsolutePath().normalize()
    require(corpus != destination && (!Files.exists(destination) || !Files.isSameFile(corpus, destination))) {
      "Request output must not overwrite the corpus input"
    }
    val envelope = buildRequestEnvelope(corpus)
    val parent = destination.parent ?: error("Request path has no parent directory")
    Files.createDirectories(parent)
    val permissions = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
    val temporary = try {
      Files.createTempFile(
        parent,
        ".${destination.fileName}.",
        ".tmp",
        PosixFilePermissions.asFileAttribute(permissions),
      )
    } catch (_: UnsupportedOperationException) {
      Files.createTempFile(parent, ".${destination.fileName}.", ".tmp")
    }
    try {
      setPermissions(temporary, permissions)
      Files.writeString(
        temporary,
        json.encodeToString(envelope),
        StandardOpenOption.WRITE,
        StandardOpenOption.TRUNCATE_EXISTING,
      )
      try {
        Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
      } catch (_: AtomicMoveNotSupportedException) {
        Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
      }
    } finally {
      Files.deleteIfExists(temporary)
    }
    return envelope
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

  private fun sha256(path: Path): String = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

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
  require(args.size == 3 && args[0] == "export") {
    "Usage: export <corpus.json> <requests.json>"
  }
  val envelope = AssertionPlanningResearch.exportRequests(Path.of(args[1]), Path.of(args[2]))
  println(
    "Exported ${envelope.caseCount} requests: ${envelope.authorityBypassCaseCount} bypass, " +
      "${envelope.implicitCaseCount} implicit; prompt/schema hashes unavailable until T2",
  )
}

private fun ResearchTree.toHierarchyNode(): HierarchyNode = HierarchyNode(
  attributes = attributes,
  states = states,
  children = children.map(ResearchTree::toHierarchyNode),
)
