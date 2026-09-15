package com.linkscope;

import com.linkscope.core.FxThread;
import com.linkscope.core.LogEntry;
import com.linkscope.core.LogSink;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Polling helpers for asynchronous service tests. Without a JavaFX toolkit, FxThread
 * applies updates inline on the service threads, so every read of an observable list
 * must go through {@link #snapshot} to avoid racing an append.
 */
public final class TestSupport {
    private TestSupport() {
    }

    /** Copy of a list that services mutate via FxThread, taken under the same lock. */
    public static <T> List<T> snapshot(List<T> list) {
        return FxThread.read(() -> new ArrayList<>(list));
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
        for (LogEntry e : snapshot(LogSink.get().entries())) {
            if (e.module().equals(module) && e.kind() == kind && e.hasPayload()
                    && Arrays.equals(e.payload(), payload)) {
                return true;
            }
        }
        return false;
    }

    public static String dumpLog() {
        StringBuilder sb = new StringBuilder();
        for (LogEntry e : snapshot(LogSink.get().entries())) {
            sb.append(e.format(false, true)).append('\n');
        }
        return sb.toString();
    }

    public static boolean integrationSkipped() {
        String v = System.getenv("SKIP_INTEGRATION");
        return v != null && !v.isBlank() && !"0".equals(v.trim()) && !"false".equalsIgnoreCase(v.trim());
    }
}
