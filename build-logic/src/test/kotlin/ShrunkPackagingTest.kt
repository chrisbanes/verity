import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ShrunkPackagingTest {
  private val directory = Files.createTempDirectory("shrunk-packaging").toFile()
  private val main = "me.chrisbanes.verity.cli.VerityKt"

  @AfterTest
  fun cleanup() {
    directory.deleteRecursively()
  }

  private val unshrunk = mapOf(
    "me/chrisbanes/verity/cli/VerityKt.class" to "main",
    "lib/Used.class" to "used",
    "lib/Unused.class" to "unused",
    "maestro-app.apk" to "apk",
    "META-INF/services/lib.Service" to "# header\nlib.Used\n",
    "META-INF/me.chrisbanes.verity_cli.kotlin_module" to "module",
    "META-INF/lib.kotlin_module" to "lib-module",
  )

  // What R8 writes: unused class removed, services re-serialized, module files regenerated, a synthetic helper.
  private val faithful = unshrunk - "lib/Unused.class" - "META-INF/lib.kotlin_module" - "META-INF/me.chrisbanes.verity_cli.kotlin_module" +
    mapOf(
      "META-INF/services/lib.Service" to "lib.Used\n",
      "META-INF/me.chrisbanes.verity:cli.kotlin_module" to "regenerated",
      "lib/Used$0.class" to "R8\$\$SyntheticClass",
    )

  private fun archive(name: String, entries: Map<String, String>): ShrunkPackaging.Contents {
    val file = File(directory, name)
    ZipOutputStream(file.outputStream()).use { zip ->
      for ((entry, content) in entries) {
        zip.putNextEntry(ZipEntry(entry))
        zip.write(content.toByteArray())
        zip.closeEntry()
      }
    }
    return ShrunkPackaging.read(file)
  }

  private fun compare(shrunk: Map<String, String>) = ShrunkPackaging.compare(archive("unshrunk.jar", unshrunk), archive("shrunk.jar", shrunk), main)

  @Test
  fun `faithful shrunk subset passes`() {
    compare(faithful)
    ShrunkPackaging.sameClasses(mapOf("a" to archive("a.jar", faithful), "b" to archive("b.jar", faithful - "maestro-app.apk")))
  }

  @Test
  fun `changed or removed resources are rejected`() {
    assertFailsWith<IllegalStateException> { compare(faithful + ("maestro-app.apk" to "changed")) }
    assertFailsWith<IllegalStateException> { compare(faithful - "maestro-app.apk") }
    assertFailsWith<IllegalStateException> { compare(faithful + ("extra.txt" to "extra")) }
  }

  @Test
  fun `changed service providers are rejected`() {
    assertFailsWith<IllegalStateException> { compare(faithful + ("META-INF/services/lib.Service" to "lib.Other\n")) }
    assertFailsWith<IllegalStateException> { compare(faithful - "META-INF/services/lib.Service") }
  }

  @Test
  fun `renamed classes unknown modules and ownerless synthetics are rejected`() {
    assertFailsWith<IllegalStateException> { compare(faithful + ("a/A.class" to "renamed")) }
    assertFailsWith<IllegalStateException> { compare(faithful + ("lib/Used$1.class" to "not synthetic")) }
    assertFailsWith<IllegalStateException> { compare(faithful + ("lib/Gone$0.class" to "R8\$\$SyntheticClass")) }
    assertFailsWith<IllegalStateException> { compare(faithful + ("META-INF/other.kotlin_module" to "module")) }
  }

  @Test
  fun `removed Verity or main classes are rejected`() {
    assertFailsWith<IllegalStateException> { compare(faithful - "me/chrisbanes/verity/cli/VerityKt.class") }
  }

  @Test
  fun `class sets must match across shrunk archives`() {
    assertFailsWith<IllegalStateException> {
      ShrunkPackaging.sameClasses(mapOf("a" to archive("a.jar", faithful), "b" to archive("b.jar", faithful - "lib/Used.class")))
    }
  }

  @Test
  fun `rules lint rejects blanket suppression and unjustified rules`() {
    ShrunkPackaging.lint("# Optional codec.\n-dontwarn org.tukaani.xz.**\n-dontwarn com.github.luben.zstd.**\n\n# Metadata.\n-keep,allowshrinking class **\n")
    for (rules in listOf(
      "# reason\n-ignorewarnings\n",
      "# reason\n-dontwarn **\n",
      "# reason\n-dontwarn *\n",
      "# reason\n-dontshrink\n",
      "# reason\n-keep class ** { *; }\n",
      "# reason\n-keep class **\n",
      "-keep class a.A\n",
      "# reason\n-keep class a.A\n\n-dontwarn b.**\n",
    )) {
      assertFailsWith<IllegalStateException>(rules) { ShrunkPackaging.lint(rules) }
    }
  }

  @Test
  fun `effective configuration must disable optimization`() {
    ShrunkPackaging.verifyConfiguration("-dontoptimize\n-keep class a.A\n")
    assertFailsWith<IllegalStateException> { ShrunkPackaging.verifyConfiguration("-keep class a.A\n") }
  }
}
