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

  // Commands Verity renders for fast-path flows plus common navigator commands, behind a config header.
  private val FLOW = """
    appId: com.example.probe
    ---
    - launchApp:
        clearState: true
    - pressKey: Enter
    - tapOn: "General"
    - tapOn:
        id: "general"
    - swipe:
        direction: UP
    - scroll
    - longPressOn: "About"
    - longPressOn:
        focused: true
    - inputText: "hello"
    - waitForAnimationToEnd:
        timeout: 1000
    - extendedWaitUntil:
        visible:
          text: "About"
        timeout: 1000
    - scrollUntilVisible:
        element:
          text: "About"
        direction: DOWN
    - assertVisible: "About"
    - assertNotVisible: "Missing"
    - back
    - hideKeyboard
    - eraseText: 3
  """.trimIndent()

  /** Maestro's Jackson-bound flow YAML and XCTest driver DTOs. */
  fun maestro() {
    val flow = java.nio.file.Files.createTempFile("packaged-probe", ".yaml")
    try {
      java.nio.file.Files.writeString(flow, FLOW)
      for (command in maestro.orchestra.yaml.YamlCommandReader.readCommands(flow)) println("MAESTRO_COMMAND ${command.description()}")
      // The XCTest driver client uses this mapper for every request and response.
      val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
      println("XCTEST_REQUEST ${mapper.writeValueAsString(xcuitest.api.TouchRequest(1f, 2f, 0.5))}")
      println("XCTEST_REQUEST ${mapper.writeValueAsString(xcuitest.api.PressKeyRequest("home"))}")
      println("XCTEST_RESPONSE keyboard=${mapper.readValue("""{"isKeyboardVisible":true}""", xcuitest.api.KeyboardInfoResponse::class.java).isKeyboardVisible}")
      val element = hierarchy.AXElement("General", 0, "id", 1, 2L, 3, true, 4, false, "title", "value", hierarchy.AXFrame(1f, 2f, 3f, 4f), true, "placeholder", arrayListOf())
      val tree = mapper.writeValueAsString(hierarchy.ViewHierarchy(element, 1))
      println("XCTEST_HIERARCHY $tree roundtrip=${mapper.readValue(tree, hierarchy.ViewHierarchy::class.java) == hierarchy.ViewHierarchy(element, 1)}")
    } finally {
      java.nio.file.Files.delete(flow)
    }
    println("PACKAGED_MAESTRO_OK")
  }

  private interface CLibrary : com.sun.jna.Library {
    fun getpid(): Int
  }

  /** JNA (Mordant terminal detection) and gRPC's shaded Netty epoll transport. */
  fun natives() {
    val pid = com.sun.jna.Native.load("c", CLibrary::class.java).getpid()
    println("NATIVE jna getpid=${pid == ProcessHandle.current().pid().toInt()}")
    println("NATIVE grpc-netty-epoll available=${io.grpc.netty.shaded.io.netty.channel.epoll.Epoll.isAvailable()}")
    println("PACKAGED_NATIVES_OK")
  }

  fun graal() {
    maestro.js.GraalJsEngine().use { engine ->
      val result = engine.evaluateScript("1 + 1", emptyMap(), "packaged-probe", false, "packaged-probe")
      println("PACKAGED_GRAAL_JS_OK result=$result")
    }
  }
}
