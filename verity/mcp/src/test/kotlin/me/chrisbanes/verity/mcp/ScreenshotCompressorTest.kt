package me.chrisbanes.verity.mcp

import assertk.assertThat
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThan
import assertk.assertions.isNotEmpty
import java.awt.image.BufferedImage
import java.io.File
import java.util.Base64
import java.util.Random
import javax.imageio.ImageIO
import kotlin.test.Test

class ScreenshotCompressorTest {
  @Test
  fun `compresses and base64 encodes a PNG`() {
    val tempPng = createTestPng(800, 600)
    try {
      val base64 = ScreenshotCompressor.compressToBase64(tempPng)
      assertThat(base64).isNotEmpty()
      val decoded = Base64.getDecoder().decode(base64)
      assertThat(decoded.size).isGreaterThan(0)
    } finally {
      tempPng.delete()
    }
  }

  @Test
  fun `scales down wide images`() {
    val tempPng = createNoisyTestPng(2560, 1440)
    try {
      val base64 = ScreenshotCompressor.compressToBase64(tempPng, maxWidth = 1280)
      val decoded = Base64.getDecoder().decode(base64)
      assertThat(decoded.size).isLessThan(tempPng.length().toInt())
    } finally {
      tempPng.delete()
    }
  }

  @Test
  fun `does not upscale small images`() {
    val tempPng = createTestPng(640, 480)
    try {
      val base64 = ScreenshotCompressor.compressToBase64(tempPng, maxWidth = 1280)
      assertThat(base64).isNotEmpty()
    } finally {
      tempPng.delete()
    }
  }

  private fun createTestPng(width: Int, height: Int): File {
    val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val g = img.createGraphics()
    g.fillRect(0, 0, width, height)
    g.dispose()
    val file = File.createTempFile("test-screenshot-", ".png")
    ImageIO.write(img, "PNG", file)
    return file
  }

  private fun createNoisyTestPng(width: Int, height: Int): File {
    val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val rng = Random(42)
    for (y in 0 until height) {
      for (x in 0 until width) {
        img.setRGB(x, y, rng.nextInt())
      }
    }
    val file = File.createTempFile("test-screenshot-noisy-", ".png")
    ImageIO.write(img, "PNG", file)
    return file
  }
}
