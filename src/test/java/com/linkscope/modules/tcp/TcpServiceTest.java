package com.linkscope.modules.tcp;

import com.linkscope.core.LogEntry;
import com.linkscope.core.LogSink;
import com.linkscope.core.ModuleStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static com.linkscope.TestSupport.awaitTrue;
import static com.linkscope.TestSupport.logged;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TcpServiceTest {
    private final TcpService service = new TcpService();

    @BeforeEach
    void setUp() {
        LogSink.get().clear();
    }

    @AfterEach
    void tearDown() {
        service.stop();
    }

    private int listen() {
        TcpService.Server server = service.server();
        server.setBindAddress("127.0.0.1");
        server.setPort(0);
        server.start();
        awaitTrue("server listening", () -> server.status() == ModuleStatus.CONNECTED);
        assertTrue(server.localPort() > 0);
        return server.localPort();
    }

    @Test
    void clientAndServerRoundTripInOneService() {
        int port = listen();
        TcpService.Client client = service.client();
        client.setHost("127.0.0.1");
        client.setPort(port);
        client.start();
        awaitTrue("client connected", () -> client.status() == ModuleStatus.CONNECTED);
        awaitTrue("server sees client", () -> service.server().liveClients().size() == 1);
        assertEquals(ModuleStatus.CONNECTED, service.statusProperty().get());

        byte[] ping = "ping\n".getBytes(StandardCharsets.UTF_8);
        client.send(ping);
        awaitTrue("server RX ping", () -> logged(TcpService.TAG_SERVER, LogEntry.Kind.RX, ping));

        byte[] pong = "pong\n".getBytes(StandardCharsets.UTF_8);
        service.server().send(pong);
        awaitTrue("client RX pong", () -> logged(TcpService.TAG_CLIENT, LogEntry.Kind.RX, pong));

        byte[] direct = {9, 9, 9};
        service.server().sendTo(service.server().liveClients().get(0), direct);
        awaitTrue("client RX direct", () -> logged(TcpService.TAG_CLIENT, LogEntry.Kind.RX, direct));

        client.stop();
        awaitTrue("client disconnected", () -> client.status() == ModuleStatus.DISCONNECTED);
        awaitTrue("server dropped client", () -> service.server().liveClients().isEmpty());
        assertEquals(ModuleStatus.CONNECTED, service.statusProperty().get(), "server still listening");
    }

    @Test
    void serverKickDisconnectsClient() {
        int port = listen();
        TcpService.Client client = service.client();
        client.setHost("127.0.0.1");
        client.setPort(port);
        client.start();
        awaitTrue("server sees client", () -> service.server().liveClients().size() == 1);

        service.server().disconnect(service.server().liveClients().get(0));
        awaitTrue("client sees close", () -> client.status() == ModuleStatus.DISCONNECTED);
        awaitTrue("server list empty", () -> service.server().liveClients().isEmpty());
    }

    @Test
    void connectFailureReportsErrorWithoutReconnect() {
        TcpService.Client client = service.client();
        client.setHost("127.0.0.1");
        client.setPort(1);
        client.setAutoReconnect(false);
        client.start();
        awaitTrue("client error", () -> client.status() == ModuleStatus.ERROR);
        assertEquals(ModuleStatus.ERROR, service.statusProperty().get());
    }

    @Test
    void autoReconnectRecoversAfterServerRestart() {
        int port = listen();
        TcpService.Client client = service.client();
        client.setHost("127.0.0.1");
        client.setPort(port);
        client.setAutoReconnect(true);
        client.setReconnectDelayMs(100);
        client.start();
        awaitTrue("client connected", () -> client.status() == ModuleStatus.CONNECTED);

        service.server().stop();
        awaitTrue("server stopped", () -> service.server().status() == ModuleStatus.DISCONNECTED);
        awaitTrue("client lost connection", () -> client.status() != ModuleStatus.CONNECTED);

        service.server().setPort(port);
        service.server().start();
        awaitTrue("server relistening", () -> service.server().status() == ModuleStatus.CONNECTED);
        awaitTrue("client reconnected", () -> client.status() == ModuleStatus.CONNECTED);
        awaitTrue("server sees client again", () -> service.server().liveClients().size() == 1);
    }

    @Test
    void combineStatusPrecedence() {
        assertEquals(ModuleStatus.ERROR, TcpService.combine(ModuleStatus.CONNECTED, ModuleStatus.ERROR));
        assertEquals(ModuleStatus.CONNECTED, TcpService.combine(ModuleStatus.CONNECTED, ModuleStatus.DISCONNECTED));
        assertEquals(ModuleStatus.CONNECTING, TcpService.combine(ModuleStatus.DISCONNECTED, ModuleStatus.CONNECTING));
        assertEquals(ModuleStatus.DISCONNECTED, TcpService.combine(ModuleStatus.DISCONNECTED, ModuleStatus.DISCONNECTED));
    }
}
