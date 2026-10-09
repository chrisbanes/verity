package me.chrisbanes.verity.smoke

import java.io.DataInputStream
import java.io.File
import java.util.zip.ZipFile

/** Dynamic entry points that R8 cannot trace: service descriptors, Log4j plugins and Graal JS. */
internal object PackagedRuntimeProbeEntries {
  private const val PLUGINS = "META-INF/org/apache/logging/log4j/core/config/plugins/Log4j2Plugins.dat"

  fun services() {
    val jar = File(System.getProperty("java.class.path").split(File.pathSeparator).first())
    val loader = PackagedRuntimeProbeEntries::class.java.classLoader
    fun loadable(name: String) = runCatching { Class.forName(name, false, loader) }.isSuccess
    ZipFile(jar).use { zip ->
      // Java collection APIs only: the shrunk archive keeps just the Kotlin stdlib members production uses.
      val services = java.util.Collections.list(zip.entries()).map { it.name }.filter { it.startsWith("META-INF/services/") && !it.endsWith("/") }.sorted()
      for (service in services) {
        println("SERVICE_FILE $service")
        val providers = zip.getInputStream(zip.getEntry(service)).bufferedReader().readLines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }
        for (provider in providers) println("SERVICE $service $provider loadable=${loadable(provider)}")
      }
      // Log4j's binary plugin cache layout: categories, then key/class/name/printable/defer per plugin.
      DataInputStream(zip.getInputStream(checkNotNull(zip.getEntry(PLUGINS))).buffered()).use { input ->
        repeat(input.readInt()) {
          val category = input.readUTF()
          repeat(input.readInt()) {
            input.readUTF()
            val className = input.readUTF()
            input.readUTF()
            input.readBoolean()
            input.readBoolean()
            println("PLUGIN $category $className loadable=${loadable(className)}")
          }
        }
      }
    }
    println("PACKAGED_SERVICES_OK")
  }

  fun graal() {
    maestro.js.GraalJsEngine().use { engine ->
      val result = engine.evaluateScript("1 + 1", emptyMap(), "packaged-probe", false, "packaged-probe")
      println("PACKAGED_GRAAL_JS_OK result=$result")
    }
  }
}
