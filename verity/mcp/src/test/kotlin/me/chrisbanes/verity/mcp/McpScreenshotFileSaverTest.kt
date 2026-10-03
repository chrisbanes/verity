package me.chrisbanes.verity.mcp

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

class McpScreenshotFileSaverTest {
  @Test
  fun `a filesystem root is rejected as a screenshot target before capture`() = runTest {
    inDirectory { directory ->
      var captured = false
      val error = failure { McpScreenshotFileSaver().save(directory.root) { captured = true } }
      assertThat(error.message.orEmpty()).contains("existing writable directory")
      assertThat(captured).isFalse()
      withContext(Dispatchers.IO) { assertThat(entries(directory)).isEqualTo(emptyList()) }
    }
  }

  @Test
  fun `rejects malformed image data and an invalid terminal IEND checksum`() = runTest {
    inDirectory { directory ->
      val target = directory.resolve("output.png")
      val png = fixturePng()
      val corruptedImage = png.copyOf()
      val idat = png.indices.first { index ->
        index + 4 <= png.size && png.copyOfRange(index, index + 4).contentEquals(byteArrayOf(0x49, 0x44, 0x41, 0x54))
      }
      corruptedImage[idat + 4] = 0 // Invalid zlib header, preserving the PNG signature and terminal IEND.
      val corruptedEnd = png.copyOf().also { it[it.lastIndex] = 0 }
      for (bytes in listOf(corruptedImage, corruptedEnd)) {
        val error = failure {
          McpScreenshotFileSaver().save(target) { staging ->
            withContext(Dispatchers.IO) { Files.write(staging, bytes) }
          }
        }
        assertThat(error.message.orEmpty()).contains("complete PNG")
        withContext(Dispatchers.IO) { assertThat(entries(directory)).isEqualTo(emptyList()) }
      }
    }
  }

  @Test
  fun `failed staging deletion after publication reports recovery instead of returning success`() = runTest {
    inDirectory { directory ->
      val target = directory.resolve("output.png")
      val sentinel = directory.resolve("unrelated-temp.png")
      val deletionFailure = IOException("known deletion failure")
      var owned: Path? = null
      var returnedSuccess = false
      withContext(Dispatchers.IO) { Files.writeString(sentinel, "untouched sentinel") }
      try {
        val saver = McpScreenshotFileSaver(deleteStaging = { throw deletionFailure })
        val error = failure {
          saver.save(target) { staging ->
            owned = staging
            writePng(staging)
          }
          returnedSuccess = true
        }
        assertThat(returnedSuccess).isFalse()
        assertThat(error).isInstanceOf<ScreenshotCleanupException>()
        assertThat((error as ScreenshotCleanupException).stagingPath).isEqualTo(owned)
        assertThat(error.cause).isSameInstanceAs(deletionFailure)
        assertThat(error.message.orEmpty()).contains(owned.toString())
        assertThat(error.message.orEmpty()).contains("Remove only")
        assertThat(error.message.orEmpty()).contains("may remain")
        withContext(Dispatchers.IO) {
          assertThat(Files.readAllBytes(target).toList()).isEqualTo(fixturePng().toList())
          assertThat(Files.readAllBytes(owned).toList()).isEqualTo(fixturePng().toList())
          assertThat(Files.readString(sentinel)).isEqualTo("untouched sentinel")
          assertThat(entries(directory).toSet()).isEqualTo(setOf(owned, target, sentinel))
        }
      } finally {
        withContext(NonCancellable + Dispatchers.IO) { owned?.let { Files.deleteIfExists(it) } }
      }
      withContext(Dispatchers.IO) {
        assertThat(Files.readAllBytes(target).toList()).isEqualTo(fixturePng().toList())
        assertThat(entries(directory).toSet()).isEqualTo(setOf(target, sentinel))
      }
    }
  }

