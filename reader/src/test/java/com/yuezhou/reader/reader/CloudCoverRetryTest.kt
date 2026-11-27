package com.yuezhou.reader.reader

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CloudCoverRetryTest {
    @Test fun legacyFailureCanRecoverWithoutClearingUserData() {
        assertTrue(shouldRetryCoverFailure(0L, 100L, 200L))
    }

    @Test fun recentFailureIsDebouncedUntilExpiry() {
        assertFalse(shouldRetryCoverFailure(100_000L, 90_000L, 159_999L))
        assertTrue(shouldRetryCoverFailure(100_000L, 90_000L, 160_000L))
    }

    @Test fun successfulCatalogRefreshAllowsImmediateRetry() {
        assertTrue(shouldRetryCoverFailure(100_000L, 100_001L, 100_002L))
        assertFalse(shouldRetryCoverFailure(100_000L, 100_000L, 100_002L))
    }

    @Test fun clockRollbackDoesNotLeavePermanentFailure() {
        assertTrue(shouldRetryCoverFailure(100_000L, 90_000L, 80_000L))
    }
}
