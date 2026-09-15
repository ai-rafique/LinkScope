package com.linkscope.core.ui;

import javafx.scene.control.CheckBox;
import javafx.scene.control.TextField;

import java.util.Map;

/** Small helpers for reading/writing form fields with clear validation messages. */
public final class Fields {
    private Fields() {
    }

    public static String text(TextField field) {
        String t = field.getText();
        return t == null ? "" : t.trim();
    }

    public static String requireText(TextField field, String label) {
        String t = text(field);
        if (t.isEmpty()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return t;
    }

    public static int intValue(TextField field, String label, int min, int max) {
        String t = text(field);
        int value;
        try {
            value = Integer.parseInt(t);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label + " must be a whole number, got \"" + t + "\"");
        }
        if (value < min || value > max) {
            throw new IllegalArgumentException(label + " must be between " + min + " and " + max);
        }
        return value;
    }

    public static int port(TextField field, String label) {
        return intValue(field, label, 0, 65535);
    }

    public static void apply(Map<String, String> fields, String key, TextField target) {
        String v = fields.get(key);
        if (v != null) {
            target.setText(v);
        }
    }

    public static void apply(Map<String, String> fields, String key, CheckBox target) {
        String v = fields.get(key);
        if (v != null) {
            target.setSelected(Boolean.parseBoolean(v));
        }
    }
}
