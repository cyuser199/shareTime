package cn.sharetime.app

import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundPolicyTest {
    @Test fun retryBackoffIsBoundedEvenAfterLongOutages() {
        listOf(-1, 0, 1, 8, 100, Int.MAX_VALUE).forEach { attempt ->
            repeat(100) {
                val delay = BackgroundPolicy.retryDelay(attempt)
                assertTrue(delay in 750L..300_000L)
                if (attempt >= 9) assertTrue(delay >= 225_000L)
            }
        }
    }
}
