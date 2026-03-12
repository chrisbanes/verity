package me.chrisbanes.verity.mcp

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThan
import assertk.assertions.isTrue
import java.awt.image.BufferedImage
import java.nio.file.Files
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
        assertThat(Files.exists(jpeg)).isTrue()
        assertThat(Files.size(jpeg)).isGreaterThan(0)
      } finally {
        Files.deleteIfExists(jpeg)
      }
    } finally {
      Files.deleteIfExists(tempPng)
    }
  }

  @Test
  fun `scales down images with scale factor`() {
    val tempPng = createNoisyTestPng(2560, 1440)
    try {
      val jpeg = ScreenshotCompressor.compress(tempPng, scale = 0.5f)
      try {
        assertThat(Files.size(jpeg)).isLessThan(Files.size(tempPng))
        val img = ImageIO.read(jpeg.toFile())
        assertThat(img.width).isEqualTo(1280)
        assertThat(img.height).isEqualTo(720)
      } finally {
        Files.deleteIfExists(jpeg)
      }
    } finally {
      Files.deleteIfExists(tempPng)
    }
  }

  @Test
  fun `scale of 1 preserves original dimensions`() {
    val tempPng = createTestPng(640, 480)
    try {
      val jpeg = ScreenshotCompressor.compress(tempPng, scale = 1f)
      try {
        val img = ImageIO.read(jpeg.toFile())
        assertThat(img.width).isEqualTo(640)
        assertThat(img.height).isEqualTo(480)
      } finally {
        Files.deleteIfExists(jpeg)
      }
    } finally {
      Files.deleteIfExists(tempPng)
    }
  }

  private fun createTestPng(width: Int, height: Int): java.nio.file.Path {
    val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val g = img.createGraphics()
    g.fillRect(0, 0, width, height)
    g.dispose()
    val file = Files.createTempFile("test-screenshot-", ".png")
    ImageIO.write(img, "PNG", file.toFile())
    return file
  }

  private fun createNoisyTestPng(width: Int, height: Int): java.nio.file.Path {
    val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val rng = Random(42)
    for (y in 0 until height) {
      for (x in 0 until width) {
        img.setRGB(x, y, rng.nextInt())
      }
    }
    val file = Files.createTempFile("test-screenshot-noisy-", ".png")
    ImageIO.write(img, "PNG", file.toFile())
    return file
  }
}
