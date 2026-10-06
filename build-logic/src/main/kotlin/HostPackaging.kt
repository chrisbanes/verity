import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** Resource selection follows the loaders' resource groups, never device target ISA. */
object HostPackaging {
  enum class Host(val classifier: String) { UNIVERSAL(""), MACOS_ARM64("macos-aarch64"), LINUX_X64("linux-x86_64") }
  data class Platform(val os: String, val arch: String?)
  data class Entry(val name: String, val size: Long, val compressed: Long, val hash: String, val binary: String?, val nested: List<String>)
  data class Archive(val file: File, val hash: String, val entries: List<Entry>)

  fun os(value: String): String = when (value.lowercase()) {
    "darwin", "osx", "macos" -> "macos"
    "win32", "windows" -> "windows"
    else -> value.lowercase()
  }

  fun arch(value: String): String = when (value.lowercase()) {
    "aarch64", "arm64", "aarch_64" -> "arm64"
    "amd64", "x86_64", "x86-64" -> "x64"
    else -> value.lowercase()
  }

  fun platform(name: String): Platform? {
    if (name.startsWith("META-INF/native/")) {
      if (!name.substringAfterLast('/').matches(Regex("(?:lib)?(?:netty_quiche42|io_grpc_netty_shaded_netty_(?:tcnative|transport_native_epoll))_.+"))) return null
      if (name.contains("_transport_native_epoll_")) return Platform("linux", arch(name.substringAfter("_epoll_").substringBefore('.')))
      val match = Regex("(?:linux|osx|darwin|macos|windows)_(?:aarch_64|aarch64|arm64|x86_64|x86-64|amd64)").find(name) ?: return null
      val split = match.value.indexOf('_')
      return Platform(os(match.value.substring(0, split)), arch(match.value.substring(split + 1)))
    }
    if (name.startsWith("com/sun/jna/")) {
      val group = name.removePrefix("com/sun/jna/").substringBefore('/')
      if (!name.substringAfterLast('/').startsWith("libjnidispatch") && name.substringAfterLast('/') != "jnidispatch.dll") return null
      val split = group.indexOf('-')
      if (split < 0) return null
      return Platform(os(group.substring(0, split)), arch(group.substring(split + 1)))
    }
    if (name.startsWith("META-INF/resources/engine/libtruffleattach/")) {
      val parts = name.removePrefix("META-INF/resources/engine/libtruffleattach/").split('/')
      if (parts.size >= 3) return Platform(os(parts[0]), arch(parts[1]))
    }
    if (name.startsWith("org/openqa/selenium/manager/")) {
      val group = name.removePrefix("org/openqa/selenium/manager/").substringBefore('/')
      if (group in setOf("linux", "macos", "windows")) return Platform(group, if (group == "macos") null else "x64")
    }
    return null
  }

  fun ios(name: String): Boolean = name.startsWith("driver-iPhoneSimulator/") || name.startsWith("driver-iphoneos/")

  fun retain(name: String, host: Host): Boolean {
    if (host == Host.UNIVERSAL) return true
    if (ios(name)) return host == Host.MACOS_ARM64
    val platform = platform(name) ?: return true
    val expected = if (host == Host.MACOS_ARM64) Platform("macos", "arm64") else Platform("linux", "x64")
    return platform.os == expected.os && (platform.arch == null || platform.arch == expected.arch)
  }

