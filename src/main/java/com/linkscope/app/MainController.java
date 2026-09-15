package com.linkscope.app;

import com.linkscope.app.ModuleRegistry.ModuleDescriptor;
import com.linkscope.core.LogEntry;
import com.linkscope.core.LogSink;
import com.linkscope.core.ModuleController;
import com.linkscope.core.Preset;
import com.linkscope.core.PresetStore;
import com.linkscope.core.ui.StatusBadge;
import com.linkscope.core.ui.Toasts;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
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
import javafx.scene.control.Separator;
import javafx.scene.control.SplitPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.kordamp.ikonli.javafx.FontIcon;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.prefs.Preferences;

/**
 * Owns the collapsible module sidebar, the content area showing the selected module,
 * the shared log panel, the preset toolbar and the global keyboard shortcuts. Modules
 * come from {@link ModuleRegistry}; nothing here knows about any specific transport.
 */
public class MainController {
    private static final String LOG_TAG = "APP";
    private static final String FXML_BASE = "/com/linkscope/fxml/";
    private static final double SIDEBAR_EXPANDED = 200;
    private static final double SIDEBAR_COLLAPSED = 52;
    private static final Preferences PREFS = Preferences.userRoot().node("com/linkscope");
    static final String STYLESHEET = Objects.requireNonNull(
            MainController.class.getResource("/com/linkscope/css/linkscope.css")).toExternalForm();

    /** One loaded module: its descriptor, controller, content node and sidebar button. */
    record ModuleEntry(ModuleDescriptor descriptor, ModuleController controller, Node content, ToggleButton button,
                       Label label, StatusBadge badge, Region spacer) {
    }

    @FXML private StackPane root;
    @FXML private Button sidebarButton;
    @FXML private Label moduleTitle;
    @FXML private ScrollPane sidebarScroller;
    @FXML private VBox sidebar;
    @FXML private StackPane contentPane;
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
    private final List<ModuleEntry> modules = new ArrayList<>();
    private final List<Label> groupHeaders = new ArrayList<>();
    private final List<Separator> groupSeparators = new ArrayList<>();
    private final ToggleGroup navGroup = new ToggleGroup();
    private ModuleEntry current;
    private boolean sidebarCollapsed;
    private QuickSwitchPopup quickSwitch;

    @FXML
    private void initialize() {
        Toasts.install(root);
        loadPresetsFromDisk();
        buildSidebar();
        quickSwitch = new QuickSwitchPopup(this::moduleTitles, this::select, STYLESHEET);

        navGroup.selectedToggleProperty().addListener((obs, old, now) -> {
            if (now == null && old != null) {
                old.setSelected(true); // always keep one module selected
            }
        });
        sidebarButton.setOnAction(e -> setSidebarCollapsed(!sidebarCollapsed, true));
        setSidebarCollapsed(PREFS.getBoolean("sidebarCollapsed", false), false);
        select(indexOf(PREFS.get("module", ModuleRegistry.MODULES.get(0).id())));

        loadPresetButton.setOnAction(e -> loadPreset());
        savePresetButton.setOnAction(e -> savePreset());
        deletePresetButton.setOnAction(e -> deletePreset());

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
        LogSink.get().info(LOG_TAG, "LinkScope ready — " + modules.size() + " modules. Presets: " + presets.path());
    }

    /** Stops every module. Called from the Application's stop(). */
    public void shutdown() {
        for (ModuleEntry m : modules) {
            try {
                m.controller().shutdown();
            } catch (RuntimeException ignored) {
                // best effort on exit
            }
        }
    }

    // --- sidebar -----------------------------------------------------------------

    private void buildSidebar() {
        String lastGroup = null;
        for (ModuleDescriptor d : ModuleRegistry.MODULES) {
            if (!d.group().equals(lastGroup)) {
                if (lastGroup != null) {
                    Separator sep = new Separator();
                    sep.getStyleClass().add("ls-nav-separator");
                    groupSeparators.add(sep);
                    sidebar.getChildren().add(sep);
                }
                Label header = new Label(d.group().toUpperCase());
                header.getStyleClass().add("ls-nav-group");
                groupHeaders.add(header);
                sidebar.getChildren().add(header);
                lastGroup = d.group();
            }
            ModuleEntry entry = loadModule(d);
            modules.add(entry);
            sidebar.getChildren().add(entry.button());
        }
    }

    private ModuleEntry loadModule(ModuleDescriptor d) {
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
        // Scroll rather than squash when the log panel takes most of the window height.
        ScrollPane scroller = new ScrollPane(content);
        scroller.setFitToWidth(true);
        scroller.setFitToHeight(true);
        scroller.getStyleClass().add("ls-tab-scroller");

        StatusBadge badge = new StatusBadge();
        badge.setCompact(true);
        badge.setLabels(controller.statusLabels());
        badge.statusProperty().bind(controller.statusProperty());
        FontIcon icon = new FontIcon(d.icon());
        icon.getStyleClass().add("ls-nav-icon");
        Label label = new Label(d.title());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox graphic = new HBox(10, icon, label, spacer, badge);
        graphic.setAlignment(Pos.CENTER_LEFT);

        ToggleButton button = new ToggleButton();
        button.setGraphic(graphic);
        button.setToggleGroup(navGroup);
        button.setMaxWidth(Double.MAX_VALUE);
        button.setAlignment(Pos.CENTER_LEFT);
        button.getStyleClass().add("ls-nav-button");
        button.setFocusTraversable(false);
        Tooltip tip = new Tooltip();
        tip.textProperty().bind(javafx.beans.binding.Bindings.createStringBinding(
                () -> d.title() + " — " + badge.getStatusText(), controller.statusProperty()));
        button.setTooltip(tip);

        ModuleEntry entry = new ModuleEntry(d, controller, scroller, button, label, badge, spacer);
        button.setOnAction(e -> select(entry));
        return entry;
    }

