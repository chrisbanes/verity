import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** Mutates the real ZIP directory/payload, retaining unrelated compressed bytes. No native loading. */
class HostPackagingArchiveTest {
  @Test
  fun `actual host ZIP rejects missing APK native config service and Kotlin metadata`() = archives { universal, macos ->
    val removals = listOf("maestro-app.apk", "com/sun/jna/darwin-aarch64/libjnidispatch.jnilib", "driver-iPhoneSimulator/maestro-driver-ios-config.xctestrun",
      macos.entries.first { it.name.startsWith("META-INF/services/") }.name, macos.entries.first { it.name.endsWith(".kotlin_module") }.name)
    for (name in removals) mutation(macos.file, remove = name) { file ->
      assertFailsWith<IllegalStateException> { HostPackaging.verifyVariant(universal, HostPackaging.snapshot(file), HostPackaging.Host.MACOS_ARM64) }
    }
  }

  @Test
  fun `actual ZIP rejects unsupported OS ISA sidecars and unknown binary`() = archives { universal, macos ->
    val source = "META-INF/native/libnetty_quiche42_osx_aarch_64.jnilib"
    for (name in listOf("META-INF/native/netty_quiche42_windows_x86_64.dll", "META-INF/native/libnetty_quiche42_linux_x86_64.so", "META-INF/native/libnetty_quiche42_osx_x86_64.jnilib", "META-INF/resources/engine/libtruffleattach/windows/amd64/sha256", "unknown/executable")) {
      mutation(macos.file, rename = source to name) { file ->
        assertFailsWith<IllegalStateException> { HostPackaging.verifyVariant(universal, HostPackaging.snapshot(file), HostPackaging.Host.MACOS_ARM64) }
      }
    }
    mutation(universal.file, rename = "com/sun/jna/freebsd-x86-64/libjnidispatch.so" to "unknown/executable") { file ->
      assertFailsWith<IllegalStateException> { HostPackaging.verifyUniversal(HostPackaging.snapshot(file)) }
    }
  }

  @Test
  fun `actual ZIP rejects duplicate corrupt nested bundle and missing nested framework`() = archives { universal, macos ->
    mutation(macos.file, duplicate = "maestro-app.apk") { file ->
      assertFailsWith<IllegalStateException> { HostPackaging.snapshot(file) }
    }
    val bundle = macos.entries.first { it.nested.any { nested -> nested.contains("MaestroDriverLib.framework/MaestroDriverLib\t") } }.name
    mutation(macos.file, corrupt = bundle) { file -> assertFailsWith<Exception> { HostPackaging.snapshot(file) } }
    val nestedBytes = ZipFile(macos.file).use { it.getInputStream(it.getEntry(bundle)).use { input -> input.readBytes() } }
    val missingFramework = ByteArrayOutputStream().also { output ->
      java.util.zip.ZipInputStream(nestedBytes.inputStream()).use { input ->
        ZipOutputStream(output).use { zip ->
          while (true) {
            val entry = input.nextEntry ?: break
            val bytes = input.readBytes()
            if (entry.name.contains("MaestroDriverLib.framework/")) continue
            zip.putNextEntry(ZipEntry(entry.name))
            zip.write(bytes)
            zip.closeEntry()
          }
        }
      }
    }.toByteArray()
    mutation(macos.file, replacement = bundle to missingFramework) { file ->
      assertFailsWith<IllegalStateException> { HostPackaging.verifyVariant(universal, HostPackaging.snapshot(file), HostPackaging.Host.MACOS_ARM64) }
    }
  }

  private fun archives(block: (HostPackaging.Archive, HostPackaging.Archive) -> Unit) {
    val universal = HostPackaging.snapshot(File(System.getProperty("hostPackaging.universal")))
    val macos = HostPackaging.snapshot(File(System.getProperty("hostPackaging.macos")))
    HostPackaging.verifyUniversal(universal)
    HostPackaging.verifyVariant(universal, macos, HostPackaging.Host.MACOS_ARM64)
    block(universal, macos)
  }

