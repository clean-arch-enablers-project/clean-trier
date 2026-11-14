package com.cae.clean_trier.retry;

import java.util.Objects;

/**
 * A permitted retry within one invocation.
 * @param cause the triggering failure
 * @param totalOfRetriesAtThisPoint the invocation's one-based retry count
 */
public record RetryNotification(Exception cause, int totalOfRetriesAtThisPoint) {
    /**
     * Validates the notification.
     * @param cause the non-null triggering failure
     * @param totalOfRetriesAtThisPoint a positive retry count
     */
    public RetryNotification {
        Objects.requireNonNull(cause, "cause");
        if (totalOfRetriesAtThisPoint < 1) {
            throw new IllegalArgumentException("Retry count must be positive");
        }
    }

    /**
     * Returns the triggering failure.
     * @return the triggering failure
     */
    public Exception getCause() {
        return cause;
    }

    /**
     * Returns the retry count.
     * @return the invocation's retry count
     */
    public int getTotalOfRetriesAtThisPoint() {
        return totalOfRetriesAtThisPoint;
    }
}