  @Test
  fun `failed staging deletion retains the exact caller cancellation with recovery diagnostics`() = runTest {
    inDirectory { directory ->
      val target = directory.resolve("output.png")
      val sentinel = directory.resolve("unrelated-temp.png")
      val primary = CancellationException("known caller cancellation")
      val deletionFailure = IOException("known deletion failure")
      var owned: Path? = null
      var observed: Throwable? = null
      withContext(Dispatchers.IO) { Files.writeString(sentinel, "untouched sentinel") }
      try {
        val task = async {
          val saver = McpScreenshotFileSaver(deleteStaging = { throw deletionFailure })
          observed = failure {
            saver.save(target) { staging ->
              owned = staging
              withContext(Dispatchers.IO) { Files.writeString(staging, "partial capture") }
              currentCoroutineContext().cancel(primary)
              throw primary
            }
          }
        }
        task.join()
        assertThat(task.isCancelled).isTrue()
        assertThat(observed).isSameInstanceAs(primary)
        val diagnostic = primary.suppressed.single()
        assertThat(diagnostic).isInstanceOf<ScreenshotCleanupException>()
        assertThat((diagnostic as ScreenshotCleanupException).stagingPath).isEqualTo(owned)
        assertThat(diagnostic.cause).isSameInstanceAs(deletionFailure)
        assertThat(diagnostic.message.orEmpty()).contains(owned.toString())
        assertThat(diagnostic.message.orEmpty()).contains("Remove only")
        withContext(Dispatchers.IO) {
          assertThat(Files.readString(owned)).isEqualTo("partial capture")
          assertThat(Files.readString(sentinel)).isEqualTo("untouched sentinel")
          assertThat(entries(directory).toSet()).isEqualTo(setOf(owned, sentinel))
        }
      } finally {
        withContext(NonCancellable + Dispatchers.IO) { owned?.let { Files.deleteIfExists(it) } }
      }
      withContext(Dispatchers.IO) { assertThat(entries(directory)).isEqualTo(listOf(sentinel)) }
    }
  }

  @Test
  fun `failed staging deletion retains the exact capture error with recovery diagnostics`() = runTest {
    inDirectory { directory ->
      val target = directory.resolve("output.png")
      val sentinel = directory.resolve("unrelated-temp.png")
      val primary = IOException("known capture failure")
      val deletionFailure = IOException("known deletion failure")
      var owned: Path? = null
      withContext(Dispatchers.IO) { Files.writeString(sentinel, "untouched sentinel") }
      try {
        val saver = McpScreenshotFileSaver(deleteStaging = { throw deletionFailure })
        val error = failure {
          saver.save(target) { staging ->
            owned = staging
            withContext(Dispatchers.IO) { Files.writeString(staging, "partial capture") }
            throw primary
          }
        }
        assertThat(error).isSameInstanceAs(primary)
        val diagnostic = error.suppressed.single()
        assertThat(diagnostic).isInstanceOf<ScreenshotCleanupException>()
        assertThat((diagnostic as ScreenshotCleanupException).stagingPath).isEqualTo(owned)
        assertThat(diagnostic.cause).isSameInstanceAs(deletionFailure)
        assertThat(diagnostic.message.orEmpty()).contains(owned.toString())
        assertThat(diagnostic.message.orEmpty()).contains("Remove only")
        withContext(Dispatchers.IO) {
          assertThat(Files.readString(owned)).isEqualTo("partial capture")
          assertThat(Files.readString(sentinel)).isEqualTo("untouched sentinel")
          assertThat(entries(directory).toSet()).isEqualTo(setOf(owned, sentinel))
        }
      } finally {
        // Deliberate retained-stage fixture recovery bypasses the failed deletion injection.
        withContext(NonCancellable + Dispatchers.IO) { owned?.let { Files.deleteIfExists(it) } }
      }
      withContext(Dispatchers.IO) { assertThat(entries(directory)).isEqualTo(listOf(sentinel)) }
    }
  }

  @Test
  fun `capture prefix failure or cancellation preserves sentinels and propagates the original error`() = runTest {
    inDirectory { directory ->
      val target = directory.resolve("output.png")
      val sentinel = directory.resolve("unrelated-temp.png")
      withContext(Dispatchers.IO) { Files.writeString(sentinel, "leave this alone") }
      for (primary in listOf(IOException("capture failed"), CancellationException("capture cancelled"))) {
        val error = failure {
          McpScreenshotFileSaver().save(target) { staging ->
            withContext(Dispatchers.IO) { Files.write(staging, byteArrayOf(0x89.toByte(), 0x50)) }
            throw primary
          }
        }
        assertThat(error).isSameInstanceAs(primary)
        withContext(Dispatchers.IO) {
          assertThat(Files.readString(sentinel)).isEqualTo("leave this alone")
          assertThat(entries(directory)).isEqualTo(listOf(sentinel))
        }
      }
    }
  }

