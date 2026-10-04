import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertFailsWith

class PackagedGrpcTest {
  @Test
  fun `mixed Java family fails with coordinate evidence`() {
    val failure = assertFailsWith<IllegalStateException> {
      PackagedGrpc.verifyCoordinates("fixture", listOf(artifact("grpc-okhttp", "1.50.2"), artifact("grpc-core")))
    }
    assertThat(failure.message!!).contains("io.grpc:grpc-okhttp:1.50.2")
  }

  @Test
  fun `independent Kotlin version is not forced to Java version`() {
    PackagedGrpc.verifyCoordinates("fixture", listOf(artifact("grpc-okhttp"), artifact("grpc-kotlin-stub", "1.5.0")))
  }

  @Test
  fun `unshaded Netty and missing OkHttp are rejected`() {
    assertFailsWith<IllegalStateException> { PackagedGrpc.verifyCoordinates("fixture", listOf(artifact("grpc-core"))) }
    assertFailsWith<IllegalStateException> { PackagedGrpc.verifyCoordinates("fixture", listOf(artifact("grpc-okhttp"), artifact("grpc-netty"))) }
  }

  @Test
  fun `complete aligned archive passes including inherited build descriptor`() = fixture { directory, jar ->
    val coordinates = listOf(PackagedGrpc.Artifact("grpc-okhttp", PackagedGrpc.VERSION, jar))
    assertThat(PackagedGrpc.verifyArchive(jar, coordinates).size).isEqualTo(7)
    val altered = File(directory, "mixed.jar")
    archive(directory, altered, corrupt = true)
    val failure = assertFailsWith<IllegalStateException> { PackagedGrpc.verifyArchive(altered, coordinates) }
    assertThat(failure.message!!).contains("io/grpc/ManagedChannel.class")
  }

  @Test
  fun `missing actual builder superclass fails under platform parent`() = fixture { directory, _ ->
    val incomplete = File(directory, "missing.jar")
    archive(directory, incomplete, missingSuperclass = true)
    URLClassLoader(arrayOf(incomplete.toURI().toURL()), ClassLoader.getPlatformClassLoader()).use { loader ->
      val failure = assertFailsWith<NoClassDefFoundError> { PackagedGrpc.verifyBuilder(loader) }
      assertThat(failure.message!!).contains("io/grpc/ForwardingChannelBuilder2")
    }
  }

  private fun artifact(name: String, version: String = PackagedGrpc.VERSION) = PackagedGrpc.Artifact(name, version, File("unused"))

  private fun fixture(block: (File, File) -> Unit) {
    val directory = Files.createTempDirectory("packaged-grpc-fixture").toFile()
    try {
      val sources = mapOf(
        "io/grpc/ManagedChannel.java" to "package io.grpc; public class ManagedChannel {}",
        "io/grpc/ForwardingChannelBuilder2.java" to "package io.grpc; public class ForwardingChannelBuilder2 { public ManagedChannel build() { return new ManagedChannel(); } }",
        "io/grpc/okhttp/OkHttpChannelBuilder.java" to """
          package io.grpc.okhttp;
          public class OkHttpChannelBuilder extends io.grpc.ForwardingChannelBuilder2 {
            public static OkHttpChannelBuilder forAddress(String host, int port) { return new OkHttpChannelBuilder(); }
            public OkHttpChannelBuilder usePlaintext() { return this; }
            public OkHttpChannelBuilder socketFactory(javax.net.SocketFactory value) { return this; }
            public OkHttpChannelBuilder keepAliveTime(long value, java.util.concurrent.TimeUnit unit) { return this; }
            public OkHttpChannelBuilder keepAliveTimeout(long value, java.util.concurrent.TimeUnit unit) { return this; }
            public OkHttpChannelBuilder keepAliveWithoutCalls(boolean value) { return this; }
          }
        """.trimIndent(),
      ).map { (name, content) -> File(directory, name).apply { parentFile.mkdirs(); writeText(content) } }
      val compiler = ToolProvider.getSystemJavaCompiler()
      assertThat(compiler.run(null, null, null, "-d", directory.path, *sources.map { it.path }.toTypedArray())).isEqualTo(0)
      val jar = File(directory, "aligned.jar")
      archive(directory, jar)
      block(directory, jar)
    } finally {
      directory.deleteRecursively()
    }
  }

  private fun archive(directory: File, jar: File, corrupt: Boolean = false, missingSuperclass: Boolean = false) {
    ZipOutputStream(jar.outputStream()).use { zip ->
      for (file in directory.walkTopDown().filter { it.extension == "class" }) {
        val path = file.relativeTo(directory).invariantSeparatorsPath
        if (missingSuperclass && path == "io/grpc/ForwardingChannelBuilder2.class") continue
        zip.putNextEntry(ZipEntry(path))
        zip.write(if (corrupt && path == "io/grpc/ManagedChannel.class") byteArrayOf(1, 2, 3) else file.readBytes())
        zip.closeEntry()
      }
    }
  }
}
