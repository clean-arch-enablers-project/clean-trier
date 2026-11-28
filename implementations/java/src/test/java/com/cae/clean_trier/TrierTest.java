package com.cae.clean_trier;

import com.cae.clean_mapped_exceptions.MappedException;
import com.cae.clean_mapped_exceptions.specifics.InputMappedException;
import com.cae.clean_mapped_exceptions.specifics.InternalMappedException;
import com.cae.clean_trier.retry.NoRetriesLeftException;
import com.cae.clean_trier.retry.OnExhaustion;
import com.cae.clean_trier.retry.RetryNotification;
import com.cae.clean_trier.retry.RetryPolicy;
import com.cae.clean_trier.retry.RetrySubscriber;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

@Isolated
class TrierTest {
    private static final UnexpectedExceptionHandler MAP_FAILURE =
            cause -> new InternalMappedException("Test action failed", cause);

    private final List<RetrySubscriber> subscriptions = new ArrayList<>();

    @AfterEach
    void removeRetrySubscriptions() {
        subscriptions.forEach(RetryNotifier.SINGLETON::unsubscribe);
    }

    @Test
    void executesAllFourActionShapesAndRetainsNullResults() {
        var received = new ArrayList<String>();
        var ran = new AtomicInteger();
        var function = Trier.of((Function<String, Integer>) String::length, "hello").onUnexpectedExceptions(MAP_FAILURE);
        var consumer = Trier.of((Consumer<String>) received::add, "input").onUnexpectedExceptions(MAP_FAILURE);
        var supplier = Trier.of((Supplier<String>) () -> "result").onUnexpectedExceptions(MAP_FAILURE);
        var runnable = Trier.of((Runnable) ran::incrementAndGet).onUnexpectedExceptions(MAP_FAILURE);
        assertAll(
                () -> assertEquals(5, function.execute()),
                () -> assertNull(consumer.execute()),
                () -> assertEquals(List.of("input"), received),
                () -> assertEquals("result", supplier.execute()),
                () -> assertNull(runnable.execute()),
                () -> assertEquals(1, ran.get()),
                () -> assertNull(Trier.of((Supplier<String>) () -> null).onUnexpectedExceptions(MAP_FAILURE).execute()),
                () -> assertNull(Trier.of((Function<String, String>) value -> value, null)
                        .onUnexpectedExceptions(MAP_FAILURE).execute()));
    }

    @Test
    void mapsUnexpectedFailuresWithTheExplicitHandler() {
        var cause = new IllegalArgumentException("Technical");
        var trier = failing(cause).onUnexpectedExceptions(MAP_FAILURE);
        var failure = assertThrows(InternalMappedException.class, trier::execute);
        assertAll(
                () -> assertSame(cause, failure.getCause()),
                () -> assertEquals("Test action failed", failure.getBriefPublicMessage()),
                () -> assertEquals(0, failure.getSuppressed().length));
    }

    @Test
    void propagatesMappedFailureUnchangedWithoutCallingMapper() {
        var cause = new InputMappedException("Invalid input");
        var trier = failing(cause).onUnexpectedExceptions(problem -> {
            fail("Mapped failures must not be mapped again");
            return null;
        });
        assertSame(cause, assertThrows(InputMappedException.class, trier::execute));
    }

    @Test
    void customMapperKeepsItsClassificationAndOriginalAsCause() {
        var original = new IllegalStateException("Technical");
        var mapped = new InputMappedException("Workflow input", original);
        var trier = failing(original).onUnexpectedExceptions(problem -> {
            assertSame(original, problem);
            return mapped;
        });
        var actual = assertThrows(InputMappedException.class, trier::execute);
        assertAll(
                () -> assertSame(mapped, actual),
                () -> assertSame(original, actual.getCause()),
                () -> assertEquals(0, actual.getSuppressed().length));
    }

