package com.cae.clean_trier;

import com.cae.clean_trier.retry.RetryNotification;
import com.cae.clean_trier.retry.RetrySubscriber;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/** Holds retry subscribers shared by all Trier instances. */
public final class RetryNotifier {
    /** The application-wide retry notifier. */
    public static final RetryNotifier SINGLETON = new RetryNotifier();

    private final CopyOnWriteArrayList<RetrySubscriber> subscribers = new CopyOnWriteArrayList<>();

    private RetryNotifier() {
    }

    /**
     * Registers a subscriber for retries across all Trier instances.
     *
     * @param subscriber the non-null subscriber
     * @return whether the subscriber was added
     */
    public boolean subscribe(RetrySubscriber subscriber) {
        return subscribers.addIfAbsent(Objects.requireNonNull(subscriber, "subscriber"));
    }

    /**
     * Removes a global retry subscriber. An in-progress notification uses its original subscriber list.
     *
     * @param subscriber the non-null subscriber
     * @return whether the subscriber was removed
     */
    public boolean unsubscribe(RetrySubscriber subscriber) {
        return subscribers.remove(Objects.requireNonNull(subscriber, "subscriber"));
    }

    void notifySubscribers(RetryNotification notification) {
        subscribers.forEach(subscriber -> subscriber.receiveRetryNotification(notification));
    }
}
