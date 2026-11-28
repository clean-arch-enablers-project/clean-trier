package com.cae.clean_trier;

import com.cae.clean_mapped_exceptions.MappedException;
import com.cae.clean_mapped_exceptions.specifics.InternalMappedException;
import com.cae.clean_trier.retry.NoRetriesLeftException;
import com.cae.clean_trier.retry.OnExhaustion;
import com.cae.clean_trier.retry.RetryNotification;
import com.cae.clean_trier.retry.RetryPolicy;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Immutable action configuration with independent retry counters for each invocation.
 * JVM errors propagate without retry, fallback or mapping. Callbacks run synchronously.
 * @param <I> the action's input type
 * @param <O> the action's result type
 */
@SuppressWarnings("overloads")
public final class Trier<I, O> {
    private final TrierBuilder<I, O> configuration;
    private final UnexpectedExceptionHandler unexpectedExceptionHandler;

    private Trier(TrierBuilder<I, O> builder, UnexpectedExceptionHandler unexpectedExceptionHandler) {
        this.unexpectedExceptionHandler = Objects.requireNonNull(unexpectedExceptionHandler, "handler");
        configuration = new TrierBuilder<>(builder);
    }

    /**
     * Configures a function.
     * @param function the non-null action
     * @param input the input, which may be null
     * @param <I> the input type
     * @param <O> the result type
     * @return a mutable builder
     */
    public static <I, O> TrierBuilder<I, O> of(Function<I, O> function, I input) {
        return new TrierBuilder<>(Objects.requireNonNull(function, "function"), input);
    }

    /**
     * Configures a consumer.
     * @param consumer the non-null action
     * @param input the input, which may be null
     * @param <I> the input type
     * @return a mutable builder
     */
    public static <I> TrierBuilder<I, Void> of(Consumer<I> consumer, I input) {
        Objects.requireNonNull(consumer, "consumer");
        return new TrierBuilder<>(value -> {
            consumer.accept(value);
            return null;
        }, input);
    }

    /**
     * Configures a supplier.
     * @param supplier the non-null action
     * @param <O> the result type
     * @return a mutable builder
     */
    public static <O> TrierBuilder<Void, O> of(Supplier<O> supplier) {
        Objects.requireNonNull(supplier, "supplier");
        return new TrierBuilder<>(ignored -> supplier.get(), null);
    }

    /**
     * Configures a runnable.
     * @param runnable the non-null action
     * @return a mutable builder
     */
    public static TrierBuilder<Void, Void> of(Runnable runnable) {
        Objects.requireNonNull(runnable, "runnable");
        return new TrierBuilder<>(ignored -> {
            runnable.run();
            return null;
        }, null);
    }

    /**
     * Executes with a fresh retry budget.
     * @return the action or fallback result; null for void actions
     */
    public O execute() {
        var retries = new HashMap<Class<? extends Exception>, Integer>();
        int totalRetries = 0;
        while (true) {
            Exception failure;
            try {
                return configuration.action.apply(configuration.input);
            } catch (Exception problem) {
                failure = problem;
            }
            var policyType = matchingPolicy(failure);
            if (policyType == null) {
                return finishFailure(failure, null);
            }
            var policy = configuration.retryPolicies.get(policyType);
            int policyRetries = retries.getOrDefault(policyType, 0);
            if (policyRetries >= policy.maxAmountOfRetries()) {
                return finishFailure(failure, new OnExhaustion.FailureStatus(failure, totalRetries, policy));
            }
            int retryNumber = policyRetries + 1;
            retries.put(policyType, retryNumber);
            totalRetries++;
            waitForRetry(failure, totalRetries, retryNumber, policy);
        }
    }

    private Class<? extends Exception> matchingPolicy(Exception failure) {
        Class<? extends Exception> match = null;
        for (var type : configuration.retryPolicies.keySet()) {
            if (type.isInstance(failure) && (match == null || match.isAssignableFrom(type))) {
                match = type;
            }
        }
        return match;
    }

