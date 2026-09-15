package com.linkscope;

import com.linkscope.core.LogEntry;
import com.linkscope.core.LogSink;

import java.time.Duration;
import java.util.Arrays;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.fail;

/** Polling helpers for asynchronous service tests (no toolkit: FxThread runs inline). */
public final class TestSupport {
    private TestSupport() {
    }

    public static void awaitTrue(String what, Duration timeout, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrupted while waiting for " + what);
            }
        }
        fail("timed out waiting for " + what + "\nlog:\n" + dumpLog());
    }

    public static void awaitTrue(String what, BooleanSupplier condition) {
        awaitTrue(what, Duration.ofSeconds(5), condition);
    }

    /** True when the shared log holds an entry of that module/kind with exactly this payload. */
    public static boolean logged(String module, LogEntry.Kind kind, byte[] payload) {
        synchronized (LogSink.get()) {
            for (LogEntry e : LogSink.get().entries().toArray(new LogEntry[0])) {
                if (e.module().equals(module) && e.kind() == kind && e.hasPayload()
                        && Arrays.equals(e.payload(), payload)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static String dumpLog() {
        StringBuilder sb = new StringBuilder();
        for (LogEntry e : LogSink.get().entries().toArray(new LogEntry[0])) {
            sb.append(e.format(false, true)).append('\n');
        }
        return sb.toString();
    }

    public static boolean integrationSkipped() {
        String v = System.getenv("SKIP_INTEGRATION");
        return v != null && !v.isBlank() && !"0".equals(v.trim()) && !"false".equalsIgnoreCase(v.trim());
    }
}
