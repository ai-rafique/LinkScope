package com.linkscope.core.ui;

import com.linkscope.core.ModuleStatus;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import org.kordamp.ikonli.javafx.FontIcon;

/**
 * Icon + text status indicator. The icon shape changes per status (never color alone),
 * and a tooltip repeats the label so the compact (icon-only) form stays accessible.
 * Usable from FXML as {@code <StatusBadge fx:id="status"/>}.
 */
public class StatusBadge extends HBox {
    private final FontIcon icon = new FontIcon("fth-circle");
    private final Label label = new Label();
    private final Tooltip tooltip = new Tooltip();
    private final ObjectProperty<ModuleStatus> status = new SimpleObjectProperty<>(this, "status", ModuleStatus.DISCONNECTED);
    private boolean compact;

    public StatusBadge() {
        getStyleClass().add("status-badge");
        setSpacing(6);
        setAlignment(Pos.CENTER_LEFT);
        icon.getStyleClass().add("status-icon");
        getChildren().addAll(icon, label);
        Tooltip.install(this, tooltip);
        status.addListener((obs, old, now) -> apply(now));
        apply(ModuleStatus.DISCONNECTED);
    }

    public ObjectProperty<ModuleStatus> statusProperty() {
        return status;
    }

    public ModuleStatus getStatus() {
        return status.get();
    }

    public void setStatus(ModuleStatus value) {
        status.set(value);
    }

    /** Icon only; the text moves into the tooltip. */
    public void setCompact(boolean compact) {
        this.compact = compact;
        label.setVisible(!compact);
        label.setManaged(!compact);
    }

    public boolean isCompact() {
        return compact;
    }

    private void apply(ModuleStatus s) {
        if (s == null) {
            s = ModuleStatus.DISCONNECTED;
        }
        getStyleClass().removeAll("status-disconnected", "status-connecting", "status-connected", "status-error");
        switch (s) {
            case CONNECTED -> {
                icon.setIconLiteral("fth-check-circle");
                getStyleClass().add("status-connected");
            }
            case CONNECTING -> {
                icon.setIconLiteral("fth-loader");
                getStyleClass().add("status-connecting");
            }
            case ERROR -> {
                icon.setIconLiteral("fth-alert-triangle");
                getStyleClass().add("status-error");
            }
            default -> {
                icon.setIconLiteral("fth-circle");
                getStyleClass().add("status-disconnected");
            }
        }
        label.setText(s.label());
        tooltip.setText(s.label());
    }
}
