package me.chrisbanes.verity.mcp

import java.awt.Image
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

object ScreenshotCompressor {

  fun compress(
    pngFile: Path,
    maxWidth: Int = 1280,
    jpegQuality: Float = 0.75f,
  ): Path {
    var image = ImageIO.read(pngFile.toFile())

    if (image.width > maxWidth) {
      val scale = maxWidth.toDouble() / image.width
      val newHeight = (image.height * scale).toInt()
      val scaled = BufferedImage(maxWidth, newHeight, BufferedImage.TYPE_INT_RGB)
      val g = scaled.createGraphics()
      g.drawImage(image.getScaledInstance(maxWidth, newHeight, Image.SCALE_SMOOTH), 0, 0, null)
      g.dispose()
      image = scaled
    }

    val output = Files.createTempFile("verity-screenshot-", ".jpg")
    val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
    try {
      val param =
        writer.defaultWriteParam.apply {
          compressionMode = ImageWriteParam.MODE_EXPLICIT
          compressionQuality = jpegQuality
        }
      ImageIO.createImageOutputStream(output.toFile()).use { ios ->
        writer.output = ios
        writer.write(null, IIOImage(image, null, null), param)
      }
    } finally {
      writer.dispose()
    }

    return output
  }
}
