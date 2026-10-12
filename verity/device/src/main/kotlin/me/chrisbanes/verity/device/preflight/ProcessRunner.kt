package me.chrisbanes.verity.device.preflight

import java.nio.file.Files
import kotlin.io.path.readText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

data class ProcessResult(
  val exitCode: Int,
  val output: String,
)

fun interface ProcessRunner {
  suspend fun run(command: List<String>): ProcessResult
}

object LocalProcessRunner : ProcessRunner {
  override suspend fun run(command: List<String>): ProcessResult = withContext(Dispatchers.IO) {
    val outputFile = Files.createTempFile("verity-process-", ".out")
    try {
      // A file cannot fill up or be held open by a daemonized descendant, unlike a pipe.
      val process = ProcessBuilder(command)
        .redirectErrorStream(true)
        .redirectOutput(outputFile.toFile())
        .start()
      try {
        val exitCode = runInterruptible { process.waitFor() }
        ProcessResult(exitCode = exitCode, output = outputFile.readText())
      } finally {
        if (process.isAlive) {
          process.destroyForcibly()
          process.waitFor()
        }
      }
    } finally {
      Files.deleteIfExists(outputFile)
    }
  }
}
