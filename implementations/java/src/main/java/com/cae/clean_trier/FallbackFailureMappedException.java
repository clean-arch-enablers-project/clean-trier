package com.cae.clean_trier;

import com.cae.clean_mapped_exceptions.specifics.InternalMappedException;

import java.util.Objects;

/** A fallback failure retaining both the fallback cause and the triggering action failure. */
public final class FallbackFailureMappedException extends InternalMappedException {
    /**
     * The original action's failure.
     * @serial the failure that caused fallback execution
     */
    private final Exception triggeringException;

    /**
     * Creates a mapped fallback failure. The fallback failure is the Java cause, and
     * the triggering failure is also retained as a suppressed exception.
     * @param fallbackException the non-null fallback failure
     * @param triggeringException the non-null action failure
     */
    public FallbackFailureMappedException(Exception fallbackException, Exception triggeringException) {
        super("Fallback action failed", "The original action and its fallback both failed",
                Objects.requireNonNull(fallbackException, "fallbackException"));
        this.triggeringException = Objects.requireNonNull(triggeringException, "triggeringException");
        addSuppressed(triggeringException);
    }

    /**
     * Returns the failure that triggered fallback execution.
     * @return the original action failure
     */
    public Exception getTriggeringException() {
        return triggeringException;
    }
}
