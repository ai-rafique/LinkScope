package com.linkscope.core.ui;

import atlantafx.base.controls.Notification;
import atlantafx.base.theme.Styles;
import atlantafx.base.util.Animations;
import com.linkscope.core.FxThread;
import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.kordamp.ikonli.javafx.FontIcon;

/**
 * Non-blocking toast notifications stacked in the top-right corner of the main window.
 * Never a modal dialog: a live capture session must not be interrupted.
 */
public final class Toasts {
    private static final Duration AUTO_HIDE = Duration.seconds(5);
    private static final int MAX_VISIBLE = 4;
    private static VBox stack;

    private Toasts() {
    }

    /** Installs the toast layer into the root StackPane. Call once from the shell. */
    public static void install(StackPane host) {
        stack = new VBox(8);
        stack.setAlignment(Pos.TOP_RIGHT);
        stack.setPickOnBounds(false);
        stack.setMaxWidth(Region.USE_PREF_SIZE);
        stack.setMaxHeight(Region.USE_PREF_SIZE);
        StackPane.setAlignment(stack, Pos.TOP_RIGHT);
        StackPane.setMargin(stack, new Insets(56, 16, 16, 16));
        host.getChildren().add(stack);
    }

    public static void info(String message) {
        show(message, "fth-info", Styles.ACCENT);
    }

    public static void success(String message) {
        show(message, "fth-check-circle", Styles.SUCCESS);
    }

    public static void warning(String message) {
        show(message, "fth-alert-circle", Styles.WARNING);
    }

    public static void error(String message) {
        show(message, "fth-alert-triangle", Styles.DANGER);
    }

    private static void show(String message, String icon, String style) {
        if (stack == null) {
            return;
        }
        FxThread.run(() -> {
            Notification n = new Notification(message, new FontIcon(icon));
            n.getStyleClass().addAll(style, Styles.ELEVATED_1, "toast");
            n.setPrefWidth(380);
            n.setMaxWidth(380);
            PauseTransition hide = new PauseTransition(AUTO_HIDE);
            hide.setOnFinished(e -> dismiss(n));
            n.setOnClose(e -> {
                hide.stop();
                dismiss(n);
            });
            while (stack.getChildren().size() >= MAX_VISIBLE) {
                stack.getChildren().remove(0);
            }
            stack.getChildren().add(n);
            Animations.slideInRight(n, Duration.millis(200)).playFromStart();
            hide.playFromStart();
        });
    }

    private static void dismiss(Notification n) {
        var out = Animations.fadeOut(n, Duration.millis(150));
        out.setOnFinished(e -> stack.getChildren().remove(n));
        out.playFromStart();
    }
}
