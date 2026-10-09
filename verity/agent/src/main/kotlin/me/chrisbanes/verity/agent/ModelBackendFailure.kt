package me.chrisbanes.verity.agent

/** Safe backend failures carry neither raw protocol data nor causes. */
class ModelBackendFailure(val kind: ModelBackendFailureKind) : Exception("Codex ${kind.name.lowercase()} failure")

enum class ModelBackendFailureKind { INSTALLATION, VERSION, HOST, PROTOCOL, ISOLATION, STARTUP_TIMEOUT, CLEANUP, REQUEST, AUTH, MODEL, MODALITY, EFFORT }
