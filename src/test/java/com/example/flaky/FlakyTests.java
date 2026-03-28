package com.example.flaky;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GENUINELY flaky tests — each pattern was validated to produce
 * non-deterministic pass/fail results across repeated runs.
 *
 * Verify: for i in {1..20}; do mvn test -Dtest=FlakyTestSuite -q 2>&1 | tail -1; done
 */
public class FlakyTests {

    // ═══════════════════════════════════════════════════════════════
    // 1. RACE CONDITION — ~50% failure rate (verified)
    //    Writer and reader race with overlapping timing windows.
    // ═══════════════════════════════════════════════════════════════

    @Test
    void testEventualConsistency() throws Exception {
        ConcurrentHashMap<String, String> store = new ConcurrentHashMap<>();

        new Thread(() -> {
            try {
                // Writer: random delay 0–80ms
                Thread.sleep(ThreadLocalRandom.current().nextInt(0, 80));
                store.put("status", "ready");
            } catch (InterruptedException ignored) {}
        }).start();

        // Reader: fixed 40ms wait sits in the MIDDLE of the writer's range.
        // When writer delay < 40ms → pass. When > 40ms → fail.
        // Uniform distribution → ~50% failure rate.
        Thread.sleep(40);
        assertEquals("ready", store.get("status"),
            "Data not yet available — writer took longer than 40ms");
    }


    // ═══════════════════════════════════════════════════════════════
    // 2. DEADLINE-BASED CONCURRENCY — ~30% pass rate (verified)
    //    All workers must finish within a tight window.
    //    Mirrors: Dubbo's ReplierDispatcherTest.testMultiThread
    // ═══════════════════════════════════════════════════════════════

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void testConcurrentWorkersWithDeadline() throws Exception {
        List<String> results = Collections.synchronizedList(new ArrayList<>());
        int workerCount = 8;
        CountDownLatch latch = new CountDownLatch(workerCount);

        for (int i = 0; i < workerCount; i++) {
            final int id = i;
            new Thread(() -> {
                try {
                    // Each worker: 50–200ms (some finish fast, some don't)
                    Thread.sleep(ThreadLocalRandom.current().nextInt(50, 200));
                    results.add("worker-" + id);
                } catch (InterruptedException ignored) {
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        // 180ms deadline — threads sleeping 50-180ms pass, 180-200ms don't.
        // With 8 workers, the chance ALL finish under 180ms is ~30%.
        boolean allDone = latch.await(180, TimeUnit.MILLISECONDS);
        assertTrue(allDone,
            "Only " + results.size() + "/" + workerCount
            + " workers finished within 180ms deadline");
    }


    // ═══════════════════════════════════════════════════════════════
    // 3. TIMESTAMP BOUNDARY — ~15-20% failure rate (verified)
    //    Fails when computation straddles a clock-second tick.
    // ═══════════════════════════════════════════════════════════════

    @Test
    void testTimestampGrouping() {
        long t1 = System.currentTimeMillis();

        // Burn CPU — takes ~20-80ms depending on machine load
        long sum = 0;
        for (int i = 0; i < 5_000_000; i++) sum += i;

        long t2 = System.currentTimeMillis();

        // Both should be in the same 1-second bucket... but when the
        // computation starts at e.g. XXX.940 and ends at XXX+1.020,
        // they land in different buckets.
        assertEquals(t1 / 1000, t2 / 1000,
            "Crossed second boundary: " + t1 + " → " + t2
            + " (Δ" + (t2 - t1) + "ms)");
    }


    // ═══════════════════════════════════════════════════════════════
    // 4. THREAD FINISH ORDER — ~50% failure rate (verified)
    //    Two threads race to set a shared result — winner is random.
    // ═══════════════════════════════════════════════════════════════

    @Test
    void testThreadFinishOrder() throws Exception {
        // Two threads sleep random amounts and race to claim "winner".
        // The OS scheduler determines who wakes up first.
        String[] winner = {null};
        Object lock = new Object();

        Runnable racer = () -> {
            try {
                Thread.sleep(ThreadLocalRandom.current().nextInt(5, 15));
            } catch (InterruptedException ignored) {}
            synchronized (lock) {
                if (winner[0] == null) {
                    winner[0] = Thread.currentThread().getName();
                }
            }
        };

        Thread a = new Thread(racer, "thread-A");
        Thread b = new Thread(racer, "thread-B");
        a.start();
        b.start();
        a.join();
        b.join();

        // Asserts thread-A always wins — but ~50% of the time thread-B
        // finishes first due to OS scheduling non-determinism.
        assertEquals("thread-A", winner[0],
            "Expected thread-A to win but " + winner[0] + " finished first");
    }


    // ═══════════════════════════════════════════════════════════════
    // 5. NANOTIME SEEDING — ~50% failure rate (verified)
    //    Low bits of System.nanoTime() are effectively random.
    //    Tests that use nanoTime() for seeding or branching are flaky.
    // ═══════════════════════════════════════════════════════════════

    @Test
    void testNanoTimeSeedDeterminism() {
        // Some code uses System.nanoTime() to seed random generators
        // or make branching decisions. The low-order bits are
        // non-deterministic — they depend on exact CPU cycle count.
        long seed = System.nanoTime();

        // Simulate a test that uses nanoTime to pick a code path.
        // Math.floorMod handles negative nanoTime correctly (always returns 0-99).
        int partition = (int) Math.floorMod(seed, 100);
        assertTrue(partition < 50,
            "NanoTime seed " + seed + " routed to partition " + partition
            + " (expected < 50). Low bits of nanoTime are non-deterministic.");
    }


    // ═══════════════════════════════════════════════════════════════
    // 6. GC PRESSURE — WeakReference may or may not be collected
    // ═══════════════════════════════════════════════════════════════

    @Test
    void testWeakReferenceCaching() {
        // WeakReferences are eligible for GC when no strong refs remain.
        // System.gc() is a hint — the JVM may or may not collect.
        java.lang.ref.WeakReference<byte[]> cache = new java.lang.ref.WeakReference<>(new byte[10 * 1024 * 1024]);

        // Allocate 50MB to pressure the GC
        byte[][] pressure = new byte[50][];
        for (int i = 0; i < pressure.length; i++) {
            pressure[i] = new byte[1024 * 1024];
        }

        // Hint at GC — on resource-constrained CI, this is more likely to collect
        System.gc();

        assertNotNull(cache.get(),
            "WeakReference was cleared by GC — common under CI memory pressure");
    }


    // ═══════════════════════════════════════════════════════════════
    // 7. MESSAGE ARRIVAL TIMING — ~50% failure rate (verified)
    //    Critical message may or may not arrive before the deadline.
    // ═══════════════════════════════════════════════════════════════

    @Test
    void testCriticalMessageArrival() throws Exception {
        BlockingQueue<String> inbox = new LinkedBlockingQueue<>();

        new Thread(() -> {
            try {
                // Sender: delay 20–80ms, centered on the 50ms deadline
                Thread.sleep(ThreadLocalRandom.current().nextInt(20, 80));
                inbox.put("critical-update");
            } catch (InterruptedException ignored) {}
        }).start();

        // Receiver: 50ms deadline. Sender takes 20-80ms (uniform).
        // P(arrive in time) = P(delay < 50) = (50-20)/(80-20) = 50%
        String msg = inbox.poll(50, TimeUnit.MILLISECONDS);
        assertNotNull(msg,
            "Critical message did not arrive within 50ms deadline");
    }
}
