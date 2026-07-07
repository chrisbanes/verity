package me.chrisbanes.verity.core.preflight

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class PathPreflightChecker {
  fun requireReadableFile(path: Path, label: String): PreflightReport {
    val issues = mutableListOf<PreflightIssue>()
    if (!Files.exists(path)) {
      issues += PreflightIssue(
        code = PreflightCodes.PATH_MISSING,
        severity = PreflightSeverity.ERROR,
        message = "$label does not exist: $path",
        remediation = "Provide an existing file path.",
        details = mapOf("path" to path.toString()),
      )
    } else if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
      issues += PreflightIssue(
        code = PreflightCodes.PATH_NOT_READABLE,
        severity = PreflightSeverity.ERROR,
        message = "$label is not a readable file: $path",
        remediation = "Choose a readable file path.",
        details = mapOf("path" to path.toString()),
      )
    }
    return PreflightReport(issues)
  }

  fun requireReadableDirectory(path: Path, label: String): PreflightReport {
    val issues = mutableListOf<PreflightIssue>()
    if (!Files.exists(path)) {
      issues += PreflightIssue(
        code = PreflightCodes.PATH_MISSING,
        severity = PreflightSeverity.ERROR,
        message = "$label does not exist: $path",
        remediation = "Provide an existing directory path.",
        details = mapOf("path" to path.toString()),
      )
    } else if (!Files.isDirectory(path) || !Files.isReadable(path)) {
      issues += PreflightIssue(
        code = PreflightCodes.PATH_NOT_READABLE,
        severity = PreflightSeverity.ERROR,
        message = "$label is not a readable directory: $path",
        remediation = "Choose a readable directory path.",
        details = mapOf("path" to path.toString()),
      )
    }
    return PreflightReport(issues)
  }

  fun requireWritableFileTarget(path: Path, label: String): PreflightReport {
    val parent = path.toAbsolutePath().parent
    return when {
      parent == null -> PreflightReport(
        listOf(
          PreflightIssue(
            code = PreflightCodes.PATH_NOT_WRITABLE,
            severity = PreflightSeverity.ERROR,
            message = "$label has no parent directory: $path",
            remediation = "Provide a file path inside an existing writable directory.",
            details = mapOf("path" to path.toString()),
          ),
        ),
      )

      !Files.isDirectory(parent) || !Files.isWritable(parent) -> PreflightReport(
        listOf(
          PreflightIssue(
            code = PreflightCodes.PATH_NOT_WRITABLE,
            severity = PreflightSeverity.ERROR,
            message = "$label parent directory is not writable: $parent",
            remediation = "Choose a path inside a writable directory.",
            details = mapOf("path" to path.toString(), "parent" to parent.toString()),
          ),
        ),
      )

      else -> PreflightReport()
    }
  }

  suspend fun requireTempWritable(): PreflightReport = withContext(Dispatchers.IO) {
    var tempFile: Path? = null
    try {
      tempFile = Files.createTempFile("verity-preflight-", ".tmp")
      PreflightReport()
    } catch (e: CancellationException) {
      throw e
    } catch (e: IOException) {
      PreflightReport(
        listOf(
          PreflightIssue(
            code = PreflightCodes.TEMP_NOT_WRITABLE,
            severity = PreflightSeverity.ERROR,
            message = "The runtime temp directory is not writable.",
            remediation = "Set a writable temp directory before running Verity.",
            details = mapOf("error" to (e.message ?: e::class.simpleName.orEmpty())),
          ),
        ),
      )
    } finally {
      val created = tempFile
      if (created != null) {
        withContext(NonCancellable + Dispatchers.IO) {
          Files.deleteIfExists(created)
        }
      }
    }
  }
}
