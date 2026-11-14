package com.cae.clean_trier.retry;

import com.cae.clean_mapped_exceptions.MappedException;

import java.io.Serializable;
import java.util.Objects;

/** Produces a mapped failure after a matching policy has exhausted its retries. */
@FunctionalInterface
public interface OnExhaustion {
    /**
     * Interprets retry exhaustion; it does not supply a recovery result.
     * @param failureStatus the last failure and retry context
     * @return a non-null mapped failure
     */
    MappedException handle(FailureStatus failureStatus);

    /**
     * The terminal failure and exhausted retry configuration.
     * @param exception the last action failure
     * @param totalOfRetries the invocation's total additional attempts
     * @param retryPolicy the exhausted policy
     */
    record FailureStatus(Exception exception, int totalOfRetries, RetryPolicy retryPolicy) implements Serializable {
        /**
         * Validates the failure context.
         * @param exception the non-null terminal failure
         * @param totalOfRetries the nonnegative total retry count
         * @param retryPolicy the non-null exhausted policy
         */
        public FailureStatus {
            Objects.requireNonNull(exception, "exception");
            Objects.requireNonNull(retryPolicy, "retryPolicy");
            if (totalOfRetries < 0) {
                throw new IllegalArgumentException("Retry count must be nonnegative");
            }
        }

        /**
         * Returns the last action failure.
         * @return the terminal failure
         */
        public Exception getException() {
            return exception;
        }

        /**
         * Returns the invocation's total retry count.
         * @return the additional attempts performed
         */
        public int getTotalOfRetries() {
            return totalOfRetries;
        }
    }
}
