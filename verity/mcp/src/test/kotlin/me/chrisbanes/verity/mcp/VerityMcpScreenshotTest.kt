package me.chrisbanes.verity.mcp

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isIn
import assertk.assertions.isNotEqualTo
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.chrisbanes.verity.core.model.Platform
import me.chrisbanes.verity.device.DeviceSession
import me.chrisbanes.verity.device.FakeDeviceSession

class VerityMcpScreenshotTest {
  @Test
  fun `empty or incomplete captures return an error rather than a saved path`() = runTest {
    inDirectory { directory ->
      val target = directory.resolve("output.png")
      for (bytes in listOf(byteArrayOf(), fixturePng().copyOf(12))) {
        withServer(capture = { staging ->
          if (bytes.isNotEmpty()) withContext(Dispatchers.IO) { Files.write(staging, bytes) }
        }) { server, id, _ ->
          val result = call(server, id, target.toString())
          assertThat(result.isError).isEqualTo(true)
          assertThat(text(result)).contains("complete PNG")
          assertThat(text(result)).doesNotContain("Screenshot saved to:")
        }
        withContext(Dispatchers.IO) { assertThat(entries(directory)).isEqualTo(emptyList()) }
      }
    }
  }

  @Test
  fun `omitting save path retains one valid inline JPEG and removes PNG and JPEG temporaries`() = runTest {
    val png = fixturePng()
    val temporaryDirectory = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()
    val before = withContext(Dispatchers.IO) { inlineTemporaryEntries(temporaryDirectory) }
    var captured: Path? = null
    withServer(capture = { staging ->
      captured = staging
      withContext(Dispatchers.IO) { Files.write(staging, png) }
    }) { server, id, _ ->
      val result = call(server, id)
      assertThat(result.isError).isIn(null, false)
      val image = result.content.single() as ImageContent
      assertThat(image.mimeType).isEqualTo("image/jpeg")
      withContext(Dispatchers.IO) {
        ByteArrayInputStream(Base64.getDecoder().decode(image.data)).use { input ->
          val decoded = ImageIO.read(input)
          assertThat(decoded.width).isEqualTo(2)
          assertThat(decoded.height).isEqualTo(1)
        }
        assertThat(Files.exists(captured)).isFalse()
        assertThat(inlineTemporaryEntries(temporaryDirectory)).isEqualTo(before)
      }
    }
  }

  @Test
  fun `successful publication with failed cleanup reports an error and retains complete caller output`() = runTest {
    inDirectory { directory ->
      val target = directory.resolve("output.png")
      val sentinel = directory.resolve("unrelated-temp.png")
      val png = fixturePng()
      var owned: Path? = null
      withContext(Dispatchers.IO) { Files.writeString(sentinel, "sentinel bytes") }
      try {
        withServer(
          capture = { staging ->
            owned = staging
            withContext(Dispatchers.IO) { Files.write(staging, png) }
          },
          screenshotFileSaver = McpScreenshotFileSaver(deleteStaging = { throw IOException("known cleanup error") }),
        ) { server, id, _ ->
          val result = call(server, id, target.toString())
          assertThat(result.isError).isEqualTo(true)
          assertThat(text(result)).contains("Failed to delete owned screenshot staging file")
          assertThat(text(result)).contains(owned.toString())
          assertThat(text(result)).contains("Remove only")
          assertThat(text(result)).contains("may remain")
          assertThat(text(result)).doesNotContain("Screenshot saved to:")
        }
        withContext(Dispatchers.IO) {
          assertThat(Files.readAllBytes(target).toList()).isEqualTo(png.toList())
          assertThat(Files.readAllBytes(owned).toList()).isEqualTo(png.toList())
          val image = ImageIO.read(target.toFile())
          assertThat(image.width).isEqualTo(2)
          assertThat(image.height).isEqualTo(1)
          assertThat(image.getRGB(0, 0)).isEqualTo(0xff123456.toInt())
          assertThat(image.getRGB(1, 0)).isEqualTo(0xffabcdef.toInt())
          assertThat(Files.readString(sentinel)).isEqualTo("sentinel bytes")
          assertThat(entries(directory).toSet()).isEqualTo(setOf(target, owned, sentinel))
        }
      } finally {
        withContext(NonCancellable + Dispatchers.IO) { owned?.let { Files.deleteIfExists(it) } }
      }
      withContext(Dispatchers.IO) {
        assertThat(Files.readAllBytes(target).toList()).isEqualTo(png.toList())
        assertThat(entries(directory).toSet()).isEqualTo(setOf(target, sentinel))
      }
    }
  }

