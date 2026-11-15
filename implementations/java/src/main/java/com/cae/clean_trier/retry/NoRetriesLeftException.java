package com.cae.clean_trier.retry;

import com.cae.clean_mapped_exceptions.specifics.InternalMappedException;

import java.util.Objects;

/** Default mapped outcome when a matching policy permits no further retries. */
public class NoRetriesLeftException extends InternalMappedException {
    /**
     * The exhausted execution's diagnostic context.
     * @serial the terminal failure and retry configuration
     */
    private final OnExhaustion.FailureStatus failureStatus;

    /**
     * Creates a retry-exhaustion failure and retains the final action failure as its cause.
     * @param briefPublicMessage the public description
     * @param failureStatus the non-null terminal retry context
     */
    public NoRetriesLeftException(String briefPublicMessage, OnExhaustion.FailureStatus failureStatus) {
        super(briefPublicMessage,
                "All retries exhausted after " + Objects.requireNonNull(failureStatus, "failureStatus").totalOfRetries()
                        + " additional attempt(s)",
                failureStatus.exception());
        this.failureStatus = failureStatus;
    }

    /**
     * Returns the exhausted execution's context.
     * @return the terminal retry context
     */
    public OnExhaustion.FailureStatus getFailureStatus() {
        return failureStatus;
    }
}
