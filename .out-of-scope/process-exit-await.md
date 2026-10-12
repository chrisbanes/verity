# Awaiting process exit with `Process.onExit()`

Verity waits for short-lived child processes, such as the `codex --version` and
`app-server generate-json-schema` processes in `CodexModelBackend.prepare`, by
polling `Process.isAlive` with a short `delay`. We do not replace this with
`process.onExit().await()`.

## Why this is out of scope

Cancelling the `CompletableFuture` returned by `Process.onExit()` does not stop the
work behind it. The JDK's default implementation calls `waitFor()` on a
common-pool thread, so if the process never exits (a hung child or a test-fake
`Process` that does not implement `onExit()`/`waitFor()`), that thread stays
blocked after the awaiting coroutine is cancelled.

Polling has no such leak: cancellation stops the loop at the next `delay`. Its
cost, one `isAlive` check every 10 ms for a process that lives well under a
second, is negligible. Swapping a cheap, cancellation-safe loop for a
cancellation leak is not a worthwhile trade.

```kotlin
// Cancellation-safe: the loop ends at the next delay.
while (process.isAlive) delay(10)
```

Reconsider only if a measured cost shows up and every awaited process is
guaranteed to be destroyed on cancellation and timeout paths.

## Prior requests

- #144: "Replace CodexModelBackend.awaitExit polling with Process.onExit()"