  @Test
  fun `capture failure with failed cleanup reports the retained owned stage and recovery`() = runTest {
    inDirectory { directory ->
      val target = directory.resolve("output.png")
      val sentinel = directory.resolve("unrelated-temp.png")
      var owned: Path? = null
      withContext(Dispatchers.IO) { Files.writeString(sentinel, "sentinel bytes") }
      try {
        withServer(
          capture = { staging ->
            owned = staging
            withContext(Dispatchers.IO) { Files.writeString(staging, "partial capture") }
            throw IOException("known capture error")
          },
          screenshotFileSaver = McpScreenshotFileSaver(deleteStaging = { throw IOException("known cleanup error") }),
        ) { server, id, _ ->
          val result = call(server, id, target.toString())
          assertThat(result.isError).isEqualTo(true)
          assertThat(text(result)).contains("known capture error")
          assertThat(text(result)).contains("Failed to delete owned screenshot staging file")
          assertThat(text(result)).contains(owned.toString())
          assertThat(text(result)).contains("Remove only")
          assertThat(text(result)).doesNotContain("Screenshot saved to:")
        }
        withContext(Dispatchers.IO) {
          assertThat(Files.readString(owned)).isEqualTo("partial capture")
          assertThat(Files.readString(sentinel)).isEqualTo("sentinel bytes")
          assertThat(entries(directory).toSet()).isEqualTo(setOf(owned, sentinel))
        }
      } finally {
        withContext(NonCancellable + Dispatchers.IO) { owned?.let { Files.deleteIfExists(it) } }
      }
      withContext(Dispatchers.IO) { assertThat(entries(directory)).isEqualTo(listOf(sentinel)) }
    }
  }

  @Test
  fun `caller cancellation escapes the safe tool unchanged and removes owned staging`() = runTest {
    inDirectory { directory ->
      val target = directory.resolve("output.png")
      val sentinel = directory.resolve("unrelated-temp.png")
      val cancellation = CancellationException("known caller cancellation")
      var observed: CancellationException? = null
      var returnedResponse = false
      withContext(Dispatchers.IO) { Files.writeString(sentinel, "sentinel bytes") }
      val task = async {
        withServer(capture = { staging ->
          withContext(Dispatchers.IO) { Files.write(staging, byteArrayOf(0x89.toByte(), 0x50)) }
          currentCoroutineContext().cancel(cancellation)
          throw cancellation
        }) { server, id, _ ->
          try {
            call(server, id, target.toString())
            returnedResponse = true
          } catch (error: CancellationException) {
            observed = error
          }
        }
      }
      task.join()
      assertThat(task.isCancelled).isTrue()
      assertThat(returnedResponse).isFalse()
      assertThat(observed).isSameInstanceAs(cancellation)
      withContext(Dispatchers.IO) {
        assertThat(Files.readString(sentinel)).isEqualTo("sentinel bytes")
        assertThat(entries(directory)).isEqualTo(listOf(sentinel))
      }
    }
  }

  @Test
  fun `capture prefix failure returns an error without output or an owned staging leak`() = runTest {
    inDirectory { directory ->
      val target = directory.resolve("output.png")
      val sentinel = directory.resolve("unrelated-temp.png")
      withContext(Dispatchers.IO) { Files.writeString(sentinel, "sentinel bytes") }
      withServer(capture = { staging ->
        withContext(Dispatchers.IO) { Files.write(staging, byteArrayOf(0x89.toByte(), 0x50)) }
        throw IOException("known capture error")
      }) { server, id, _ ->
        val result = call(server, id, target.toString())
        assertThat(result.isError).isEqualTo(true)
        assertThat(text(result)).contains("known capture error")
        assertThat(text(result)).doesNotContain("Screenshot saved to:")
      }
      withContext(Dispatchers.IO) {
        assertThat(Files.readString(sentinel)).isEqualTo("sentinel bytes")
        assertThat(entries(directory)).isEqualTo(listOf(sentinel))
      }
    }
  }

