package com.linkscope.modules.sftp;

import com.linkscope.TestSupport;
import com.linkscope.core.ModuleStatus;
import com.linkscope.modules.sftp.SftpClient.Entry;
import com.linkscope.modules.ssh.SshService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static com.linkscope.TestSupport.awaitTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Path and size helpers run everywhere; the live round trip (mkdir, upload, list, download,
 * rename, delete) needs LINKSCOPE_SSH_TARGET as {@code user:password@host:port}.
 */
class SftpClientTest {
    private final SshService ssh = new SshService();

    @AfterEach
    void tearDown() {
        ssh.stop();
    }

    @Test
    void helpers() {
        assertEquals("/home/pi/x", SftpClient.join("/home/pi", "x"));
        assertEquals("/home/pi/x", SftpClient.join("/home/pi/", "x"));
        assertEquals("/home", SftpClient.parent("/home/pi"));
        assertEquals("/", SftpClient.parent("/home"));
        assertEquals("/", SftpClient.parent("/"));
        assertEquals("512 B", SftpClient.humanSize(512));
        assertEquals("1.5 KB", SftpClient.humanSize(1536));
        assertEquals("100 MB", SftpClient.humanSize(100L * 1024 * 1024));
        Entry dir = new Entry("etc", true, false, 4096, 0, "drwxr-xr-x");
        assertEquals("", dir.sizeText());
    }

    @Test
    void liveRoundTrip() throws Exception {
        Assumptions.assumeFalse(TestSupport.integrationSkipped(), "SKIP_INTEGRATION set");
        String target = System.getenv("LINKSCOPE_SSH_TARGET");
        Assumptions.assumeTrue(target != null && target.contains("@") && target.contains(":"), "LINKSCOPE_SSH_TARGET not set");
        String creds = target.substring(0, target.lastIndexOf('@'));
        String hostPort = target.substring(target.lastIndexOf('@') + 1);
        ssh.setOpenShell(false);
        ssh.setTarget(hostPort.substring(0, hostPort.lastIndexOf(':')),
                Integer.parseInt(hostPort.substring(hostPort.lastIndexOf(':') + 1)), creds.substring(0, creds.indexOf(':')));
        ssh.setPassword(creds.substring(creds.indexOf(':') + 1));
        ssh.start();
        awaitTrue("connected", Duration.ofSeconds(20), () -> ssh.status() == ModuleStatus.CONNECTED);

        SftpClient client = new SftpClient(() -> ssh);
        AtomicReference<String> home = new AtomicReference<>();
        AtomicReference<List<Entry>> listing = new AtomicReference<>();
        AtomicReference<String> error = new AtomicReference<>();
        client.list(".", home::set, listing::set, error::set);
        awaitTrue("home listed", Duration.ofSeconds(20), () -> listing.get() != null || error.get() != null);
        assertNull(error.get());
        assertTrue(home.get().startsWith("/"), home.get());

        String dir = SftpClient.join(home.get(), "linkscope-test-" + UUID.randomUUID().toString().substring(0, 8));
        AtomicReference<Boolean> done = new AtomicReference<>(false);
        client.mkdir(dir, () -> done.set(true), error::set);
        awaitTrue("mkdir", Duration.ofSeconds(20), () -> done.get() || error.get() != null);
        assertNull(error.get());

        File local = Files.createTempFile("linkscope", ".txt").toFile();
        Files.writeString(local.toPath(), "hello sftp " + UUID.randomUUID());
        done.set(false);
        client.upload(local, dir, p -> { }, () -> done.set(true), error::set);
        awaitTrue("upload", Duration.ofSeconds(30), () -> done.get() || error.get() != null);
        assertNull(error.get());

        listing.set(null);
        client.list(dir, s -> { }, listing::set, error::set);
        awaitTrue("dir listed", Duration.ofSeconds(20), () -> listing.get() != null || error.get() != null);
        assertEquals(1, listing.get().size());
        assertEquals(local.getName(), listing.get().get(0).name());
        assertEquals(local.length(), listing.get().get(0).size());

        File back = Files.createTempFile("linkscope-back", ".txt").toFile();
        done.set(false);
        client.download(SftpClient.join(dir, local.getName()), back, p -> { }, () -> done.set(true), error::set);
        awaitTrue("download", Duration.ofSeconds(30), () -> done.get() || error.get() != null);
        assertNull(error.get());
        assertEquals(Files.readString(local.toPath(), StandardCharsets.UTF_8), Files.readString(back.toPath(), StandardCharsets.UTF_8));

        done.set(false);
        client.rename(SftpClient.join(dir, local.getName()), SftpClient.join(dir, "renamed.txt"), () -> done.set(true), error::set);
        awaitTrue("rename", Duration.ofSeconds(20), () -> done.get() || error.get() != null);
        done.set(false);
        client.delete(SftpClient.join(dir, "renamed.txt"), false, () -> done.set(true), error::set);
        awaitTrue("rm", Duration.ofSeconds(20), () -> done.get() || error.get() != null);
        done.set(false);
        client.delete(dir, true, () -> done.set(true), error::set);
        awaitTrue("rmdir", Duration.ofSeconds(20), () -> done.get() || error.get() != null);
        assertNull(error.get());
        client.closeChannel();
    }
}
