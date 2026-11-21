<p align="center">CAE Java Version</p>

---

<br>

### Clean Trier for Java

Java 25 LTS implementation of the [Clean Trier concept](../../concept/README.md).

Clean Trier executes an action with explicit retry, fallback, exhaustion, and exception-mapping rules. Its only runtime dependency is [Clean Mapped Exceptions](https://github.com/clean-arch-enablers-project/clean-mapped-exceptions).

<br>

#### Build and dependency

Use Java 25 and Maven 3.9 or newer. Trier uses release candidate version `1.0.0-RC1` and resolves Clean Mapped Exceptions `1.0.0-RC1` from Maven Central.

From the Clean Trier repository root:

```shell
mvn -B -ntp -f implementations/java/pom.xml clean verify
```

Both verification and publishing workflows use the published dependency. A separate Clean Mapped Exceptions checkout is not required.

To consume the locally installed release candidate, run `mvn -f implementations/java/pom.xml install` and declare:

```xml
<dependency>
    <groupId>com.clean-arch-enablers</groupId>
    <artifactId>clean-trier</artifactId>
    <version>1.0.0-RC1</version>
</dependency>
```

No published release is assumed; install the release candidate locally until it is published.

<br>

#### Usage

```java
import com.cae.clean_trier.Trier;
import com.cae.clean_mapped_exceptions.specifics.InternalMappedException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

var customer = Trier.of((Function<String, Customer>) repository::load, customerId)
        .retryOn(IllegalStateException.class, 3, 100, TimeUnit.MILLISECONDS)
        .subscribe(notification -> logger.info("Retry {}", notification.getTotalOfRetriesAtThisPoint()))
        .fallback(cache::load)
        .onUnexpectedExceptions(original ->
                new InternalMappedException("Customer could not be loaded", original))
        .execute();
```

The method references and domain types above are supplied by the application. Actions use Java's standard functional interfaces:

```java
Trier.of(function, input); // Function<I, O>
Trier.of(consumer, input); // Consumer<I>
Trier.of(supplier);        // Supplier<O>
Trier.of(runnable);        // Runnable
```

Use an explicit interface cast when a lambda or method reference is ambiguous between overloads. Void actions return null. Inputs and successful results may be null. Standard Java functional interfaces do not declare checked exceptions; callers must adapt operations that throw them.

`build()` finishes configuration with default exception mapping. `onUnexpectedExceptions(handler)` installs a custom mapper and also builds, preserving the original builder style.

<br>

#### Execution contract

1. Execute the original action and return its result on success.
2. Select a matching retry policy before mapping the failure. A policy for a superclass applies to its subclasses; the most specific matching type wins.
3. Retry while that policy permits another attempt, notifying subscribers and then waiting for the configured delay.
4. When retries are exhausted or no policy applies, execute the fallback if configured.
5. Without fallback, report matching-policy exhaustion; otherwise propagate an already mapped exception unchanged or map the unexpected exception.

JVM `Error` instances propagate without retry or mapping, including errors from the fallback. Interruption during a retry delay restores the thread's interrupted flag and immediately throws an `InternalMappedException` whose cause is the `InterruptedException`; the triggering action failure is suppressed. It does not start fallback or another attempt.

<br>

##### Retry policies

`retryOn(type, maxRetries, baseTime, timeUnit)` creates an immutable `RetryPolicy`. Alternatively pass `RetryPolicy.of(...)` to `retryOn(type, policy)`.

`maxRetries` counts additional attempts: three retries permit four executions. Zero retries is valid; a matching failure immediately exhausts the policy. Negative retry counts or delays, and null policy types, policies, or time units, are rejected.

Delays are `baseTime × 2^(policyRetryNumber - 1)`. Overflow saturates at `Long.MAX_VALUE` in the configured unit. A zero delay remains zero. `RetryPolicy.delayForRetry(int)` accepts a positive, one-based retry number.

Counters are local to each invocation and each selected policy. Different exception types using the same policy key share that policy's budget; different policy keys have separate budgets. Retrying another type does not reset previously consumed budgets. A second invocation starts fresh.

Built Trier instances snapshot their builder's configuration. Later builder changes do not change earlier instances. Configuration can be shared between concurrent invocations; supplied actions, inputs, fallbacks, and callbacks must support their intended concurrency. Builders themselves are mutable and are not intended for concurrent mutation.

<br>

##### Retry observations

`subscribe(RetrySubscriber)` registers an observer on that Trier configuration. Observers run synchronously in registration order immediately before a permitted retry's delay. Notifications retain the exact triggering exception and the invocation's cumulative one-based retry count, across all policies.

No notification is sent for the initial attempt, successful completion, or exhaustion without another retry. Callback exceptions propagate unchanged and stop execution. Applications can delegate reporting to their own executors; Trier creates no global notification pool or shutdown hook.

<br>

##### Fallback

`fallback(Function<I, O>)` receives the same input as the original action and supplies an acceptable replacement result. For suppliers or runnables, use an ignored parameter; void fallbacks return null:

```java
Trier.of(runnable)
        .fallback(ignored -> {
            recovery.run();
            return null;
        })
        .build()
        .execute();
```

Fallback runs once and is not retried by the original action's policies. Success bypasses both exhaustion and unexpected-exception handlers. Failure produces `FallbackFailureMappedException`: its cause is the fallback failure, `getTriggeringException()` retains the original action failure, and the latter is also suppressed for standard Java diagnostic tooling.

<br>

##### Exhaustion and exception mapping

The default exhaustion outcome is `NoRetriesLeftException`, an `InternalMappedException`. Its `getFailureStatus()` provides the final action exception, cumulative retry count, and exhausted policy.

`onExhaustion(status -> mappedException)` replaces that outcome. The handler returns a mapped exception; it does not recover a result. Use fallback for recovery.

The default unexpected-exception mapper creates an `InternalMappedException` with the original failure as its cause. Custom mappers should also retain the original as the cause. If a returned exception omits it, Trier preserves it as a suppressed failure. Returning the triggering mapped exception itself preserves its identity. Handler results must be non-null; callback exceptions propagate unchanged.

Exception classes do not declare explicit `serialVersionUID` fields. Java generates their serialization identifiers; stable serialization across releases is not promised.

<br>

#### Migration from CAE Trier

| Old | New |
| --- | --- |
| `com.clean-arch-enablers:cae-trier` | `com.clean-arch-enablers:clean-trier` |
| `com.cae.trier` / `com.cae.trier.retry` | `com.cae.clean_trier` / `com.cae.clean_trier.retry` |
| CAE Mapped Exceptions dependency | Clean Mapped Exceptions dependency |
| Java 11 | Java 25 LTS |
| Shared, mutable retry tracker | Invocation-local counters and immutable policies |
| Exact exception class matching | Most specific matching superclass |
| Recursive retry execution | Iterative execution |
| `OnExhaustion.handle(...)` returns void | Returns the mapped exception to throw |
| Missing exhaustion handler fails configuration at runtime | Default mapped exhaustion outcome |
| `RetryNotifier.SINGLETON.subscribe(...)` | Builder-local `subscribe(...)` |
| Global notification executor and shutdown hook | Synchronous callbacks; caller owns any executor |
| Separate `Action` adapters and `RetryTracker` | Supplied callables adapted internally |
| No Java fallback API | Builder `fallback(Function<I, O>)` |

The four `Trier.of(...)` shapes and terminal `onUnexpectedExceptions(...)` builder method are retained. Replace imports and dependency coordinates, adapt exhaustion handlers to return a mapped exception, and recompile consumers.

Use `new RetryNotification(cause, count)` for notifications and `new OnExhaustion.FailureStatus(cause, count, policy)` for exhaustion context. Those immutable records retain the legacy getters used by handlers and observers, alongside their record accessors. The action adapter classes, mutable trackers, global notifier, and its pool provider are no longer public APIs. This artifact is not a binary-compatible replacement.

<br>

#### Verification

Automatic Maven Central publishing on `main` is described in the [publishing guide](../../.github/PUBLISHING.md), including required GitHub Secrets and release ordering.

From this Java directory:

```shell
mvn -B -ntp clean verify
```

Compilation enables `-Xlint:all,-serial` and fails on warnings. The targeted `overloads` suppression on Trier acknowledges the existing Function/Consumer API overloads. Javadoc also fails on warnings. JaCoCo requires zero missed instructions, branches, lines, methods, and complexity for each production class, without exclusions.

Reports are available under `target/surefire-reports/` and `target/site/jacoco/`.

Set `SONAR_HOST_URL` and `SONAR_TOKEN` for your SonarQube instance, then run:

```shell
mvn -B -ntp clean verify sonar:sonar
```

The scanner imports JaCoCo's XML report and waits for the configured server quality gate.

<br>
<br>

---

<p align="center">clean software, made easy.</p>
