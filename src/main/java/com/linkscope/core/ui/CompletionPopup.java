package com.linkscope.core.ui;

import javafx.geometry.Bounds;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;

import java.util.List;
import java.util.function.BiFunction;

/**
 * Attaches an inline suggestion list to a text field, the way an IDE or Wireshark's filter
 * bar does: suggestions appear under the field as you type, Down/Up move, Tab or Enter
 * accept, Esc hides. The owner supplies the suggestions for the current text and caret and
 * applies the chosen one, so the popup knows nothing about the language.
 */
public final class CompletionPopup<S> {

    /** How a suggestion is rendered: a label and a muted detail. */
    public interface Renderer<S> {
        String label(S s);

        String detail(S s);
    }

    /** Applies a suggestion: given text and caret, returns the new text and caret. */
    public interface Applier<S> {
        String[] apply(String text, int caret, S s);
    }

    private final TextField field;
    private final BiFunction<String, Integer, List<S>> suggester;
    private final Applier<S> applier;
    private final Popup popup = new Popup();
    private final ListView<S> list = new ListView<>();
    private boolean suppress;

    public CompletionPopup(TextField field, BiFunction<String, Integer, List<S>> suggester, Renderer<S> renderer, Applier<S> applier,
                           String stylesheet) {
        this.field = field;
        this.suggester = suggester;
        this.applier = applier;
        list.setCellFactory(v -> new ListCell<>() {
            private final Label label = new Label();
            private final Label detail = new Label();
            private final HBox box;

            {
                label.getStyleClass().add("ls-completion-label");
                detail.getStyleClass().addAll("text-muted", "small");
                Region spacer = new Region();
                HBox.setHgrow(spacer, Priority.ALWAYS);
                box = new HBox(10, label, spacer, detail);
            }

            @Override
            protected void updateItem(S item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    return;
                }
                label.setText(renderer.label(item));
                detail.setText(renderer.detail(item));
                setGraphic(box);
            }
        });
        list.setPrefHeight(10 * 26 + 2);
        list.setFocusTraversable(false);
        VBox box = new VBox(list);
        box.getStyleClass().add("ls-completion");
        box.setPrefWidth(520);
        if (stylesheet != null) {
            box.getStylesheets().add(stylesheet);
        }
        popup.getContent().add(box);
        popup.setAutoHide(true);
        popup.setHideOnEscape(true);

        field.textProperty().addListener((obs, old, now) -> {
            if (!suppress && field.isFocused()) {
                refresh();
            }
        });
        field.caretPositionProperty().addListener((obs, old, now) -> {
            if (!suppress && popup.isShowing()) {
                refresh();
            }
        });
        field.focusedProperty().addListener((obs, old, now) -> {
            if (!now) {
                popup.hide();
            }
        });
        field.addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);
        list.setOnMouseClicked(e -> accept());
    }

    /** Shows suggestions for the current text even before typing (bound to Ctrl+Space by the owner). */
    public void open() {
        refresh();
    }

    private void refresh() {
        List<S> items = suggester.apply(field.getText(), field.getCaretPosition());
        if (items.isEmpty()) {
            popup.hide();
            return;
        }
        list.getItems().setAll(items);
        list.getSelectionModel().select(0);
        list.setPrefHeight(Math.min(10, items.size()) * 26 + 2);
        if (!popup.isShowing()) {
            Bounds b = field.localToScreen(field.getBoundsInLocal());
            if (b == null) {
                return;
            }
            popup.show(field, b.getMinX(), b.getMaxY() + 2);
        }
    }

    private void onKey(KeyEvent e) {
        if (e.isControlDown() && e.getCode() == KeyCode.SPACE) {
            open();
            e.consume();
            return;
        }
        if (!popup.isShowing()) {
            return;
        }
        switch (e.getCode()) {
            case DOWN -> {
                move(1);
                e.consume();
            }
            case UP -> {
                move(-1);
                e.consume();
            }
            case TAB, ENTER -> {
                accept();
                e.consume();
            }
            case ESCAPE -> {
                popup.hide();
                e.consume();
            }
            default -> { }
        }
    }

    private void move(int delta) {
        int size = list.getItems().size();
        if (size == 0) {
            return;
        }
        int idx = list.getSelectionModel().getSelectedIndex();
        idx = ((idx + delta) % size + size) % size;
        list.getSelectionModel().select(idx);
        list.scrollTo(idx);
    }

    private void accept() {
        S s = list.getSelectionModel().getSelectedItem();
        if (s == null) {
            popup.hide();
            return;
        }
        String[] result = applier.apply(field.getText(), field.getCaretPosition(), s);
        suppress = true;
        field.setText(result[0]);
        field.positionCaret(Integer.parseInt(result[1]));
        suppress = false;
        // keep suggesting for the next position (operator after field, value after operator)
        refresh();
    }

    public boolean isShowing() {
        return popup.isShowing();
    }
}
