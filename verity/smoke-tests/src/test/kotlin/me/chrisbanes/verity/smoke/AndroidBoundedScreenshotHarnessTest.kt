package me.chrisbanes.verity.smoke

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.google.protobuf.ByteString
import io.grpc.Context
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.grpc.stub.StreamObserver
import java.lang.reflect.Proxy
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import maestro.DeviceInfo
import maestro.Driver
import maestro.Maestro
import maestro_android.MaestroAndroid
import maestro_android.MaestroDriverGrpc
import me.chrisbanes.verity.core.model.Platform
import me.chrisbanes.verity.device.android.AndroidDeviceSession
import okio.Buffer
import okio.Sink

/** Supplemental generated gRPC transport evidence, not actual native SDK qualification. */
class AndroidBoundedScreenshotHarnessTest {
  @Test
  fun `generated screenshot RPC cancellation joins SDK driver before bounded and noarg reuse`() = runTest {
    withContext(Dispatchers.Default) {
      supervisorScope {
        val entered = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val release = CountDownLatch(1)
        val requests = AtomicInteger()
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val bytes = ByteArray(20001) { it.toByte() }
        val server = NettyServerBuilder.forAddress(InetSocketAddress("127.0.0.1", 0))
          .addService(object : MaestroDriverGrpc.MaestroDriverImplBase() {
            override fun screenshot(request: MaestroAndroid.ScreenshotRequest, observer: StreamObserver<MaestroAndroid.ScreenshotResponse>) {
              if (requests.incrementAndGet() == 1) {
                val context = Context.current()
                context.addListener({ release.countDown() }, java.util.concurrent.Executor { it.run() })
                try {
                  entered.countDown()
                  check(release.await(4, TimeUnit.SECONDS))
                  if (context.isCancelled) return
                } finally {
                  exited.countDown()
                }
              }
              observer.onNext(MaestroAndroid.ScreenshotResponse.newBuilder().setBytes(ByteString.copyFrom(bytes)).build())
              observer.onCompleted()
            }
          }).build().start()
        val channel = NettyChannelBuilder.forAddress("127.0.0.1", server.port).usePlaintext().build()
        val stub = MaestroDriverGrpc.newBlockingStub(channel)
        val driver = Proxy.newProxyInstance(Driver::class.java.classLoader, arrayOf(Driver::class.java)) { _, method, arguments ->
          when (method.name) {
            "deviceInfo" -> DeviceInfo(maestro.device.Platform.ANDROID, 100, 100, 100, 100)

            "takeScreenshot" -> {
              peak.accumulateAndGet(active.incrementAndGet(), ::maxOf)
              try {
                assertThat(arguments!![1]).isEqualTo(false)
                val result = stub.screenshot(MaestroAndroid.ScreenshotRequest.newBuilder().build()).bytes.toByteArray()
                (arguments[0] as Sink).write(Buffer().write(result), result.size.toLong())
              } finally {
                active.decrementAndGet()
              }
            }

            "name" -> "supplemental-screenshot-rpc"

            "close" -> Unit

            else -> error("Unexpected fixture method: ${method.name}")
          }
        } as Driver
        val session = AndroidDeviceSession(Maestro(driver), Platform.ANDROID_MOBILE) { "" }
        val directory = Files.createTempDirectory("screenshot-rpc-test")
        val output = directory.resolve("screen.png")
        Files.writeString(output, "prior")
        val invocation = async { session.captureScreenshot(output, 2.seconds) }
        try {
          check(withContext(Dispatchers.IO) { entered.await(2, TimeUnit.SECONDS) })
          invocation.cancel(CancellationException("caller rpc stop"))
          invocation.join()
          assertThat(runCatching { invocation.await() }.exceptionOrNull()?.message).isEqualTo("caller rpc stop")
          assertThat(active.get()).isEqualTo(0)
          check(withContext(Dispatchers.IO) { exited.await(2, TimeUnit.SECONDS) })
          assertThat(Files.readString(output)).isEqualTo("prior")
          session.captureScreenshot(output, 2.seconds)
          assertThat(Files.readAllBytes(output).toList()).isEqualTo(bytes.toList())
          session.captureScreenshot(output)
          assertThat(Files.readAllBytes(output).toList()).isEqualTo(bytes.toList())
          assertThat(active.get()).isEqualTo(0)
          assertThat(peak.get()).isEqualTo(1)
          Files.list(directory).use { assertThat(it.toList()).isEqualTo(listOf(output)) }
        } finally {
          release.countDown()
          invocation.cancel()
          invocation.join()
          session.close()
          channel.shutdownNow().awaitTermination(3, TimeUnit.SECONDS)
          server.shutdownNow().awaitTermination(3, TimeUnit.SECONDS)
          Files.list(directory).use { paths -> paths.forEach(Files::deleteIfExists) }
          Files.delete(directory)
        }
      }
    }
  }
}
