<p align="center">CAE Implementation</p>

---

<br>

### Clean Trier for Java

Use Clean Trier on Java 25 to execute a Java action with retry, fallback, and mapped failure handling.

Maven automatically adds Clean Mapped Exceptions as a transitive dependency when your application declares this artifact.

<br>

#### Add to your Java application

To use this artifact in a Java application, add the following dependency to the `<dependencies>` section of your application's `pom.xml`.

```xml
<dependency>
    <groupId>com.clean-arch-enablers</groupId>
    <artifactId>clean-trier</artifactId>
    <version>1.0.0-RC2</version>
</dependency>
```

<br>

#### Fluent examples

These examples follow the concept's [execution order](../../concept/README.md#execution-order).

##### Configure and run an action

Register retry observers once during application setup. For each action, chain the options you need and finish by supplying the required unexpected-exception handler. This lookup retries and falls back to a cached value.

```java
import com.cae.clean_mapped_exceptions.specifics.InternalMappedException;
import com.cae.clean_trier.RetryNotifier;
import com.cae.clean_trier.Trier;
import com.cae.clean_trier.retry.RetryPolicy;
import com.cae.clean_trier.retry.RetrySubscriber;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

var notifier = RetryNotifier.SINGLETON;
RetrySubscriber observer = event -> System.out.println(
        "Retry " + event.totalOfRetriesAtThisPoint() + ": " + event.cause().getClass().getSimpleName());
notifier.subscribe(observer); // application setup

Function<String, String> remoteLookup = id -> {
    throw new IllegalStateException("Customer service unavailable");
};
var cache = Map.of("42", "cached customer");

String customer = Trier.of(remoteLookup, "42")
        .retryOn(IllegalStateException.class, RetryPolicy.of(2, 10, TimeUnit.MILLISECONDS))
        .retryOn(NumberFormatException.class, 0, 0, TimeUnit.MILLISECONDS)
        .fallback(cache::get)
        .onUnexpectedExceptions(cause -> new InternalMappedException("Customer lookup failed", cause))
        .execute();

Trier.of((Consumer<String>) System.out::println, customer)
        .onUnexpectedExceptions(cause -> new InternalMappedException("Could not print customer", cause))
        .execute();

String status = Trier.of((Supplier<String>) () -> "ready")
        .onUnexpectedExceptions(cause -> new InternalMappedException("Could not read service status", cause))
        .execute();

Trier.of((Runnable) () -> System.out.println("done"))
        .onUnexpectedExceptions(cause -> new InternalMappedException("Could not signal completion", cause))
        .execute();

notifier.unsubscribe(observer); // when the application no longer needs the observer
```

<br>

##### Choose how failures leave the action

Use `onExhaustion` to describe a failure after retries run out. The required `onUnexpectedExceptions` handler describes an unmapped failure without a matching retry policy or a fallback.

```java
import com.cae.clean_mapped_exceptions.MappedException;
import com.cae.clean_mapped_exceptions.specifics.InternalMappedException;
import com.cae.clean_trier.FallbackFailureMappedException;
import com.cae.clean_trier.Trier;
import com.cae.clean_trier.retry.NoRetriesLeftException;
import java.util.concurrent.TimeUnit;

Runnable refresh = () -> { throw new IllegalStateException("Inventory service unavailable"); };

try {
    Trier.of(refresh)
            .retryOn(IllegalStateException.class, 2, 0, TimeUnit.MILLISECONDS)
            .onExhaustion(status -> new NoRetriesLeftException("Inventory refresh retries exhausted", status))
            .onUnexpectedExceptions(cause -> new InternalMappedException("Inventory refresh failed", cause))
            .execute();
} catch (NoRetriesLeftException failure) {
    var status = failure.getFailureStatus();
    Exception original = status.exception();
    int retries = status.totalOfRetries();
    var exhaustedPolicy = status.retryPolicy();
}

try {
    Trier.of(refresh)
            .onUnexpectedExceptions(cause -> new InternalMappedException("Inventory refresh failed", cause))
            .execute();
} catch (MappedException failure) {
    String message = failure.getBriefPublicMessage();
    Throwable original = failure.getCause();
}

try {
    Trier.of(refresh)
            .fallback(ignored -> { throw new IllegalStateException("Inventory cache unavailable"); })
            .onUnexpectedExceptions(cause -> new InternalMappedException("Inventory refresh failed", cause))
            .execute();
} catch (FallbackFailureMappedException failure) {
    Exception actionFailure = failure.getTriggeringException();
    Throwable recoveryFailure = failure.getCause();
}
```

