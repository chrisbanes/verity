package me.chrisbanes.verity.smoke

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import io.grpc.Context
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.grpc.stub.StreamObserver
import java.lang.reflect.Proxy
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import maestro.DeviceInfo
import maestro.Driver
import maestro.Maestro
import maestro.TreeNode
import maestro_android.MaestroAndroid
import maestro_android.MaestroDriverGrpc
import me.chrisbanes.verity.core.hierarchy.HierarchyNode
import me.chrisbanes.verity.core.model.Platform
import me.chrisbanes.verity.device.DeviceSession
import me.chrisbanes.verity.device.android.AndroidDeviceSession

/** Ordinary supplemental transport harness; it cannot supply actual native Android phase evidence. */
class AndroidBoundedCaptureHarnessTest {
  @Test
  fun `generated shaded Netty transport cancellation joins fake driver before reuse`() = runTest(timeout = 15.seconds) {
    withContext(Dispatchers.Default) {
      val entered = CountDownLatch(1)
      val exited = CountDownLatch(1)
      val release = CountDownLatch(1)
      val stall = AtomicBoolean(true)
      val serverActive = AtomicInteger()
      val driverActive = AtomicInteger()
      val peakDriverActive = AtomicInteger()
      val delayed = AtomicBoolean()
      val lateEntered = CountDownLatch(1)
      val lateRelease = CountDownLatch(1)
      val server = NettyServerBuilder.forAddress(InetSocketAddress("127.0.0.1", 0))
        .addService(object : MaestroDriverGrpc.MaestroDriverImplBase() {
          override fun viewHierarchy(request: MaestroAndroid.ViewHierarchyRequest, responseObserver: StreamObserver<MaestroAndroid.ViewHierarchyResponse>) {
            if (stall.get()) {
              serverActive.incrementAndGet()
              val context = Context.current()
              context.addListener({ release.countDown() }, java.util.concurrent.Executor { it.run() })
              try {
                entered.countDown()
                check(release.await(4, TimeUnit.SECONDS))
                if (context.isCancelled) return
              } finally {
                serverActive.decrementAndGet()
                exited.countDown()
              }
            }
            responseObserver.onNext(MaestroAndroid.ViewHierarchyResponse.newBuilder().setHierarchy("supplemental-only").build())
            responseObserver.onCompleted()
          }
        })
        .build()
        .start()
      val channel = NettyChannelBuilder.forAddress("127.0.0.1", server.port).usePlaintext().build()
      val stub = MaestroDriverGrpc.newBlockingStub(channel)
      val driver = Proxy.newProxyInstance(Driver::class.java.classLoader, arrayOf(Driver::class.java)) { _, method, _ ->
        when (method.name) {
          "deviceInfo" -> DeviceInfo(maestro.device.Platform.ANDROID, 100, 100, 100, 100)

          "contentDescriptor" -> {
            val active = driverActive.incrementAndGet()
            peakDriverActive.updateAndGet { maxOf(it, active) }
            try {
              val response = stub.viewHierarchy(MaestroAndroid.ViewHierarchyRequest.newBuilder().build())
              if (delayed.get()) {
                lateEntered.countDown()
                check(lateRelease.await(2, TimeUnit.SECONDS))
              }
              TreeNode(attributes = mutableMapOf("text" to response.hierarchy, "bounds" to "[0,0][100,100]"))
            } finally {
              driverActive.decrementAndGet()
            }
          }

          "name" -> "supplemental-local-grpc"

          "close" -> Unit

          else -> error("Unexpected fake Driver method: ${method.name}")
        }
      } as Driver
      val session = AndroidDeviceSession(Maestro(driver), Platform.ANDROID_MOBILE) { "" }
      val workers = AndroidProofWorkers()
      try {
        val invocation = async(workers) { session.captureHierarchyTree(2.seconds) }
        check(withContext(Dispatchers.IO) { entered.await(2, TimeUnit.SECONDS) })
        check(workers.threads.keys.any { "hierarchyRPC" in androidProofStages(it.stackTrace.toList()) })
        invocation.cancel()
        invocation.join()
        assertThat(invocation.isCompleted).isEqualTo(true)
        // DispatchedTask resumes continuations before restoring thread context. Tokens sample
        // live work; joined invocations and actual driver/server counts prove completion.
        assertThat(driverActive.get()).isEqualTo(0)
        check(withContext(Dispatchers.IO) { exited.await(2, TimeUnit.SECONDS) })
        assertThat(serverActive.get()).isEqualTo(0)
        stall.set(false)
        assertThat(withContext(workers) { session.captureHierarchyTree(2.seconds) }.attributes["text"]).isEqualTo("supplemental-only")
        assertThat(driverActive.get()).isEqualTo(0)
        delayed.set(true)
        supervisorScope {
          val failed = async(workers) {
            session.captureHierarchyTree(2.seconds)
            error("ordinary delayed assertion")
          }
          val parentFailure = try {
            runCatching {
              check(withContext(Dispatchers.IO) { lateEntered.await(1, TimeUnit.SECONDS) })
              error("ordinary parent assertion")
            }.exceptionOrNull()
          } finally {
            withContext(NonCancellable) {
              lateRelease.countDown()
              failed.join()
            }
          }
          assertThat(parentFailure?.message).isEqualTo("ordinary parent assertion")
          assertThat(runCatching { failed.await() }.exceptionOrNull()?.message).isEqualTo("ordinary delayed assertion")
          assertThat(driverActive.get()).isEqualTo(0)
          assertThat(serverActive.get()).isEqualTo(0)
          assertThat(failed.isCompleted).isEqualTo(true)
        }
        assertThat(peakDriverActive.get()).isEqualTo(1)
      } finally {
        withContext(NonCancellable + Dispatchers.IO) {
          release.countDown()
          lateRelease.countDown()
          session.close()
          channel.shutdownNow()
          server.shutdownNow()
          check(channel.awaitTermination(2, TimeUnit.SECONDS))
          check(server.awaitTermination(2, TimeUnit.SECONDS))
        }
      }
    }
  }

