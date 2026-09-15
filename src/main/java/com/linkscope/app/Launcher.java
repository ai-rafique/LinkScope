package com.linkscope.app;

/**
 * Plain entry point that does not extend {@code javafx.application.Application}.
 * JavaFX refuses to start when the main class itself is an Application subclass
 * launched from the classpath ("JavaFX runtime components are missing"), which is
 * exactly how the jpackage image runs the app. Delegating from here avoids that.
 */
public final class Launcher {
    private Launcher() {
    }

    public static void main(String[] args) {
        LinkScopeApp.main(args);
    }
}
