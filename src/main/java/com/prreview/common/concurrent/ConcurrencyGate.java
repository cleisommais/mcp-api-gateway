package com.prreview.common.concurrent;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded admission in one process, independent of durable queued jobs. */
public final class ConcurrencyGate {
    private final Semaphore slots;
    private final int limit, maxWaiting;
    private final Duration wait;
    private final AtomicInteger waiting = new AtomicInteger(),
            active = new AtomicInteger(),
            peak = new AtomicInteger();
    private final AtomicLong admitted = new AtomicLong(),
            rejected = new AtomicLong(),
            waitMillis = new AtomicLong();

    public ConcurrencyGate(int limit, int maxWaiting, Duration wait) {
        if (limit < 1
                || limit > 32
                || maxWaiting < 0
                || maxWaiting > 128
                || wait.isNegative()
                || wait.isZero()) {
            throw new IllegalArgumentException("Invalid call concurrency/queue/wait limits");
        }
        this.limit = limit;
        this.maxWaiting = maxWaiting;
        this.wait = wait;
        slots = new Semaphore(limit, true);
    }

    public Permit acquire() throws Exception {
        long started = System.nanoTime();
        boolean acquired = slots.tryAcquire(0, TimeUnit.MILLISECONDS);
        if (!acquired) {
            if (waiting.incrementAndGet() > maxWaiting) {
                waiting.decrementAndGet();
                rejected.incrementAndGet();
                throw new IOException("Call admission queue is full");
            }
            try {
                acquired = slots.tryAcquire(wait.toMillis(), TimeUnit.MILLISECONDS);
            } finally {
                waiting.decrementAndGet();
            }
            if (!acquired) {
                rejected.incrementAndGet();
                throw new IOException("Call admission wait timed out");
            }
        }
        long elapsed = (System.nanoTime() - started) / 1_000_000;
        waitMillis.addAndGet(elapsed);
        admitted.incrementAndGet();
        peak.accumulateAndGet(active.incrementAndGet(), Math::max);
        return new Permit(elapsed);
    }

    public final class Permit implements AutoCloseable {
        private boolean closed;
        private final long waited;

        private Permit(long waited) {
            this.waited = waited;
        }

        public long waitMillis() {
            return waited;
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                active.decrementAndGet();
                slots.release();
            }
        }
    }

    public Map<String, Object> metrics() {
        return Map.of(
                "scope",
                "process-cumulative",
                "limit",
                limit,
                "active",
                active.get(),
                "waiting",
                waiting.get(),
                "peakActive",
                peak.get(),
                "admitted",
                admitted.get(),
                "rejected",
                rejected.get(),
                "waitMillis",
                waitMillis.get());
    }

    public static int setting(
            Map<String, String> env, String name, int fallback, int min, int max) {
        int value = Integer.parseInt(env.getOrDefault(name, Integer.toString(fallback)));
        if (value < min || value > max) throw new IllegalArgumentException("Invalid " + name);
        return value;
    }

    public static ConcurrencyGate inference(Map<String, String> env) {
        return new ConcurrencyGate(
                setting(env, "INFERENCE_MAX_CONCURRENT", 1, 1, 16),
                setting(env, "INFERENCE_MAX_WAITING", 4, 0, 128),
                Duration.ofSeconds(setting(env, "INFERENCE_WAIT_SECONDS", 180, 1, 600)));
    }
}
