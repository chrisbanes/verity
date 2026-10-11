package me.chrisbanes.verity.mcp

import java.awt.Image
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

object ScreenshotCompressor {

  fun compress(
    pngFile: Path,
    scale: Float = 1f,
    jpegQuality: Float = 0.75f,
  ): ByteArray {
    var image = ImageIO.read(pngFile.toFile())

    if (scale < 1f) {
      val newWidth = (image.width * scale).toInt()
      val newHeight = (image.height * scale).toInt()
      val scaled = BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB)
      val g = scaled.createGraphics()
      g.drawImage(image.getScaledInstance(newWidth, newHeight, Image.SCALE_SMOOTH), 0, 0, null)
      g.dispose()
      image = scaled
    }

    val output = ByteArrayOutputStream()
    val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
    try {
      val param =
        writer.defaultWriteParam.apply {
          compressionMode = ImageWriteParam.MODE_EXPLICIT
          compressionQuality = jpegQuality
        }
      ImageIO.createImageOutputStream(output).use { ios ->
        writer.output = ios
        writer.write(null, IIOImage(image, null, null), param)
      }
    } finally {
      writer.dispose()
    }

    return output.toByteArray()
  }
}
