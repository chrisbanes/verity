package me.chrisbanes.verity.device

import kotlin.coroutines.cancellation.CancellationException

/** The complete capture operation exhausted its own caller-supplied budget. */
open class CaptureDeadlineExceededException(val operation: CaptureOperation) : CancellationException("${operation.name.lowercase()} capture deadline expired")

enum class CaptureOperation {
  HIERARCHY,
  SCREENSHOT,
}

internal class ScreenshotCaptureTimeoutException : CaptureDeadlineExceededException(CaptureOperation.SCREENSHOT)
