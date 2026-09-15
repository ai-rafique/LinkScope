package com.linkscope.modules.tcp;

import com.linkscope.core.ModuleController;
import com.linkscope.core.ModuleStatus;
import com.linkscope.core.ui.Fields;
import com.linkscope.core.ui.SendBarController;
import com.linkscope.core.ui.StatusBadge;
import com.linkscope.core.ui.Toasts;
import com.linkscope.modules.tcp.TcpService.ClientConn;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.collections.ListChangeListener;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextField;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.LinkedHashMap;
import java.util.Map;

/** TCP tab: client panel and server panel side by side, both live at once. */
public class TcpController implements ModuleController {
    public static final String ID = "tcp";
    private static final String ALL_CLIENTS = "All clients";

    // client half
    @FXML private TextField clientHostField;
    @FXML private TextField clientPortField;
    @FXML private CheckBox autoReconnectCheck;
    @FXML private Button connectButton;
    @FXML private StatusBadge clientStatus;
    @FXML private SendBarController clientSendBarController;

    // server half
    @FXML private TextField serverBindField;
    @FXML private TextField serverPortField;
    @FXML private Button listenButton;
    @FXML private StatusBadge serverStatus;
    @FXML private ComboBox<String> clientsCombo;
    @FXML private Button kickButton;
    @FXML private SendBarController serverSendBarController;

    private final TcpService service = new TcpService();

    @FXML
    private void initialize() {
        TcpService.Client client = service.client();
        TcpService.Server server = service.server();

        // --- client
        clientStatus.statusProperty().bind(client.statusProperty());
        BooleanBinding clientActive = Bindings.createBooleanBinding(
                () -> client.statusProperty().get().isActive(), client.statusProperty());
        clientHostField.disableProperty().bind(clientActive);
        clientPortField.disableProperty().bind(clientActive);
        clientSendBarController.sendDisabledProperty().bind(
                client.statusProperty().isNotEqualTo(ModuleStatus.CONNECTED));
        clientSendBarController.setOnSend(client::send);
        client.statusProperty().addListener((obs, old, now) -> updateConnectButton(now));
        updateConnectButton(client.statusProperty().get());
        connectButton.setOnAction(e -> toggleConnect());
        autoReconnectCheck.selectedProperty().addListener((obs, old, now) -> client.setAutoReconnect(now));

        // --- server
        serverStatus.statusProperty().bind(server.statusProperty());
        BooleanBinding serverActive = Bindings.createBooleanBinding(
                () -> server.statusProperty().get().isActive(), server.statusProperty());
        serverBindField.disableProperty().bind(serverActive);
        serverPortField.disableProperty().bind(serverActive);
        server.statusProperty().addListener((obs, old, now) -> updateListenButton(now));
        updateListenButton(server.statusProperty().get());
        listenButton.setOnAction(e -> toggleListen());

        clientsCombo.getItems().setAll(ALL_CLIENTS);
        clientsCombo.getSelectionModel().selectFirst();
        server.clients().addListener((ListChangeListener<ClientConn>) change -> refreshClients());
        BooleanBinding noClients = Bindings.isEmpty(server.clients());
        serverSendBarController.sendDisabledProperty().bind(noClients);
        kickButton.disableProperty().bind(noClients);
        serverSendBarController.setOnSend(this::serverSend);
        kickButton.setOnAction(e -> kick());
    }

    // --- client actions -----------------------------------------------------------

    private void toggleConnect() {
        TcpService.Client client = service.client();
        if (client.statusProperty().get().isActive()) {
            client.stop();
            return;
        }
        try {
            client.setHost(Fields.requireText(clientHostField, "Host"));
            client.setPort(Fields.port(clientPortField, "Port"));
            client.setAutoReconnect(autoReconnectCheck.isSelected());
        } catch (IllegalArgumentException ex) {
            Toasts.error(ex.getMessage());
            return;
        }
        client.start();
    }

    private void updateConnectButton(ModuleStatus s) {
        boolean active = s != null && s.isActive();
        connectButton.setText(active ? "Disconnect" : "Connect");
        connectButton.setGraphic(new FontIcon(active ? "fth-square" : "fth-play"));
    }

    // --- server actions -----------------------------------------------------------

    private void toggleListen() {
        TcpService.Server server = service.server();
        if (server.statusProperty().get().isActive()) {
            server.stop();
            return;
        }
        try {
            server.setBindAddress(Fields.text(serverBindField));
            server.setPort(Fields.port(serverPortField, "Listen port"));
        } catch (IllegalArgumentException ex) {
            Toasts.error(ex.getMessage());
            return;
        }
        server.start();
    }

    private void updateListenButton(ModuleStatus s) {
        boolean active = s != null && s.isActive();
        listenButton.setText(active ? "Stop listening" : "Listen");
        listenButton.setGraphic(new FontIcon(active ? "fth-square" : "fth-play"));
    }

    private void refreshClients() {
        String selected = clientsCombo.getValue();
        clientsCombo.getItems().setAll(ALL_CLIENTS);
        for (ClientConn c : service.server().clients()) {
            clientsCombo.getItems().add(c.id());
        }
        if (selected != null && clientsCombo.getItems().contains(selected)) {
            clientsCombo.setValue(selected);
        } else {
            clientsCombo.getSelectionModel().selectFirst();
        }
    }

    private ClientConn selectedClient() {
        String id = clientsCombo.getValue();
        if (id == null || ALL_CLIENTS.equals(id)) {
            return null;
        }
        for (ClientConn c : service.server().liveClients()) {
            if (c.id().equals(id)) {
                return c;
            }
        }
        return null;
    }

    private void serverSend(byte[] data) {
        ClientConn target = selectedClient();
        if (target == null) {
            service.server().send(data);
        } else {
            service.server().sendTo(target, data);
        }
    }

    private void kick() {
        ClientConn target = selectedClient();
        if (target == null) {
            for (ClientConn c : service.server().liveClients()) {
                service.server().disconnect(c);
            }
        } else {
            service.server().disconnect(target);
        }
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
        m.put("clientHost", Fields.text(clientHostField));
        m.put("clientPort", Fields.text(clientPortField));
        m.put("autoReconnect", Boolean.toString(autoReconnectCheck.isSelected()));
        m.put("serverBind", Fields.text(serverBindField));
        m.put("serverPort", Fields.text(serverPortField));
        for (Map.Entry<String, String> e : clientSendBarController.captureFields().entrySet()) {
            m.put("client." + e.getKey(), e.getValue());
        }
        for (Map.Entry<String, String> e : serverSendBarController.captureFields().entrySet()) {
            m.put("server." + e.getKey(), e.getValue());
        }
        return m;
    }

    @Override
    public void applyFields(Map<String, String> fields) {
        Fields.apply(fields, "clientHost", clientHostField);
        Fields.apply(fields, "clientPort", clientPortField);
        Fields.apply(fields, "autoReconnect", autoReconnectCheck);
        Fields.apply(fields, "serverBind", serverBindField);
        Fields.apply(fields, "serverPort", serverPortField);
        clientSendBarController.applyFields(stripPrefix(fields, "client."));
        serverSendBarController.applyFields(stripPrefix(fields, "server."));
    }

    private static Map<String, String> stripPrefix(Map<String, String> fields, String prefix) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : fields.entrySet()) {
            if (e.getKey().startsWith(prefix)) {
                out.put(e.getKey().substring(prefix.length()), e.getValue());
            }
        }
        return out;
    }

    @Override
    public void shutdown() {
        service.stop();
    }
}
