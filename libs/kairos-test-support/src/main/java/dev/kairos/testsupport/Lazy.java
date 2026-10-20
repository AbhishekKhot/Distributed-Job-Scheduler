package dev.kairos.testsupport;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * A value computed once, on first use.
 *
 * <p>Used for the container singletons: the first test that needs a container starts it, and every later test in the
 * same JVM gets the same instance. A failed start is remembered and rethrown, so a broken Docker setup fails each test
 * quickly with the original cause instead of retrying a slow start in every test class.
 *
 * <p>Thread-safe.
 *
 * @param <T> the type of the value
 */
final class Lazy<T> {

    private final Supplier<T> factory;
    private final ReentrantLock lock = new ReentrantLock();
    private @Nullable T value;
    private @Nullable RuntimeException failure;

    Lazy(Supplier<T> factory) {
        this.factory = factory;
    }

    T get() {
        lock.lock();
        try {
            if (value != null) {
                return value;
            }
            if (failure != null) {
                throw new IllegalStateException("An earlier attempt failed", failure);
            }
            try {
                T created = factory.get();
                value = created;
                return created;
            } catch (RuntimeException e) {
                failure = e;
                throw e;
            }
        } finally {
            lock.unlock();
        }
    }
}