  /** JVM class magic overlaps fat Mach-O. Validate its slice count and CPU table. */
  fun binary(header: ByteArray): String? {
    if (header.size < 20) return null
    val bytes = ByteBuffer.wrap(header)
    val magic = bytes.int
    val xcoff = bytes.getShort(0).toInt() and 0xffff
    if (xcoff in setOf(0x01df, 0x01f7)) return "XCOFF machine=${xcoff.toString(16)}"
    if (magic == 0x7f454c46) {
      bytes.order(if (header[5].toInt() == 1) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
      return "ELF machine=${bytes.getShort(18).toInt() and 0xffff} class=${header[4]}"
    }
    if (header[0] == 'M'.code.toByte() && header[1] == 'Z'.code.toByte()) {
      if (header.size >= 64) {
        bytes.order(ByteOrder.LITTLE_ENDIAN)
        val offset = bytes.getInt(60)
        if (offset >= 0 && offset + 6 <= header.size && bytes.getInt(offset) == 0x00004550) return "PE machine=${bytes.getShort(offset + 4).toInt() and 0xffff}"
      }
      return "PE header-unavailable"
    }
    if (String(header.take(8).toByteArray(), Charsets.US_ASCII) in setOf("!<arch>\n", "<bigaf>\n")) return "archive"
    if (magic in setOf(0xfeedface.toInt(), 0xfeedfacf.toInt(), 0xcefaedfe.toInt(), 0xcffaedfe.toInt())) {
      bytes.order(if (magic == 0xcefaedfe.toInt() || magic == 0xcffaedfe.toInt()) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
      return "Mach-O cpu=${bytes.getInt(4).toUInt().toString(16)}"
    }
    if (magic in setOf(0xcafebabe.toInt(), 0xbebafeca.toInt(), 0xcafebabf.toInt(), 0xbfbafeca.toInt())) {
      bytes.order(if (magic == 0xbebafeca.toInt() || magic == 0xbfbafeca.toInt()) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
      val count = bytes.getInt(4)
      val stride = if (magic == 0xcafebabf.toInt() || magic == 0xbfbafeca.toInt()) 32 else 20
      if (count !in 1..8 || header.size < 8 + stride * count) return null
      val cpus = (0 until count).map { bytes.getInt(8 + it * stride) }
      if (cpus.any { it !in setOf(7, 12, 0x1000007, 0x100000c) }) return null
      return "Mach-O fat cpus=${cpus.joinToString(",") { it.toUInt().toString(16) }}"
    }
    return null
  }

  fun snapshot(file: File): Archive = ZipFile(file).use { zip ->
    val entries = zip.entries().asSequence().filterNot { it.isDirectory }.map { entry ->
      val header = zip.getInputStream(entry).use { it.readNBytes(512) }
      val nested = if (ios(entry.name) && entry.name.endsWith(".zip")) nested(zip.getInputStream(entry).use { it.readBytes() }) else emptyList()
      Entry(entry.name, entry.size, entry.compressedSize, zip.getInputStream(entry).use { hash(it.readBytes()) }, binary(header), nested)
    }.toList()
    check(entries.map { it.name }.toSet().size == entries.size) { "Duplicate ZIP entry in ${file.name}" }
    Archive(file, hash(file), entries)
  }

  fun nested(bytes: ByteArray): List<String> {
    // Read the central directory too: Unix permissions are part of bundle identity.
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val end = (bytes.size - 22 downTo maxOf(0, bytes.size - 65557)).firstOrNull { buffer.getInt(it) == 0x06054b50 }
    check(end != null) { "Corrupt nested ZIP: missing central directory" }
    var offset = buffer.getInt(end + 16)
    val count = buffer.getShort(end + 10).toInt() and 0xffff
    check(count > 0) { "Empty nested iOS bundle" }
    val modes = mutableMapOf<String, Int>()
    repeat(count) {
      check(offset >= 0 && offset + 46 <= bytes.size && buffer.getInt(offset) == 0x02014b50) { "Corrupt nested ZIP central entry" }
      val length = buffer.getShort(offset + 28).toInt() and 0xffff
      val extra = buffer.getShort(offset + 30).toInt() and 0xffff
      val comment = buffer.getShort(offset + 32).toInt() and 0xffff
      val name = String(bytes, offset + 46, length, Charsets.UTF_8)
      check(modes.put(name, buffer.getInt(offset + 38) ushr 16) == null) { "Duplicate nested ZIP entry $name" }
      offset += 46 + length + extra + comment
    }
    val inventory = mutableListOf<String>()
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
      while (true) {
        val entry = zip.nextEntry ?: break
        val payload = zip.readBytes() // also verifies CRC
        inventory += "${entry.name}\t${payload.size}\t${modes.getValue(entry.name)}\t${hash(payload)}\t${binary(payload.take(512).toByteArray()) ?: "resource"}"
      }
    }
    check(inventory.size == count) { "Nested ZIP local/central entry mismatch" }
    return inventory.sorted()
  }

  fun verifyUniversal(archive: Archive) {
    val names = archive.entries.map { it.name }.toSet()
    check(setOf("maestro-app.apk", "maestro-server.apk").all { it in names }) { "Missing Android APK" }
    for (group in listOf("driver-iPhoneSimulator", "driver-iphoneos")) {
      check(archive.entries.count { it.name.startsWith("$group/") && it.nested.isNotEmpty() } == 2) { "Incomplete iOS ZIP group $group" }
      check("$group/maestro-driver-ios-config.xctestrun" in names) { "Missing iOS config $group" }
    }
    for (required in listOf(
      "com/sun/jna/darwin-aarch64/libjnidispatch.jnilib", "com/sun/jna/linux-x86-64/libjnidispatch.so",
      "META-INF/native/libnetty_quiche42_osx_aarch_64.jnilib", "META-INF/native/libnetty_quiche42_linux_x86_64.so",
      "META-INF/native/libio_grpc_netty_shaded_netty_tcnative_osx_aarch_64.jnilib", "META-INF/native/libio_grpc_netty_shaded_netty_tcnative_linux_x86_64.so",
      "META-INF/native/libio_grpc_netty_shaded_netty_transport_native_epoll_x86_64.so",
      "org/openqa/selenium/manager/macos/selenium-manager", "org/openqa/selenium/manager/linux/selenium-manager",
    )) check(required in names) { "Missing compatible native payload $required" }
    for (group in listOf("darwin/aarch64", "linux/amd64")) {
      val base = "META-INF/resources/engine/libtruffleattach/$group/"
      check(names.count { it.startsWith(base) } == 3 && "${base}files" in names && "${base}sha256" in names) { "Incomplete Truffle resource group $group" }
    }
    check(names.any { it.startsWith("META-INF/services/") } && names.any { it.endsWith(".kotlin_module") }) { "Missing service/Kotlin metadata" }
    for (entry in archive.entries) if (entry.binary != null) {
      val platform = platform(entry.name)
      check(platform != null) { "Unclassified native binary ${entry.name}: ${entry.binary}" }
      if (platform.os == "linux" && platform.arch in setOf("arm64", "x64")) {
        val machine = if (platform.arch == "arm64") 183 else 62
        check(entry.binary.startsWith("ELF machine=$machine ")) { "Native path/header architecture mismatch ${entry.name}: ${entry.binary}" }
      }
      if (platform.os == "macos") {
        check(entry.binary.startsWith("Mach-O")) { "Non-Mach-O macOS payload ${entry.name}: ${entry.binary}" }
        val cpu = if (platform.arch == "arm64") "100000c" else "1000007"
        if (platform.arch == null) check(entry.binary.contains("100000c") && entry.binary.contains("1000007")) { "Selenium macOS fat binary lacks supported slices" }
        check(entry.binary.contains(cpu)) { "Native path/header architecture mismatch ${entry.name}: ${entry.binary}" }
      }
    }
    val simulator = archive.entries.filter { it.name.startsWith("driver-iPhoneSimulator/") && it.nested.isNotEmpty() }
    check(simulator.all { zip -> zip.nested.any { it.contains("Mach-O fat cpus=1000007,100000c") } }) { "Missing simulator architecture slices" }
    check(simulator.any { zip -> zip.nested.any { it.contains("MaestroDriverLib.framework/MaestroDriverLib\t") } }) { "Missing simulator framework" }
  }

  fun verifyVariant(universal: Archive, variant: Archive, host: Host) {
    check(variant.entries.map { it.name }.toSet().size == variant.entries.size) { "Duplicate ZIP entry" }
    val expected = universal.entries.filter { retain(it.name, host) }.associateBy { it.name }
    val actual = variant.entries.associateBy { it.name }
    check(expected.keys == actual.keys) { "${host.classifier}: missing=${expected.keys - actual.keys}, unexpected=${actual.keys - expected.keys}" }
    for ((name, entry) in expected) {
      check(entry.hash == actual.getValue(name).hash && entry.nested == actual.getValue(name).nested) { "Changed retained payload $name" }
    }
  }

  fun hash(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
      val buffer = ByteArray(8192)
      while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
      }
    }
    return java.util.HexFormat.of().formatHex(digest.digest())
  }

