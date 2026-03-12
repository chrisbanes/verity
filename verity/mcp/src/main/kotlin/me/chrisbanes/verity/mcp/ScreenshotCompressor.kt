package me.chrisbanes.verity.mcp

import java.awt.Image
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

object ScreenshotCompressor {

  fun compressToBase64(
    pngFile: File,
    maxWidth: Int = 1280,
    jpegQuality: Float = 0.75f,
  ): String {
    var image = ImageIO.read(pngFile)

    if (image.width > maxWidth) {
      val scale = maxWidth.toDouble() / image.width
      val newHeight = (image.height * scale).toInt()
      val scaled = BufferedImage(maxWidth, newHeight, BufferedImage.TYPE_INT_RGB)
      val g = scaled.createGraphics()
      g.drawImage(image.getScaledInstance(maxWidth, newHeight, Image.SCALE_SMOOTH), 0, 0, null)
      g.dispose()
      image = scaled
    }

    val baos = ByteArrayOutputStream()
    val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
    val param =
      writer.defaultWriteParam.apply {
        compressionMode = ImageWriteParam.MODE_EXPLICIT
        compressionQuality = jpegQuality
      }
    writer.output = ImageIO.createImageOutputStream(baos)
    writer.write(null, IIOImage(image, null, null), param)
    writer.dispose()

    return Base64.getEncoder().encodeToString(baos.toByteArray())
  }
}