  @Test
  fun `stage classifier retains public native stage identities`() {
    val frames = listOf(
      StackTraceElement("maestro_android.MaestroDriverGrpc\$MaestroDriverBlockingStub", "deviceInfo", "MaestroDriverGrpc.java", 1),
      StackTraceElement("maestro_android.MaestroDriverGrpc\$MaestroDriverBlockingStub", "viewHierarchy", "MaestroDriverGrpc.java", 2),
      StackTraceElement("com.sun.org.apache.xerces.internal.jaxp.DocumentBuilderImpl", "parse", "DocumentBuilderImpl.java", 3),
      StackTraceElement("maestro.drivers.AndroidDriver", "mapHierarchy", "AndroidDriver.kt", 4),
      StackTraceElement("maestro.android.AndroidWebViewHierarchyClient", "augmentHierarchy", "AndroidWebViewHierarchyClient.kt", 5),
      StackTraceElement("maestro.ViewHierarchy\$Companion", "from-8JJjmZI", "ViewHierarchy.kt", 6),
      StackTraceElement("maestro.ViewHierarchyKt", "filterOutOfBounds", "ViewHierarchy.kt", 7),
      StackTraceElement("me.chrisbanes.verity.device.android.MaestroTreeConverter", "convert", "MaestroTreeConverter.kt", 8),
    )
    assertThat(androidProofStages(frames)).isEqualTo(setOf("deviceInfoRPC", "hierarchyRPC", "XML", "map", "WebView", "from", "filter", "converter"))
    assertThat(androidProofStages(listOf(StackTraceElement("unowned.Decoy", "parse", "Decoy.kt", 1)))).isEqualTo(emptySet())
    assertThat(androidProofProcessingSample(frames, androidProofStages(frames))).isEqualTo(false)
    val enclosingFrom = StackTraceElement("maestro.ViewHierarchy\$Companion", "from-8JJjmZI", "ViewHierarchy.kt", 1)
    val rpcOnly = listOf(frames[1], enclosingFrom)
    assertThat(androidProofProcessingSample(rpcOnly, androidProofStages(rpcOnly))).isEqualTo(false)
    assertThat(androidProofProcessingSample(listOf(enclosingFrom), androidProofStages(listOf(enclosingFrom)))).isEqualTo(true)
    val map = listOf(frames[3], enclosingFrom)
    assertThat(androidProofProcessingSample(map, androidProofStages(map))).isEqualTo(true)
  }
}

private val androidProcessingStages = setOf("XML", "map", "WebView", "from", "filter", "converter")

/** The enclosing from frame also appears during RPC, so only its own active top frame is processing evidence. */
private fun androidProofProcessingSample(stack: List<StackTraceElement>, stages: Set<String>): Boolean {
  if (stages.any { it.endsWith("RPC") }) return false
  return stages.any { it in androidProcessingStages && it != "from" } ||
    stack.firstOrNull()?.let { it.className == "maestro.ViewHierarchy\$Companion" && it.methodName == "from-8JJjmZI" } == true
}

/** Public stack inspection is restricted to the current invocation's coroutine worker tokens. */
private class AndroidProofWorkers :
  AbstractCoroutineContextElement(Key),
  ThreadContextElement<Boolean> {
  companion object Key : CoroutineContext.Key<AndroidProofWorkers>
  val threads = ConcurrentHashMap<Thread, Boolean>()
  override fun updateThreadContext(context: CoroutineContext): Boolean = threads.put(Thread.currentThread(), true) != null
  override fun restoreThreadContext(context: CoroutineContext, oldState: Boolean) {
    if (oldState) threads[Thread.currentThread()] = true else threads.remove(Thread.currentThread())
  }
}

private fun androidProofStages(stack: List<StackTraceElement>): Set<String> = buildSet {
  for (frame in stack) {
    when {
      frame.className == maestro_android.MaestroDriverGrpc.MaestroDriverBlockingStub::class.java.name && frame.methodName == "deviceInfo" -> add("deviceInfoRPC")
      frame.className == maestro_android.MaestroDriverGrpc.MaestroDriverBlockingStub::class.java.name && frame.methodName == "viewHierarchy" -> add("hierarchyRPC")
      frame.className.startsWith("com.sun.org.apache.xerces.") || frame.className.startsWith("javax.xml.parsers.") -> add("XML")
      frame.className == "maestro.drivers.AndroidDriver" && frame.methodName.startsWith("mapHierarchy") -> add("map")
      frame.className.contains("AndroidWebViewHierarchyClient") && frame.methodName.contains("augmentHierarchy") -> add("WebView")
      frame.className.startsWith("maestro.ViewHierarchy") && frame.methodName == "from-8JJjmZI" -> add("from")
      frame.className.startsWith("maestro.ViewHierarchy") && frame.methodName.contains("filterOutOfBounds") -> add("filter")
      frame.className == "me.chrisbanes.verity.device.android.MaestroTreeConverter" && frame.methodName == "convert" -> add("converter")
    }
  }
}
