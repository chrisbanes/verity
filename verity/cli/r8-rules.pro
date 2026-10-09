# Keep rules for the R8-shrunk CLI archives (universalShrunkJar).
# Policy: no -ignorewarnings, no bare -dontwarn */**, no -dontshrink, no global keep.
# Every -keep and -dontwarn is preceded by a comment naming the path or failure it addresses.

# Verity's modules use kotlinx-serialization, Kaml and Clikt reflection and are small; keep them whole so
# savings come from dependencies only. Shadow already keeps :verity:cli classes.
-keep,includedescriptorclasses class me.chrisbanes.verity.** { *; }

# Names stay unchanged, but R8 strips attributes by default. Jackson's Kotlin module, kotlin-reflect, Kaml and
# Graal read generic signatures, annotations (including kotlin.Metadata) and inner-class tables at runtime;
# keep source and line attributes for readable stack traces.
-keepattributes Signature,InnerClasses,EnclosingMethod,Exceptions,*Annotation*,MethodParameters,SourceFile,LineNumberTable,Record,PermittedSubclasses,NestHost,NestMembers

# R8 otherwise strips kotlin.Metadata from every class not pinned by a keep rule. kotlin-reflect and
# jackson-module-kotlin (Maestro's flow models) read it at runtime.
-keepkotlinmetadata
# R8 retains class metadata only for classes matched by a keep rule. Match every class without pinning it or its
# members, so unused classes and members are still removed.
-keep,allowshrinking class **

# Missing-class diagnostics. Every class below is also absent from the unshrunk archive: these are optional
# integrations that the referencing library probes for at runtime, or code paths Verity never reaches.

# Log4j and bnd OSGi/SPI annotations (compile-time only) and Log4j's OSGi service locator.
-dontwarn aQute.bnd.annotation.**
-dontwarn org.osgi.framework.**
# kotlin-logging's optional Logback backend; Verity logs through Log4j.
-dontwarn ch.qos.logback.classic.**
# Netty's optional Log4j 1 logger factory.
-dontwarn org.apache.log4j.**
# Maestro's unused Maestro AI cloud client is compiled against Ktor 2 plugin classes that Ktor 3 removed.
-dontwarn io.ktor.client.plugins.HttpTimeout
-dontwarn io.ktor.client.plugins.HttpTimeout$*
-dontwarn io.ktor.client.plugins.contentnegotiation.ContentNegotiation
-dontwarn io.ktor.client.plugins.contentnegotiation.ContentNegotiation$*
# Maestro references a jackson-module-kotlin exception removed from the version on the classpath.
-dontwarn com.fasterxml.jackson.module.kotlin.MissingKotlinParameterException
# Commons Compress optional codecs (zstd, brotli, xz) for archive formats Verity does not read.
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**
-dontwarn org.tukaani.xz.**
# OpenTelemetry SDK build-time annotations and incubator declarative-config API.
-dontwarn com.google.auto.value.**
-dontwarn io.opentelemetry.api.incubator.**
# Micrometer context-propagation integration, used only when that library is present.
-dontwarn io.micrometer.context.**
# Netty's optional native OpenSSL binding (netty-tcnative) used only when present.
-dontwarn io.netty.internal.tcnative.**
# Optional TLS providers probed by OkHttp and Netty: BouncyCastle, Conscrypt and OpenJSSE.
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
# Netty's BlockHound integration, loaded only when BlockHound is installed.
-dontwarn reactor.blockhound.**
