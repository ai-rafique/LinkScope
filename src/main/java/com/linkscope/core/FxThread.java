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
}
