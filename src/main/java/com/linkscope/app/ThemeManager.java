package com.linkscope.app;

import atlantafx.base.theme.PrimerDark;
import atlantafx.base.theme.PrimerLight;
import javafx.application.Application;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleObjectProperty;

import java.util.prefs.Preferences;

/** Runtime-switchable light/dark theme, remembered across launches via java.util.prefs. */
public final class ThemeManager {

    public enum Mode { LIGHT, DARK }

    private static final Preferences PREFS = Preferences.userRoot().node("com/linkscope");
    private static final String KEY = "theme";
    private static final ObjectProperty<Mode> MODE = new SimpleObjectProperty<>(Mode.DARK);

    static {
        MODE.addListener((obs, old, now) -> apply(now));
    }

    private ThemeManager() {
    }

    /** Applies the saved theme (dark by default). Call before loading any FXML. */
    public static void init() {
        Mode saved;
        try {
            saved = Mode.valueOf(PREFS.get(KEY, Mode.DARK.name()));
        } catch (IllegalArgumentException e) {
            saved = Mode.DARK;
        }
        MODE.set(saved);
        apply(saved);
    }

    public static void toggle() {
        MODE.set(isDark() ? Mode.LIGHT : Mode.DARK);
    }

    public static boolean isDark() {
        return MODE.get() == Mode.DARK;
    }

    public static ReadOnlyObjectProperty<Mode> modeProperty() {
        return MODE;
    }

    private static void apply(Mode mode) {
        String css = mode == Mode.DARK
                ? new PrimerDark().getUserAgentStylesheet()
                : new PrimerLight().getUserAgentStylesheet();
        Application.setUserAgentStylesheet(css);
        PREFS.put(KEY, mode.name());
    }
}
