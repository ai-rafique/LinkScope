package com.linkscope.core.ui;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.VBox;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

/**
 * Controller for {@code history-list.fxml}: a newest-first list of things the user did
 * (HTTP requests, WebSocket URLs and frames) that can be clicked to restore. Shared by
 * the HTTP and WebSocket tabs via {@code <fx:include fx:id="history" .../>}.
 */
public class HistoryListController {
    public static final int LIMIT = 100;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** One history row: a short label (method + URL, frame type), an optional detail, and the data to restore. */
    public record Item(LocalTime at, String label, String detail, Object data) {
        public static Item now(String label, String detail, Object data) {
            return new Item(LocalTime.now(), label, detail == null ? "" : detail, data);
        }
    }

    @FXML private Label titleLabel;
    @FXML private ListView<Item> list;
    @FXML private Button clearButton;

    private final ObservableList<Item> items = FXCollections.observableArrayList();
    private Consumer<Item> onSelect = item -> { };

    @FXML
    private void initialize() {
        list.setItems(items);
        list.setPlaceholder(new Label("Nothing yet. Requests you send show up here; click one to restore it."));
        list.setCellFactory(v -> new ListCell<>() {
            private final Label title = new Label();
            private final Label detail = new Label();
            private final VBox box = new VBox(2, title, detail);

            {
                detail.getStyleClass().addAll("text-muted", "small");
                title.getStyleClass().add("history-title");
            }

            @Override
            protected void updateItem(Item item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    return;
                }
                title.setText(TIME.format(item.at()) + "  " + item.label());
                detail.setText(item.detail());
                detail.setVisible(!item.detail().isEmpty());
                detail.setManaged(!item.detail().isEmpty());
                setGraphic(box);
            }
        });
        list.setOnMouseClicked(e -> {
            Item it = list.getSelectionModel().getSelectedItem();
            if (it != null) {
                onSelect.accept(it);
            }
        });
        clearButton.setOnAction(e -> items.clear());
    }

    public void setTitle(String title) {
        titleLabel.setText(title);
    }

    public void setOnSelect(Consumer<Item> handler) {
        this.onSelect = handler == null ? item -> { } : handler;
    }

    /** Inserts at the top; drops duplicates with the same label so the list stays useful. */
    public void add(Item item) {
        items.removeIf(existing -> existing.label().equals(item.label()) && existing.detail().equals(item.detail()));
        items.add(0, item);
        while (items.size() > LIMIT) {
            items.remove(items.size() - 1);
        }
    }

    public ObservableList<Item> items() {
        return items;
    }
}
