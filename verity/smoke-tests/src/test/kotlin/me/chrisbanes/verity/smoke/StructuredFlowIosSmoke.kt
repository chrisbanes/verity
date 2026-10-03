package me.chrisbanes.verity.smoke

import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import me.chrisbanes.verity.core.model.Platform
import org.junit.jupiter.api.Tag

@Tag("qualification-ios")
class StructuredFlowIosSmoke {
  @Test
  fun `structured and supplied Settings flows satisfy finite native qualification`() = runBlocking {
    qualifyStructuredFlows(Platform.IOS)
  }
}
