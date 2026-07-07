package me.chrisbanes.verity.core.preflight

import kotlinx.serialization.Serializable

@Serializable
enum class PreflightSeverity {
  WARNING,
  ERROR,
}

@Serializable
data class PreflightIssue(
  val code: String,
  val severity: PreflightSeverity,
  val message: String,
  val remediation: String,
  val details: Map<String, String> = emptyMap(),
)

@Serializable
data class PreflightReport(
  val issues: List<PreflightIssue> = emptyList(),
) {
  val errors: List<PreflightIssue>
    get() = issues.filter { it.severity == PreflightSeverity.ERROR }

  val warnings: List<PreflightIssue>
    get() = issues.filter { it.severity == PreflightSeverity.WARNING }

  val hasErrors: Boolean
    get() = errors.isNotEmpty()

  val passed: Boolean
    get() = !hasErrors

  operator fun plus(other: PreflightReport): PreflightReport = PreflightReport(issues = issues + other.issues)

  fun renderPlainText(): String {
    if (issues.isEmpty()) return "Preflight passed."
    return buildString {
      appendLine("Preflight failed:")
      issues.forEach { issue ->
        appendLine("- [${issue.severity}] ${issue.code}: ${issue.message}")
        appendLine("  Remediation: ${issue.remediation}")
        if (issue.details.isNotEmpty()) {
          appendLine("  Details: ${issue.details.entries.joinToString { "${it.key}=${it.value}" }}")
        }
      }
    }.trimEnd()
  }
}

object PreflightCodes {
  const val PATH_MISSING = "path.missing"
  const val PATH_NOT_READABLE = "path.not_readable"
  const val PATH_NOT_WRITABLE = "path.not_writable"
  const val TEMP_NOT_WRITABLE = "runtime.temp_not_writable"

  const val ANDROID_DEVICE_ID_INVALID = "android.device_id.invalid"
  const val ANDROID_ADB_MISSING = "android.adb.missing"
  const val ANDROID_ADB_FAILED = "android.adb.failed"
  const val ANDROID_DEVICE_MISSING = "android.device.missing"
  const val ANDROID_DEVICE_UNAVAILABLE = "android.device.unavailable"

  const val IOS_XCRUN_MISSING = "ios.xcrun.missing"
  const val IOS_SIMCTL_FAILED = "ios.simctl.failed"
  const val IOS_SIMULATOR_NONE = "ios.simulator.none"
  const val IOS_SIMULATOR_MULTIPLE = "ios.simulator.multiple"
  const val IOS_DEVICE_NOT_BOOTED = "ios.device.not_booted"

  const val PROVIDER_UNKNOWN = "provider.unknown"
  const val PROVIDER_MODEL_UNKNOWN = "provider.model.unknown"
  const val PROVIDER_CREDENTIAL_MISSING = "provider.credential.missing"
}
