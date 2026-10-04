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
