rootProject.name = "verity"

dependencyResolutionManagement {
  repositories {
    mavenCentral()
  }
}

include(":verity:core")
include(":verity:device")
include(":verity:agent")
include(":verity:mcp")
include(":verity:cli")
include(":verity:smoke-tests")
