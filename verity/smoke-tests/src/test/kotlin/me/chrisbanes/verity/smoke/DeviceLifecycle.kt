package me.chrisbanes.verity.smoke

import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.chrisbanes.verity.core.model.Platform
import me.chrisbanes.verity.device.DeviceSession
import me.chrisbanes.verity.device.DeviceSessionFactory

/**
 * Manages device lifecycle for smoke tests.
 * Discovers a running device or boots one, and tears down only what it started.
 */
class DeviceLifecycle private constructor(
  val platform: Platform,
  private val bootedByUs: Boolean,
  private val processToKill: Process?,
  private val simulatorUdid: String?,
) : AutoCloseable {

  suspend fun connect(retries: Int = if (platform == Platform.IOS) 2 else 1): DeviceSession {
    require(retries >= 1) { "retries must be at least 1, but was $retries" }
    var lastException: Exception? = null
    repeat(retries) { attempt ->
      try {
        return DeviceSessionFactory.connect(
          platform = platform,
          deviceId = simulatorUdid,
          disableAnimations = false,
        )
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        lastException = e
        System.err.println("Device connect attempt ${attempt + 1}/$retries failed: ${e.message}")
        if (attempt < retries - 1) delay(5.seconds)
      }
    }
    throw lastException!!
  }

  override fun close() {
    if (!bootedByUs) return

    when (platform) {
      Platform.ANDROID_TV, Platform.ANDROID_MOBILE -> {
        processToKill?.destroyForcibly()
      }

      Platform.IOS -> {
        simulatorUdid?.let { udid ->
          ProcessBuilder("xcrun", "simctl", "shutdown", udid)
            .redirectErrorStream(true)
            .start()
            .waitFor()
        }
      }
    }
  }

  companion object {
    private val androidHome: File? by lazy {
      val envHome = System.getenv("ANDROID_HOME")
        ?: System.getenv("ANDROID_SDK_ROOT")
      if (envHome != null) {
        File(envHome)
      } else {
        val default = File(System.getProperty("user.home"), "Library/Android/sdk")
        default.takeIf { it.isDirectory }
      }
    }

    private fun adb(): String = androidHome?.resolve("platform-tools/adb")?.takeIf { it.canExecute() }?.absolutePath
      ?: "adb"

    private fun emulator(): String = androidHome?.resolve("emulator/emulator")?.takeIf { it.canExecute() }?.absolutePath
      ?: "emulator"

    fun android(): DeviceLifecycle = DeviceLifecycle(
      platform = Platform.ANDROID_MOBILE,
      bootedByUs = false,
      processToKill = null,
      simulatorUdid = null,
    )

    fun ios(): DeviceLifecycle = DeviceLifecycle(
      platform = Platform.IOS,
      bootedByUs = false,
      processToKill = null,
      simulatorUdid = null,
    )

    suspend fun discoverOrBootAndroid(): DeviceLifecycle = withContext(Dispatchers.IO) {
      val existingDevice = try {
        dadb.Dadb.discover()
      } catch (_: Exception) {
        null
      }

      if (existingDevice != null) {
        existingDevice.close()
        return@withContext DeviceLifecycle(
          platform = Platform.ANDROID_MOBILE,
          bootedByUs = false,
          processToKill = null,
          simulatorUdid = null,
        )
      }

      val avdName = System.getProperty("verity.smoke.avd") ?: findFirstAvd()
      val process = ProcessBuilder(
        emulator(),
        "-avd",
        avdName,
        "-no-window",
        "-no-audio",
        "-no-boot-anim",
      ).redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .start()

      try {
        withTimeout(3.minutes) {
          waitForAndroidBoot()
        }
      } catch (e: Exception) {
        process.destroyForcibly()
        throw e
      }

      DeviceLifecycle(
        platform = Platform.ANDROID_MOBILE,
        bootedByUs = true,
        processToKill = process,
        simulatorUdid = null,
      )
    }

    suspend fun discoverOrBootIos(): DeviceLifecycle = withContext(Dispatchers.IO) {
      val bootedUdids = discoverBootedIosSimulators()

      if (bootedUdids.isNotEmpty()) {
        return@withContext DeviceLifecycle(
          platform = Platform.IOS,
          bootedByUs = false,
          processToKill = null,
          simulatorUdid = bootedUdids.first(),
        )
      }

      val udid = System.getProperty("verity.smoke.ios.udid") ?: findFirstIosSimulator()
      val bootProcess = ProcessBuilder("xcrun", "simctl", "boot", udid)
        .redirectErrorStream(true)
        .start()
      val bootOutput = bootProcess.inputStream.bufferedReader().readText()
      check(bootProcess.waitFor() == 0) { "xcrun simctl boot $udid failed: $bootOutput" }

      try {
        withTimeout(2.minutes) {
          waitForIosBoot(udid)
        }
      } catch (e: Exception) {
        ProcessBuilder("xcrun", "simctl", "shutdown", udid)
          .redirectErrorStream(true)
          .start()
          .waitFor()
        throw e
      }

      DeviceLifecycle(
        platform = Platform.IOS,
        bootedByUs = true,
        processToKill = null,
        simulatorUdid = udid,
      )
    }

    private fun findFirstAvd(): String {
      val process = ProcessBuilder(emulator(), "-list-avds")
        .redirectErrorStream(true)
        .start()
      val output = process.inputStream.bufferedReader().readText()
      check(process.waitFor() == 0) { "emulator -list-avds failed: $output" }
      val avds = output.lines().filter { it.isNotBlank() }
      check(avds.isNotEmpty()) { "No AVDs found. Create one with avdmanager." }
      return avds.first()
    }

    private suspend fun waitForAndroidBoot() {
      while (true) {
        val output = try {
          val p = ProcessBuilder(adb(), "shell", "getprop", "sys.boot_completed")
            .redirectErrorStream(true)
            .start()
          p.inputStream.bufferedReader().readText().trim()
        } catch (_: Exception) {
          null
        }
        if (output == "1") break
        delay(2.seconds)
      }
    }

    private fun discoverBootedIosSimulators(): List<String> {
      val process = ProcessBuilder("xcrun", "simctl", "list", "devices", "booted", "-j")
        .redirectErrorStream(true)
        .start()
      val output = process.inputStream.bufferedReader().readText()
      if (process.waitFor() != 0) return emptyList()

      val root = Json.parseToJsonElement(output).jsonObject
      val devices = root["devices"]?.jsonObject ?: return emptyList()
      return devices.values.flatMap { runtimeDevices ->
        runtimeDevices.jsonArray.mapNotNull { device ->
          device.jsonObject["udid"]?.jsonPrimitive?.contentOrNull
        }
      }.distinct()
    }

    private fun findFirstIosSimulator(): String {
      val process = ProcessBuilder("xcrun", "simctl", "list", "devices", "available", "-j")
        .redirectErrorStream(true)
        .start()
      val output = process.inputStream.bufferedReader().readText()
      check(process.waitFor() == 0) { "xcrun simctl list failed: $output" }

      val root = Json.parseToJsonElement(output).jsonObject
      val devices = root["devices"]?.jsonObject ?: error("No simulators found")
      for (entry in devices.entries) {
        val runtime = entry.key
        if (!runtime.contains("iPhone") && !runtime.contains("iOS")) continue
        for (device in entry.value.jsonArray) {
          val udid = device.jsonObject["udid"]?.jsonPrimitive?.contentOrNull
          if (udid != null) return udid
        }
      }
      error("No iPhone simulator found. Create one with xcrun simctl.")
    }

    private suspend fun waitForIosBoot(udid: String) {
      while (true) {
        val booted = discoverBootedIosSimulators()
        if (udid in booted) break
        delay(2.seconds)
      }
    }
  }
}
