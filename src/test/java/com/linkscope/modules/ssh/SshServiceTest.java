package com.linkscope.modules.ssh;

import com.linkscope.TestSupport;
import com.linkscope.core.LogSink;
import com.linkscope.core.ModuleStatus;
import com.linkscope.modules.ssh.SshService.ExecResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static com.linkscope.TestSupport.awaitTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ANSI stripping and validation run everywhere. The live round trip needs a reachable
 * server named in LINKSCOPE_SSH_TARGET as {@code user:password@host:port} and is skipped
 * otherwise (and whenever SKIP_INTEGRATION is set).
 */
class SshServiceTest {
    private final SshService service = new SshService();

    @AfterEach
    void tearDown() {
        service.stop();
    }

    @Test
    void stripsColoursCursorMovesTitlesAndCarriageReturns() {
        String raw = "\u001B[01;32muser@host\u001B[00m:\u001B[01;34m~\u001B[00m$ ls\r\n"
                + "\u001B]0;user@host: ~\u0007file1  file2\r\n\u001B[?2004h";
        assertEquals("user@host:~$ ls\nfile1  file2\n", SshService.stripAnsi(raw));
        assertEquals("a\nb\tc", SshService.stripAnsi("a\rb\tc\u0007"));
    }

    @Test
    void refusesToStartWithoutHostOrUser() {
        LogSink.get().clear();
        service.setTarget("", 22, "");
        service.start();
        assertEquals(ModuleStatus.ERROR, service.status());
        assertTrue(TestSupport.snapshot(LogSink.get().entries()).stream()
                .anyMatch(e -> e.note() != null && e.note().contains("Host and user are required")));
    }

    @Test
    void connectionRefusedEndsInError() {
        LogSink.get().clear();
        service.setTarget("127.0.0.1", 1, "nobody");
        service.setPassword("x");
        service.start();
        awaitTrue("error", Duration.ofSeconds(15), () -> service.status() == ModuleStatus.ERROR);
    }

    @Test
    void liveExecAndShellRoundTrip() throws Exception {
        Assumptions.assumeFalse(TestSupport.integrationSkipped(), "SKIP_INTEGRATION set");
        String target = System.getenv("LINKSCOPE_SSH_TARGET");
        Assumptions.assumeTrue(target != null && target.contains("@") && target.contains(":"), "LINKSCOPE_SSH_TARGET not set");
        String creds = target.substring(0, target.lastIndexOf('@'));
        String hostPort = target.substring(target.lastIndexOf('@') + 1);
        String user = creds.substring(0, creds.indexOf(':'));
        String password = creds.substring(creds.indexOf(':') + 1);
        String host = hostPort.substring(0, hostPort.lastIndexOf(':'));
        int port = Integer.parseInt(hostPort.substring(hostPort.lastIndexOf(':') + 1));

        LogSink.get().clear();
        StringBuilder console = new StringBuilder();
        service.setOnOutput(console::append);
        service.setTarget(host, port, user);
        service.setPassword(password);
        service.setStrictHostKey(false);
        service.start();
        awaitTrue("connected", Duration.ofSeconds(20), () -> service.status() == ModuleStatus.CONNECTED);

        AtomicReference<ExecResult> result = new AtomicReference<>();
        service.exec("echo linkscope-$((6*7))", result::set);
        awaitTrue("exec done", Duration.ofSeconds(20), () -> result.get() != null);
        assertNotNull(result.get());
        assertEquals(0, result.get().exitStatus());
        assertTrue(result.get().output().contains("linkscope-42"), result.get().output());

        service.sendLine("echo shell-$((7*6))");
        awaitTrue("shell echo", Duration.ofSeconds(20), () -> console.toString().contains("shell-42"));

        // A local forward back to the server's own SSH port must hand us an SSH banner.
        int localPort;
        try (java.net.ServerSocket probe = new java.net.ServerSocket(0)) {
            localPort = probe.getLocalPort();
        }
        SshService.Forward forward = new SshService.Forward(true, "127.0.0.1", localPort, "127.0.0.1", port);
        service.addForward(forward);
        try (java.net.Socket through = new java.net.Socket("127.0.0.1", localPort)) {
            through.setSoTimeout(10_000);
            byte[] banner = new byte[8];
            int n = through.getInputStream().readNBytes(banner, 0, 8);
            assertEquals("SSH-2.0-", new String(banner, 0, n, java.nio.charset.StandardCharsets.US_ASCII));
        }
        service.removeForward(forward);

        service.stop();
        awaitTrue("disconnected", () -> service.status() == ModuleStatus.DISCONNECTED);
    }
}
