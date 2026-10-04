# Host packaging

The approved host matrix is macOS ARM64 with Android TV/mobile and the existing
local iOS simulator workflow, and Linux x86-64 with Android TV/mobile. A universal
JAR retains the existing resource fallback. It does not establish support for
additional hosts. macOS x86-64, Linux ARM64, Windows variants and new physical-iOS
support are deferred.

## Runtime prerequisite

Before filtering resources, the universal archive must resolve the Java gRPC
family through the enforced `io.grpc:grpc-bom:1.84.0`. Independently versioned
`grpc-kotlin-stub` is excluded from that Java version assertion. Unshaded
`grpc-netty` is excluded; shaded Netty and OkHttp remain available.

`verifyPackagedGrpc` compares resolved CLI and smoke/probe coordinates, every
packaged `io/grpc` class against its resolved artifact, and the seven exact public
JVM method descriptors invoked by Maestro's Android channel builder. The archive
is loaded with a platform parent, without Gradle application dependencies.

`packagedAndroidTest` is an explicit CI device test. Default offline checks do not
establish Android interoperability. It uses the actual universal JAR for CLI and
both MCP transports, and that JAR plus a test-only probe JAR for direct production
factory operations. Job-owned device setup and the capture budget are separate.
No model calls or local device experiments are required by offline validation.

The stdio command sends its startup diagnostic to stderr and disables Kotlin
Logging's optional initialization message before logger creation. Every stdout
line is checked as an MCP frame; clients do not discard diagnostics. HTTP keeps
its existing stdout startup diagnostic.

Host archive filtering remains gated on a successful universal packaged Android
CI prerequisite and renewed ordinary functional qualification. Historical native
phase evidence keeps its original source/runtime binding and limited claims;
offline checks do not renew it.

## Archives and resource policy

`hostJars` assembles `verity-V.jar`, `verity-V-macos-aarch64.jar` and
`verity-V-linux-x86_64.jar` with identical dependency, manifest, service merging,
ZIP64 and reproducibility settings. `V` is the project version. The original
universal filename remains available. Host filtering selects file entries;
it does not remove dependency classes or alter device architecture slices.

| Resource family | macOS ARM64 | Linux x86-64 | Universal |
| --- | --- | --- | --- |
| Netty QUIC `META-INF/native` | `osx_aarch_64` | `linux_x86_64` | All five platforms |
| Shaded Netty tcnative / epoll | macOS ARM64 tcnative | Linux x86-64 tcnative and epoll | All seven payloads |
| JNA `com/sun/jna/<platform>` | `darwin-aarch64` | `linux-x86-64` | All 26 payloads, including AIX XCOFF |
| Truffle `engine/libtruffleattach/<os>/<arch>` | `darwin/aarch64` | `linux/amd64` | All five groups |
| Selenium Manager | Complete macOS fat binary | Linux executable | All three OS binaries |
| `maestro-app.apk`, `maestro-server.apk` | Both, unchanged | Both, unchanged | Both, unchanged |
| `driver-iPhoneSimulator`, `driver-iphoneos` | Both complete groups | Neither group | Both complete groups |

The two Netty namespaces use mapped library names in `META-INF/native`;
epoll filenames imply Linux without an OS token. JNA uses its platform resource
prefix. Truffle needs each selected binary and its `files` and `sha256` siblings.
Selenium selects by OS, so its complete macOS binary is retained. Generic
native-image reflection/JNI/resource declarations remain intact, including
optional patterns mentioning other platforms. Such declarations are distinct
from platform payloads and group-specific sidecars.

The iOS payload comprises four ZIPs and two `xctestrun` configuration files.
The simulator ZIPs contain 17 and 75 entries; physical-device ZIPs contain 18
and 80 entries. Simulator executables and frameworks contain ARM64 and x86-64
Mach-O slices. macOS and universal retain the original ZIP bytes, inner hashes,
Unix permissions and both slices. Linux removes complete iOS resource groups.
Retaining existing physical-device bundles does not establish new physical-iOS
support.

`verifyHostJars` records resolved artifact coordinates/SHA256, resource origins,
archive entry sizes and hashes, native header/architecture classifications,
loader metadata and complete nested bundle inventories. It rejects unknown
native payloads and verifies every retained entry against universal, including
classes, context, service providers and Kotlin metadata. Each archive must
resolve Maestro's seven exact public gRPC descriptors with a platform-only
class loader. `check` depends on this verifier and `verifyPackagedGrpc`.

Build-logic policy fixtures cover loader aliases and groups, metadata, device
payloads and native header detection. The explicit offline
`hostPackagingArchiveTest` suite takes the produced universal and macOS JARs
through Gradle properties `hostPackagingUniversalArchive` and
`hostPackagingFixtureArchive`. It mutates real ZIP directories and nested
payloads to test missing required resources, forbidden OS/ISA entries and
sidecars, duplicates, unknown binaries and corrupt or incomplete iOS bundles.
It never loads native libraries. The verifier also applies negative mutations
to snapshots of the actual three archives.

## Offline measurements

With project version `0.1.0` and the aligned T1 runtime, the universal archive is
189,732,241 bytes, macOS ARM64 is 169,668,566 bytes and Linux x86-64 is
151,985,838 bytes. These are comparable compressed archive sizes under the same
assembly inputs. Filtering removes 20,063,675 bytes for macOS and 37,746,403 bytes
for Linux. The universal SHA256 remains
`a216975a6308dad3ac2a4dce936abb5cd956af9209cd245c9aab68660826d6d7`, identical to
the accepted T1 archive. Historical pre-alignment sizes are separate observations
and are not the baseline for these differences.

These are resource and ABI checks. Matching-host production factory, CLI and
MCP qualification of the two filtered archives remains a separate CI gate.
No new host support or release publication follows from offline assembly alone.
