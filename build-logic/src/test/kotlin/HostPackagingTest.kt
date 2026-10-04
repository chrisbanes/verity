import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertFailsWith

class HostPackagingTest {
  @Test
  fun `loader aliases normalize independently of executing host`() {
    for (os in listOf("darwin", "osx", "macos")) assertThat(HostPackaging.os(os)).isEqualTo("macos")
    for (arch in listOf("aarch64", "arm64", "aarch_64")) assertThat(HostPackaging.arch(arch)).isEqualTo("arm64")
    for (arch in listOf("amd64", "x86_64", "x86-64")) assertThat(HostPackaging.arch(arch)).isEqualTo("x64")
  }

  @Test
  fun `whole host resource groups follow each loader namespace`() {
    val mac = HostPackaging.Host.MACOS_ARM64
    val linux = HostPackaging.Host.LINUX_X64
    for (prefix in listOf("META-INF/native/libnetty_quiche42_", "META-INF/native/libio_grpc_netty_shaded_netty_tcnative_")) {
      assertThat(HostPackaging.retain("${prefix}osx_aarch_64.jnilib", mac)).isTrue()
      assertThat(HostPackaging.retain("${prefix}linux_x86_64.so", mac)).isFalse()
      assertThat(HostPackaging.retain("${prefix}linux_x86_64.so", linux)).isTrue()
      assertThat(HostPackaging.retain("${prefix}linux_aarch_64.so", linux)).isFalse()
      assertThat(HostPackaging.retain("${prefix}windows_x86_64.dll", linux)).isFalse()
    }
    assertThat(HostPackaging.retain("META-INF/native/libio_grpc_netty_shaded_netty_transport_native_epoll_x86_64.so", linux)).isTrue()
    assertThat(HostPackaging.retain("META-INF/native/libio_grpc_netty_shaded_netty_transport_native_epoll_aarch_64.so", mac)).isFalse()
    assertThat(HostPackaging.retain("com/sun/jna/darwin-aarch64/libjnidispatch.jnilib", mac)).isTrue()
    assertThat(HostPackaging.retain("com/sun/jna/linux-x86-64/libjnidispatch.so", linux)).isTrue()
    assertThat(HostPackaging.retain("com/sun/jna/win32-x86-64/jnidispatch.dll", mac)).isFalse()
    for (leaf in listOf("files", "sha256", "bin/libtruffleattach.so")) {
      assertThat(HostPackaging.retain("META-INF/resources/engine/libtruffleattach/linux/amd64/$leaf", linux)).isTrue()
      assertThat(HostPackaging.retain("META-INF/resources/engine/libtruffleattach/linux/aarch64/$leaf", linux)).isFalse()
    }
    assertThat(HostPackaging.retain("org/openqa/selenium/manager/macos/selenium-manager", mac)).isTrue()
    assertThat(HostPackaging.retain("org/openqa/selenium/manager/windows/selenium-manager.exe", mac)).isFalse()
  }

  @Test
  fun `device payloads and generic loader metadata are not host executables`() {
    for (host in HostPackaging.Host.entries) {
      for (name in listOf("maestro-app.apk", "maestro-server.apk", "io/grpc/ManagedChannel.class", "META-INF/native-image/windows/resource-config.json", "META-INF/services/provider", "META-INF/module.kotlin_module", "context/android.md", "a/windows-description.txt")) {
        assertThat(HostPackaging.retain(name, host)).isTrue()
      }
      assertThat(HostPackaging.retain("driver-iPhoneSimulator/maestro-driver-ios-config.xctestrun", host)).isEqualTo(host != HostPackaging.Host.LINUX_X64)
      assertThat(HostPackaging.retain("driver-iphoneos/Debug-iphoneos/maestro-driver-ios.zip", host)).isEqualTo(host != HostPackaging.Host.LINUX_X64)
    }
  }

  @Test
  fun `headers distinguish JVM bytecode from native binary`() {
    val jvm = ByteBuffer.allocate(512).putInt(0xcafebabe.toInt()).putInt(65).array()
    assertThat(HostPackaging.binary(jvm)).isEqualTo(null)
    val fat = ByteBuffer.allocate(512).putInt(0xcafebabe.toInt()).putInt(2).putInt(0x1000007).array()
    ByteBuffer.wrap(fat).putInt(28, 0x100000c)
    assertThat(HostPackaging.binary(fat)).isEqualTo("Mach-O fat cpus=1000007,100000c")
    assertThat(HostPackaging.binary(ByteArray(512).apply { this[0] = 1; this[1] = 0xdf.toByte() })).isEqualTo("XCOFF machine=1df")
    assertThat(HostPackaging.platform("META-INF/native/unknown_linux_x86_64.so")).isEqualTo(null)
    val elf = ByteArray(512).apply { this[0] = 0x7f; this[1] = 69; this[2] = 76; this[3] = 70; this[4] = 2; this[5] = 1; this[18] = 62 }
    assertThat(HostPackaging.binary(elf)).isEqualTo("ELF machine=62 class=2")
    assertThat(HostPackaging.binary(ByteArray(512).apply { this[0] = 77; this[1] = 90 })).isEqualTo("PE header-unavailable")
  }

  @Test
  fun `nested inventory retains hashes modes and architectures and rejects corrupt ZIP`() {
    val bytes = ByteArrayOutputStream().also { output ->
      ZipOutputStream(output).use { zip ->
        zip.putNextEntry(ZipEntry("app/Frameworks/library"))
        zip.write(byteArrayOf(1, 2, 3))
        zip.closeEntry()
      }
    }.toByteArray()
    assertThat(HostPackaging.nested(bytes).single().substringBefore('\t')).isEqualTo("app/Frameworks/library")
    assertFailsWith<IllegalStateException> { HostPackaging.nested(byteArrayOf(1, 2, 3)) }
    // Local payload corruption must fail CRC validation, not merely ZIP enumeration.
    val corrupted = bytes.copyOf().apply { this["app/Frameworks/library".length + 30] = 0 }
    assertFailsWith<Exception> { HostPackaging.nested(corrupted) }
  }

  @Test
  fun `missing changed forbidden and duplicate archive entries fail independently`() {
    val entry = HostPackaging.Entry("maestro-app.apk", 3, 3, "original", null, emptyList())
    val service = entry.copy(name = "META-INF/services/provider")
    val baseline = HostPackaging.Archive(File("fixture.jar"), "hash", listOf(entry, service))
    HostPackaging.verifyVariant(baseline, baseline, HostPackaging.Host.MACOS_ARM64)
    for (entries in listOf(listOf(service), listOf(entry), listOf(entry, service, service), listOf(entry.copy(hash = "changed"), service), listOf(entry, service, entry.copy(name = "META-INF/native/windows_x86_64.dll")))) {
      assertFailsWith<IllegalStateException> { HostPackaging.verifyVariant(baseline, baseline.copy(entries = entries), HostPackaging.Host.MACOS_ARM64) }
    }
    assertFailsWith<IllegalStateException> { HostPackaging.verifyUniversal(baseline) }
  }
}
