package me.chrisbanes.verity.core.preflight

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import java.nio.file.Files
import kotlin.test.Test
import kotlinx.coroutines.test.runTest

class PathPreflightCheckerTest {
  @Test
  fun `readable file passes`() {
    val file = Files.createTempFile("verity-path-", ".txt")
    Files.writeString(file, "content")

    val report = PathPreflightChecker().requireReadableFile(file, "Journey file")

    assertThat(report.issues).isEmpty()
  }

  @Test
  fun `missing readable file fails`() {
    val file = Files.createTempDirectory("verity-path-").resolve("missing.yaml")

    val report = PathPreflightChecker().requireReadableFile(file, "Journey file")

    assertThat(report.passed).isFalse()
    assertThat(report.issues.single().code).isEqualTo(PreflightCodes.PATH_MISSING)
    assertThat(report.issues.single().message).contains("Journey file")
  }

  @Test
  fun `writable target passes when parent is writable directory`() {
    val dir = Files.createTempDirectory("verity-output-")
    val target = dir.resolve("screenshot.png")

    val report = PathPreflightChecker().requireWritableFileTarget(target, "Screenshot output")

    assertThat(report.passed).isTrue()
  }

  @Test
  fun `temp directory probe passes`() = runTest {
    val report = PathPreflightChecker().requireTempWritable()

    assertThat(report.passed).isTrue()
  }
}
