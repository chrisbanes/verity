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

`packagedAndroidTest` and `packagedIosTest` are explicit CI device tests. They
exercise the matching-host and universal production JARs for CLI and both MCP
transports, and each JAR plus a test-only probe for direct production factory
operations. Default offline checks do not establish native interoperability.
Action-managed device setup and capture budgets are separate; offline validation
requires no model calls or local device experiments.

The stdio command sends startup diagnostics and actual backend ERROR logging to
stderr. It disables Kotlin Logging's optional initialization message before
logger creation and uses a dedicated Log4j console configuration. All three
archives merge binary Log4j plugin metadata so that configuration remains
discoverable in packaged execution. Every stdout line must be an MCP frame;
clients do not discard diagnostics. Ordinary CLI and HTTP logging retain their
existing thresholds and behavior.

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

## Shrunk archives

`shrunkJars` builds R8-shrunk counterparts of the three archives in
`verity/cli/build/libs/shrunk/`, with the same release filenames. The unshrunk
`shadowJar`, `macosArm64Jar` and `linuxX64Jar` tasks, names and outputs are
unchanged. They remain the comparison baseline and the recovery path, and
releases still publish them.

`universalShrunkJar` is a ShadowJar with the shared archive settings and
`minimize { r8 { } }`. R8 `com.android.tools:r8:9.1.31` comes from Google Maven,
restricted to the `com.android.tools` group. R8 runs once, on the universal
archive. `macosArm64ShrunkJar` and `linuxX64ShrunkJar` are `Zip` tasks that apply
`HostPackaging.retain` to that output, with the host archives' ordering,
timestamp and ZIP64 settings. Host filtering selects resources only, so this
gives the same classes as shrinking each host archive separately.

Shrinking removes code only. Shadow passes `--no-minification` and generates
`-dontoptimize`, so retained classes keep their names and bytecode is not
optimized. Shadow keeps every `META-INF/services` provider and applies consumer
rules published in dependency JARs. Verity's own `me.chrisbanes.verity.**`
classes are kept whole by a rule in `verity/cli/r8-rules.pro`.
`minimize { exclude(project(...)) }` is not used: Shadow also excludes every
transitive dependency of an excluded project, which kept 61,303 of 65,140
classes.

`r8-rules.pro` contains only targeted rules. Each `-keep` and `-dontwarn` block
has a comment naming the runtime path or diagnostic it addresses. The file
forbids `-ignorewarnings`, bare `-dontwarn *`/`**`, `-dontshrink` and a pinned
global keep. The current rules cover:

- reflection attributes and Kotlin metadata for every retained class;
- reflective enum `values()`/`valueOf`;
- Log4j plugins, their builder fields, visitors and validators;
- Truffle's by-name accessors, generated libraries and DSL state fields;
- missing-class diagnostics for optional integrations. Every class named by a
  `-dontwarn` is also absent from the unshrunk archive.

`verifyShrunkJars` runs in `check` and writes
`verity/cli/build/reports/shrunk-packaging.tsv`. For each host it compares the
shrunk archive with its unshrunk counterpart:

- Every resource has the same name, hash and nested iOS inventory. R8 rewrites
  two families itself. Service descriptors are re-serialized, so their provider
  lists must match exactly. `.kotlin_module` files are regenerated from the
  retained file facades; their names must map to unshrunk module files.
- Shrunk class names are a subset of the unshrunk names. The only exception is
  an R8 synthetic `Owner$N` helper, marked by its `R8$$SyntheticClass` source
  and owned by a retained class. One is currently emitted, for a cast in dead
  Koog tool-result code.
- The `Main-Class` and every Verity class are present, the class sets of the
  three shrunk archives are identical, and Maestro's gRPC descriptors resolve
  under a platform-only class loader.

It also lints the rules, requires `-dontoptimize` in the effective R8
configuration (`build/reports/r8/configuration.txt`) and rejects 11 mutations of
the real archive snapshots.

Offline `check` runs the packaged probes against both universal archives.
Shrunk output must match unshrunk output:

- `McpCommandTest` covers stdio frames, HTTP startup and logging thresholds for
  all six archives.
- `PackagedCliPlatformOptionTest` runs `--help`, `list` and a fast-path
  `run --dry-run`.
- `PackagedDynamicEntryTest` checks the gRPC ABI chain and the loadability of
  every service provider and Log4j plugin. It evaluates Graal JS through
  Maestro's engine. It creates all nine production provider clients and sends
  text and, where supported, image prompts through settings-redirected clients to
  a local server.

Probe and fixture code runs beside the shrunk archive, so it may only use
members that production reaches. Smoke-test Kotlin is compiled without null-check
intrinsics, because R8 rewrites production's intrinsic calls and drops the unused
methods. Rules are never added only to make test code link.

CI runs the `-shrunk` variants of `packagedAndroidTest` and `packagedIosTest`
beside the unshrunk variants. Each shrunk variant is accepted on its unshrunk
counterpart's host.

