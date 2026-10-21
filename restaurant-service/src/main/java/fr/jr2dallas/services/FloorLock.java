package fr.jr2dallas.services;

import org.springframework.stereotype.Component;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Single lock guarding the whole floor (sessions, seats, waiters).
 *
 * The floor is mutated from three kinds of threads: the scheduler tick, HTTP animation
 * callbacks and the Kafka "dish ready" listener. Every entry point runs its work inside
 * this lock, so a waiter or a session is never seen half-updated. Work done under the lock
 * is in-memory and short (no I/O apart from async Kafka sends).
 */
@Component
public class FloorLock {

    @FunctionalInterface
    public interface Action<E extends Exception> { void run() throws E; }

    @FunctionalInterface
    public interface Query<T, E extends Exception> { T get() throws E; }

    private final ReentrantLock lock = new ReentrantLock();

    public <E extends Exception> void run(Action<E> action) throws E {
        lock.lock();
        try {
            action.run();
        } finally {
            lock.unlock();
        }
    }

    public <T, E extends Exception> T get(Query<T, E> query) throws E {
        lock.lock();
        try {
            return query.get();
        } finally {
            lock.unlock();
        }
    }
}