  @Test
  fun `cancellation after admission or link commit retains the complete published output`() = runTest {
    inDirectory { directory ->
      val target = directory.resolve("output.png")
      for (cancelBeforeLink in listOf(true, false)) {
        var returnedSuccess = false
        val task = async {
          val caller = currentCoroutineContext().job
          val cancel = { caller.cancel(CancellationException("cancel after admission")) }
          val saver = McpScreenshotFileSaver(publish = { destination, staging ->
            if (cancelBeforeLink) cancel()
            Files.createLink(destination, staging)
            if (!cancelBeforeLink) cancel()
          })
          saver.save(target) { writePng(it) }
          returnedSuccess = true
        }
        task.join()
        assertThat(task.isCancelled).isTrue()
        assertThat(returnedSuccess).isFalse()
        withContext(Dispatchers.IO) {
          assertThat(Files.readAllBytes(target).toList()).isEqualTo(fixturePng().toList())
          val image = ImageIO.read(target.toFile())
          assertThat(image.width).isEqualTo(2)
          assertThat(image.height).isEqualTo(1)
          assertThat(image.getRGB(0, 0)).isEqualTo(0xff123456.toInt())
          assertThat(image.getRGB(1, 0)).isEqualTo(0xffabcdef.toInt())
          assertThat(entries(directory)).isEqualTo(listOf(target))
          Files.delete(target)
        }
      }
    }
  }

  @Test
  fun `cancellation observed before publication leaves no destination`() = runTest {
    inDirectory { directory ->
      val target = directory.resolve("output.png")
      var publicationAttempted = false
      val task = async {
        val saver = McpScreenshotFileSaver(publish = { destination, staging ->
          publicationAttempted = true
          Files.createLink(destination, staging)
        })
        saver.save(target) { staging ->
          writePng(staging)
          currentCoroutineContext().cancel(CancellationException("cancel before admission"))
        }
      }
      task.join()
      assertThat(task.isCancelled).isTrue()
      assertThat(publicationAttempted).isFalse()
      withContext(Dispatchers.IO) { assertThat(entries(directory)).isEqualTo(emptyList()) }
    }
  }

  @Test
  fun `cancellation during allocation handoff cleans the owned staging file`() = runTest {
    inDirectory { directory ->
      val target = directory.resolve("output.png")
      var allocated: Path? = null
      var captured = false
      val task = async {
        val caller = currentCoroutineContext().job
        val saver = McpScreenshotFileSaver(createStaging = { parent ->
          Files.createTempFile(parent, "owned-stage-", ".png").also {
            allocated = it
            caller.cancel(CancellationException("cancel during allocation"))
          }
        })
        saver.save(target) { captured = true }
      }
      task.join()
      assertThat(task.isCancelled).isTrue()
      assertThat(allocated).isNotNull()
      assertThat(captured).isFalse()
      withContext(Dispatchers.IO) { assertThat(entries(directory)).isEqualTo(emptyList()) }
    }
  }

  @Test
  fun `rejects no-op JPEG truncated and missing-IEND captures before publication`() = runTest {
    inDirectory { directory ->
      val target = directory.resolve("output.png")
      val png = fixturePng()
      val jpeg = withContext(Dispatchers.IO) {
        ByteArrayOutputStream().use { output ->
          ImageIO.write(BufferedImage(2, 1, BufferedImage.TYPE_INT_RGB), "jpeg", output)
          output.toByteArray()
        }
      }
      for (bytes in listOf(byteArrayOf(), jpeg, png.copyOf(png.size / 2), png.copyOf(png.size - 12))) {
        val error = failure {
          McpScreenshotFileSaver().save(target) { staging ->
            if (bytes.isNotEmpty()) withContext(Dispatchers.IO) { Files.write(staging, bytes) }
          }
        }
        assertThat(error.message.orEmpty()).contains("complete PNG")
        withContext(Dispatchers.IO) { assertThat(entries(directory)).isEqualTo(emptyList()) }
      }
    }
  }

  @Test
  fun `unsupported or failed publication recommends a hard link capable writable directory`() = runTest {
    inDirectory { directory ->
      val target = directory.resolve("output.png")
      for (cause in listOf(UnsupportedOperationException("unsupported link"), IOException("link failed"))) {
        val saver = McpScreenshotFileSaver(publish = { _, _ -> throw cause })
        val error = failure { saver.save(target) { writePng(it) } }
        assertThat(error.message.orEmpty()).contains("existing writable local directory")
        assertThat(error.message.orEmpty()).contains("hard links")
        assertThat(generateSequence(error) { it.cause }.last()).isSameInstanceAs(cause)
        withContext(Dispatchers.IO) { assertThat(entries(directory)).isEqualTo(emptyList()) }
      }
    }
  }

