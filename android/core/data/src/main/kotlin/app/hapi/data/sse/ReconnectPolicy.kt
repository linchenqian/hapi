package app.hapi.data.sse

import kotlin.random.Random

/**
 * SSE reconnect constants and backoff schedule based on
 * `docs/api/client-contract/sse.md` ("Reconnect policy") / the web reference
 * client (`web/src/hooks/useSSE.ts`). Android keeps the foreground cap at
 * 30 s even after repeated failures: mobile connectivity may recover without
 * a route-identity change. Background retries are suspended by SseEngine.
 * Pure — all randomness is injected so tests can pin the exact schedule.
 */
object ReconnectPolicy {
    /** An attempt that has not completed the handshake in 10 s is hung — abandon it. */
    const val CONNECT_TIMEOUT_MS: Long = 10_000

    /** No frames of any kind (heartbeat included) for 90 s ⇒ tear down and reconnect. */
    const val STALE_MS: Long = 90_000

    /**
     * On app-foreground, a connection whose last frame is older than 45 s is
     * reconnected immediately: an OS suspend can kill the socket without any
     * error surfacing, and one missed 30 s heartbeat already means distrust.
     */
    const val RESUME_STALE_MS: Long = 45_000

    /** Staleness check interval. Checks are skipped while backgrounded. */
    const val WATCHDOG_TICK_MS: Long = 10_000

    const val BASE_DELAY_MS: Long = 1_000
    const val MAX_DELAY_MS: Long = 30_000
    /** Uniform 0..[JITTER_MS] (inclusive) added to every delay. */
    const val JITTER_MS: Int = 500

    /**
     * Delay before retry number `attempt` (0-based count of consecutive
     * failures so far, reset to 0 on every successful handshake):
     *
     * - attempt 0 → 0 ms exponential part (first retry is immediate, jitter only);
     * - attempt n ≥ 1 → `min(30000, 1000 · 2^(n-1))`;
     * - plus uniform jitter 0..500 ms on every delay.
     *
     * Schedule (jitter aside): 0, 1s, 2s, 4s, 8s, 16s, 30s, 30s, …
     */
    fun delayForAttempt(attempt: Int, random: Random): Long {
        require(attempt >= 0) { "attempt must be >= 0, got $attempt" }
        val cap = MAX_DELAY_MS
        val exponential = when {
            attempt == 0 -> 0L
            // 2^(n-1) would overflow / always exceed the cap long before n=63.
            attempt - 1 >= 20 -> cap
            else -> minOf(cap, BASE_DELAY_MS shl (attempt - 1))
        }
        return exponential + random.nextInt(JITTER_MS + 1)
    }
}
