package com.linkscope.core;

import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Base for transport services: a virtual-thread executor for blocking I/O, a status
 * property updated on the FX thread, and the shared log sink.
 */
public abstract class AbstractService implements TransportModule {
    protected final ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
    protected final LogSink log = LogSink.get();
    private final ReadOnlyObjectWrapper<ModuleStatus> status = new ReadOnlyObjectWrapper<>(ModuleStatus.DISCONNECTED);
    private volatile ModuleStatus lastStatus = ModuleStatus.DISCONNECTED;

    @Override
    public ReadOnlyObjectProperty<ModuleStatus> statusProperty() {
        return status.getReadOnlyProperty();
    }

    /** Thread-safe view of the most recently requested status (may lead the FX property slightly). */
    @Override
    public ModuleStatus status() {
        return lastStatus;
    }

    protected void setStatus(ModuleStatus value) {
        lastStatus = value;
        FxThread.run(() -> status.set(value));
    }

    protected void logInfo(String message) {
        log.info(moduleName(), message);
    }

    protected void logError(String message) {
        log.error(moduleName(), message);
    }

    protected void logError(String message, Throwable cause) {
        log.error(moduleName(), message, cause);
    }

    protected static int requirePort(int port) {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("Port must be 0-65535, got " + port);
        }
        return port;
    }
}