  fun hash(bytes: ByteArray): String = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
}

abstract class VerifyHostJars : DefaultTask() {
  @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val universal: RegularFileProperty
  @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val macos: RegularFileProperty
  @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val linux: RegularFileProperty
  @get:Classpath abstract val runtimeArtifacts: ConfigurableFileCollection
  @get:Input abstract val coordinates: ListProperty<String>
  @get:OutputFile abstract val receipt: RegularFileProperty

  private fun expectRejected(name: String, block: () -> Unit) {
    val failure = runCatching(block).exceptionOrNull()
    check(failure is IllegalStateException) { "Negative fixture $name was not rejected: $failure" }
  }

  @TaskAction
  fun verify() {
    val baseline = HostPackaging.snapshot(universal.get().asFile)
    HostPackaging.verifyUniversal(baseline)
    val variants = listOf(
      HostPackaging.Host.UNIVERSAL to baseline,
      HostPackaging.Host.MACOS_ARM64 to HostPackaging.snapshot(macos.get().asFile),
      HostPackaging.Host.LINUX_X64 to HostPackaging.snapshot(linux.get().asFile),
    )
    val report = mutableListOf("format=host-packaging-v1")
    for (coordinate in coordinates.get().sorted()) {
      val file = runtimeArtifacts.files.single { it.name == coordinate.substringAfterLast('|') }
      report += "artifact\t$coordinate\t${HostPackaging.hash(file)}"
      if (file.extension == "jar") ZipFile(file).use { zip ->
        for (entry in zip.entries().asSequence().filterNot { it.isDirectory }) {
          val name = entry.name
          if (HostPackaging.platform(name) != null || HostPackaging.ios(name) || name in setOf("maestro-app.apk", "maestro-server.apk") || name.startsWith("META-INF/native-image/") || name.contains("NativeLibraryLoader") || name.endsWith("/Native.class") || name.contains("InternalResourceCache") || name.contains("SeleniumManager.class")) {
            report += "origin\t$coordinate\t$name\t${entry.size}\t${entry.compressedSize}\t${HostPackaging.hash(zip.getInputStream(entry).use { it.readBytes() })}"
          }
        }
      }
    }
    for ((host, archive) in variants) {
      HostPackaging.verifyVariant(baseline, archive, host)
      report += "archive\t${host.name}\t${archive.file.name}\t${archive.file.length()}\t${archive.hash}"
      for (entry in archive.entries) {
        report += "entry\t${host.name}\t${entry.name}\t${entry.size}\t${entry.compressed}\t${entry.hash}\t${entry.binary ?: "resource"}"
        report += entry.nested.map { "nested\t${host.name}\t${entry.name}\t$it" }
      }
      // Exact gRPC descriptor lookup under each archive's platform-only class loader.
      java.net.URLClassLoader(arrayOf(archive.file.toURI().toURL()), ClassLoader.getPlatformClassLoader()).use {
        report += PackagedGrpc.verifyBuilder(it).map { descriptor -> "abi\t${host.name}\t$descriptor" }
      }
    }
    // Negative mutations use entry/hash/header/nested snapshots of these actual archives.
    // Each comparison is the same path used for on-disk archive verification above.
    val mac = variants.single { it.first == HostPackaging.Host.MACOS_ARM64 }.second
    val removals = listOf("maestro-app.apk", "com/sun/jna/darwin-aarch64/libjnidispatch.jnilib", "driver-iPhoneSimulator/maestro-driver-ios-config.xctestrun",
      mac.entries.first { it.name.startsWith("META-INF/services/") }.name, mac.entries.first { it.name.endsWith(".kotlin_module") }.name)
    for (name in removals) {
      expectRejected("remove:$name") { HostPackaging.verifyVariant(baseline, mac.copy(entries = mac.entries.filterNot { it.name == name }), HostPackaging.Host.MACOS_ARM64) }
    }
    for (name in listOf("META-INF/native/netty_quiche42_windows_x86_64.dll", "META-INF/native/libnetty_quiche42_linux_x86_64.so", "META-INF/native/libnetty_quiche42_osx_x86_64.jnilib", "META-INF/resources/engine/libtruffleattach/windows/amd64/sha256")) {
      val forbidden = baseline.entries.first { it.name == name }
      expectRejected("insert:$name") { HostPackaging.verifyVariant(baseline, mac.copy(entries = mac.entries + forbidden), HostPackaging.Host.MACOS_ARM64) }
    }
    expectRejected("duplicate") { HostPackaging.verifyVariant(baseline, mac.copy(entries = mac.entries + mac.entries.first()), HostPackaging.Host.MACOS_ARM64) }
    val bundle = mac.entries.first { it.name.contains("iPhoneSimulator") && it.nested.any { nested -> nested.contains("MaestroDriverLib.framework/MaestroDriverLib\t") } }
    expectRejected("remove:simulator-framework") {
      HostPackaging.verifyVariant(baseline, mac.copy(entries = mac.entries.map { if (it == bundle) it.copy(nested = it.nested.filterNot { nested -> nested.contains("MaestroDriverLib.framework/MaestroDriverLib\t") }) else it }), HostPackaging.Host.MACOS_ARM64)
    }
    expectRejected("corrupt:nested-zip") { HostPackaging.nested(byteArrayOf(1, 2, 3)) }
    expectRejected("unknown-native") { HostPackaging.verifyUniversal(baseline.copy(entries = baseline.entries + mac.entries.first().copy(name = "unknown/payload", binary = "ELF"))) }
    report += "sensitivity\t13\tpassed\tactual-archive-snapshot-mutations"
    val output = receipt.get().asFile
    output.parentFile.mkdirs()
    output.writeText(report.joinToString("\n", postfix = "\n"))
    logger.lifecycle("$path: three archives verified; retained payloads byte-identical; native inventory classified")
  }
}
