package com.linkscope.core;

import javafx.application.Platform;

/**
 * Marshals work onto the JavaFX Application Thread. When the toolkit is not running
 * (unit tests, headless use) the work runs inline under a lock instead, so services
 * and the log sink stay testable without a display.
 */
public final class FxThread {
    private static final Object FALLBACK_LOCK = new Object();
    private static volatile boolean fxAvailable = true;

    private FxThread() {
    }

    public static void run(Runnable work) {
        if (fxAvailable) {
            try {
                if (Platform.isFxApplicationThread()) {
                    work.run();
                } else {
                    Platform.runLater(work);
                }
                return;
            } catch (IllegalStateException toolkitNotInitialized) {
                fxAvailable = false;
            }
        }
        synchronized (FALLBACK_LOCK) {
            work.run();
        }
    }

    /**
     * Reads state that {@link #run} mutates. With the toolkit running this must be called on
     * the FX thread (as any read of FX state); without it, the read is serialized against the
     * inline updates so an iteration never races an append.
     */
    public static <T> T read(java.util.function.Supplier<T> reader) {
        if (fxAvailable) {
            return reader.get();
        }
        synchronized (FALLBACK_LOCK) {
            return reader.get();
        }
    }
}
