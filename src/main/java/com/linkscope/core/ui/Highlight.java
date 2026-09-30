package com.linkscope.core.ui;

import javafx.animation.PauseTransition;
import javafx.scene.Node;
import javafx.scene.control.Control;
import javafx.util.Duration;

/** Draws attention to a control for a moment after the help palette navigated to it. */
public final class Highlight {
    private static final String STYLE = "ls-highlight";

    private Highlight() {
    }

    /** Adds a glow for two seconds and, for controls, requests focus. */
    public static void flash(Node node) {
        if (node == null) {
            return;
        }
        if (!node.getStyleClass().contains(STYLE)) {
            node.getStyleClass().add(STYLE);
        }
        if (node instanceof Control c) {
            c.requestFocus();
        }
        PauseTransition off = new PauseTransition(Duration.seconds(2));
        off.setOnFinished(e -> node.getStyleClass().remove(STYLE));
        off.play();
    }
}