  @Test
  fun `a collision during real publication returns an error and preserves the racing output`() = runTest {
    inDirectory { directory ->
      val target = directory.resolve("output.png")
      val png = fixturePng()
      val saver = McpScreenshotFileSaver(publish = { destination, staging ->
        Files.writeString(destination, "racing output")
        Files.createLink(destination, staging)
      })
      withServer(
        capture = { staging -> withContext(Dispatchers.IO) { Files.write(staging, png) } },
        screenshotFileSaver = saver,
      ) { server, id, _ ->
        val result = call(server, id, target.toString())
        assertThat(result.isError).isEqualTo(true)
        assertThat(text(result)).contains("Choose another path")
      }
      withContext(Dispatchers.IO) {
        assertThat(Files.readString(target)).isEqualTo("racing output")
        assertThat(entries(directory)).isEqualTo(listOf(target))
      }
    }
  }

  @Test
  fun `existing output files and valid or dangling links remain unchanged`() = runTest {
    inDirectory { directory ->
      val original = directory.resolve("original.png")
      val valid = directory.resolve("valid.png")
      val dangling = directory.resolve("dangling.png")
      val missing = directory.resolve("missing.png")
      withContext(Dispatchers.IO) {
        Files.writeString(original, "original bytes")
        Files.createSymbolicLink(valid, original)
        Files.createSymbolicLink(dangling, missing)
      }
      var captured = false
      withServer(capture = { captured = true }) { server, id, _ ->
        for (target in listOf(original, valid, dangling)) {
          val result = call(server, id, target.toString())
          assertThat(result.isError).isEqualTo(true)
          assertThat(text(result)).contains("Choose another path")
          assertThat(text(result)).contains("explicitly remove")
          assertThat(captured).isFalse()
        }
      }
      withContext(Dispatchers.IO) {
        assertThat(Files.readString(original)).isEqualTo("original bytes")
        assertThat(Files.readSymbolicLink(valid)).isEqualTo(original)
        assertThat(Files.readSymbolicLink(dangling)).isEqualTo(missing)
        assertThat(entries(directory).toSet()).isEqualTo(setOf(original, valid, dangling))
      }
    }
  }

  @Test
  fun `missing parent retains structured preflight and saver permissions reject before capture`() = runTest {
    inDirectory { directory ->
      var captured = false
      withServer(capture = { captured = true }) { server, id, _ ->
        val result = call(server, id, directory.resolve("missing/output.png").toString())
        assertThat(result.isError).isEqualTo(true)
        assertThat(text(result)).contains("path.not_writable")
        assertThat(text(result)).contains("writable directory")
        assertThat(captured).isFalse()
      }
      withServer(
        capture = { captured = true },
        screenshotFileSaver = McpScreenshotFileSaver(isParentWritable = { false }),
      ) { server, id, _ ->
        val result = call(server, id, directory.resolve("output.png").toString())
        assertThat(result.isError).isEqualTo(true)
        assertThat(text(result)).contains("existing writable directory")
        assertThat(captured).isFalse()
      }
      withContext(Dispatchers.IO) { assertThat(entries(directory)).isEqualTo(emptyList()) }
    }
  }

  @Test
  fun `screenshot schema retains its string option and describes safe absolute output`() {
    val server = VerityMcpServer().create()
    val tool = server.tools["capture_screenshot"]!!.tool
    val option = tool.inputSchema.properties!!["save_to_file"]!!.jsonObject
    assertThat(option["type"]!!.jsonPrimitive.content).isEqualTo("string")
    assertThat(tool.inputSchema.required).isEqualTo(listOf("session_id"))
    assertThat(option["description"]!!.jsonPrimitive.content).contains("existing destinations")
    assertThat(option["description"]!!.jsonPrimitive.content).contains("absolute saved path")
    assertThat(server.tools.size).isEqualTo(14)
  }

