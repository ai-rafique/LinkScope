package com.linkscope.modules.ssh;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleObjectProperty;

/**
 * The SSH tab's connection, exposed so Tunnels and SFTP can attach to it instead of
 * logging in again. There is exactly one primary connection: the SSH tab's.
 */
public final class SshSessions {
    private static final ObjectProperty<SshService> PRIMARY = new SimpleObjectProperty<>();

    private SshSessions() {
    }

    /** Called once by the SSH tab. */
    public static void setPrimary(SshService service) {
        PRIMARY.set(service);
    }

    public static SshService primary() {
        return PRIMARY.get();
    }

    public static ReadOnlyObjectProperty<SshService> primaryProperty() {
        return PRIMARY;
    }
}
