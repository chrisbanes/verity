package me.chrisbanes.verity.smoke

import io.grpc.okhttp.OkHttpChannelBuilder
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.chrisbanes.verity.core.hierarchy.HierarchyNode
import me.chrisbanes.verity.core.model.Platform
import me.chrisbanes.verity.device.DeviceSessionFactory

/** Only this fixture's classes accompany the selected production fat JAR in its child JVM. */
object PackagedRuntimeProbe {
  @JvmStatic
  fun main(args: Array<String>) = runBlocking {
    check(System.getProperty("java.class.path").split(java.io.File.pathSeparator).size == 2) {
      "Probe requires only the production fat JAR and test-only probe JAR"
    }
    when (args.first()) {
      "grpc" -> verifyGrpc()

      "android" -> {
        check(System.getenv("GITHUB_ACTIONS") == "true") { "Native probe requires configured job-owned CI" }
        check(args[1].startsWith("emulator-"))
        for (platform in listOf(Platform.ANDROID_MOBILE, Platform.ANDROID_TV)) verifyDevice(platform, args[1])
      }

      "ios" -> {
        check(System.getenv("GITHUB_ACTIONS") == "true") { "Native probe requires configured job-owned CI" }
        java.util.UUID.fromString(args[1])
        verifyDevice(Platform.IOS, args[1])
      }

      else -> error("Unknown probe mode")
    }
  }

  private fun verifyGrpc() {
    val sockets = object : SocketFactory() {
      var attempts = 0
      private fun forbidden(): Socket {
        attempts++
        error("Offline ABI probe must not connect")
      }
      override fun createSocket(): Socket = forbidden()
      override fun createSocket(host: String, port: Int): Socket = forbidden()
      override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket = forbidden()
      override fun createSocket(host: InetAddress, port: Int): Socket = forbidden()
      override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket = forbidden()
    }
    val channel = OkHttpChannelBuilder.forAddress("127.0.0.1", 1)
      .usePlaintext().socketFactory(sockets)
      .keepAliveTime(1, TimeUnit.MINUTES).keepAliveTimeout(10, TimeUnit.SECONDS)
      .keepAliveWithoutCalls(true).build()
    try {
      check(sockets.attempts == 0)
      println("PACKAGED_GRPC_ABI_CHAIN_OK sockets=0")
    } finally {
      channel.shutdownNow()
      check(channel.awaitTermination(10, TimeUnit.SECONDS)) { "Channel failed to terminate" }
    }
  }

  private suspend fun verifyDevice(platform: Platform, target: String) = kotlinx.coroutines.coroutineScope {
    println("PACKAGED_FACTORY_START platform=$platform target=$target")
    val session = withTimeout(600.seconds) { DeviceSessionFactory.connect(platform, target) }
    try {
      check(session.platform == platform) { "Factory changed requested platform mapping" }
      println("PACKAGED_FACTORY_CONNECTED platform=$platform")
      withTimeout(120.seconds) {
        fun nonempty(tree: HierarchyNode): Boolean = tree.attributes.isNotEmpty() || tree.states.isNotEmpty() || tree.children.any(::nonempty)
        check(nonempty(session.captureHierarchyTree()))
        check(nonempty(session.captureHierarchyTree(2000.milliseconds)))
        session.pressKey(if (platform == Platform.IOS) "return" else "BACK")
        check(nonempty(session.captureHierarchyTree()))
        val bodyFailure = CompletableDeferred<Throwable?>()
        val capture = async(start = CoroutineStart.UNDISPATCHED) {
          try {
            session.captureHierarchyTree(2000.milliseconds).also { bodyFailure.complete(null) }
          } catch (e: Throwable) {
            bodyFailure.complete(e)
            throw e
          }
        }
        try {
          withTimeout(2000) {
            while (true) {
              val entered = withContext(Dispatchers.IO) {
                Thread.getAllStackTraces().values.any { frames ->
                  if (platform == Platform.IOS) {
                    frames.any { it.className.startsWith("me.chrisbanes.verity.device.ios.BoundedIosHierarchyCapture") }
                  } else {
                    frames.any { it.className.startsWith("maestro.") } &&
                      frames.any { it.className.startsWith("me.chrisbanes.verity.device.android.AndroidDeviceSession") }
                  }
                }
              }
              if (entered) break
              check(!capture.isCompleted) { "Capture completed before in-flight worker was observed" }
              delay(1)
            }
          }
          val caller = ProbeCallerCancellation()
          capture.cancel(caller)
          capture.join()
          val observed = bodyFailure.await()
          check(observed === caller || (observed is ProbeCallerCancellation && observed.cause === caller)) {
            "Expected explicit caller cancellation after entered capture: $observed"
          }
          check(nonempty(session.captureHierarchyTree()))
          println("PACKAGED_FACTORY_CAPTURE_BOUNDED_KEY_CALLER_JOIN_REUSE_OK platform=$platform")
        } finally {
          withContext(NonCancellable) { capture.cancelAndJoin() }
        }
      }
    } finally {
      withContext(NonCancellable + Dispatchers.IO) { session.close() }
      println("PACKAGED_FACTORY_CLOSE_COMPLETED platform=$platform")
    }
  }
}

private class ProbeCallerCancellation : CancellationException("Packaged probe explicit caller stop")