  private fun mutation(source: File, remove: String? = null, rename: Pair<String, String>? = null, duplicate: String? = null, corrupt: String? = null, replacement: Pair<String, ByteArray>? = null, block: (File) -> Unit) {
    val file = Files.createTempFile("host-archive-negative", ".jar").toFile()
    try {
      Files.copy(source.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
      RandomAccessFile(file, "rw").use { raf ->
        // Actual Shadow archives use ZIP64 counts, with <4GiB offsets and no ZIP comment.
        val end = ByteArray(98)
        raf.seek(raf.length() - end.size)
        raf.readFully(end)
        val tail = ByteBuffer.wrap(end).order(ByteOrder.LITTLE_ENDIAN)
        check(tail.getInt(0) == 0x06064b50 && tail.getInt(56) == 0x07064b50 && tail.getInt(76) == 0x06054b50)
        val start = tail.getLong(48)
        val directory = ByteArray(tail.getLong(40).toInt())
        raf.seek(start)
        raf.readFully(directory)
        val result = ByteArrayOutputStream()
        var offset = 0
        var count = 0L
        val original = ByteBuffer.wrap(directory).order(ByteOrder.LITTLE_ENDIAN)
        while (offset < directory.size) {
          check(original.getInt(offset) == 0x02014b50)
          val nameLength = original.getShort(offset + 28).toInt() and 0xffff
          val extraLength = original.getShort(offset + 30).toInt() and 0xffff
          val commentLength = original.getShort(offset + 32).toInt() and 0xffff
          val length = 46 + nameLength + extraLength + commentLength
          val name = String(directory, offset + 46, nameLength, Charsets.UTF_8)
          if (name == corrupt) {
            val local = original.getInt(offset + 42).toLong() and 0xffffffffL
            raf.seek(local + 26)
            val lengths = ByteArray(4).also { raf.readFully(it) }
            val localHeader = ByteBuffer.wrap(lengths).order(ByteOrder.LITTLE_ENDIAN)
            val payload = local + 30 + (localHeader.short.toInt() and 0xffff) + (localHeader.short.toInt() and 0xffff)
            raf.seek(payload)
            val first = raf.readByte()
            raf.seek(payload)
            raf.writeByte(first.toInt() xor 0xff)
          }
          if (name != remove && name != replacement?.first) {
            val bytes = if (name == rename?.first) {
              val renamed = rename.second.toByteArray()
              val header = directory.copyOfRange(offset, offset + 46)
              ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).putShort(28, renamed.size.toShort())
              header + renamed + directory.copyOfRange(offset + 46 + nameLength, offset + length)
            } else directory.copyOfRange(offset, offset + length)
            result.write(bytes)
            count++
            if (name == duplicate) { result.write(bytes); count++ }
          }
          offset += length
        }
        raf.setLength(start)
        raf.seek(start)
        var newStart = start
        if (replacement != null) {
          val tiny = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
              zip.putNextEntry(ZipEntry(replacement.first))
              zip.write(replacement.second)
              zip.closeEntry()
            }
          }.toByteArray()
          val tinyEnd = ByteBuffer.wrap(tiny).order(ByteOrder.LITTLE_ENDIAN)
          val tinyStart = tinyEnd.getInt(tiny.size - 6)
          val tinySize = tinyEnd.getInt(tiny.size - 10)
          raf.write(tiny, 0, tinyStart)
          val record = tiny.copyOfRange(tinyStart, tinyStart + tinySize)
          ByteBuffer.wrap(record).order(ByteOrder.LITTLE_ENDIAN).putInt(42, start.toInt())
          result.write(record)
          count++
          newStart += tinyStart
        }
        val central = result.toByteArray()
        raf.write(central)
        tail.putLong(24, count).putLong(32, count).putLong(40, central.size.toLong()).putLong(48, newStart)
        tail.putLong(64, newStart + central.size)
        tail.putInt(88, central.size).putInt(92, newStart.toInt())
        tail.putShort(84, minOf(count, 65535).toShort()).putShort(86, minOf(count, 65535).toShort())
        raf.write(end)
      }
      block(file)
    } finally {
      file.delete()
    }
  }
}
