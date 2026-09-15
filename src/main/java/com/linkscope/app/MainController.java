package com.linkscope.app;

import com.linkscope.app.ModuleRegistry.ModuleDescriptor;
import com.linkscope.core.LogEntry;
import com.linkscope.core.LogSink;
import com.linkscope.core.ModuleController;
import com.linkscope.core.Preset;
import com.linkscope.core.PresetStore;
import com.linkscope.core.ui.StatusBadge;
import com.linkscope.core.ui.Toasts;
import javafx.collections.ListChangeListener;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import org.kordamp.ikonli.javafx.FontIcon;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Owns the TabPane of module tabs, the shared log panel, the preset toolbar and the
 * global keyboard shortcuts. Module tabs come from {@link ModuleRegistry}; nothing here
 * knows about any specific transport.
 */
public class MainController {
    private static final String LOG_TAG = "APP";
    private static final String FXML_BASE = "/com/linkscope/fxml/";
    static final String STYLESHEET = Objects.requireNonNull(
            MainController.class.getResource("/com/linkscope/css/linkscope.css")).toExternalForm();

    @FXML private StackPane root;
    @FXML private TabPane tabs;
    @FXML private SplitPane split;
    @FXML private Node logPanel;
    @FXML private LogPanelController logPanelController;
    @FXML private ComboBox<String> presetCombo;
    @FXML private Button loadPresetButton;
    @FXML private Button savePresetButton;
    @FXML private Button deletePresetButton;
    @FXML private ToggleButton logToggle;
    @FXML private Button themeButton;
    @FXML private FontIcon themeIcon;

    private final PresetStore presets = PresetStore.defaultStore();
    private final List<ModuleController> controllers = new ArrayList<>();
    private QuickSwitchPopup quickSwitch;

    @FXML
    private void initialize() {
        Toasts.install(root);
        loadPresetsFromDisk();

        for (ModuleDescriptor d : ModuleRegistry.MODULES) {
            tabs.getTabs().add(createTab(d));
        }
        quickSwitch = new QuickSwitchPopup(tabs, STYLESHEET);

        tabs.getSelectionModel().selectedItemProperty().addListener((obs, old, now) -> refreshPresetCombo());
        refreshPresetCombo();
        loadPresetButton.setOnAction(e -> loadPreset());
        savePresetButton.setOnAction(e -> savePreset());
        deletePresetButton.setOnAction(e -> deletePreset());
        presetCombo.setOnAction(e -> { });

        logToggle.selectedProperty().addListener((obs, old, shown) -> setLogVisible(shown));
        themeButton.setOnAction(e -> ThemeManager.toggle());
        ThemeManager.modeProperty().addListener((obs, old, now) -> updateThemeIcon());
        updateThemeIcon();

        LogSink.get().entries().addListener((ListChangeListener<LogEntry>) change -> {
            while (change.next()) {
                if (change.wasAdded()) {
                    for (LogEntry e : change.getAddedSubList()) {
                        if (e.kind() == LogEntry.Kind.ERROR) {
                            Toasts.error("[" + e.module() + "] " + e.note());
                        }
                    }
                }
            }
        });

        root.sceneProperty().addListener((obs, old, scene) -> {
            if (scene != null) {
                installAccelerators(scene);
            }
        });
        LogSink.get().info(LOG_TAG, "LinkScope ready — " + ModuleRegistry.MODULES.size()
                + " modules. Presets: " + presets.path());
    }

    /** Stops every module. Called from the Application's stop(). */
    public void shutdown() {
        for (ModuleController c : controllers) {
            try {
                c.shutdown();
            } catch (RuntimeException ignored) {
                // best effort on exit
            }
        }
    }

    private Tab createTab(ModuleDescriptor d) {
        URL url = Objects.requireNonNull(getClass().getResource(FXML_BASE + d.fxml()), d.fxml() + " not found");
        FXMLLoader loader = new FXMLLoader(url);
        Parent content;
        try {
            content = loader.load();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load " + d.fxml(), e);
        }
        ModuleController controller = loader.getController();
        if (controller == null) {
            throw new IllegalStateException(d.fxml() + " has no ModuleController");
        }
        controllers.add(controller);

        StatusBadge badge = new StatusBadge();
        badge.setCompact(true);
        badge.statusProperty().bind(controller.statusProperty());
        HBox header = new HBox(6, new FontIcon(d.icon()), new Label(d.title()), badge);
        header.setAlignment(Pos.CENTER_LEFT);

        // Scroll rather than squash when the log panel takes most of the window height.
        ScrollPane scroller = new ScrollPane(content);
        scroller.setFitToWidth(true);
        scroller.setFitToHeight(true);
        scroller.getStyleClass().add("ls-tab-scroller");

        Tab tab = new Tab();
        tab.setId(d.id());
        tab.setGraphic(header);
        tab.setContent(scroller);
        tab.setClosable(false);
        tab.setUserData(controller);
        tab.getProperties().put(QuickSwitchPopup.TITLE_KEY, d.title());
        return tab;
    }

