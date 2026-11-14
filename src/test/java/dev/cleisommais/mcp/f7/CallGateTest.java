package dev.cleisommais.mcp.f7;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class CallGateTest {
    private void awaitWaiting(CallGate gate) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (((Number) gate.metrics().get("waiting")).intValue() == 0 && System.nanoTime() < end) Thread.sleep(5);
        assertEquals(1, gate.metrics().get("waiting"));
    }
    @Test void boundsTheQueueAndReleasesPermitsAfterFailure() throws Exception {
        var gate = new CallGate(1, 1, Duration.ofSeconds(2));
        try (var threads = Executors.newSingleThreadExecutor()) {
            var first = gate.acquire();
            var second = threads.submit(() -> { try (var permit = gate.acquire()) { return true; } });
            awaitWaiting(gate); assertThrows(java.io.IOException.class, gate::acquire);
            first.close(); first.close(); assertTrue(second.get(2, TimeUnit.SECONDS));
            assertEquals(0, gate.metrics().get("active")); assertEquals(0, gate.metrics().get("waiting"));
            assertEquals(1, gate.metrics().get("peakActive")); assertEquals(1L, gate.metrics().get("rejected"));
        }
    }
    @Test void changingTheConfiguredSlotCountChangesAdmission() throws Exception {
        var gate = new CallGate(2, 0, Duration.ofSeconds(1));
        try (var first = gate.acquire(); var second = gate.acquire()) {
            assertEquals(2, gate.metrics().get("active")); assertThrows(java.io.IOException.class, gate::acquire);
        }
        assertEquals(2, gate.metrics().get("peakActive")); assertEquals(0, gate.metrics().get("active"));
    }
    @Test void timeoutDoesNotLeakAQueuePositionOrPermit() throws Exception {
        var gate = new CallGate(1, 1, Duration.ofMillis(30));
        try (var first = gate.acquire()) { assertThrows(java.io.IOException.class, gate::acquire); }
        try (var next = gate.acquire()) { assertEquals(0, gate.metrics().get("waiting")); }
        assertEquals(0, gate.metrics().get("active"));
    }
    @Test void interruptedWaitDoesNotConsumeCapacity() throws Exception {
        var gate = new CallGate(1, 1, Duration.ofSeconds(3));
        try (var threads = Executors.newSingleThreadExecutor()) {
            var first = gate.acquire(); var waiting = threads.submit(gate::acquire); awaitWaiting(gate);
            waiting.cancel(true); threads.shutdown(); assertTrue(threads.awaitTermination(2, TimeUnit.SECONDS));
            assertEquals(0, gate.metrics().get("waiting")); assertEquals(1, gate.metrics().get("active"));
            first.close(); try (var next = gate.acquire()) { assertEquals(1, gate.metrics().get("active")); }
        }
    }
}