  @Test
  fun `two independent saves have one complete winner and one collision`() = runTest {
    inDirectory { directory ->
      val target = directory.resolve("output.png")
      val firstCaptured = CompletableDeferred<Unit>()
      val secondCaptured = CompletableDeferred<Unit>()
      val release = CompletableDeferred<Unit>()
      val firstPng = fixturePng()
      val secondPng = fixturePng(0xff654321.toInt())
      suspend fun attempt(bytes: ByteArray, ready: CompletableDeferred<Unit>): Result<Path> = try {
        Result.success(
          McpScreenshotFileSaver().save(target) { staging ->
            withContext(Dispatchers.IO) { Files.write(staging, bytes) }
            ready.complete(Unit)
            release.await()
          },
        )
      } catch (error: IOException) {
        Result.failure(error)
      }
      val first = async { attempt(firstPng, firstCaptured) }
      val second = async { attempt(secondPng, secondCaptured) }
      firstCaptured.await()
      secondCaptured.await()
      release.complete(Unit)
      val results = listOf(first.await(), second.await())
      assertThat(results.count { it.isSuccess }).isEqualTo(1)
      val collision = results.single { it.isFailure }.exceptionOrNull()
      assertThat(collision).isNotNull().isInstanceOf<FileAlreadyExistsException>()
      assertThat(collision?.message.orEmpty()).contains("Choose another path")
      val expected = if (results[0].isSuccess) firstPng else secondPng
      withContext(Dispatchers.IO) {
        assertThat(Files.readAllBytes(target).toList()).isEqualTo(expected.toList())
        val image = ImageIO.read(target.toFile())
        assertThat(image.width).isEqualTo(2)
        assertThat(image.height).isEqualTo(1)
        assertThat(image.getRGB(0, 0)).isEqualTo(if (results[0].isSuccess) 0xff123456.toInt() else 0xff654321.toInt())
        assertThat(image.getRGB(1, 0)).isEqualTo(0xffabcdef.toInt())
        assertThat(entries(directory)).isEqualTo(listOf(target))
      }
    }
  }

  @Test
  fun `a destination racing publication is preserved by real hard link creation`() = runTest {
    inDirectory { directory ->
      val target = directory.resolve("output.png")
      val missing = directory.resolve("missing")
      for (symlink in listOf(false, true)) {
        val saver = McpScreenshotFileSaver(publish = { destination, staging ->
          if (symlink) {
            Files.createSymbolicLink(destination, missing)
          } else {
            Files.writeString(destination, "racing output")
          }
          Files.createLink(destination, staging)
        })
        val error = failure { saver.save(target) { writePng(it) } }
        assertThat(error).isInstanceOf<FileAlreadyExistsException>()
        assertThat(error.message.orEmpty()).contains("Choose another path")
        withContext(Dispatchers.IO) {
          if (symlink) {
            assertThat(Files.readSymbolicLink(target)).isEqualTo(missing)
          } else {
            assertThat(Files.readString(target)).isEqualTo("racing output")
          }
          assertThat(entries(directory)).isEqualTo(listOf(target))
          Files.delete(target)
        }
      }
    }
  }

  @Test
  fun `preserves existing files directories and valid or dangling symlinks before capture`() = runTest {
    inDirectory { directory ->
      val original = directory.resolve("original")
      val folder = directory.resolve("folder")
      val validLink = directory.resolve("valid-link")
      val danglingLink = directory.resolve("dangling-link")
      val missing = directory.resolve("missing")
      withContext(Dispatchers.IO) {
        Files.writeString(original, "original bytes")
        Files.createDirectory(folder)
        Files.createSymbolicLink(validLink, original)
        Files.createSymbolicLink(danglingLink, missing)
      }
      for (target in listOf(original, folder, validLink, danglingLink)) {
        var captured = false
        val error = failure { McpScreenshotFileSaver().save(target) { captured = true } }
        assertThat(error).isInstanceOf<FileAlreadyExistsException>()
        assertThat(error.message.orEmpty()).contains("Choose another path")
        assertThat(error.message.orEmpty()).contains("explicitly remove")
        assertThat(captured).isFalse()
      }
      withContext(Dispatchers.IO) {
        assertThat(Files.readString(original)).isEqualTo("original bytes")
        assertThat(Files.readSymbolicLink(validLink)).isEqualTo(original)
        assertThat(Files.readSymbolicLink(danglingLink)).isEqualTo(missing)
        assertThat(entries(folder)).isEqualTo(emptyList())
        assertThat(entries(directory)).isEqualTo(listOf(danglingLink, folder, original, validLink))
      }
    }
  }

