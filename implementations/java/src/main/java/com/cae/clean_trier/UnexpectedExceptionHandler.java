package com.cae.clean_trier;

import com.cae.clean_mapped_exceptions.MappedException;

/** Maps an unmapped action failure to a workflow failure. */
@FunctionalInterface
public interface UnexpectedExceptionHandler {
    /**
     * Interprets an action failure.
     * @param unexpectedException the original failure
     * @return a non-null mapped failure, ideally retaining the original as its cause
     */
    MappedException handle(Exception unexpectedException);
}
