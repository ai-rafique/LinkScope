package com.linkscope.core;

import javafx.beans.property.ReadOnlyObjectProperty;

import java.util.Map;

/**
 * Implemented by every module tab's FXML controller so the shell can show status,
 * save/load presets and shut the module down uniformly.
 */
public interface ModuleController {

    /** Stable id used as the preset "module" key, e.g. {@code udp}. */
    String moduleId();

    /** Aggregate status shown on the tab header. */
    ReadOnlyObjectProperty<ModuleStatus> statusProperty();

    /** Snapshot of the tab's fields for saving as a preset. Values may contain ${VAR} placeholders. */
    Map<String, String> captureFields();

    /** Applies a (resolved) preset to the tab's fields. Unknown keys are ignored. */
    void applyFields(Map<String, String> fields);

    /** Called once when the application closes. */
    void shutdown();

    /**
     * Optional per-module wording for statuses, e.g. a scanner shows "Scanning…" for
     * CONNECTING and "Done" for CONNECTED. Empty means the default connection wording.
     */
    default Map<ModuleStatus, String> statusLabels() {
        return Map.of();
    }

    /**
     * Takes the user to a place inside the tab, named by the help catalogue: {@code view:stream},
     * {@code focus:needed}, ... Implementations switch the view, focus the control and usually
     * flash it with {@link com.linkscope.core.ui.Highlight}. Return false for unknown targets.
     */
    default boolean navigate(String target) {
        return false;
    }
}
