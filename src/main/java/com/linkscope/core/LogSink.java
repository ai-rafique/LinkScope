package com.linkscope.core;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

/**
 * Shared logging event bus. Every module writes here; the log panel binds to
 * {@link #entries()}. All mutations happen on the FX thread via {@link FxThread},
 * so callers may post from any thread.
 */
public final class LogSink {
    public static final int MAX_ENTRIES = 20_000;
    private static final int TRIM_BATCH = 2_000;
    private static final LogSink INSTANCE = new LogSink();

    private final ObservableList<LogEntry> entries = FXCollections.observableArrayList();
    private final ObservableList<LogEntry> readOnly = FXCollections.unmodifiableObservableList(entries);

    private LogSink() {
    }

    public static LogSink get() {
        return INSTANCE;
    }

    public ObservableList<LogEntry> entries() {
        return readOnly;
    }

    public void tx(String module, byte[] payload, String peer) {
        post(LogEntry.now(module, LogEntry.Kind.TX, payload, peer));
    }

    public void rx(String module, byte[] payload, String peer) {
        post(LogEntry.now(module, LogEntry.Kind.RX, payload, peer));
    }

    public void info(String module, String message) {
        post(LogEntry.now(module, LogEntry.Kind.INFO, null, message));
    }

    public void error(String module, String message) {
        post(LogEntry.now(module, LogEntry.Kind.ERROR, null, message));
    }

    public void error(String module, String message, Throwable cause) {
        String detail = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        error(module, message + ": " + detail);
    }

    public void clear() {
        FxThread.run(entries::clear);
    }

    private void post(LogEntry entry) {
        FxThread.run(() -> {
            entries.add(entry);
            if (entries.size() > MAX_ENTRIES) {
                entries.remove(0, TRIM_BATCH);
            }
        });
    }
}