  @Test
  fun `rejects an unwritable parent including the real POSIX permission check`() = runTest {
    inDirectory { directory ->
      val target = directory.resolve("output.png")
      var captured = false
      val error = failure {
        McpScreenshotFileSaver(isParentWritable = { false }).save(target) { captured = true }
      }
      assertThat(error.message.orEmpty()).contains("existing writable directory")
      assertThat(captured).isFalse()
      withContext(Dispatchers.IO) { assertThat(entries(directory)).isEqualTo(emptyList()) }

      val permissions = withContext(Dispatchers.IO) { Files.getPosixFilePermissions(directory) }
      try {
        withContext(Dispatchers.IO) {
          Files.setPosixFilePermissions(
            directory,
            permissions - setOf(
              PosixFilePermission.OWNER_WRITE,
              PosixFilePermission.GROUP_WRITE,
              PosixFilePermission.OTHERS_WRITE,
            ),
          )
        }
        // Privileged runners may still be writable; the injected check above always runs.
        if (!withContext(Dispatchers.IO) { Files.isWritable(directory) }) {
          val realError = failure { McpScreenshotFileSaver().save(target) { captured = true } }
          assertThat(realError.message.orEmpty()).contains("existing writable directory")
          assertThat(captured).isFalse()
        }
      } finally {
        withContext(NonCancellable + Dispatchers.IO) { Files.setPosixFilePermissions(directory, permissions) }
      }
    }
  }

  @Test
  fun `requires an existing writable directory before capture`() = runTest {
    inDirectory { directory ->
      val regularParent = directory.resolve("regular-parent")
      withContext(Dispatchers.IO) { Files.writeString(regularParent, "untouched") }
      val targets = listOf(directory.resolve("missing/output.png"), regularParent.resolve("output.png"))
      for (target in targets) {
        var captured = false
        val error = failure { McpScreenshotFileSaver().save(target) { captured = true } }
        assertThat(error).isInstanceOf<IOException>()
        assertThat(error.message.orEmpty()).contains("existing writable directory")
        assertThat(captured).isFalse()
      }
      withContext(Dispatchers.IO) {
        assertThat(Files.readString(regularParent)).isEqualTo("untouched")
        assertThat(entries(directory)).isEqualTo(listOf(regularParent))
      }
    }
  }

  @Test
  fun `publishes the complete PNG and removes only its sibling staging file`() = runTest {
    inDirectory { directory ->
      val target = directory.resolve("screenshot.png")
      val png = fixturePng()
      val result = McpScreenshotFileSaver().save(target) { staging ->
        assertThat(staging.parent).isEqualTo(directory)
        assertThat(staging).isNotEqualTo(target)
        withContext(Dispatchers.IO) { Files.write(staging, png) }
      }

      assertThat(result).isEqualTo(target)
      withContext(Dispatchers.IO) {
        assertThat(Files.readAllBytes(target).toList()).isEqualTo(png.toList())
        val image = ImageIO.read(target.toFile())
        assertThat(image.width).isEqualTo(2)
        assertThat(image.height).isEqualTo(1)
        assertThat(image.getRGB(0, 0)).isEqualTo(0xff123456.toInt())
        assertThat(image.getRGB(1, 0)).isEqualTo(0xffabcdef.toInt())
        assertThat(entries(directory)).isEqualTo(listOf(target))
      }
    }
  }

  private suspend fun inDirectory(block: suspend (Path) -> Unit) {
    val directory = withContext(Dispatchers.IO) { Files.createTempDirectory("verity-screenshot-test-") }
    try {
      block(directory)
    } finally {
      withContext(NonCancellable + Dispatchers.IO) {
        Files.walk(directory).use { paths ->
          paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
      }
    }
  }

  private suspend fun failure(block: suspend () -> Unit): Throwable = try {
    block()
    error("Expected save to fail")
  } catch (error: CancellationException) {
    // These fixtures explicitly inspect cancellation propagation at the saver boundary.
    error
  } catch (error: IOException) {
    error
  }

  private suspend fun fixturePng(firstPixel: Int = 0xff123456.toInt()): ByteArray = withContext(Dispatchers.IO) {
    val image = BufferedImage(2, 1, BufferedImage.TYPE_INT_RGB)
    image.setRGB(0, 0, firstPixel)
    image.setRGB(1, 0, 0xffabcdef.toInt())
    ByteArrayOutputStream().use { output ->
      ImageIO.write(image, "png", output)
      output.toByteArray()
    }
  }

  private suspend fun writePng(path: Path) = withContext(Dispatchers.IO) { Files.write(path, fixturePng()) }

  private fun entries(directory: Path): List<Path> = Files.list(directory).use { it.sorted().toList() }
}
