package me.chrisbanes.verity.device.ios

import device.IOSDevice
import hierarchy.ViewHierarchy

/** Avoids LocalIOSDevice's unclosed warning scheduler while retaining its controller behavior. */
internal class XCTestHierarchyIosDevice(
  localDevice: IOSDevice,
  private val xcTestDevice: IOSDevice,
) : IOSDevice by localDevice {
  override fun viewHierarchy(excludeKeyboardElements: Boolean): ViewHierarchy = xcTestDevice.viewHierarchy(excludeKeyboardElements)
}