    private void waitForRetry(Exception failure, int totalRetries, int retryNumber, RetryPolicy policy) {
        RetryNotifier.SINGLETON.notifySubscribers(new RetryNotification(failure, totalRetries));
        try {
            policy.timeUnit().sleep(policy.delayForRetry(retryNumber));
        } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
            var mapped = new InternalMappedException("Retry interrupted", interruption);
            mapped.addSuppressed(failure);
            throw mapped;
        }
    }

    private O finishFailure(Exception failure, OnExhaustion.FailureStatus exhaustion) {
        if (configuration.fallback != null) {
            try {
                return configuration.fallback.apply(configuration.input);
            } catch (Exception fallbackFailure) {
                throw new FallbackFailureMappedException(fallbackFailure, failure);
            }
        }
        if (exhaustion != null) {
            throw preserveFailure(configuration.onExhaustion.handle(exhaustion), failure);
        }
        if (failure instanceof MappedException mapped) {
            throw mapped;
        }
        throw preserveFailure(unexpectedExceptionHandler.handle(failure), failure);
    }

    private static MappedException preserveFailure(MappedException mapped, Exception original) {
        Objects.requireNonNull(mapped, "Exception handlers must return a mapped exception");
        if (mapped != original && mapped.getCause() != original) {
            mapped.addSuppressed(original);
        }
        return mapped;
    }

    /**
     * Mutable configuration builder; built Trier instances retain independent snapshots.
     * @param <I> the input type
     * @param <O> the result type
     */
    public static final class TrierBuilder<I, O> {
        private final Function<I, O> action;
        private final I input;
        private final Map<Class<? extends Exception>, RetryPolicy> retryPolicies;
        private Function<I, O> fallback;
        private OnExhaustion onExhaustion =
                status -> new NoRetriesLeftException("No retries left", status);

        private TrierBuilder(Function<I, O> action, I input) {
            this.action = action;
            this.input = input;
            retryPolicies = new LinkedHashMap<>();
        }

        private TrierBuilder(TrierBuilder<I, O> builder) {
            action = builder.action;
            input = builder.input;
            retryPolicies = new LinkedHashMap<>(builder.retryPolicies);
            fallback = builder.fallback;
            onExhaustion = builder.onExhaustion;
        }

        /**
         * Adds or replaces an exponential-backoff policy for an exception type.
         * The most specific matching superclass wins.
         * @param exceptionClass the non-null failure type
         * @param maxRetries the nonnegative additional-attempt limit
         * @param baseTime the nonnegative initial delay
         * @param timeUnit the non-null delay unit
         * @return this builder
         */
        public TrierBuilder<I, O> retryOn(Class<? extends Exception> exceptionClass,
                                          int maxRetries, long baseTime, TimeUnit timeUnit) {
            return retryOn(exceptionClass, RetryPolicy.of(maxRetries, baseTime, timeUnit));
        }

        /**
         * Adds or replaces an immutable retry policy.
         * @param exceptionClass the non-null failure type
         * @param policy the non-null retry configuration
         * @return this builder
         */
        public TrierBuilder<I, O> retryOn(Class<? extends Exception> exceptionClass, RetryPolicy policy) {
            retryPolicies.put(Objects.requireNonNull(exceptionClass, "exceptionClass"),
                    Objects.requireNonNull(policy, "policy"));
            return this;
        }

        /**
         * Sets a function fallback receiving the original input.
         * @param recovery the non-null recovery action
         * @return this builder
         */
        public TrierBuilder<I, O> fallback(Function<I, O> recovery) {
            fallback = Objects.requireNonNull(recovery, "recovery");
            return this;
        }

        /**
         * Sets a handler that returns the mapped exhaustion failure.
         * @param handler the non-null exhaustion mapper
         * @return this builder
         */
        public TrierBuilder<I, O> onExhaustion(OnExhaustion handler) {
            onExhaustion = Objects.requireNonNull(handler, "handler");
            return this;
        }

        /**
         * Builds a configuration snapshot with the required unexpected-failure mapper.
         * @param handler the non-null failure mapper
         * @return an executable Trier
         */
        public Trier<I, O> onUnexpectedExceptions(UnexpectedExceptionHandler handler) {
            return new Trier<>(this, handler);
        }
    }
}
