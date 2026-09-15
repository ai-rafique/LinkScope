package com.linkscope.app;

import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;
import javafx.stage.Window;

import java.util.Objects;

/** Ctrl+K quick tab switcher: type to filter, Up/Down to move, Enter to jump, Esc to close. */
final class QuickSwitchPopup {
    static final String TITLE_KEY = "ls.title";
    private static final double WIDTH = 360;

    private final TabPane tabs;
    private final Popup popup = new Popup();
    private final TextField filter = new TextField();
    private final ListView<Tab> list = new ListView<>();

    QuickSwitchPopup(TabPane tabs, String stylesheet) {
        this.tabs = tabs;
        filter.setPromptText("Jump to tab…");
        list.setPrefHeight(200);
        list.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(Tab item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : titleOf(item));
            }
        });
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

    static String titleOf(Tab tab) {
        Object t = tab.getProperties().get(TITLE_KEY);
        return t != null ? t.toString() : Objects.toString(tab.getText(), "");
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
        list.getItems().clear();
        for (Tab tab : tabs.getTabs()) {
            if (q.isEmpty() || titleOf(tab).toLowerCase().contains(q)) {
                list.getItems().add(tab);
            }
        }
        if (!list.getItems().isEmpty()) {
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
        Tab tab = list.getSelectionModel().getSelectedItem();
        popup.hide();
        if (tab != null) {
            tabs.getSelectionModel().select(tab);
            tab.getContent().requestFocus();
        }
    }
}
