package com.linkscope.modules.udp;

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

class UdpServiceTest {
    private final UdpService listener = new UdpService();
    private final UdpService sender = new UdpService();

    @BeforeEach
    void setUp() {
        LogSink.get().clear();
    }

    @AfterEach
    void tearDown() {
        listener.stop();
        sender.stop();
    }

    @Test
    void loopbackRoundTripAndReply() {
        listener.setBindAddress("127.0.0.1");
        listener.setBindPort(0);
        listener.start();
        awaitTrue("listener bound", () -> listener.status() == ModuleStatus.CONNECTED);
        assertTrue(listener.localPort() > 0);

        sender.setBindAddress("127.0.0.1");
        sender.setBindPort(0);
        sender.start();
        awaitTrue("sender bound", () -> sender.status() == ModuleStatus.CONNECTED);

        byte[] hello = "hello".getBytes(StandardCharsets.UTF_8);
        sender.setTargetHost("127.0.0.1");
        sender.setTargetPort(listener.localPort());
        sender.send(hello);
        awaitTrue("listener RX", () -> logged(UdpService.TAG, LogEntry.Kind.RX, hello));
        awaitTrue("last sender recorded", () -> listener.lastSender() != null);
        assertEquals(sender.localPort(), listener.lastSender().getPort());

        byte[] reply = "reply".getBytes(StandardCharsets.UTF_8);
        listener.replyToLastSender(reply);
        awaitTrue("sender RX reply", () -> logged(UdpService.TAG, LogEntry.Kind.RX, reply));
    }

    @Test
    void sendsWithoutListeningFromTemporarySocket() {
        listener.setBindAddress("127.0.0.1");
        listener.setBindPort(0);
        listener.start();
        awaitTrue("listener bound", () -> listener.status() == ModuleStatus.CONNECTED);

        byte[] ping = {1, 2, 3};
        sender.setTargetHost("127.0.0.1");
        sender.setTargetPort(listener.localPort());
        sender.send(ping);
        awaitTrue("TX logged", () -> logged(UdpService.TAG, LogEntry.Kind.TX, ping));
        awaitTrue("listener RX", () -> logged(UdpService.TAG, LogEntry.Kind.RX, ping));
        assertEquals(ModuleStatus.DISCONNECTED, sender.status());
    }

    @Test
    void stopReturnsToDisconnected() {
        listener.setBindAddress("127.0.0.1");
        listener.setBindPort(0);
        listener.start();
        awaitTrue("listener bound", () -> listener.status() == ModuleStatus.CONNECTED);
        listener.stop();
        awaitTrue("listener stopped", () -> listener.status() == ModuleStatus.DISCONNECTED);
        assertEquals(-1, listener.localPort());
    }
}
