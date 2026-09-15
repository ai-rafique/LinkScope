package com.linkscope.app;

import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/** Ctrl+K quick module switcher: type to filter, Up/Down to move, Enter to jump, Esc to close. */
final class QuickSwitchPopup {
    private static final double WIDTH = 360;

    /** A filtered row: the module title and its index in the registry order. */
    private record Row(String title, int index) {
        @Override
        public String toString() {
            return title;
        }
    }

    private final Supplier<List<String>> titles;
    private final IntConsumer select;
    private final Popup popup = new Popup();
    private final TextField filter = new TextField();
    private final ListView<Row> list = new ListView<>();

    QuickSwitchPopup(Supplier<List<String>> titles, IntConsumer select, String stylesheet) {
        this.titles = titles;
        this.select = select;
        filter.setPromptText("Jump to module…");
        list.setPrefHeight(240);
        VBox box = new VBox(8, filter, list);
        box.getStyleClass().add("quick-switch");
        box.setPrefWidth(WIDTH);
        box.getStylesheets().add(stylesheet);

        filter.textProperty().addListener((obs, old, now) -> refilter(now));
        filter.setOnKeyPressed(e -> {
            switch (e.getCode()) {
                case DOWN -> { move(1); e.consume(); }
                case UP -> { move(-1); e.consume(); }
                case ENTER -> { choose(); e.consume(); }
                case ESCAPE -> { popup.hide(); e.consume(); }
                default -> { }
            }
        });
        list.setOnMouseClicked(e -> choose());
        list.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                choose();
            } else if (e.getCode() == KeyCode.ESCAPE) {
                popup.hide();
            }
        });
        popup.setAutoHide(true);
        popup.getContent().add(box);
    }

    void show(Window owner) {
        if (popup.isShowing()) {
            popup.hide();
            return;
        }
        filter.clear();
        refilter("");
        double x = owner.getX() + (owner.getWidth() - WIDTH) / 2;
        double y = owner.getY() + 96;
        popup.show(owner, x, y);
        filter.requestFocus();
    }

    private void refilter(String text) {
        String q = text == null ? "" : text.trim().toLowerCase();
        List<Row> rows = new ArrayList<>();
        List<String> all = titles.get();
        for (int i = 0; i < all.size(); i++) {
            if (q.isEmpty() || all.get(i).toLowerCase().contains(q)) {
                rows.add(new Row(all.get(i), i));
            }
        }
        list.getItems().setAll(rows);
        if (!rows.isEmpty()) {
            list.getSelectionModel().select(0);
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

    private void choose() {
        Row row = list.getSelectionModel().getSelectedItem();
        popup.hide();
        if (row != null) {
            select.accept(row.index());
        }
    }
}
