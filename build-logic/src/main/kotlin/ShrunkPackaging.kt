import java.io.File
import java.util.zip.ZipFile
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.provider.Property

/**
 * Compares R8-shrunk archives with their unshrunk counterparts.
 *
 * Recorded exceptions to byte identity, both written by R8 itself:
 * - `META-INF/services/` descriptors are re-serialized; the provider lists must match exactly.
 * - `.kotlin_module` files are regenerated from the retained file facades, keyed by the unsanitized module name.
 * R8 may also synthesize `Owner$N` helper classes whose source file is `R8$$SyntheticClass`. It emits one that
 * throws for casts to types with no retained subtype. The receipt lists each helper's callers, and no caller may be
 * in code whose reflective types Verity depends on.
 */
object ShrunkPackaging {
  private const val SERVICES = "META-INF/services/"
  private const val MODULE = ".kotlin_module"
  private val SYNTHETIC = Regex("(.+)\\$\\d+\\.class")

  /** Packages whose Jackson, kotlinx-serialization or Kotlin-reflect types must never meet R8's throwing cast. */
  val PROTECTED = listOf("me/chrisbanes/verity/", "ai/koog/", "maestro/", "xcuitest/", "hierarchy/", "util/", "device/", "ios/")

  /** Archive contents needed for comparison: the host snapshot, service lists and R8 synthetic classes with their callers. */
  data class Contents(val archive: HostPackaging.Archive, val services: Map<String, List<String>>, val synthetic: Map<String, List<String>>) {
    val classes: Set<String> by lazy { archive.entries.filter { it.name.endsWith(".class") }.map { it.name }.toSet() }
  }

  fun read(file: File): Contents {
    val archive = HostPackaging.snapshot(file)
    return ZipFile(file).use { zip ->
      // Latin-1 maps bytes one-to-one, so class-file constant searches are plain substring checks.
      fun text(name: String) = String(zip.getInputStream(zip.getEntry(name)).use { it.readBytes() }, Charsets.ISO_8859_1)
      val services = archive.entries.filter { it.name.startsWith(SERVICES) }.associate { it.name to providers(text(it.name).toByteArray(Charsets.ISO_8859_1)) }
      val classes = archive.entries.map { it.name }.filter { it.endsWith(".class") }
      val synthetic = classes.filter { SYNTHETIC.matches(it) && "R8\$\$SyntheticClass" in text(it) }
      val callers = synthetic.associateWith { mutableListOf<String>() }
      if (synthetic.isNotEmpty()) {
        for (name in classes) {
          val source = text(name)
          for (helper in synthetic) if (name != helper && helper.removeSuffix(".class") in source) callers.getValue(helper) += name
        }
      }
      Contents(archive, services, callers)
    }
  }