    @Test
    void preservesOriginalWhenCustomMapperOmitsIt() {
        var original = new IllegalStateException("Technical");
        var mapped = new InputMappedException("Workflow input");
        var trier = failing(original).onUnexpectedExceptions(problem -> mapped);
        var actual = assertThrows(InputMappedException.class, trier::execute);
        assertAll(
                () -> assertSame(mapped, actual),
                () -> assertEquals(List.of(original), List.of(actual.getSuppressed())));
    }

    @Test
    void customExhaustionCanPropagateSameMappedFailure() {
        var original = new InputMappedException("Unavailable");
        var trier = failing(original).retryOn(InputMappedException.class, 0, 0, TimeUnit.SECONDS)
                .onExhaustion(status -> original).onUnexpectedExceptions(MAP_FAILURE);
        assertSame(original, assertThrows(InputMappedException.class, trier::execute));
        assertEquals(0, original.getSuppressed().length);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 3, 10000})
    void retriesIterativelyWithFreshBudgetOnEveryInvocation(int maxRetries) {
        var attempts = new AtomicInteger();
        var original = new IllegalStateException("Dependency");
        var trier = Trier.of((Runnable) () -> {
            attempts.incrementAndGet();
            throw original;
        }).retryOn(IllegalStateException.class, maxRetries, 0, TimeUnit.SECONDS).onUnexpectedExceptions(MAP_FAILURE);
        for (int invocation = 1; invocation <= 2; invocation++) {
            var failure = assertThrows(NoRetriesLeftException.class, trier::execute);
            assertAll(
                    () -> assertEquals(maxRetries, failure.getFailureStatus().getTotalOfRetries()),
                    () -> assertSame(original, failure.getCause()),
                    () -> assertEquals(maxRetries, failure.getFailureStatus().retryPolicy().maxAmountOfRetries()));
            assertEquals(invocation * (maxRetries + 1), attempts.get());
        }
    }

    @Test
    void retriesMappedFailuresAndReturnsSuccessfulResult() {
        var attempts = new AtomicInteger();
        var notifications = new ArrayList<RetryNotification>();
        subscribe(notifications::add);
        var original = new InputMappedException("Transient input");
        var trier = Trier.of((Supplier<String>) () -> {
            if (attempts.incrementAndGet() < 3) {
                throw original;
            }
            return "ready";
        }).retryOn(InputMappedException.class, 3, 0, TimeUnit.SECONDS)
                .onUnexpectedExceptions(MAP_FAILURE);
        assertAll(
                () -> assertEquals("ready", trier.execute()),
                () -> assertEquals(3, attempts.get()),
                () -> assertEquals(List.of(1, 2), notifications.stream()
                        .map(RetryNotification::getTotalOfRetriesAtThisPoint).toList()),
                () -> assertTrue(notifications.stream().allMatch(note -> note.getCause() == original)));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void mostSpecificPolicyWinsIndependentlyOfRegistrationOrder(boolean broadFirst) {
        var notifications = new ArrayList<RetryNotification>();
        subscribe(notifications::add);
        var builder = failing(new IllegalArgumentException("Failure"));
        if (broadFirst) {
            builder.retryOn(RuntimeException.class, 0, 0, TimeUnit.SECONDS)
                    .retryOn(IllegalArgumentException.class, 2, 0, TimeUnit.SECONDS);
        } else {
            builder.retryOn(IllegalArgumentException.class, 2, 0, TimeUnit.SECONDS)
                    .retryOn(RuntimeException.class, 0, 0, TimeUnit.SECONDS);
        }
        var trier = builder.retryOn(IllegalStateException.class, 5, 0, TimeUnit.SECONDS)
                .onUnexpectedExceptions(MAP_FAILURE);
        var failure = assertThrows(NoRetriesLeftException.class, trier::execute);
        assertAll(
                () -> assertEquals(2, failure.getFailureStatus().totalOfRetries()),
                () -> assertEquals(2, notifications.size()));
    }

    @Test
    void nonmatchingPolicyDoesNotReportExhaustion() {
        var original = new IllegalStateException("Dependency");
        var trier = failing(original).retryOn(IllegalArgumentException.class, 3, 0, TimeUnit.SECONDS)
                .onExhaustion(status -> {
                    fail("No applicable policy");
                    return null;
                }).onUnexpectedExceptions(MAP_FAILURE);
        var failure = assertThrows(InternalMappedException.class, trier::execute);
        assertAll(
                () -> assertFalse(failure instanceof NoRetriesLeftException),
                () -> assertSame(original, failure.getCause()));
    }

    @Test
    void alternatingFailureTypesHaveIndependentPolicyBudgetsAndGlobalNotifications() {
        var attempts = new AtomicInteger();
        var observed = new ArrayList<RetryNotification>();
        subscribe(observed::add);
        var lastFailure = new IllegalStateException("State");
        var trier = Trier.of((Runnable) () -> {
            if (attempts.incrementAndGet() % 2 == 1) {
                throw lastFailure;
            }
            throw new IllegalArgumentException("Argument");
        }).retryOn(IllegalStateException.class, 1, 0, TimeUnit.SECONDS)
                .retryOn(IllegalArgumentException.class, 1, 0, TimeUnit.SECONDS)
                .onUnexpectedExceptions(MAP_FAILURE);
        var failure = assertThrows(NoRetriesLeftException.class, trier::execute);
        assertAll(
                () -> assertEquals(3, attempts.get()),
                () -> assertEquals(2, failure.getFailureStatus().totalOfRetries()),
                () -> assertSame(lastFailure, failure.getCause()),
                () -> assertEquals(List.of(1, 2),
                        observed.stream().map(RetryNotification::getTotalOfRetriesAtThisPoint).toList()));
    }

    @Test
    void snapshotsPoliciesAndExhaustionHandlersWhenBuilt() {
        var attempts = new AtomicInteger();
        var builder = Trier.of((Runnable) () -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("Dependency");
        }).retryOn(IllegalStateException.class, 1, 0, TimeUnit.SECONDS);
        var first = builder.onUnexpectedExceptions(MAP_FAILURE);
        var replacement = new InputMappedException("Replacement");
        var second = builder.retryOn(IllegalStateException.class, 0, 0, TimeUnit.SECONDS)
                .onExhaustion(status -> replacement)
                .onUnexpectedExceptions(problem -> replacement);
        assertThrows(NoRetriesLeftException.class, first::execute);
        assertSame(replacement, assertThrows(InputMappedException.class, second::execute));
        assertEquals(3, attempts.get());
    }

    @Test
    void reusingABuilderKeepsEachTriersExplicitHandler() {
        var original = new IllegalStateException("Original");
        var builder = failing(original);
        var firstFailure = new InternalMappedException("First operation failed", original);
        var secondFailure = new InternalMappedException("Second operation failed", original);
        var first = builder.onUnexpectedExceptions(cause -> firstFailure);
        var second = builder.onUnexpectedExceptions(cause -> secondFailure);

        assertSame(firstFailure, assertThrows(InternalMappedException.class, first::execute));
        assertSame(secondFailure, assertThrows(InternalMappedException.class, second::execute));
    }

    @Test
    void concurrentInvocationsDoNotConsumeEachOthersBudgets() throws Exception {
        var attempts = new AtomicInteger();
        var trier = Trier.of((Runnable) () -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("Transient");
        }).retryOn(RuntimeException.class, 2, 0, TimeUnit.SECONDS).onUnexpectedExceptions(MAP_FAILURE);
        try (var executor = Executors.newFixedThreadPool(4)) {
            var tasks = new ArrayList<Callable<Integer>>();
            for (int index = 0; index < 24; index++) {
                tasks.add(() -> assertThrows(NoRetriesLeftException.class, trier::execute)
                        .getFailureStatus().totalOfRetries());
            }
            for (var result : executor.invokeAll(tasks)) {
                assertEquals(2, result.get());
            }
        }
        assertEquals(72, attempts.get());
    }

    @Test
    void fallbackReceivesOriginalInputBeforeUnexpectedMapping() {
        var attempts = new AtomicInteger();
        var trier = Trier.of((Function<String, String>) value -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("Remote unavailable");
        }, "customer").fallback(value -> value + "-cached")
                .onUnexpectedExceptions(problem -> {
                    fail("Fallback succeeded");
                    return null;
                });
        assertAll(
                () -> assertEquals("customer-cached", trier.execute()),
                () -> assertEquals(1, attempts.get()));
    }

    @Test
    void fallbackRunsAfterExhaustionAndBeforeExhaustionHandler() {
        var attempts = new AtomicInteger();
        var trier = Trier.of((Supplier<String>) () -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("Dependency");
        }).retryOn(RuntimeException.class, 2, 0, TimeUnit.SECONDS)
                .fallback(ignored -> "cached").onExhaustion(status -> {
                    fail("Fallback succeeded");
                    return null;
                }).onUnexpectedExceptions(MAP_FAILURE);
        assertAll(
                () -> assertEquals("cached", trier.execute()),
                () -> assertEquals(3, attempts.get()));
    }

    @Test
    void fallbackRecoversMappedFailuresAndCanReturnNull() {
        var trier = failing(new InputMappedException("Missing"))
                .fallback(ignored -> null).onUnexpectedExceptions(MAP_FAILURE);
        assertNull(trier.execute());
    }

    @Test
    void successfulActionDoesNotExecuteFallbackOrObservers() {
        subscribe(note -> fail("Action succeeded"));
        var trier = Trier.of((Supplier<String>) () -> "original")
                .fallback(ignored -> fail("Action succeeded"))
                .onUnexpectedExceptions(MAP_FAILURE);
        assertEquals("original", trier.execute());
    }

    @Test
    void fallbackFailuresRetainBothFailuresAndAreNotRetried() {
        var original = new IllegalStateException("Original");
        var fallback = new InputMappedException("Fallback");
        var attempts = new AtomicInteger();
        var trier = failing(original).retryOn(InputMappedException.class, 3, 0, TimeUnit.SECONDS)
                .fallback(ignored -> {
                    attempts.incrementAndGet();
                    throw fallback;
                }).onUnexpectedExceptions(MAP_FAILURE);
        var failure = assertThrows(FallbackFailureMappedException.class, trier::execute);
        assertAll(
                () -> assertSame(fallback, failure.getCause()),
                () -> assertSame(original, failure.getTriggeringException()),
                () -> assertEquals(List.of(original), List.of(failure.getSuppressed())),
                () -> assertEquals(1, attempts.get()));
    }

    @Test
    void fallbackCanRethrowOriginalWithoutLosingIt() {
        var original = new IllegalStateException("Original");
        var trier = failing(original).fallback(ignored -> { throw original; }).onUnexpectedExceptions(MAP_FAILURE);
        var failure = assertThrows(FallbackFailureMappedException.class, trier::execute);
        assertAll(
                () -> assertSame(original, failure.getCause()),
                () -> assertSame(original, failure.getTriggeringException()));
    }

    @Test
    void interruptionRestoresFlagAndStopsRecovery() {
        var original = new IllegalStateException("Original");
        var trier = failing(original).retryOn(IllegalStateException.class, 1, 1, TimeUnit.DAYS)
                .fallback(ignored -> fail("Interruption must stop execution")).onUnexpectedExceptions(MAP_FAILURE);
        try {
            Thread.currentThread().interrupt();
            var failure = assertThrows(InternalMappedException.class, trier::execute);
            assertAll(
                    () -> assertTrue(Thread.currentThread().isInterrupted()),
                    () -> assertInstanceOf(InterruptedException.class, failure.getCause()),
                    () -> assertEquals(List.of(original), List.of(failure.getSuppressed())));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void jvmErrorsPropagateWithoutRecovery() {
        var fatal = new AssertionError("Fatal");
        var action = Trier.of((Runnable) () -> { throw fatal; })
                .retryOn(Exception.class, 3, 0, TimeUnit.SECONDS)
                .fallback(ignored -> fail("Errors must propagate")).onUnexpectedExceptions(MAP_FAILURE);
        assertSame(fatal, assertThrows(AssertionError.class, action::execute));
        var fallback = failing(new IllegalStateException("Original"))
                .fallback(ignored -> { throw fatal; }).onUnexpectedExceptions(MAP_FAILURE);
        assertSame(fatal, assertThrows(AssertionError.class, fallback::execute));
    }

    @Test
    void subscriberFailuresPropagateWithoutRetryingOrMapping() {
        var callbackFailure = new IllegalArgumentException("Subscriber");
        subscribe(note -> { throw callbackFailure; });
        var attempts = new AtomicInteger();
        var trier = Trier.of((Runnable) () -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("Original");
        }).retryOn(Exception.class, 2, 0, TimeUnit.SECONDS)
                .onUnexpectedExceptions(MAP_FAILURE);
        assertSame(callbackFailure, assertThrows(IllegalArgumentException.class, trier::execute));
        assertEquals(1, attempts.get());
    }

    @Test
    void notifiesEverySubscriberInRegistrationOrder() {
        var observed = new ArrayList<String>();
        subscribe(note -> observed.add("first"));
        subscribe(note -> observed.add("second"));
        var trier = failing(new IllegalStateException("Original"))
                .retryOn(Exception.class, 1, 0, TimeUnit.SECONDS)
                .onUnexpectedExceptions(MAP_FAILURE);
        assertThrows(NoRetriesLeftException.class, trier::execute);
        assertEquals(List.of("first", "second"), observed);
    }

    @Test
    void globalSubscriptionsApplyToExistingTriersUntilRemoved() {
        var firstFailure = new IllegalStateException("First");
        var secondFailure = new IllegalStateException("Second");
        var first = failing(firstFailure).retryOn(Exception.class, 1, 0, TimeUnit.SECONDS)
                .onUnexpectedExceptions(MAP_FAILURE);
        var second = failing(secondFailure).retryOn(Exception.class, 1, 0, TimeUnit.SECONDS)
                .onUnexpectedExceptions(MAP_FAILURE);

        var observed = new ArrayList<Exception>();
        RetrySubscriber subscriber = notification -> observed.add(notification.cause());
        subscribe(subscriber);
        var notifier = RetryNotifier.SINGLETON;
        assertFalse(notifier.subscribe(subscriber));
        assertThrows(NoRetriesLeftException.class, first::execute);
        assertThrows(NoRetriesLeftException.class, second::execute);

        assertTrue(notifier.unsubscribe(subscriber));
        assertFalse(notifier.unsubscribe(subscriber));
        assertThrows(NoRetriesLeftException.class, first::execute);
        assertEquals(List.of(firstFailure, secondFailure), observed);
    }

    @Test
    void rejectsNullConfiguration() {
        var builder = failing(new IllegalStateException("Original"));
        var policy = RetryPolicy.of(0, 0, TimeUnit.SECONDS);
        assertAll(
                () -> assertThrows(NullPointerException.class, () -> Trier.of((Function<String, String>) null, "input")),
                () -> assertThrows(NullPointerException.class, () -> Trier.of((Consumer<String>) null, "input")),
                () -> assertThrows(NullPointerException.class, () -> Trier.of((Supplier<String>) null)),
                () -> assertThrows(NullPointerException.class, () -> Trier.of((Runnable) null)),
                () -> assertThrows(NullPointerException.class, () -> builder.retryOn(null, policy)),
                () -> assertThrows(NullPointerException.class, () -> builder.retryOn(Exception.class, null)),
                () -> assertThrows(NullPointerException.class, () -> builder.fallback(null)),
                () -> assertThrows(NullPointerException.class, () -> RetryNotifier.SINGLETON.subscribe(null)),
                () -> assertThrows(NullPointerException.class, () -> RetryNotifier.SINGLETON.unsubscribe(null)),
                () -> assertThrows(NullPointerException.class, () -> builder.onExhaustion(null)),
                () -> assertThrows(NullPointerException.class, () -> builder.onUnexpectedExceptions(null)));
    }

    @Test
    void rejectsNullHandlerResultsAndPropagatesHandlerFailures() {
        var cause = new IllegalStateException("Original");
        var nullMapper = failing(cause).onUnexpectedExceptions(problem -> null);
        var nullExhaustion = failing(cause).retryOn(Exception.class, 0, 0, TimeUnit.SECONDS)
                .onExhaustion(status -> null).onUnexpectedExceptions(MAP_FAILURE);
        var callbackFailure = new IllegalArgumentException("Mapper");
        var throwingMapper = failing(cause).onUnexpectedExceptions(problem -> { throw callbackFailure; });
        var throwingExhaustion = failing(cause).retryOn(Exception.class, 0, 0, TimeUnit.SECONDS)
                .onExhaustion(status -> { throw callbackFailure; }).onUnexpectedExceptions(MAP_FAILURE);
        assertAll(
                () -> assertThrows(NullPointerException.class, nullMapper::execute),
                () -> assertThrows(NullPointerException.class, nullExhaustion::execute),
                () -> assertSame(callbackFailure, assertThrows(IllegalArgumentException.class, throwingMapper::execute)),
                () -> assertSame(callbackFailure, assertThrows(IllegalArgumentException.class, throwingExhaustion::execute)));
    }

    @Test
    void exhaustionAndFallbackFailuresPreserveDiagnosticsThroughSerialization() throws Exception {
        var original = new IllegalStateException("Original");
        var status = new OnExhaustion.FailureStatus(original, 2, RetryPolicy.of(2, 0, TimeUnit.SECONDS));
        var exhausted = new NoRetriesLeftException("Exhausted", status);
        var restored = roundTrip(exhausted, NoRetriesLeftException.class);
        assertAll(
                () -> assertEquals(2, restored.getFailureStatus().getTotalOfRetries()),
                () -> assertSame(restored.getCause(), restored.getFailureStatus().getException()),
                () -> assertEquals(status.retryPolicy(), restored.getFailureStatus().retryPolicy()));
        var fallback = new FallbackFailureMappedException(new IllegalArgumentException("Fallback"), original);
        var restoredFallback = roundTrip(fallback, FallbackFailureMappedException.class);
        assertAll(
                () -> assertEquals("Fallback", restoredFallback.getCause().getMessage()),
                () -> assertEquals("Original", restoredFallback.getTriggeringException().getMessage()),
                () -> assertSame(restoredFallback.getTriggeringException(), restoredFallback.getSuppressed()[0]));
    }

    @Test
    void diagnosticExceptionConstructorsRejectNullFailures() {
        var cause = new IllegalStateException("Original");
        assertAll(
                () -> assertThrows(NullPointerException.class, () -> new NoRetriesLeftException("Exhausted", null)),
                () -> assertThrows(NullPointerException.class, () -> new FallbackFailureMappedException(null, cause)),
                () -> assertThrows(NullPointerException.class, () -> new FallbackFailureMappedException(cause, null)));
    }

    private void subscribe(RetrySubscriber subscriber) {
        assertTrue(RetryNotifier.SINGLETON.subscribe(subscriber));
        subscriptions.add(subscriber);
    }

    private static Trier.TrierBuilder<Void, Void> failing(RuntimeException cause) {
        return Trier.of((Runnable) () -> { throw cause; });
    }

    private static <T extends MappedException> T roundTrip(T exception, Class<T> type) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var output = new ObjectOutputStream(bytes)) {
            output.writeObject(exception);
        }
        try (var input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return type.cast(input.readObject());
        }
    }
}
