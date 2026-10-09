package me.chrisbanes.verity.smoke

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test

/** Runs each dynamic-entry probe against the universal and universal-shrunk archives; shrunk must match. */
class PackagedDynamicEntryTest {
  private val jars = checkNotNull(System.getProperty("verity.packaged.cli.options.jars")).split(File.pathSeparator).map(::File)
  private val probe = File(checkNotNull(System.getProperty("verity.packaged.probe.jar")))

  private fun run(jar: File, mode: String, env: Map<String, String> = emptyMap()): List<String> {
    val directory = Files.createTempDirectory("packaged-dynamic-entry").toFile()
    try {
      val stdout = File(directory, "stdout")
      val stderr = File(directory, "stderr")
      val process = ProcessBuilder(
        File(System.getProperty("java.home"), "bin/java").path,
        "-Xmx512m",
        "-cp",
        "${jar.absolutePath}${File.pathSeparator}${probe.absolutePath}",
        "me.chrisbanes.verity.smoke.PackagedRuntimeProbe",
        mode,
      ).directory(directory).redirectOutput(stdout).redirectError(stderr).apply { environment().putAll(env) }.start()
      val child = PackagedChild(process, stdout, stderr)
      try {
        check(process.waitFor(120, TimeUnit.SECONDS)) { "Probe $mode timed out for ${jar.name}" }
        check(process.exitValue() == 0) { "Probe $mode failed for ${jar.path}: ${stderr.readText().takeLast(4000)}" }
        return stdout.readLines()
      } finally {
        child.stop()
        assertThat(child.aliveOwnedCount()).isEqualTo(0)
      }
    } finally {
      check(directory.deleteRecursively())
    }
  }

  private fun differential(mode: String, env: Map<String, String> = emptyMap()): Pair<List<String>, List<String>> {
    check(jars.size == 2 && jars.all(File::isFile) && probe.isFile) { "Missing packaged archives or probe" }
    return run(jars[0], mode, env) to run(jars[1], mode, env)
  }

  @Test
  fun `gRPC ABI chain links in both archives`() {
    val (unshrunk, shrunk) = differential("grpc")
    assertThat(unshrunk).contains("PACKAGED_GRPC_ABI_CHAIN_OK sockets=0")
    assertThat(shrunk).isEqualTo(unshrunk)
  }

  @Test
  fun `service providers and Log4j plugins stay loadable after shrinking`() {
    val (unshrunk, shrunk) = differential("services")
    assertThat(unshrunk.last()).isEqualTo("PACKAGED_SERVICES_OK")
    assertThat(shrunk.filter { it.startsWith("SERVICE_FILE ") }).isEqualTo(unshrunk.filter { it.startsWith("SERVICE_FILE ") })
    assertThat(lostEntries(unshrunk, shrunk)).isEmpty()
  }

  @Test
  fun `Maestro flow YAML and XCTest DTOs bind identically after shrinking`() {
    val (unshrunk, shrunk) = differential("maestro")
    assertThat(unshrunk.last()).isEqualTo("PACKAGED_MAESTRO_OK")
    assertThat(unshrunk.count { it.startsWith("MAESTRO_COMMAND ") }).isEqualTo(18)
    assertThat(unshrunk.any { it.startsWith("XCTEST_HIERARCHY ") && it.endsWith(" roundtrip=true") }).isEqualTo(true)
    assertThat(shrunk).isEqualTo(unshrunk)
  }

  @Test
  fun `JNA and gRPC epoll natives load identically after shrinking`() {
    val (unshrunk, shrunk) = differential("natives")
    assertThat(unshrunk).contains("NATIVE jna getpid=true")
    assertThat(shrunk).isEqualTo(unshrunk)
  }

  @Test
  fun `Graal JS evaluates in both archives`() {
    val (unshrunk, shrunk) = differential("graal")
    assertThat(unshrunk).contains("PACKAGED_GRAAL_JS_OK result=2")
    assertThat(shrunk).isEqualTo(unshrunk)
  }

  @Test
  fun `every provider creates clients and serializes requests identically after shrinking`() {
    val (unshrunk, shrunk) = differential("providers", mapOf("AWS_SECRET_ACCESS_KEY" to "fixture"))
    assertThat(unshrunk.last()).isEqualTo("PACKAGED_PROVIDERS_OK count=9")
    assertThat(unshrunk.count { it.startsWith("PACKAGED_PROVIDER name=") }).isEqualTo(9)
    assertThat(shrunk).isEqualTo(unshrunk)
  }

  @Test
  fun `differential detects a provider or plugin lost by shrinking`() {
    val baseline = listOf("SERVICE_FILE META-INF/services/a.A", "SERVICE META-INF/services/a.A a.Impl loadable=true", "PLUGIN core a.Plugin loadable=true", "PLUGIN core a.Missing loadable=false")
    assertThat(lostEntries(baseline, baseline)).isEmpty()
    assertThat(lostEntries(baseline, baseline.map { it.replace("a.Impl loadable=true", "a.Impl loadable=false") })).isEqualTo(listOf("SERVICE META-INF/services/a.A a.Impl"))
    assertThat(lostEntries(baseline, baseline - "PLUGIN core a.Plugin loadable=true")).isEqualTo(listOf("PLUGIN core a.Plugin"))
  }

  /** Entries loadable from the unshrunk archive but absent or unloadable in the shrunk archive. */
  private fun lostEntries(unshrunk: List<String>, shrunk: List<String>): List<String> {
    fun loadable(lines: List<String>) = lines.filter { it.endsWith(" loadable=true") }.map { it.removeSuffix(" loadable=true") }.toSet()
    return (loadable(unshrunk) - loadable(shrunk)).sorted()
  }

  @Test
  fun `probe rejects a non-archive classpath`() {
    val directory = Files.createTempDirectory("packaged-dynamic-entry-invalid").toFile()
    try {
      val notJar = File(directory, "not-a.jar").apply { writeText("not a jar") }
      val failure = runCatching { run(notJar, "services") }.exceptionOrNull()
      assertThat(failure?.message.orEmpty()).contains("Probe services failed")
    } finally {
      directory.deleteRecursively()
    }
  }
}