    private void setSidebarCollapsed(boolean collapsed, boolean animate) {
        sidebarCollapsed = collapsed;
        PREFS.putBoolean("sidebarCollapsed", collapsed);
        for (ModuleEntry m : modules) {
            m.label().setVisible(!collapsed);
            m.label().setManaged(!collapsed);
            m.spacer().setVisible(!collapsed);
            m.spacer().setManaged(!collapsed);
            m.badge().setVisible(!collapsed);
            m.badge().setManaged(!collapsed);
            m.button().setAlignment(collapsed ? Pos.CENTER : Pos.CENTER_LEFT);
        }
        for (Label h : groupHeaders) {
            h.setVisible(!collapsed);
            h.setManaged(!collapsed);
        }
        for (Separator s : groupSeparators) {
            s.setVisible(collapsed);
            s.setManaged(collapsed);
        }
        sidebar.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("collapsed"), collapsed);
        double target = collapsed ? SIDEBAR_COLLAPSED : SIDEBAR_EXPANDED;
        sidebarScroller.setMinWidth(Region.USE_PREF_SIZE);
        sidebarScroller.setMaxWidth(Region.USE_PREF_SIZE);
        if (animate) {
            Timeline t = new Timeline(new KeyFrame(Duration.millis(150),
                    new KeyValue(sidebarScroller.prefWidthProperty(), target)));
            t.play();
        } else {
            sidebarScroller.setPrefWidth(target);
        }
    }

    // --- selection ---------------------------------------------------------------

    private int indexOf(String id) {
        for (int i = 0; i < modules.size(); i++) {
            if (modules.get(i).descriptor().id().equals(id)) {
                return i;
            }
        }
        return 0;
    }

    private List<String> moduleTitles() {
        List<String> titles = new ArrayList<>();
        for (ModuleEntry m : modules) {
            titles.add(m.descriptor().title());
        }
        return titles;
    }

    private void select(int index) {
        if (index >= 0 && index < modules.size()) {
            select(modules.get(index));
        }
    }

    private void select(ModuleEntry entry) {
        current = entry;
        entry.button().setSelected(true);
        contentPane.getChildren().setAll(entry.content());
        moduleTitle.setText("/ " + entry.descriptor().title());
        PREFS.put("module", entry.descriptor().id());
        refreshPresetCombo();
    }

    private ModuleController currentController() {
        return current == null ? null : current.controller();
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
        ModuleController c = currentController();
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
        ModuleController c = currentController();
        String name = presetName();
        if (c == null || name.isEmpty()) {
            Toasts.warning("Pick or type a preset name first");
            return;
        }
        Preset p = presets.find(c.moduleId(), name);
        if (p == null) {
            Toasts.warning("No preset named \"" + name + "\" for " + current.descriptor().title());
            return;
        }
        c.applyFields(presets.resolve(p).fields());
        LogSink.get().info("PRESETS", "Loaded preset \"" + name + "\" into " + current.descriptor().title());
        Toasts.success("Loaded preset \"" + name + "\"");
    }

    private void savePreset() {
        ModuleController c = currentController();
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
        ModuleController c = currentController();
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
        themeButton.setTooltip(new Tooltip(dark ? "Switch to light theme" : "Switch to dark theme"));
    }

    private void installAccelerators(Scene scene) {
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.L, KeyCombination.SHORTCUT_DOWN),
                () -> logPanelController.clear());
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.H, KeyCombination.SHORTCUT_DOWN),
                () -> logPanelController.toggleHex());
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.I, KeyCombination.SHORTCUT_DOWN),
                () -> logPanelController.toggleInspector());
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.K, KeyCombination.SHORTCUT_DOWN),
                () -> quickSwitch.show(scene.getWindow()));
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.B, KeyCombination.SHORTCUT_DOWN),
                () -> setSidebarCollapsed(!sidebarCollapsed, true));
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.BACK_QUOTE, KeyCombination.SHORTCUT_DOWN),
                () -> logToggle.setSelected(!logToggle.isSelected()));
        KeyCode[] digits = {KeyCode.DIGIT1, KeyCode.DIGIT2, KeyCode.DIGIT3, KeyCode.DIGIT4, KeyCode.DIGIT5,
                KeyCode.DIGIT6, KeyCode.DIGIT7, KeyCode.DIGIT8, KeyCode.DIGIT9};
        for (int i = 0; i < digits.length; i++) {
            final int index = i;
            scene.getAccelerators().put(new KeyCodeCombination(digits[i], KeyCombination.SHORTCUT_DOWN), () -> select(index));
        }
    }
}
