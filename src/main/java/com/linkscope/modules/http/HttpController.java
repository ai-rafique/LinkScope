package com.linkscope.modules.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkscope.core.FxThread;
import com.linkscope.core.LogSink;
import com.linkscope.core.ModuleController;
import com.linkscope.core.ModuleStatus;
import com.linkscope.core.ui.Fields;
import com.linkscope.core.ui.HistoryListController;
import com.linkscope.core.ui.HistoryListController.Item;
import com.linkscope.core.ui.StatusBadge;
import com.linkscope.core.ui.Toasts;
import com.linkscope.modules.http.HttpRequestSpec.AuthType;
import com.linkscope.modules.http.HttpRequestSpec.BodyType;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.LinkedHashMap;
import java.util.Map;

/** HTTP tester tab: request form, response viewer, and the shared history list. */
public class HttpController implements ModuleController {
    public static final String ID = "http";
    private static final ObjectMapper JSON = new ObjectMapper();

    @FXML private ComboBox<String> methodCombo;
    @FXML private TextField urlField;
    @FXML private Button sendButton;
    @FXML private StatusBadge statusBadge;
    @FXML private TextArea headersArea;
    @FXML private ComboBox<BodyType> bodyTypeCombo;
    @FXML private TextArea bodyArea;
    @FXML private ComboBox<AuthType> authCombo;
    @FXML private HBox basicBox;
    @FXML private TextField usernameField;
    @FXML private PasswordField passwordField;
    @FXML private HBox bearerBox;
    @FXML private PasswordField tokenField;
    @FXML private TextField timeoutField;
    @FXML private CheckBox followRedirectsCheck;

    @FXML private Label responseStatusLabel;
    @FXML private ToggleButton prettyToggle;
    @FXML private TextArea responseBodyArea;
    @FXML private TextArea responseHeadersArea;
    @FXML private HistoryListController historyController;

    private final HttpService service = new HttpService();
    private HttpExchange lastExchange;

