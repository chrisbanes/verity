package me.chrisbanes.verity.agent

/** Only these diagnostics may leave a model request boundary. Never retain a raw cause. */
class ModelFailureException(val stage: ModelRequestStage, val failure: ModelFailureKind) : Exception("${stage.diagnostic} ${failure.diagnostic}")

enum class ModelRequestStage(val diagnostic: String) {
  INSPECTOR_TREE("Inspector tree"),
  INSPECTOR_VISUAL("Inspector visual"),
}

enum class ModelFailureKind(val diagnostic: String) {
  REQUEST("request failed"),
  TIMEOUT("request timed out"),
  TRUNCATED("response was truncated"),
  EMPTY("response was empty"),
  INVALID_VERDICT("response had an invalid verdict"),
}

/** Defense in depth for authored diagnostics; raw request errors must use fixed stages instead. */
fun redactModelDiagnostic(message: String): String = message
  .replace(Regex("""(?i)Bearer\s+[^\s,;]+"""), "Bearer [redacted]")
  .replace(Regex("""\bsk-[A-Za-z0-9_-]+\b"""), "[redacted]")
  .replace(Regex("""\bAIza[A-Za-z0-9_-]+\b"""), "[redacted]")
  .replace(Regex("""\beyJ[A-Za-z0-9_-]*\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b"""), "[redacted]")
  .replace(Regex("""(?i)(api[_-]?key\s*[:=]\s*)[^\s,;]+"""), "$1[redacted]")
