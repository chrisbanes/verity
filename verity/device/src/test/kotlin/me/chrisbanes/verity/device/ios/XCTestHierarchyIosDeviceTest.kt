package me.chrisbanes.verity.device.ios

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isSameInstanceAs
import device.IOSDevice
import hierarchy.AXElement
import hierarchy.AXFrame
import hierarchy.ViewHierarchy
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest
import maestro.Maestro
import maestro.drivers.IOSDriver

class XCTestHierarchyIosDeviceTest {
  private val hierarchy = ViewHierarchy(
    AXElement("Settings", 0, "root", 0, 0L, 0, false, 0, false, "", "", AXFrame(0f, 0f, 100f, 50f), true, "", arrayListOf()),
    1,
  )

  private fun device(call: (String, Array<out Any?>) -> Any?): IOSDevice = Proxy.newProxyInstance(
    IOSDevice::class.java.classLoader,
    arrayOf(IOSDevice::class.java),
  ) { _, method, args -> call(method.name, args ?: emptyArray()) } as IOSDevice

  private fun local(call: (String, Array<out Any?>) -> Any? = { _, _ -> null }): IOSDevice = device { name, args ->
    check(name != "viewHierarchy") { "Unclosed SDK warning scheduler path must never be used" }
    call(name, args)
  }

  @Test
  fun `hierarchy preserves both hierarchy flags and exact result without local warning path`() {
    val flags = mutableListOf<Boolean>()
    val adapter = XCTestHierarchyIosDevice(
      local(),
      device { name, args ->
        check(name == "viewHierarchy")
        flags += args.single() as Boolean
        hierarchy
      },
    )
    assertThat(adapter.viewHierarchy(false)).isSameInstanceAs(hierarchy)
    assertThat(adapter.viewHierarchy(true)).isSameInstanceAs(hierarchy)
    assertThat(flags).isEqualTo(listOf(false, true))
  }

  @Test
  fun `hierarchy preserves original SDK failure`() {
    val failure = IllegalStateException("XCTest transport failure")
    val adapter = XCTestHierarchyIosDevice(local(), device { _, _ -> throw failure })
    assertThat(assertFailsWith<IllegalStateException> { adapter.viewHierarchy(true) }).isSameInstanceAs(failure)
  }

  @Test
  fun `local controller and close operations stay delegated`() {
    val calls = mutableListOf<Pair<String, List<Any?>>>()
    val adapter = XCTestHierarchyIosDevice(
      local { name, args ->
        calls += name to args.toList()
        if (name == "getDeviceId") "owned-device" else null
      },
      device { _, _ -> error("Only hierarchy belongs to XCTest delegate") },
    )
    assertThat(adapter.deviceId).isEqualTo("owned-device")
    adapter.pressKey("return")
    adapter.tap(12, 34)
    adapter.close()
    assertThat(calls).isEqualTo(listOf("getDeviceId" to emptyList(), "pressKey" to listOf("return"), "tap" to listOf(12, 34), "close" to emptyList()))
  }

  @Test
  fun `real IOSDriver and noarg session use same adapter hierarchy dispatch`() = runTest {
    val flags = mutableListOf<Boolean>()
    val adapter = XCTestHierarchyIosDevice(
      local(),
      device { name, args ->
        check(name == "viewHierarchy")
        flags += args.single() as Boolean
        hierarchy
      },
    )
    val driver = IOSDriver(adapter)
    driver.contentDescriptor(false)
    driver.contentDescriptor(true)
    val session = IosDeviceSession(Maestro.ios(driver, openDriver = false), adapter)
    assertThat(session.captureHierarchyTree().attributes["text"]).isEqualTo("Settings")
    assertThat(flags).isEqualTo(listOf(false, true, false))
  }
}