    private ModuleController current() {
        Tab t = tabs.getSelectionModel().getSelectedItem();
        return t == null ? null : (ModuleController) t.getUserData();
    }

    // --- presets -----------------------------------------------------------------

    private void loadPresetsFromDisk() {
        try {
            presets.load();
        } catch (IOException | RuntimeException e) {
            LogSink.get().error("PRESETS", "Could not read " + presets.path(), e);
        }
    }

    private void refreshPresetCombo() {
        ModuleController c = current();
        presetCombo.getItems().clear();
        if (c != null) {
            for (Preset p : presets.forModule(c.moduleId())) {
                presetCombo.getItems().add(p.name());
            }
        }
        presetCombo.getEditor().clear();
        presetCombo.setValue(null);
    }

    private String presetName() {
        String typed = presetCombo.getEditor().getText();
        if (typed == null || typed.isBlank()) {
            typed = presetCombo.getValue();
        }
        return typed == null ? "" : typed.trim();
    }

    private void loadPreset() {
        ModuleController c = current();
        String name = presetName();
        if (c == null || name.isEmpty()) {
            Toasts.warning("Pick or type a preset name first");
            return;
        }
        Preset p = presets.find(c.moduleId(), name);
        if (p == null) {
            Toasts.warning("No preset named \"" + name + "\" for " + c.moduleId().toUpperCase());
            return;
        }
        c.applyFields(presets.resolve(p).fields());
        LogSink.get().info("PRESETS", "Loaded preset \"" + name + "\" into " + c.moduleId().toUpperCase());
        Toasts.success("Loaded preset \"" + name + "\"");
    }

    private void savePreset() {
        ModuleController c = current();
        String name = presetName();
        if (c == null || name.isEmpty()) {
            Toasts.warning("Type a preset name in the box first");
            return;
        }
        presets.put(new Preset(name, c.moduleId(), c.captureFields()));
        if (persistPresets()) {
            refreshPresetCombo();
            presetCombo.setValue(name);
            Toasts.success("Saved preset \"" + name + "\"");
        }
    }

    private void deletePreset() {
        ModuleController c = current();
        String name = presetName();
        if (c == null || name.isEmpty() || !presets.remove(c.moduleId(), name)) {
            Toasts.warning("No preset named \"" + name + "\" to delete");
            return;
        }
        if (persistPresets()) {
            refreshPresetCombo();
            Toasts.info("Deleted preset \"" + name + "\"");
        }
    }

    private boolean persistPresets() {
        try {
            presets.save();
            return true;
        } catch (IOException e) {
            LogSink.get().error("PRESETS", "Could not write " + presets.path(), e);
            return false;
        }
    }

    // --- chrome ------------------------------------------------------------------

    private void setLogVisible(boolean shown) {
        if (shown) {
            if (!split.getItems().contains(logPanel)) {
                split.getItems().add(logPanel);
                split.setDividerPositions(0.6);
            }
        } else {
            split.getItems().remove(logPanel);
        }
    }

    private void updateThemeIcon() {
        boolean dark = ThemeManager.isDark();
        themeIcon.setIconLiteral(dark ? "fth-sun" : "fth-moon");
        themeButton.setTooltip(new javafx.scene.control.Tooltip(dark ? "Switch to light theme" : "Switch to dark theme"));
    }

    private void installAccelerators(Scene scene) {
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.L, KeyCombination.SHORTCUT_DOWN),
                () -> logPanelController.clear());
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.H, KeyCombination.SHORTCUT_DOWN),
                () -> logPanelController.toggleHex());
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.K, KeyCombination.SHORTCUT_DOWN),
                () -> quickSwitch.show(scene.getWindow()));
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.BACK_QUOTE, KeyCombination.SHORTCUT_DOWN),
                () -> logToggle.setSelected(!logToggle.isSelected()));
        KeyCode[] digits = {KeyCode.DIGIT1, KeyCode.DIGIT2, KeyCode.DIGIT3, KeyCode.DIGIT4, KeyCode.DIGIT5,
                KeyCode.DIGIT6, KeyCode.DIGIT7, KeyCode.DIGIT8, KeyCode.DIGIT9};
        for (int i = 0; i < digits.length; i++) {
            final int index = i;
            scene.getAccelerators().put(new KeyCodeCombination(digits[i], KeyCombination.SHORTCUT_DOWN), () -> {
                if (index < tabs.getTabs().size()) {
                    tabs.getSelectionModel().select(index);
                }
            });
        }
    }
}
