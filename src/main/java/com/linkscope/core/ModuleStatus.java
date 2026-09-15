package com.linkscope.core;

/** Connection state of a transport module. Every status has a text label so UI is never color-only. */
public enum ModuleStatus {
    DISCONNECTED("Disconnected"),
    CONNECTING("Connecting…"),
    CONNECTED("Connected"),
    ERROR("Error");

    private final String label;

    ModuleStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public boolean isActive() {
        return this == CONNECTING || this == CONNECTED;
    }
}
