package com.cae.clean_trier.retry;

/** Receives synchronous notifications immediately before a retry delay begins. */
@FunctionalInterface
public interface RetrySubscriber {
    /**
     * Observes a permitted retry; this does not indicate success.
     * @param retryNotification the triggering failure and invocation's retry count
     */
    void receiveRetryNotification(RetryNotification retryNotification);
}
