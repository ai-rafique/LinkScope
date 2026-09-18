package com.linkscope.core;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Build-time facts shown in the status bar: version (major.minor.patch) and rights line. */
public final class AppInfo {
    public static final String NAME = "LinkScope";
    /** Change here when a license is chosen, e.g. "MIT License". */
    public static final String RIGHTS = "© 2026 LinkScope · All rights reserved";

    private static final String VERSION = load();

    private AppInfo() {
    }

    /** The build version such as "0.2.1", or "dev" when running from sources without the processed resource. */
    public static String version() {
        return VERSION;
    }

    /** Short line for the status bar: {@code v0.2.1 · © 2026 LinkScope · All rights reserved}. */
    public static String statusLine() {
        return "v" + VERSION + " · " + RIGHTS;
    }

    /** Longer detail for a tooltip or an about box. */
    public static String details() {
        return NAME + " " + VERSION
                + "\nJava " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")"
                + "\nJavaFX " + System.getProperty("javafx.version", "?")
                + "\n" + System.getProperty("os.name") + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch");
    }

    private static String load() {
        try (InputStream in = AppInfo.class.getResourceAsStream("/com/linkscope/version.properties")) {
            if (in == null) {
                return "dev";
            }
            Properties p = new Properties();
            p.load(in);
            String v = p.getProperty("version", "dev").trim();
            return v.isEmpty() || v.startsWith("${") ? "dev" : v;
        } catch (IOException e) {
            return "dev";
        }
    }
}
