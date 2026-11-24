package com.nuvio.tv.core.tmdb

import org.junit.Assert.assertEquals
import org.junit.Test

class PublicPersonPolicyTest {
    @Test fun respectsRetryAfterSeconds() {
        assertEquals(123_000L, publicPersonRetryDelayMillis("123", 0))
    }

    @Test fun respectsRetryAfterHttpDate() {
        val now = 1_735_689_600_000L // 2025-01-01 00:00 UTC
        assertEquals(90_000L, publicPersonRetryDelayMillis("Wed, 01 Jan 2025 00:01:30 GMT", now))
    }

    @Test fun missingOrInvalidRetryHeaderBacksOffOneMinute() {
        assertEquals(60_000L, publicPersonRetryDelayMillis(null, 0))
        assertEquals(60_000L, publicPersonRetryDelayMillis("not a date", 0))
    }

    @Test fun expiredRetryDateDoesNotCreateBusyLoop() {
        assertEquals(1000L, publicPersonRetryDelayMillis("Wed, 01 Jan 2025 00:00:00 GMT", 1_735_689_600_001L))
    }
}
