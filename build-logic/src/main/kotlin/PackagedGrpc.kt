import java.io.File
import java.net.URLClassLoader
import java.util.zip.ZipFile
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** Packaging prerequisite: one Java gRPC family and Maestro's exact public JVM ABI. */
object PackagedGrpc {
  const val VERSION = "1.84.0"

  data class Artifact(val name: String, val version: String, val file: File)

  fun verifyCoordinates(scope: String, artifacts: List<Artifact>) {
    val java = artifacts.filter { it.name != "grpc-kotlin-stub" && it.name != "grpc-bom" }
    check(java.isNotEmpty()) { "$scope has no Java gRPC artifacts" }
    check(java.any { it.name == "grpc-okhttp" }) { "$scope is missing io.grpc:grpc-okhttp:$VERSION" }
    check(java.none { it.name == "grpc-netty" }) { "$scope includes forbidden unshaded io.grpc:grpc-netty" }
    val mixed = java.filter { it.version != VERSION }
    check(mixed.isEmpty()) { "$scope has mixed Java gRPC coordinates: ${mixed.map { "io.grpc:${it.name}:${it.version}" }}" }
  }

  fun verifyArchive(archive: File, artifacts: List<Artifact>): List<String> {
    verifyCoordinates("archive input", artifacts)
    val expected = mutableMapOf<String, ByteArray>()
    for (artifact in artifacts) {
      ZipFile(artifact.file).use { zip ->
        for (entry in zip.entries().asSequence().filter { it.name.startsWith("io/grpc/") && it.name.endsWith(".class") }) {
          val bytes = zip.getInputStream(entry).use { it.readBytes() }
          val previous = expected.put(entry.name, bytes)
          check(previous == null || previous.contentEquals(bytes)) { "Conflicting resolved class ${entry.name} in ${artifact.name}:${artifact.version}" }
        }
      }
    }
    ZipFile(archive).use { zip ->
      val actual = zip.entries().asSequence().filter { it.name.startsWith("io/grpc/") && it.name.endsWith(".class") }.toList()
      check(actual.map { it.name }.toSet() == expected.keys) { "Packaged gRPC class set differs: missing=${expected.keys - actual.map { it.name }.toSet()}, unexpected=${actual.map { it.name }.toSet() - expected.keys}" }
      for (entry in actual) {
        val bytes = zip.getInputStream(entry).use { it.readBytes() }
        check(bytes.contentEquals(expected.getValue(entry.name))) { "Packaged class ${entry.name} differs from resolved io.grpc:$VERSION inputs (mixed old provider/core)" }
      }
    }
    // Platform parent cannot hide missing production classes with Gradle's classpath.
    URLClassLoader(arrayOf(archive.toURI().toURL()), ClassLoader.getPlatformClassLoader()).use { loader ->
      return verifyBuilder(loader)
    }
  }

  fun verifyBuilder(loader: ClassLoader): List<String> {
    val builder = Class.forName("io.grpc.okhttp.OkHttpChannelBuilder", false, loader)
    val expected = listOf(
      "forAddress(Ljava/lang/String;I)Lio/grpc/okhttp/OkHttpChannelBuilder;",
      "usePlaintext()Lio/grpc/okhttp/OkHttpChannelBuilder;",
      "socketFactory(Ljavax/net/SocketFactory;)Lio/grpc/okhttp/OkHttpChannelBuilder;",
      "keepAliveTime(JLjava/util/concurrent/TimeUnit;)Lio/grpc/okhttp/OkHttpChannelBuilder;",
      "keepAliveTimeout(JLjava/util/concurrent/TimeUnit;)Lio/grpc/okhttp/OkHttpChannelBuilder;",
      "keepAliveWithoutCalls(Z)Lio/grpc/okhttp/OkHttpChannelBuilder;",
      "build()Lio/grpc/ManagedChannel;",
    )
    val lookup = java.lang.invoke.MethodHandles.publicLookup()
    // Resolve only the invoked public ABI. Enumerating every declared method also
    // resolves deprecated optional overload types that Maestro never calls.
    val chain = generateSequence(builder) { it.superclass }.map { it.name }.toList()
    for (signature in expected) {
      val name = signature.substringBefore('(')
      val type = java.lang.invoke.MethodType.fromMethodDescriptorString(signature.substring(name.length), loader)
      try {
        if (name == "forAddress") lookup.findStatic(builder, name, type) else lookup.findVirtual(builder, name, type)
      } catch (e: ReflectiveOperationException) {
        throw IllegalStateException("Missing Maestro public builder descriptor $signature; superclass chain=$chain", e)
      }
    }
    return expected
  }
}

/** Project-local input snapshots avoid inspecting other project models under isolation. */
abstract class VerifyPackagedGrpc : DefaultTask() {
  @get:Input abstract val coordinates: ListProperty<String>
  @get:Classpath abstract val grpcArtifacts: ConfigurableFileCollection
  @get:Optional @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val archive: RegularFileProperty
  @get:OutputFile abstract val receipt: RegularFileProperty

  @TaskAction
  fun verify() {
    val artifacts = coordinates.get().map { coordinate ->
      val (name, version, fileName) = coordinate.split(':')
      PackagedGrpc.Artifact(name, version, grpcArtifacts.files.single { it.name == fileName })
    }
    PackagedGrpc.verifyCoordinates(path, artifacts)
    val signatures = if (archive.isPresent) PackagedGrpc.verifyArchive(archive.get().asFile, artifacts) else emptyList()
    val hashes = artifacts.map { artifact ->
      "io.grpc:${artifact.name}:${artifact.version} sha256=${sha256(artifact.file)}"
    }.sorted()
    val output = receipt.get().asFile
    output.parentFile.mkdirs()
    output.writeText((hashes + signatures + if (archive.isPresent) listOf("archive_sha256=${sha256(archive.get().asFile)}") else emptyList()).joinToString("\n", postfix = "\n"))
    logger.lifecycle("${path}: Java gRPC ${PackagedGrpc.VERSION} verified; Maestro descriptors=${signatures.size}")
  }

  private fun sha256(file: File): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
      val buffer = ByteArray(8192)
      while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }
}