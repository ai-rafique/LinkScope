package com.linkscope.app;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;

import java.io.IOException;
import java.net.URL;
import java.util.Objects;

public class LinkScopeApp extends Application {

    public static final String TITLE = "LinkScope";

    private MainController mainController;

    @Override
    public void start(Stage stage) throws IOException {
        ThemeManager.init();

        FXMLLoader loader = new FXMLLoader(Objects.requireNonNull(
                getClass().getResource("/com/linkscope/fxml/main.fxml"),
                "main.fxml not found on classpath"));
        Parent root = loader.load();
        mainController = loader.getController();

        Scene scene = new Scene(root, 1180, 760);
        scene.getStylesheets().add(MainController.STYLESHEET);

        stage.setTitle(TITLE);
        for (int size : new int[] {16, 32, 48, 64, 128, 256}) {
            URL icon = getClass().getResource("/com/linkscope/icons/linkscope-" + size + ".png");
            if (icon != null) {
                stage.getIcons().add(new Image(icon.toExternalForm()));
            }
        }
        stage.setScene(scene);
        stage.setMinWidth(860);
        stage.setMinHeight(560);
        stage.show();
    }

    @Override
    public void stop() {
        if (mainController != null) {
            mainController.shutdown();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
