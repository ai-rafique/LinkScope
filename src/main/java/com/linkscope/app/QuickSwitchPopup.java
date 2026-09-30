package com.linkscope.app;

import com.linkscope.core.help.HelpEntry;
import com.linkscope.core.help.HelpIndex;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Ctrl+K palette: type a feature name or a question ("which ports are free", "rumble"),
 * pick a result, land on the tab with the control highlighted. Empty query shows the whole
 * map of the app. Ranking comes from {@link HelpIndex} and is deterministic.
 */
final class QuickSwitchPopup {
    private static final double WIDTH = 560;

    private final HelpIndex index;
    private final Consumer<HelpEntry> navigate;
    private final Popup popup = new Popup();
    private final TextField filter = new TextField();
    private final ListView<HelpEntry> list = new ListView<>();
    private final Label hint = new Label();

    QuickSwitchPopup(HelpIndex index, Consumer<HelpEntry> navigate, String stylesheet) {
        this.index = index;
        this.navigate = navigate;
        filter.setPromptText("Find a feature or ask where something is…  (rumble, which ports are free, crc table)");
        list.setPrefHeight(340);
        list.setCellFactory(v -> new EntryCell());
        hint.getStyleClass().addAll("text-muted", "small");
        VBox box = new VBox(8, filter, list, hint);
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
        String q = text == null ? "" : text.trim();
        List<HelpEntry> rows = new ArrayList<>();
        if (q.isEmpty()) {
            rows.addAll(index.all());
            hint.setText(rows.size() + " places · type to search, Enter to go, Esc to close");
        } else {
            for (HelpIndex.Hit h : index.search(q, 12)) {
                rows.add(h.entry());
            }
            hint.setText(rows.isEmpty() ? "Nothing matches; try another word (module names always work)"
                    : rows.size() + " match(es) · Enter opens the first");
        }
        list.getItems().setAll(rows);
        if (!rows.isEmpty()) {
            list.getSelectionModel().select(0);
            list.scrollTo(0);
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
        HelpEntry entry = list.getSelectionModel().getSelectedItem();
        popup.hide();
        if (entry != null) {
            navigate.accept(entry);
        }
    }

    /** Title on the left, path on the right, one-line description underneath. */
    private static final class EntryCell extends ListCell<HelpEntry> {
        private final Label title = new Label();
        private final Label path = new Label();
        private final Label about = new Label();
        private final VBox box;

        EntryCell() {
            title.getStyleClass().add("ls-help-title");
            path.getStyleClass().addAll("text-muted", "small");
            about.getStyleClass().addAll("text-muted", "small");
            about.setWrapText(true);
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            HBox top = new HBox(8, title, spacer, path);
            box = new VBox(2, top, about);
            box.getStyleClass().add("ls-help-row");
        }

        @Override
        protected void updateItem(HelpEntry e, boolean empty) {
            super.updateItem(e, empty);
            if (empty || e == null) {
                setGraphic(null);
                setText(null);
                return;
            }
            title.setText(e.title());
            path.setText(e.path());
            about.setText(e.about());
            setGraphic(box);
            setText(null);
        }
    }
}