  fun providers(bytes: ByteArray): List<String> = String(bytes, Charsets.UTF_8).lines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }

  fun compare(unshrunk: Contents, shrunk: Contents, mainClass: String) {
    val before = unshrunk.archive.entries.associateBy { it.name }
    val after = shrunk.archive.entries.associateBy { it.name }
    fun resource(name: String) = !name.endsWith(".class") && !name.endsWith(MODULE) && !name.startsWith(SERVICES)
    val expected = before.keys.filter(::resource).toSet()
    val actual = after.keys.filter(::resource).toSet()
    check(expected == actual) { "Resource set changed: missing=${(expected - actual).take(10)}, unexpected=${(actual - expected).take(10)}" }
    for (name in expected) {
      check(before.getValue(name).hash == after.getValue(name).hash && before.getValue(name).nested == after.getValue(name).nested) { "Changed resource $name" }
    }
    check(unshrunk.services == shrunk.services) { "Service providers changed: ${(unshrunk.services.entries - shrunk.services.entries).map { it.key }.take(10)}" }
    val modules = before.keys.filter { it.endsWith(MODULE) }.toSet()
    val unknownModules = after.keys.filter { it.endsWith(MODULE) && it.replace(':', '_') !in modules }
    check(unknownModules.isEmpty()) { "Unexpected Kotlin module files: $unknownModules" }
    val classes = unshrunk.classes
    val renamed = shrunk.classes - classes - shrunk.synthetic.keys
    check(renamed.isEmpty()) { "Classes absent from the unshrunk archive: ${renamed.take(10)}" }
    val orphans = shrunk.synthetic.keys.filter { SYNTHETIC.matchEntire(it)!!.groupValues[1] + ".class" !in classes }
    check(orphans.isEmpty()) { "R8 synthetic classes without an unshrunk owner: $orphans" }
    val protectedCallers = shrunk.synthetic.values.flatten().filter { caller -> PROTECTED.any(caller::startsWith) }
    check(protectedCallers.isEmpty()) { "R8 rewrote casts into throws in reflection-dependent code: ${protectedCallers.take(10)}" }
    val required = classes.filter { it.startsWith("me/chrisbanes/verity/") } + "${mainClass.replace('.', '/')}.class"
    val missing = required.filterNot { it in shrunk.classes }
    check(missing.isEmpty()) { "Shrinking removed required classes: ${missing.take(10)}" }
  }

  fun sameClasses(variants: Map<String, Contents>) {
    val sets = variants.mapValues { it.value.classes }
    check(sets.values.distinct().size == 1) { "Class sets differ between shrunk archives: ${sets.mapValues { it.value.size }}" }
  }

  /**
   * Rules policy: no blanket suppression, no shrink bypass, every rule justified by a preceding comment, and no
   * global rule that pins classes or keeps every member. The only global forms allowed match classes without
   * pinning them (`-keep,allowshrinking class **`) or keep constructors only.
   */
  fun lint(rules: String) {
    var commented = false
    var statement: String? = null
    var start = 0
    fun finish() {
      val rule = statement ?: return
      statement = null
      val directive = rule.substringBefore(' ').substringBefore(',')
      check(directive !in setOf("-ignorewarnings", "-dontshrink")) { "Forbidden rule at line $start: $rule" }
      if (directive == "-dontwarn") check(Regex("-dontwarn\\s+\\S+").containsMatchIn(rule) && !Regex("-dontwarn\\s+\\*{1,2}\\s*$").matches(rule)) { "Blanket -dontwarn at line $start" }
      if (directive.startsWith("-keep")) {
        val header = rule.substringBefore('{')
        val targets = Regex("(?:class|interface|enum)\\s+([^{]*?)(?:\\s+(?:extends|implements)\\s+(\\S+))?\\s*$").find(header)
        val names = targets?.groupValues?.get(1)?.split(',')?.map(String::trim).orEmpty()
        // A supertype or class annotation narrows a wildcard target only when it names a real type.
        val supertype = targets?.groupValues?.get(2).orEmpty()
        val annotation = Regex("@(\\S+)").find(header)?.groupValues?.get(1).orEmpty()
        val narrowed = (supertype.isNotEmpty() && supertype != "java.lang.Object" && supertype !in setOf("*", "**")) ||
          (annotation.isNotEmpty() && annotation.any(Char::isLetter))
        if (names.any { it in setOf("*", "**") } && !narrowed) {
          val members = rule.substringAfter('{', "").substringBefore('}').split(';').map(String::trim).filter(String::isNotEmpty)
          val wildcard = members.any { it in setOf("*", "<fields>", "<methods>") }
          val pins = directive in setOf("-keep", "-keepnames", "-keepclasseswithmembers", "-keepclasseswithmembernames") && !rule.substringBefore(' ').contains("allowshrinking")
          val constructorsOnly = members.isNotEmpty() && members.all { it.startsWith("<init>") }
          val enumAccessors = rule.contains("enum") && members.isNotEmpty() && !wildcard
          val annotatedMembers = members.isNotEmpty() && members.all { it.startsWith("@") }
          check(!wildcard && !pins && (members.isEmpty() || constructorsOnly || enumAccessors || annotatedMembers)) { "Global keep at line $start: $rule" }
        }
      }
      if (directive.startsWith("-keep") || directive == "-dontwarn") check(commented) { "Uncommented rule at line $start: $rule" }
    }
    for ((index, raw) in rules.lines().withIndex()) {
      val line = raw.trim()
      when {
        statement != null && !line.startsWith("-") && line.isNotEmpty() && !line.startsWith("#") -> statement += " $line"
        line.isEmpty() -> {
          finish()
          commented = false
        }
        line.startsWith("#") -> {
          finish()
          commented = true
        }
        else -> {
          finish()
          statement = line
          start = index + 1
        }
      }
    }
    finish()
  }

  fun verifyConfiguration(configuration: String) {
    check(configuration.lines().any { it.trim() == "-dontoptimize" }) { "Effective R8 configuration does not disable optimization" }
  }

  fun families(archive: HostPackaging.Archive): Map<String, Long> = archive.entries.filterNot { it.name.endsWith(".class") }.groupBy {
    when {
      it.name.endsWith(".apk") -> "apk"
      HostPackaging.ios(it.name) -> "ios"
      it.binary != null || HostPackaging.platform(it.name) != null -> "native"
      else -> "other"
    }
  }.mapValues { (_, entries) -> entries.sumOf { it.compressed } }
}

abstract class VerifyShrunkJars : DefaultTask() {
  @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val universal: RegularFileProperty
  @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val macos: RegularFileProperty
  @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val linux: RegularFileProperty
  @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val universalShrunk: RegularFileProperty
  @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val macosShrunk: RegularFileProperty
  @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val linuxShrunk: RegularFileProperty
  @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val rules: RegularFileProperty
  @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val configuration: RegularFileProperty
  @get:Input abstract val mainClass: Property<String>
  @get:OutputFile abstract val receipt: RegularFileProperty

  private fun expectRejected(name: String, block: () -> Unit) {
    val failure = runCatching(block).exceptionOrNull()
    check(failure is IllegalStateException) { "Negative fixture $name was not rejected: $failure" }
  }