    @FXML
    private void initialize() {
        statusBadge.statusProperty().bind(service.statusProperty());
        methodCombo.getItems().setAll(HttpRequestSpec.METHODS);
        methodCombo.setValue("GET");
        bodyTypeCombo.getItems().setAll(BodyType.values());
        bodyTypeCombo.setValue(BodyType.NONE);
        authCombo.getItems().setAll(AuthType.values());
        authCombo.setValue(AuthType.NONE);
        authCombo.valueProperty().addListener((obs, old, now) -> showAuthFields(now));
        showAuthFields(AuthType.NONE);
        bodyArea.disableProperty().bind(bodyTypeCombo.valueProperty().isEqualTo(BodyType.NONE));

        urlField.setOnAction(e -> send());
        bodyArea.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ENTER && e.isShortcutDown()) {
                send();
                e.consume();
            }
        });
        sendButton.setOnAction(e -> send());
        service.statusProperty().addListener((obs, old, now) -> updateSendButton(now));
        updateSendButton(service.statusProperty().get());
        prettyToggle.selectedProperty().addListener((obs, old, now) -> renderBody());

        historyController.setTitle("History");
        historyController.setOnSelect(this::restore);
    }

    private void showAuthFields(AuthType type) {
        boolean basic = type == AuthType.BASIC;
        boolean bearer = type == AuthType.BEARER;
        basicBox.setVisible(basic);
        basicBox.setManaged(basic);
        bearerBox.setVisible(bearer);
        bearerBox.setManaged(bearer);
    }

    private void updateSendButton(ModuleStatus s) {
        boolean busy = s == ModuleStatus.CONNECTING;
        sendButton.setText(busy ? "Cancel" : "Send");
        sendButton.setGraphic(new FontIcon(busy ? "fth-x" : "fth-send"));
    }

    private HttpRequestSpec buildSpec() {
        String url = Fields.requireText(urlField, "URL");
        int timeout = Fields.intValue(timeoutField, "Timeout", 100, 600_000);
        AuthType auth = authCombo.getValue();
        String secret = auth == AuthType.BASIC ? passwordField.getText() : auth == AuthType.BEARER ? tokenField.getText() : "";
        return new HttpRequestSpec(methodCombo.getValue(), url, HttpRequestSpec.parseHeaders(headersArea.getText()),
                bodyTypeCombo.getValue(), bodyArea.getText(), auth, usernameField.getText(), secret, timeout,
                followRedirectsCheck.isSelected());
    }

    private void send() {
        if (service.statusProperty().get() == ModuleStatus.CONNECTING) {
            service.stop();
            return;
        }
        HttpRequestSpec spec;
        try {
            spec = buildSpec();
            if (spec.bodyType() == BodyType.JSON && spec.hasBody() && !spec.body().isBlank()) {
                JSON.readTree(spec.body());
            }
        } catch (IllegalArgumentException ex) {
            Toasts.error(ex.getMessage());
            return;
        } catch (java.io.IOException ex) {
            Toasts.error("Body is not valid JSON: " + ex.getMessage().split("\\R")[0]);
            return;
        }
        responseStatusLabel.setText("Sending…");
        service.execute(spec).thenAccept(exchange -> FxThread.run(() -> show(exchange)));
    }

    private void show(HttpExchange exchange) {
        lastExchange = exchange;
        if (exchange.failed()) {
            responseStatusLabel.setText(exchange.statusLine() + "  ·  " + exchange.durationMs() + " ms");
            responseBodyArea.setText("");
            responseHeadersArea.setText("");
        } else {
            int size = exchange.body() == null ? 0 : exchange.body().length;
            responseStatusLabel.setText(exchange.statusLine() + "  ·  " + exchange.durationMs() + " ms  ·  "
                    + size + " B" + (exchange.contentType().isEmpty() ? "" : "  ·  " + exchange.contentType()));
            responseHeadersArea.setText(exchange.formatHeaders());
            renderBody();
        }
        HttpRequestSpec r = exchange.request();
        historyController.add(Item.now(r.method() + " " + r.url(),
                exchange.failed() ? exchange.statusLine() : exchange.status() + " · " + exchange.durationMs() + " ms", r));
    }

    private void renderBody() {
        if (lastExchange == null || lastExchange.failed()) {
            return;
        }
        String text = lastExchange.bodyText();
        if (prettyToggle.isSelected() && (lastExchange.isJson() || looksLikeJson(text))) {
            try {
                JsonNode node = JSON.readTree(text);
                text = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(node);
            } catch (java.io.IOException ignored) {
                // show raw
            }
        }
        responseBodyArea.setText(text);
    }

    private static boolean looksLikeJson(String text) {
        String t = text.trim();
        return (t.startsWith("{") && t.endsWith("}")) || (t.startsWith("[") && t.endsWith("]"));
    }

    private void restore(Item item) {
        if (item.data() instanceof HttpRequestSpec r) {
            applySpec(r);
        }
    }

    private void applySpec(HttpRequestSpec r) {
        methodCombo.setValue(r.method());
        urlField.setText(r.url());
        headersArea.setText(HttpRequestSpec.formatHeaders(r.headers()));
        bodyTypeCombo.setValue(r.bodyType());
        bodyArea.setText(r.body());
        authCombo.setValue(r.auth());
        usernameField.setText(r.username());
        passwordField.setText(r.auth() == AuthType.BASIC ? r.secret() : "");
        tokenField.setText(r.auth() == AuthType.BEARER ? r.secret() : "");
        timeoutField.setText(String.valueOf(r.timeoutMs()));
        followRedirectsCheck.setSelected(r.followRedirects());
    }

    // --- ModuleController -----------------------------------------------------------

    @Override
    public String moduleId() {
        return ID;
    }

    @Override
    public ReadOnlyObjectProperty<ModuleStatus> statusProperty() {
        return service.statusProperty();
    }

    @Override
    public Map<String, String> captureFields() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("method", methodCombo.getValue());
        m.put("url", Fields.text(urlField));
        m.put("headers", headersArea.getText() == null ? "" : headersArea.getText());
        m.put("bodyType", bodyTypeCombo.getValue().name());
        m.put("body", bodyArea.getText() == null ? "" : bodyArea.getText());
        m.put("auth", authCombo.getValue().name());
        m.put("username", usernameField.getText() == null ? "" : usernameField.getText());
        AuthType auth = authCombo.getValue();
        String secret = auth == AuthType.BASIC ? passwordField.getText() : auth == AuthType.BEARER ? tokenField.getText() : "";
        m.put("secret", secret == null ? "" : secret);
        m.put("timeoutMs", Fields.text(timeoutField));
        m.put("followRedirects", Boolean.toString(followRedirectsCheck.isSelected()));
        if (auth != AuthType.NONE && secret != null && !secret.isEmpty() && !secret.trim().startsWith("${")) {
            String warning = "Preset stores a literal " + (auth == AuthType.BASIC ? "password" : "token")
                    + " — prefer a ${VAR} placeholder resolved from .env (see .env.example)";
            LogSink.get().info(HttpService.TAG, warning);
            Toasts.warning(warning);
        }
        return m;
    }

    @Override
    public void applyFields(Map<String, String> fields) {
        String method = fields.get("method");
        if (method != null && HttpRequestSpec.METHODS.contains(method.toUpperCase())) {
            methodCombo.setValue(method.toUpperCase());
        }
        Fields.apply(fields, "url", urlField);
        if (fields.containsKey("headers")) {
            headersArea.setText(fields.get("headers"));
        }
        applyEnum(fields, "bodyType", BodyType.class, bodyTypeCombo);
        if (fields.containsKey("body")) {
            bodyArea.setText(fields.get("body"));
        }
        applyEnum(fields, "auth", AuthType.class, authCombo);
        Fields.apply(fields, "username", usernameField);
        String secret = fields.get("secret");
        if (secret != null) {
            passwordField.setText(authCombo.getValue() == AuthType.BASIC ? secret : "");
            tokenField.setText(authCombo.getValue() == AuthType.BEARER ? secret : "");
        }
        Fields.apply(fields, "timeoutMs", timeoutField);
        Fields.apply(fields, "followRedirects", followRedirectsCheck);
    }

    private static <E extends Enum<E>> void applyEnum(Map<String, String> fields, String key, Class<E> type, ComboBox<E> combo) {
        String v = fields.get(key);
        if (v == null) {
            return;
        }
        try {
            combo.setValue(Enum.valueOf(type, v));
        } catch (IllegalArgumentException ignored) {
            // keep current
        }
    }

    @Override
    public void shutdown() {
        service.stop();
    }
}
