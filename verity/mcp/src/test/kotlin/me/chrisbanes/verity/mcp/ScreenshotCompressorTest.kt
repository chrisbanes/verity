package me.chrisbanes.verity.mcp

import assertk.assertThat
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThan
import assertk.assertions.isTrue
import java.awt.image.BufferedImage
import java.io.File
import java.util.Random
import javax.imageio.ImageIO
import kotlin.test.Test

class ScreenshotCompressorTest {
  @Test
  fun `compresses PNG to JPEG file`() {
    val tempPng = createTestPng(800, 600)
    try {
      val jpeg = ScreenshotCompressor.compress(tempPng)
      try {
        assertThat(jpeg.exists()).isTrue()
        assertThat(jpeg.length()).isGreaterThan(0)
      } finally {
        jpeg.delete()
      }
    } finally {
      tempPng.delete()
    }
  }

  @Test
  fun `scales down wide images`() {
    val tempPng = createNoisyTestPng(2560, 1440)
    try {
      val jpeg = ScreenshotCompressor.compress(tempPng, maxWidth = 1280)
      try {
        assertThat(jpeg.length()).isLessThan(tempPng.length())
      } finally {
        jpeg.delete()
      }
    } finally {
      tempPng.delete()
    }
  }

  @Test
  fun `does not upscale small images`() {
    val tempPng = createTestPng(640, 480)
    try {
      val jpeg = ScreenshotCompressor.compress(tempPng, maxWidth = 1280)
      try {
        val img = ImageIO.read(jpeg)
        assertThat(img.width).isLessThan(1280)
      } finally {
        jpeg.delete()
      }
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
