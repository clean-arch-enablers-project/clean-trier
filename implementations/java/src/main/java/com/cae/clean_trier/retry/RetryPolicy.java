package com.cae.clean_trier.retry;

import java.io.Serializable;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Immutable exponential-backoff configuration, containing no execution counters.
 * @param maxAmountOfRetries the maximum additional attempts
 * @param baseTime the delay before the first retry
 * @param timeUnit the delay unit
 */
public record RetryPolicy(int maxAmountOfRetries, long baseTime, TimeUnit timeUnit) implements Serializable {
    /**
     * Validates a retry configuration.
     * @param maxAmountOfRetries the nonnegative retry limit
     * @param baseTime the nonnegative initial delay
     * @param timeUnit the non-null delay unit
     */
    public RetryPolicy {
        if (maxAmountOfRetries < 0) {
            throw new IllegalArgumentException("Retry limit must be nonnegative");
        }
        if (baseTime < 0) {
            throw new IllegalArgumentException("Base delay must be nonnegative");
        }
        Objects.requireNonNull(timeUnit, "timeUnit");
    }

    /**
     * Creates an immutable retry policy.
     * @param maxAmountOfRetries the nonnegative retry limit
     * @param baseTime the nonnegative initial delay
     * @param timeUnit the non-null delay unit
     * @return the retry policy
     */
    public static RetryPolicy of(int maxAmountOfRetries, long baseTime, TimeUnit timeUnit) {
        return new RetryPolicy(maxAmountOfRetries, baseTime, timeUnit);
    }

    /**
     * Calculates exponential backoff, saturating at the largest long value on overflow.
     * @param retryNumber the one-based retry number within this policy
     * @return the delay in this policy's time unit
     */
    public long delayForRetry(int retryNumber) {
        if (retryNumber < 1) {
            throw new IllegalArgumentException("Retry number must be positive");
        }
        if (baseTime == 0) {
            return 0;
        }
        if (retryNumber > 63 || baseTime > (Long.MAX_VALUE >> (retryNumber - 1))) {
            return Long.MAX_VALUE;
        }
        return baseTime << (retryNumber - 1);
    }
}
