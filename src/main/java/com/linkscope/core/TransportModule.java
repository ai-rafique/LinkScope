package com.linkscope.core;

import javafx.beans.property.ReadOnlyObjectProperty;

/** The minimum contract every transport service implements. Configure via setters, then start(). */
public interface TransportModule {

    /** Tag used in log lines, e.g. {@code UDP} or {@code TCP/CLIENT}. */
    String moduleName();

    ReadOnlyObjectProperty<ModuleStatus> statusProperty();

    default ModuleStatus status() {
        return statusProperty().get();
    }

    /** Opens the connection / starts listening. Never blocks the caller. */
    void start();

    /** Closes everything. Safe to call when already stopped. */
    void stop();

    /** Sends a payload on the module's primary channel. Never blocks the caller. */
    void send(byte[] payload);
}