  @Test
  fun `absolute and CWD relative paths return caller-owned normalized PNGs surviving registered close`() = runTest {
    inDirectory { directory ->
      val png = fixturePng()
      val captured = mutableListOf<Path>()
      val targets = listOf(directory.resolve("absolute.png"), directory.resolve("relative.png"))
      val cwd = Path.of("").toAbsolutePath().normalize()
      val inputs = listOf(
        targets[0].toString(),
        cwd.relativize(directory.resolve(".").resolve("unused").resolve("..").resolve("relative.png")).toString(),
      )
      withServer(capture = { staging ->
        captured.add(staging)
        withContext(Dispatchers.IO) { Files.write(staging, png) }
      }) { server, sessionId, fake ->
        for ((index, input) in inputs.withIndex()) {
          val result = call(server, sessionId, saveToFile = input)
          assertThat(result.isError).isIn(null, false)
          assertThat(text(result)).isEqualTo("Screenshot saved to: ${targets[index]}")
          assertThat(captured[index].parent).isEqualTo(directory)
          assertThat(captured[index]).isNotEqualTo(targets[index])
        }
        val closed = call(server, sessionId, name = "close_session")
        assertThat(closed.isError).isIn(null, false)
        assertThat(fake.closed).isTrue()
        withContext(Dispatchers.IO) {
          for (target in targets) {
            assertThat(Files.readAllBytes(target).toList()).isEqualTo(png.toList())
            val image = ImageIO.read(target.toFile())
            assertThat(image.width).isEqualTo(2)
            assertThat(image.height).isEqualTo(1)
            assertThat(image.getRGB(0, 0)).isEqualTo(0xff123456.toInt())
            assertThat(image.getRGB(1, 0)).isEqualTo(0xffabcdef.toInt())
          }
          assertThat(entries(directory).toSet()).isEqualTo(targets.toSet())
          targets.forEach { Files.delete(it) }
          assertThat(entries(directory)).isEqualTo(emptyList())
        }
      }
    }
  }

  private suspend fun withServer(
    capture: suspend (Path) -> Unit,
    screenshotFileSaver: McpScreenshotFileSaver = McpScreenshotFileSaver(),
    block: suspend (Server, UUID, FakeDeviceSession) -> Unit,
  ) {
    val fake = FakeDeviceSession()
    val session = object : DeviceSession by fake {
      override suspend fun captureScreenshot(output: Path) = capture(output)
    }
    val manager = McpDeviceSessionManager { _, _, _ -> session }
    val handle = manager.open(Platform.ANDROID_MOBILE, "fixture-device")
    val server = VerityMcpServer(sessionManager = manager, screenshotFileSaver = screenshotFileSaver).create()
    try {
      block(server, handle.sessionId, fake)
    } finally {
      withContext(NonCancellable) {
        if (manager.isOpen(handle.sessionId)) manager.close(handle.sessionId)
      }
    }
  }

  private suspend fun call(
    server: Server,
    sessionId: UUID,
    saveToFile: String? = null,
    name: String = "capture_screenshot",
  ): CallToolResult {
    val request = CallToolRequest(
      CallToolRequestParams(
        name,
        arguments = buildJsonObject {
          put("session_id", sessionId.toString())
          if (saveToFile != null) put("save_to_file", saveToFile)
        },
      ),
    )
    return server.tools[name]!!.handler.invoke(StubClientConnection(), request)
  }

  private fun text(result: CallToolResult): String = (result.content.single() as TextContent).text

  private suspend fun inDirectory(block: suspend (Path) -> Unit) {
    val directory = withContext(Dispatchers.IO) { Files.createTempDirectory("verity-mcp-screenshot-test-") }
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

  private suspend fun fixturePng(): ByteArray = withContext(Dispatchers.IO) {
    val image = BufferedImage(2, 1, BufferedImage.TYPE_INT_RGB)
    image.setRGB(0, 0, 0xff123456.toInt())
    image.setRGB(1, 0, 0xffabcdef.toInt())
    ByteArrayOutputStream().use { output ->
      ImageIO.write(image, "png", output)
      output.toByteArray()
    }
  }

  private fun entries(directory: Path): List<Path> = Files.list(directory).use { it.sorted().toList() }

  private fun inlineTemporaryEntries(directory: Path): Set<Path> = entries(directory)
    .filter { it.fileName.toString().startsWith("verity-screenshot-") }
    .toSet()
}