  @TaskAction
  fun verify() {
    val rulesText = rules.get().asFile.readText()
    val configurationText = configuration.get().asFile.readText()
    ShrunkPackaging.lint(rulesText)
    ShrunkPackaging.verifyConfiguration(configurationText)
    val pairs = listOf(
      Triple(HostPackaging.Host.UNIVERSAL, universal, universalShrunk),
      Triple(HostPackaging.Host.MACOS_ARM64, macos, macosShrunk),
      Triple(HostPackaging.Host.LINUX_X64, linux, linuxShrunk),
    ).map { (host, before, after) -> Triple(host, ShrunkPackaging.read(before.get().asFile), ShrunkPackaging.read(after.get().asFile)) }
    val report = mutableListOf("format=shrunk-packaging-v1", "columns\tarchive\thost\tvariant\tfile\tbytes\tsha256\tclasses\tclass_compressed\tresource_compressed\tapk\tios\tnative\tother")
    for ((host, before, after) in pairs) {
      ShrunkPackaging.compare(before, after, mainClass.get())
      for ((variant, contents) in listOf("unshrunk" to before, "shrunk" to after)) {
        val archive = contents.archive
        val classBytes = archive.entries.filter { it.name.endsWith(".class") }.sumOf { it.compressed }
        val resourceBytes = archive.entries.filterNot { it.name.endsWith(".class") }.sumOf { it.compressed }
        val families = ShrunkPackaging.families(archive)
        report += listOf("archive", host.name, variant, archive.file.name, archive.file.length(), archive.hash, contents.classes.size, classBytes, resourceBytes,
          families["apk"] ?: 0, families["ios"] ?: 0, families["native"] ?: 0, families["other"] ?: 0).joinToString("\t")
      }
      for ((helper, callers) in after.synthetic.toSortedMap()) {
        report += "synthetic\t${host.name}\t$helper\tcallers=${callers.size}"
        report += callers.sorted().map { "synthetic-caller\t${host.name}\t$helper\t$it" }
      }
      // Exact gRPC descriptor lookup under each shrunk archive's platform-only class loader.
      java.net.URLClassLoader(arrayOf(after.archive.file.toURI().toURL()), ClassLoader.getPlatformClassLoader()).use {
        report += PackagedGrpc.verifyBuilder(it).map { descriptor -> "abi\t${host.name}\t$descriptor" }
      }
    }
    ShrunkPackaging.sameClasses(pairs.associate { it.first.name to it.third })

    // Negative mutations use snapshots of these actual archives through the same comparison paths.
    val (_, before, after) = pairs.first { it.first == HostPackaging.Host.MACOS_ARM64 }
    val main = mainClass.get()
    fun mutate(entries: List<HostPackaging.Entry>) = after.copy(archive = after.archive.copy(entries = entries))
    val apk = after.archive.entries.first { it.name.endsWith(".apk") }
    val verityClass = after.archive.entries.first { it.name.startsWith("me/chrisbanes/verity/") && it.name.endsWith(".class") }
    val service = after.services.entries.first { it.value.isNotEmpty() }
    val mutations = mapOf<String, () -> Unit>(
      "changed-resource" to { ShrunkPackaging.compare(before, mutate(after.archive.entries.map { if (it == apk) it.copy(hash = "0") else it }), main) },
      "removed-resource" to { ShrunkPackaging.compare(before, mutate(after.archive.entries - apk), main) },
      "renamed-class" to { ShrunkPackaging.compare(before, mutate(after.archive.entries + verityClass.copy(name = "a/a.class")), main) },
      "removed-verity-class" to { ShrunkPackaging.compare(before, mutate(after.archive.entries - verityClass), main) },
      "protected-synthetic-caller" to {
        ShrunkPackaging.compare(before, after.copy(synthetic = after.synthetic + (verityClass.name.removeSuffix(".class") + "$0.class" to listOf("maestro/Probe.class"))), main)
      },
      "removed-provider" to { ShrunkPackaging.compare(before, after.copy(services = after.services + (service.key to service.value.drop(1))), main) },
      "unknown-module" to { ShrunkPackaging.compare(before, mutate(after.archive.entries + apk.copy(name = "META-INF/unknown.kotlin_module")), main) },
      "class-set-mismatch" to { ShrunkPackaging.sameClasses(mapOf("a" to after, "b" to mutate(after.archive.entries - verityClass))) },
      "ignorewarnings" to { ShrunkPackaging.lint("$rulesText\n# reason\n-ignorewarnings\n") },
      "blanket-dontwarn" to { ShrunkPackaging.lint("$rulesText\n# reason\n-dontwarn **\n") },
      "uncommented-keep" to { ShrunkPackaging.lint("$rulesText\n\n-keep class a.A\n") },
      "optimized-configuration" to { ShrunkPackaging.verifyConfiguration(configurationText.lines().filterNot { it.trim() == "-dontoptimize" }.joinToString("\n")) },
    )
    for ((name, block) in mutations) expectRejected(name, block)
    report += "sensitivity\t${mutations.size}\tpassed\tactual-archive-snapshot-mutations"
    val output = receipt.get().asFile
    output.parentFile.mkdirs()
    output.writeText(report.joinToString("\n", postfix = "\n"))
    logger.lifecycle("$path: three shrunk archives verified against unshrunk counterparts; resources, services and class names preserved")
  }
}