<br>

##### Configure a Trier with every option

This example registers a global observer, then creates one Trier with two retry policies, a fallback, an exhaustion handler, and the required unexpected-exception handler.

```java
import com.cae.clean_mapped_exceptions.specifics.InternalMappedException;
import com.cae.clean_trier.RetryNotifier;
import com.cae.clean_trier.Trier;
import com.cae.clean_trier.retry.NoRetriesLeftException;
import com.cae.clean_trier.retry.RetryPolicy;
import com.cae.clean_trier.retry.RetrySubscriber;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

var notifier = RetryNotifier.SINGLETON;
RetrySubscriber observer = event -> System.out.println("Inventory retry " + event.totalOfRetriesAtThisPoint());
notifier.subscribe(observer);

try {
    Function<String, Integer> fetchStock = sku -> {
        throw new IllegalStateException("Inventory service unavailable");
    };
    var cachedStock = Map.of("SKU-42", 12);

    int available = Trier.of(fetchStock, "SKU-42")
            .retryOn(IllegalStateException.class, RetryPolicy.of(2, 10, TimeUnit.MILLISECONDS))
            .retryOn(NumberFormatException.class, 0, 0, TimeUnit.MILLISECONDS)
            .fallback(cachedStock::get)
            .onExhaustion(status -> new NoRetriesLeftException("Inventory lookup retries exhausted", status))
            .onUnexpectedExceptions(cause -> new InternalMappedException("Inventory lookup failed", cause))
            .execute(); // 12, from the cache after two retries
} finally {
    notifier.unsubscribe(observer);
}
```

Fallback takes precedence after an unmatched failure or retry exhaustion. This run returns the cached result. A failing fallback produces `FallbackFailureMappedException`. The exhaustion and unexpected-exception handlers apply when executing without a fallback.

<br>

#### Configure and execute an action

