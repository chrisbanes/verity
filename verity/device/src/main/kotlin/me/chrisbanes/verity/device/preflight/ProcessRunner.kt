package me.chrisbanes.verity.device.preflight

import kotlinx.coroutines.Dispatchers
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
    val process = ProcessBuilder(command)
      .redirectErrorStream(true)
      .start()
    val output = process.inputStream.bufferedReader().readText()
    ProcessResult(exitCode = process.waitFor(), output = output)
  }
}
