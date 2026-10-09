# Keep rules for the R8-shrunk CLI archives (universalShrunkJar).
# Policy: no -ignorewarnings, no bare -dontwarn */**, no -dontshrink, no global keep.
# Every -keep and -dontwarn is preceded by a comment naming the path or failure it addresses.

# Verity's modules use kotlinx-serialization, Kaml and Clikt reflection and are small; keep them whole so
# savings come from dependencies only. Shadow already keeps :verity:cli classes.
-keep,includedescriptorclasses class me.chrisbanes.verity.** { *; }

# Names stay unchanged, but R8 strips attributes by default. Jackson's Kotlin module, kotlin-reflect, Kaml and
# Graal read generic signatures, annotations (including kotlin.Metadata) and inner-class tables at runtime;
# keep source and line attributes for readable stack traces. PermittedSubclasses is deliberately dropped: R8 removes
# unused intermediate sealed types (Koog's MessagePart$Tool) but leaves stale permitted lists, so the JVM rejects
# retained subclasses (IncompatibleClassChangeError). Kotlin reads sealed hierarchies from kotlin.Metadata.
-keepattributes Signature,InnerClasses,EnclosingMethod,Exceptions,*Annotation*,MethodParameters,SourceFile,LineNumberTable,Record,NestHost,NestMembers

# R8 otherwise strips kotlin.Metadata from every class not pinned by a keep rule. kotlin-reflect and
# jackson-module-kotlin (Maestro's flow models) read it at runtime.
-keepkotlinmetadata
# R8 retains class metadata only for classes matched by a keep rule. Match every class without pinning it or its
# members, so unused classes and members are still removed.
-keep,allowshrinking class **

# Log4j loads every plugin in the merged Log4j2Plugins.dat cache by class name and invokes its
# @PluginFactory/@PluginBuilderFactory members reflectively.
-keep @org.apache.logging.log4j.core.config.plugins.Plugin class * { *; }
# Plugin builders (for example ConsoleAppender$Builder and its superclasses) receive configuration through
# fields annotated @PluginBuilderAttribute/@PluginElement/@Required, set reflectively.
-keepclassmembers class * {
  @org.apache.logging.log4j.core.config.plugins.** *;
}
# Those annotations name their PluginVisitor and ConstraintValidator implementations, which Log4j
# instantiates reflectively through their no-argument constructors.
-keep class org.apache.logging.log4j.core.config.plugins.visitors.** { <init>(); }
-keep class org.apache.logging.log4j.core.config.plugins.validation.validators.** { <init>(); }

# R8 treats a class whose constructors are never called directly as uninstantiable and rewrites casts to it
# into an always-throwing R8 synthetic helper. Jackson, ServiceLoader and Class.newInstance callers create
# such classes reflectively, so keep the constructors of every retained class (classes stay removable).
-keepclassmembers class ** {
  <init>(...);
}

# Class.getEnumConstants(), Enum.valueOf and EnumSet call the synthetic values()/valueOf members reflectively
# (Truffle, Jackson, Clikt choice options); R8 otherwise drops them when no direct call remains.
-keepclassmembers enum * {
  public static **[] values();
  public static ** valueOf(java.lang.String);
}

# Maestro binds its flow YAML, XCTest driver requests/responses, view hierarchy and simctl/devicectl JSON with
# Jackson (Kotlin module): constructors, properties and generic-only element types are reached reflectively.
# Keep the YAML models and the iOS driver packages whole; keep every member of other retained Maestro classes.
-keep class maestro.orchestra.yaml.** { *; }
-keep class xcuitest.** { *; }
-keep class hierarchy.** { *; }
-keep class util.** { *; }
-keep class device.** { *; }
-keepclassmembers class maestro.**, ios.** { *; }

# JNA (Mordant terminal detection) registers natives and calls back Java members such as Native.fromNative by
# name from its dispatch library, and maps Library interfaces and Structure fields reflectively.
-keep class com.sun.jna.** { *; }
-keep class * extends com.sun.jna.** { *; }
-keep interface * extends com.sun.jna.** { *; }

# Netty resolves its own fields and methods by name through MethodHandles, VarHandles and atomic field updaters
# (for example ConcurrentSkipListIntObjMultimap's "head" and "acquireFenceFallback"); without them the Ktor
# server's allocator fails to initialize. Keep every member of retained Netty classes, shaded copy included.
-keepclassmembers class io.netty.**, io.grpc.netty.shaded.io.netty.** { *; }

# gRPC's shaded Netty probes Epoll reflectively; its epoll and tcnative JNI code registers natives and looks up
# Java classes, fields and methods by name.
-keep class io.grpc.netty.shaded.io.netty.channel.epoll.** { *; }
-keep class io.grpc.netty.shaded.io.netty.channel.unix.** { *; }
-keep class io.grpc.netty.shaded.io.netty.internal.tcnative.** { *; }

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
# OpenTelemetry SDK build-time annotations and the incubator declarative-config API and SDK extension.
-dontwarn com.google.auto.value.**
-dontwarn io.opentelemetry.api.incubator.**
-dontwarn io.opentelemetry.sdk.extension.incubator.**
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

# Truffle (Graal JS for Maestro scripts) loads DSL-generated library and export classes by name
# (<Library>Gen, <Receiver>Gen); without them Context creation fails with "not a registered library".
-keep class com.oracle.truffle.**Gen { *; }
# Truffle DSL inlined nodes look up the state fields of generated nested data classes reflectively
# (InlineSupport.StateField.create(lookup, "append3_state_0_")).
-keepclassmembers class com.oracle.truffle.**Gen$** { <fields>; }
# Truffle's Accessor bridge (com.oracle.truffle.api.impl.Accessor) loads every module accessor, its nested
# implementation and the *SupportImpl classes by name (for example LanguageAccessor$LanguageImpl,
# DynamicObjectSupportImpl).
-keep class com.oracle.truffle.**Accessor { *; }
-keep class com.oracle.truffle.**Accessor$* { *; }
-keep class com.oracle.truffle.**SupportImpl { *; }
# Optional dependencies of Log4j plugins kept above (async loggers, JMS/Kafka/ZeroMQ/SMTP appenders, CSV
# layouts, OSGi bundle and versioning annotations); Verity's Log4j configurations use none of these plugins.
-dontwarn com.conversantmedia.util.concurrent.**
-dontwarn com.lmax.disruptor.**
-dontwarn javax.activation.**
-dontwarn javax.jms.**
-dontwarn javax.mail.**
-dontwarn org.apache.commons.csv.**
-dontwarn org.apache.kafka.**
-dontwarn org.jctools.queues.**
-dontwarn org.osgi.annotation.**
-dontwarn org.zeromq.**
