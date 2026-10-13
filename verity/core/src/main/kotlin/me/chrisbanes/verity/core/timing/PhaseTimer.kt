package me.chrisbanes.verity.core.timing

import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

enum class PhaseKind { DEVICE, MODEL_REQUEST, MODEL_DETAIL }

/** Records how long named steps and the phases inside them take; opt-in via `run --timing`. */
interface PhaseTimer {
  suspend fun <T> step(label: String, block: suspend () -> T): T

  suspend fun <T> phase(kind: PhaseKind, name: String, block: suspend () -> T): T
}

object NoOpPhaseTimer : PhaseTimer {
  override suspend fun <T> step(label: String, block: suspend () -> T): T = block()

  override suspend fun <T> phase(kind: PhaseKind, name: String, block: suspend () -> T): T = block()
}

data class PhaseTiming(val kind: PhaseKind, val name: String, val count: Int, val total: Duration)

data class StepTiming(val label: String, val total: Duration, val phases: List<PhaseTiming>) {
  /** Only outer model requests; [PhaseKind.MODEL_DETAIL] runs inside them and would double count. */
  val modelTotal: Duration get() = phases.filter { it.kind == PhaseKind.MODEL_REQUEST }.fold(Duration.ZERO) { acc, it -> acc + it.total }
  val deviceTotal: Duration get() = phases.filter { it.kind == PhaseKind.DEVICE }.fold(Duration.ZERO) { acc, it -> acc + it.total }
}

data class TimingReport(val steps: List<StepTiming>) {
  fun render(): String = buildString {
    append("Timing")
    steps.forEach { step ->
      append("\n${step.label}: ${step.total.ms()} (model ${step.modelTotal.ms()}, device ${step.deviceTotal.ms()})")
      step.phases.forEach { append("\n  ${it.kind.name.lowercase()} ${it.name} x${it.count} ${it.total.ms()}") }
    }
  }

  private fun Duration.ms() = "${inWholeMilliseconds}ms"
}

/** Steps are sequential, so one current-step slot suffices; synchronized because phases may record from several dispatchers. */
class RecordingPhaseTimer(private val nowNanos: () -> Long = System::nanoTime) : PhaseTimer {
  private class Step(val label: String) {
    var total = Duration.ZERO
    val phases = LinkedHashMap<Pair<PhaseKind, String>, PhaseTiming>()
  }

  private val lock = Any()
  private val steps = mutableListOf<Step>()
  private var current: Step? = null

  override suspend fun <T> step(label: String, block: suspend () -> T): T {
    val step = synchronized(lock) {
      Step(label).also {
        steps += it
        current = it
      }
    }
    val start = nowNanos()
    try {
      return block()
    } finally {
      synchronized(lock) {
        step.total = (nowNanos() - start).nanoseconds
        if (current === step) current = null
      }
    }
  }

  override suspend fun <T> phase(kind: PhaseKind, name: String, block: suspend () -> T): T {
    val start = nowNanos()
    try {
      return block()
    } finally {
      val elapsed = (nowNanos() - start).nanoseconds
      synchronized(lock) {
        val step = current ?: steps.firstOrNull { it.label == NO_STEP } ?: Step(NO_STEP).also { steps += it }
        step.phases.merge(kind to name, PhaseTiming(kind, name, 1, elapsed)) { old, new -> old.copy(count = old.count + 1, total = old.total + new.total) }
      }
    }
  }

  fun report(): TimingReport = synchronized(lock) {
    TimingReport(steps.map { StepTiming(it.label, it.total, it.phases.values.toList()) })
  }

  private companion object {
    const val NO_STEP = "(no step)"
  }
}