Shrinking becomes the release default only after these offline and device
checks, and a granted live ChatGPT journey through the shrunk macOS archive, all
pass on the same archive bytes. Until then, `packageRelease` publishes the
unshrunk archives.

### Shrunk measurements

Measured at
[`dcbe48fc7f1220120ee1067526d092aca486ec47`](https://github.com/chrisbanes/verity/tree/dcbe48fc7f1220120ee1067526d092aca486ec47)
on macOS 26.7 arm64, with the JDK 21.0.12.1 toolchain and launcher, Gradle
9.8.1, Shadow 9.6.1 and R8 9.1.31. The SHA256 of the `artifact` lines of
`host-packaging.tsv` is
`cefd12b508c01937c11734b7f31751dd17f4d4a971554467aaa783211d537239`. Sizes come
from the `archive` rows of `shrunk-packaging.tsv`. Three rebuilds produced
identical bytes.

| Archive pair | Unshrunk bytes | Shrunk bytes | Saved | Shrunk SHA256 |
| --- | ---: | ---: | ---: | --- |
| Universal | 190,285,922 | 126,819,182 | 63,466,740 (33.4%) | `2322a9f260c6929b488ef0b5ea265af30fc1ff59bd3a1d64134be302fb18bc1f` |
| macOS ARM64 | 170,222,247 | 106,921,943 | 63,300,304 (37.2%) | `0a3d6a70c0ce2786eb7c07daebd159fe0c39cff701738d068a54dcb2c49ee746` |
| Linux x86-64 | 152,539,519 | 89,239,465 | 63,300,054 (41.5%) | `6b1a098d315547794b385fc4107554969b23c18e1d3a12b8afdeb945494c23df` |

Classes fall from 65,140 to 32,900 in every archive, and compressed class bytes
from 100,771,962 to 44,693,162. Compressed resource bytes fall by 21,234 only,
from the regenerated Kotlin module files and re-serialized service descriptors.
The remaining compressed resource costs are:

| Resource family | Universal | macOS ARM64 | Linux x86-64 |
| --- | ---: | ---: | ---: |
| Android APKs | 12,270,189 | 12,270,189 | 12,270,189 |
| iOS driver groups | 16,697,725 | 16,697,725 | 0 |
| Native payloads | 27,587,560 | 7,539,561 | 6,556,292 |
| Other resources | 16,418,510 | 16,418,510 | 16,418,510 |

Savings compare each host archive with its own shrunk counterpart. The host
filter's savings are separate and are described in the measurements below.

Build time is the median of three `--rerun --profile` runs of
`:verity:cli:shadowJar :verity:cli:universalShrunkJar` (`build/reports/profile/`).
`shadowJar` took 15.5 s (13.3–17.9 s). `universalShrunkJar`, which assembles its
own archive and then runs R8, took 89.4 s (84.1–96.7 s). R8 runs in a separate
JVM. Shadow then rewrites its output in the Gradle daemon and buffers every
entry, so `gradle.properties` sets the daemon heap to 2 GiB, matching CI.

## Release assets and installation

For version `V`, `packageRelease` produces exactly four publication assets:

- `verity-V.jar`
- `verity-V-macos-aarch64.jar`
- `verity-V-linux-x86_64.jar`
- `verity-V-checksums.sha256`, containing three SHA256 lines with exact JAR names.

Choose by host OS/ISA, download the selected JAR and checksum manifest from the
same release, and verify the exact filename's SHA256 before invoking
`java -jar JAR`. Java 21 or later is required. The universal filename remains the
fallback; it does not qualify hosts outside the matrix.

The Homebrew formula selects macOS ARM64 and Linux Intel assets on those hosts,
with universal as its fallback. Each URL/SHA pair uses `:nounzip`; the selected
asset is installed under the canonical name `libexec/verity.jar`. Its launcher
uses Homebrew's Java 21 and that canonical path, independent of the downloaded
classifier basename.

The future version-tag workflow serializes each tag without cancelling an active
run. Existing releases are reusable only with exactly these four matching assets;
partial or conflicting publication fails without overwriting. After a new upload,
provider readback and downloads verify the tag, names, sizes and SHA256 before a
receipt permits formula generation and the tap update. The JSON build manifest is
internal, not an extra published asset. Offline Python and executed Ruby DSL
fixtures cover publication failures, host fallback, URLs, checksums, installation
and launch paths using simulated publication data. No release or tap update has
been performed as part of this change.

## Comparable measurements

These measurements use version `0.1.0` at source
[`b9d777ac315dc76f4f606aec5869eac6e00ed69f`](https://github.com/chrisbanes/verity/tree/b9d777ac315dc76f4f606aec5869eac6e00ed69f),
with Kotlin 2.4.20, Gradle 9.8.0, Maestro 2.11.0, Java gRPC BOM 1.84.0 and
Kotlin gRPC stub 1.5.0. The source configures JVM 21 as the compilation toolchain;
CI configures its launcher through `setup-java` with `25.0.4+101.0.LTS`. These are
configured versions, not a claim about an unrecorded local resolved JDK build.
All three archives share dependency, manifest, service/plugin merging and ZIP
compression inputs. The final universal archive is the same-input unfiltered
baseline. Its retained classes/resources are the comparison reference for the
filtered archives.

| Archive | Bytes | Bytes omitted against universal | SHA256 |
| --- | ---: | ---: | --- |
| `verity-0.1.0.jar` | 189,740,640 | 0 | `9e9ab61c1c41e5ee1c08bd895176d858d2916d8b57b05a75040c22ac0e8b0bd9` |
| `verity-0.1.0-macos-aarch64.jar` | 169,676,965 | 20,063,675 | `4fcdf0c68b80e63ca661bf0f39a18641b0a0bbb3c2f7fce381a95c3b2e9ff1c2` |
| `verity-0.1.0-linux-x86_64.jar` | 151,994,237 | 37,746,403 | `c8c36908f8dff826039d5d352abae3091505eed20fbc27e0f1904d2b935e9774` |

The following cells are **uncompressed / compressed payload bytes**, excluding
ZIP headers and directory overhead. Common entries, both Android APKs and the
retained iOS groups are byte-identical across applicable archives.

| Family | Universal | macOS ARM64 | Linux x86-64 |
| --- | ---: | ---: | ---: |
| Common | 305,885,741 / 116,717,630 | 305,885,741 / 116,717,630 | 305,885,741 / 116,717,630 |
| Android APKs | 12,621,280 / 12,270,189 | 12,621,280 / 12,270,189 | 12,621,280 / 12,270,189 |
| iOS driver groups | 16,852,695 / 16,697,725 | 16,852,695 / 16,697,725 | 0 / 0 |
| gRPC shaded native | 15,676,648 / 6,161,052 | 2,799,120 / 1,118,103 | 3,140,592 / 1,392,967 |
| Selenium Manager | 17,061,072 / 8,474,440 | 8,027,024 / 4,158,842 | 5,386,048 / 2,429,170 |
| Truffle attach | 297,873 / 60,476 | 51,619 / 2,854 | 63,450 / 24,149 |
| JNA | 5,114,679 / 1,659,002 | 159,800 / 28,492 | 134,447 / 51,987 |
| Netty QUIC | 31,332,128 / 11,232,590 | 6,490,704 / 2,231,270 | 6,777,872 / 2,658,019 |

ZIP container overhead is 16,467,536 bytes for universal, 16,451,860 for macOS and
16,450,126 for Linux. The complete inventory SHA256 is
`698b9c3e1232366800e80a471e10a213bd99def100bfbe660bb05f9ee8104c29`.
It includes 380 nested rows across macOS and universal (190 each), covering the
four ZIPs described above and their original payload hashes and permissions.
These size differences measure packaging only; they do not establish startup,
memory or execution-performance improvements.

Historical observations use different inputs and are not subtraction baselines:
189,776,800 bytes at `430c8a6`, 177,683,241 at `5a09cfd`, and the then-broken
189,877,337-byte universal at `7f58ae4`.

## Current CI coverage

The `smoke-android` job tests the Linux and universal JARs and their shrunk
counterparts against an action-managed API 34 Android emulator. The independent
`smoke-ios` job tests the macOS and universal JARs and their shrunk counterparts
against an available, erased iOS simulator on `macos-latest`. The actions
handle boot and shutdown, and their selected device IDs are passed directly to
Gradle. No macOS Android job runs in normal CI, so macOS-specific
Android interoperability is outside that CI coverage. The supported host matrix
is unchanged. The packaged harness still owns and joins its child processes.

## Historical qualification and limits

[CI run 37408570148, attempt 1](https://github.com/chrisbanes/verity/actions/runs/37408570148)
passed the build, Linux and macOS checks for the measured runtime. Linux ran two
packaged tests (Linux and universal) plus three ordinary Android tests; macOS ran
two packaged Android tests (macOS and universal), two packaged iOS tests (macOS
and universal) and three ordinary iOS tests. All 12 native XML cases had zero
failures, errors or skips. The tested merge `4978e2eb03960fca4b78149fc1a261d709f8c25f`
had the same source tree as the measured commit.

The packaged tests exercise actual production factories, no-argument and bounded
hierarchy capture, key mapping, caller cancellation with joined worker reuse,
close and natural child exit. They also verify real CLI help/list and model-free
Settings runs with persisted results, and real SDK stdio/HTTP initialization,
device tools, snapshots, close/reopen and old-snapshot rejection. Model request
counters and live child counts finish at zero. The job-owned Android emulator
handoff is checked before iOS, and the exact iOS simulator is shut down and
deleted afterward.

Android TV mappings are exercised on mobile emulators; this is not native TV
hardware qualification. The iOS evidence is for the configured simulator, not
physical devices. Additional host combinations remain deferred. A supplementary
macOS health snapshot timed out and remains unknown; the universal snapshot
retained a selected fragment only. Failure-only kernel diagnostics were not
exercised in this successful run. None of these observations establishes a guest
failure cause or replaces the native assertions. Publication remains a separate
future-tag operation.
