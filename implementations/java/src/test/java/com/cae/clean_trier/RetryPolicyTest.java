package com.cae.clean_trier;

import com.cae.clean_trier.retry.OnExhaustion;
import com.cae.clean_trier.retry.RetryNotification;
import com.cae.clean_trier.retry.RetryPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RetryPolicyTest {
    @ParameterizedTest
    @CsvSource({"0,1,0", "0,100,0", "2,1,2", "2,2,4", "2,3,8", "1,63,4611686018427387904",
            "2,63,9223372036854775807", "1,64,9223372036854775807"})
    void calculatesBackoffWithoutOverflow(long base, int retry, long expected) {
        var policy = RetryPolicy.of(100, base, TimeUnit.NANOSECONDS);
        assertEquals(expected, policy.delayForRetry(retry));
    }

    @Test
    void preservesConfigurationAndSaturatesLargeBase() {
        var policy = new RetryPolicy(3, Long.MAX_VALUE, TimeUnit.SECONDS);
        assertAll(
                () -> assertEquals(3, policy.maxAmountOfRetries()),
                () -> assertEquals(Long.MAX_VALUE, policy.baseTime()),
                () -> assertEquals(TimeUnit.SECONDS, policy.timeUnit()),
                () -> assertEquals(Long.MAX_VALUE, policy.delayForRetry(1)),
                () -> assertEquals(Long.MAX_VALUE, policy.delayForRetry(2)));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void rejectsNonpositiveRetryNumber(int number) {
        var policy = RetryPolicy.of(1, 0, TimeUnit.SECONDS);
        assertThrows(IllegalArgumentException.class, () -> policy.delayForRetry(number));
    }

    @Test
    void rejectsInvalidPoliciesAndContexts() {
        var cause = new IllegalStateException("Failure");
        var policy = RetryPolicy.of(0, 0, TimeUnit.SECONDS);
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> RetryPolicy.of(-1, 0, TimeUnit.SECONDS)),
                () -> assertThrows(IllegalArgumentException.class, () -> RetryPolicy.of(0, -1, TimeUnit.SECONDS)),
                () -> assertThrows(NullPointerException.class, () -> RetryPolicy.of(0, 0, null)),
                () -> assertThrows(IllegalArgumentException.class, () -> new RetryNotification(cause, 0)),
                () -> assertThrows(NullPointerException.class, () -> new RetryNotification(null, 1)),
                () -> assertThrows(NullPointerException.class, () -> new OnExhaustion.FailureStatus(null, 0, policy)),
                () -> assertThrows(NullPointerException.class, () -> new OnExhaustion.FailureStatus(cause, 0, null)),
                () -> assertThrows(IllegalArgumentException.class, () -> new OnExhaustion.FailureStatus(cause, -1, policy)));
    }

    @Test
    void exposesObservationAndExhaustionContext() {
        var cause = new IllegalStateException("Failure");
        var notification = new RetryNotification(cause, 2);
        var policy = RetryPolicy.of(2, 0, TimeUnit.SECONDS);
        var status = new OnExhaustion.FailureStatus(cause, 2, policy);
        assertAll(
                () -> assertSame(cause, notification.getCause()),
                () -> assertEquals(2, notification.getTotalOfRetriesAtThisPoint()),
                () -> assertSame(cause, status.getException()),
                () -> assertEquals(2, status.getTotalOfRetries()),
                () -> assertSame(policy, status.retryPolicy()));
    }
}