The four `of(...)` overloads capture the [action shapes](../../concept/README.md#action).

| API on `com.cae.clean_trier.Trier` | Purpose |
| --- | --- |
| `of(Function<I, O> action, I input)` | Configure an action that takes an input and produces a result, such as loading a customer by ID. |
| `of(Consumer<I> action, I input)` | Configure an action that takes an input and performs a side effect, such as sending a supplied notification. |
| `of(Supplier<O> action)` | Configure an action that produces a result without taking an input, such as reading the current service status. |
| `of(Runnable action)` | Configure an action with no input or result, such as refreshing a cache. |
| `TrierBuilder.onUnexpectedExceptions(UnexpectedExceptionHandler handler)` | Supply the required handler for unmapped failures and create a reusable Trier from the configured action and policies. |
| `execute()` | Run the configured action with a fresh retry budget each time. Return the action's result or the fallback's result when recovery succeeds. |

Typed functional interfaces disambiguate overloaded lambdas and method references. Void actions return `null`. Inputs and successful results may also be `null`. Adapt checked failures before passing an operation to a standard Java functional interface.

Finish configuring the builder by calling `onUnexpectedExceptions(handler)` with a non-null handler. It snapshots the configuration and returns a Trier that can be reused across invocations, with a new retry budget each time. Use the builder from one thread at a time and ensure the supplied actions and callbacks can handle the concurrency of their callers.

<br>

#### Retry and observe attempts

The policies govern [when to retry](../../concept/README.md#retry). Subscribers receive [retry observations](../../concept/README.md#retry-observations).

| Builder API | Use |
| --- | --- |
| `retryOn(Class<? extends Exception> type, int maxRetries, long baseTime, TimeUnit unit)` | Set a retry policy from its parameters. |
| `retryOn(Class<? extends Exception> type, RetryPolicy policy)` | Set a reusable retry policy. |

| Global observer API | Use |
| --- | --- |
| `com.cae.clean_trier.RetryNotifier.SINGLETON` | Access the retry notifier shared by all Trier instances. |
| `RetryNotifier.subscribe(RetrySubscriber subscriber)` | Register an observer for all Trier instances. Returns whether a new registration was added. |
| `RetryNotifier.unsubscribe(RetrySubscriber subscriber)` | Remove a registered observer. Returns whether a registration was removed. |

| API in `com.cae.clean_trier.retry` | Use |
| --- | --- |
| `new RetryPolicy(int maxAmountOfRetries, long baseTime, TimeUnit timeUnit)` or `RetryPolicy.of(...)` | Create an immutable exponential-backoff policy. |
| `RetryPolicy.maxAmountOfRetries()`, `baseTime()`, `timeUnit()` | Read the retry limit, first delay, and time unit. |
| `RetryPolicy.delayForRetry(int retryNumber)` | Calculate the delay for a positive, one-based retry number in this policy. |
| `RetrySubscriber.receiveRetryNotification(RetryNotification notification)` | Implement the callback invoked before each permitted retry delay. |
| `new RetryNotification(Exception cause, int totalOfRetriesAtThisPoint)` | Construct a retry event, for example when testing an observer. |
| `RetryNotification.cause()`, `totalOfRetriesAtThisPoint()` | Read the original action failure and cumulative retry count for the invocation. |
| `RetryNotification.getCause()`, `getTotalOfRetriesAtThisPoint()` | Getter forms of the same accessors. |

A retry is an additional attempt. Three retries allow up to four executions. The most specific matching exception class determines the policy, and each policy key has its own budget within the invocation. The delay doubles after each retry and saturates at `Long.MAX_VALUE` in the configured unit. Zero retries exhausts a matching policy immediately. Zero delay is allowed.

Subscribers observe retries from every Trier, including instances created before registration. Each notification uses the subscriber list at the start of delivery. Subscribers run synchronously in registration order before the delay and may be called concurrently by separate Trier invocations. A subscriber failure propagates and stops the current execution.

An interrupted retry delay restores the interrupt flag and raises an `InternalMappedException` with the interruption as its cause and the triggering action failure suppressed.

<br>

#### Recover with a fallback

The recovery function implements [fallback](../../concept/README.md#fallback).

| API | Use |
| --- | --- |
| `TrierBuilder.fallback(Function<I, O> recovery)` | Run recovery once after an unmatched failure or exhaustion. The recovery function receives the original input. |
| `new FallbackFailureMappedException(Exception fallbackException, Exception triggeringException)` | Represent a failed recovery while retaining both failures. |
| `FallbackFailureMappedException.getTriggeringException()` | Retrieve the original action failure. Java's `getCause()` returns the recovery failure. |

For an action without input, ignore the fallback parameter. For a void action, return `null` from the fallback. A completed fallback supplies the final result. If recovery fails, the triggering failure is also available through `getSuppressed()`.

<br>

#### Map exhaustion and unexpected failures

The two handlers implement [retry exhaustion](../../concept/README.md#exhaustion) and [unexpected exception mapping](../../concept/README.md#exception-mapping).

| API | Use |
| --- | --- |
| `TrierBuilder.onExhaustion(OnExhaustion handler)` | Choose the mapped exception thrown on exhaustion when there is no fallback. |
| `OnExhaustion.handle(FailureStatus status)` | Implement the callback that maps an exhausted invocation. |
| `new OnExhaustion.FailureStatus(Exception exception, int totalOfRetries, RetryPolicy retryPolicy)` | Create the terminal retry record, for example when testing a handler. |
| `FailureStatus.exception()`, `totalOfRetries()`, `retryPolicy()` | Read the final action failure, invocation-wide retry count, and exhausted policy. |
| `FailureStatus.getException()`, `getTotalOfRetries()` | Getter forms for the failure and retry count. |
| `new NoRetriesLeftException(String briefPublicMessage, OnExhaustion.FailureStatus status)` | Create an exhaustion exception with the terminal action failure as its cause. |
| `NoRetriesLeftException.getFailureStatus()` | Read the terminal retry record. |
| `TrierBuilder.onUnexpectedExceptions(UnexpectedExceptionHandler handler)` | Create the Trier with the required mapper for unmapped failures that reach the mapping step. |
| `UnexpectedExceptionHandler.handle(Exception failure)` | Implement the unexpected-failure mapping callback. |

The default exhaustion handler throws `NoRetriesLeftException`. For an unmatched failure with no fallback, Trier propagates an already mapped exception unchanged or applies the application's unexpected-failure handler. Use the handler to describe the action that failed and select the mapped exception category that fits the workflow. It must return a non-null mapped exception. If it omits the original cause, Trier attaches the original as a suppressed failure unless the handler returns that failure itself. JVM errors propagate directly.

<br>
<br>

---

<p align="center">clean software, made easy.</p>
