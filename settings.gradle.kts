import org.gradle.caching.http.HttpBuildCache

pluginManagement {
  includeBuild("build-logic")
}

rootProject.name = "verity"

dependencyResolutionManagement {
  repositories {
    mavenCentral()
    // R8 is published only to Google Maven.
    exclusiveContent {
      forRepository { google() }
      filter { includeGroup("com.android.tools") }
    }
  }
}

include(":verity:core")
include(":verity:device")
include(":verity:agent")
include(":verity:mcp")
include(":verity:cli")
include(":verity:smoke-tests")

val isCi = !providers.environmentVariable("CI").orNull.isNullOrEmpty()

fun gradleProperty(name: String): String? = providers.gradleProperty(name).orNull?.takeIf(String::isNotBlank)

fun gradleBooleanProperty(name: String): Boolean = providers.gradleProperty(name).map(String::toBoolean).getOrElse(false)

val remoteBuildCacheUrl = gradleProperty("remoteBuildCacheUrl")
val remoteBuildCacheUsername = gradleProperty("remoteBuildCacheUsername")
val remoteBuildCachePassword = gradleProperty("remoteBuildCachePassword")
val isRemoteBuildCachePushEnabled = gradleBooleanProperty("remoteBuildCachePush")
val isRemoteBuildCacheEnabled =
  remoteBuildCacheUrl != null &&
    remoteBuildCacheUsername != null &&
    remoteBuildCachePassword != null

buildCache {
  local {
    isEnabled = !isCi || !isRemoteBuildCacheEnabled
    if (!isEnabled) {
      logger.lifecycle("Local build cache disabled because remote build cache is enabled on CI")
    }
  }

  remote<HttpBuildCache> {
    if (isRemoteBuildCacheEnabled) {
      isEnabled = true
      isPush = isRemoteBuildCachePushEnabled
      url = uri(checkNotNull(remoteBuildCacheUrl))
      credentials {
        username = remoteBuildCacheUsername
        password = remoteBuildCachePassword
      }
      logger.lifecycle("Remote build cache enabled (push: $isRemoteBuildCachePushEnabled)")
    } else {
      isEnabled = false
      logger.lifecycle(
        "Remote build cache disabled " +
          "(url present: ${remoteBuildCacheUrl != null}, " +
          "username present: ${remoteBuildCacheUsername != null}, " +
          "password present: ${remoteBuildCachePassword != null})",
      )
    }
  }
}
