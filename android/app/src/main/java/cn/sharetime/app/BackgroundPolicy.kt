package cn.sharetime.app

import kotlin.random.Random

/** Bounded backoff with jitter avoids synchronized reconnects during outages. */
object BackgroundPolicy {
    fun retryDelay(attempt: Int): Long {
        val base = (1000L shl attempt.coerceIn(0, 9)).coerceAtMost(300_000L)
        return Random.nextLong(base * 3 / 4, base + 1)
    }
}
