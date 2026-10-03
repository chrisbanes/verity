package me.chrisbanes.verity.mcp

import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class McpScreenshotFileSaver(
  private val createStaging: (Path) -> Path = { parent -> Files.createTempFile(parent, ".verity-screenshot-", ".png") },
  private val isParentWritable: (Path) -> Boolean = Files::isWritable,
  private val publish: (Path, Path) -> Unit = { destination, staging -> Files.createLink(destination, staging) },
  private val deleteStaging: (Path) -> Boolean = Files::deleteIfExists,
) {
  suspend fun save(target: Path, capture: suspend (Path) -> Unit): Path {
    val destination = target.toAbsolutePath().normalize()
    var ownedStaging: Path? = null
    var primaryFailure: Exception? = null
    try {
      val staging = withContext(Dispatchers.IO) {
        val parent = destination.parent
          ?: throw IOException("Screenshot target must name a file in an existing writable directory: $destination. Choose a file path.")
        if (!Files.isDirectory(parent) || !isParentWritable(parent)) {
          throw IOException("Screenshot parent must be an existing writable directory: $parent. Choose an existing writable directory; parent directories are not created.")
        }
        if (Files.exists(destination, NOFOLLOW_LINKS)) throw collision(destination)
        createStaging(parent).also { ownedStaging = it }
      }
      capture(staging)
      withContext(Dispatchers.IO) {
        verifyPng(staging)
        // Admission ends here: cancellation after this check cannot revoke the blocking link operation.
        currentCoroutineContext().ensureActive()
        try {
          publish(destination, staging)
        } catch (error: FileAlreadyExistsException) {
          throw collision(destination).apply { initCause(error) }
        } catch (error: UnsupportedOperationException) {
          throw publicationFailure(destination, error)
        } catch (error: IOException) {
          throw publicationFailure(destination, error)
        }
      }
      return destination
    } catch (error: CancellationException) {
      primaryFailure = error
      throw error
    } catch (error: Exception) {
      primaryFailure = error
      throw error
    } finally {
      ownedStaging?.let { staging ->
        var cleanupDiagnostic: ScreenshotCleanupException? = null
        try {
          withContext(NonCancellable + Dispatchers.IO) {
            // Attach diagnostics before a dispatcher handoff can discard a cleanup result.
            runCatching { deleteStaging(staging) }.exceptionOrNull()?.let { cause ->
              val diagnostic = ScreenshotCleanupException(staging, cause)
              cleanupDiagnostic = diagnostic
              val primary = primaryFailure
              if (primary != null) primary.addSuppressed(diagnostic) else throw diagnostic
            }
          }
        } catch (error: CancellationException) {
          // A cancelled return dispatcher must not replace an already propagating primary error.
          primaryFailure?.let { throw it }
          cleanupDiagnostic?.let { error.addSuppressed(it) }
          throw error
        }
      }
    }
  }

  private fun collision(destination: Path): FileAlreadyExistsException = FileAlreadyExistsException(
    destination.toString(),
    null,
    "Screenshot destination already exists. Choose another path or explicitly remove the existing output before retrying.",
  )

  private fun publicationFailure(destination: Path, cause: Exception): IOException = IOException(
    "Could not publish screenshot to $destination. Choose an existing writable local directory on a filesystem supporting hard links.",
    cause,
  )

  private fun verifyPng(staging: Path) {
    val size = Files.size(staging)
    if (size < PNG_SIGNATURE.size + PNG_END.size) throw invalidPng(staging)
    Files.newInputStream(staging).use { input ->
      if (!input.readNBytes(PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE)) throw invalidPng(staging)
      input.skipNBytes(size - PNG_SIGNATURE.size - PNG_END.size)
      if (!input.readNBytes(PNG_END.size).contentEquals(PNG_END)) throw invalidPng(staging)
    }
    val reader = ImageIO.getImageReadersByFormatName("png").next()
    try {
      ImageIO.createImageInputStream(staging.toFile()).use { input ->
        reader.input = input
        val image = reader.read(0)
        if (image.width <= 0 || image.height <= 0) throw invalidPng(staging)
      }
    } catch (error: IOException) {
      throw invalidPng(staging, error)
    } finally {
      reader.dispose()
    }
  }

  private fun invalidPng(staging: Path, cause: IOException? = null): IOException = IOException(
    "Capture did not produce a complete PNG in $staging. Retry screenshot capture; no destination was published.",
    cause,
  )

  private companion object {
    val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
    val PNG_END = byteArrayOf(0, 0, 0, 0, 0x49, 0x45, 0x4e, 0x44, 0xae.toByte(), 0x42, 0x60, 0x82.toByte())
  }
}

internal class ScreenshotCleanupException(val stagingPath: Path, cause: Throwable) :
  IOException(
    "Failed to delete owned screenshot staging file $stagingPath; it may remain. Remove only this staging file when permissions and the filesystem allow. Any published screenshot remains caller-owned.",
    cause,
  )
