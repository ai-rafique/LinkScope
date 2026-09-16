package com.linkscope.modules.sftp;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;
import com.jcraft.jsch.SftpProgressMonitor;
import com.linkscope.core.FxThread;
import com.linkscope.core.LogSink;
import com.linkscope.modules.ssh.SshService;

import java.io.File;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * SFTP operations on top of an {@link SshService} session (own or shared). One channel is
 * opened lazily and reused; every call runs off the FX thread and reports back on it.
 * Not a TransportModule: the connection state belongs to the SSH session it rides on.
 */
public final class SftpClient {
    public static final String TAG = "SFTP";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** One directory listing row. */
    public record Entry(String name, boolean directory, boolean link, long size, long modifiedEpochSeconds, String permissions) {
        public String sizeText() {
            return directory ? "" : humanSize(size);
        }

        public String modifiedText() {
            return TIME.format(Instant.ofEpochSecond(modifiedEpochSeconds).atZone(ZoneId.systemDefault()));
        }
    }

    /** Transfer progress: bytes so far of total (-1 when unknown). */
    public record Progress(String file, long done, long total, boolean finished) {
    }

    private final Supplier<SshService> sessionSource;
    private final ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
    private volatile ChannelSftp channel;
    private volatile Session channelSession;
    private volatile boolean cancelRequested;

    public SftpClient(Supplier<SshService> sessionSource) {
        this.sessionSource = sessionSource;
    }

    // --- channel --------------------------------------------------------------------

    private ChannelSftp channel() throws JSchException {
        SshService ssh = sessionSource.get();
        Session s = ssh == null ? null : ssh.session();
        if (s == null || !s.isConnected()) {
            throw new JSchException("Not connected — connect the SSH session first");
        }
        ChannelSftp ch = channel;
        if (ch != null && ch.isConnected() && channelSession == s) {
            return ch;
        }
        closeChannel();
        ch = (ChannelSftp) s.openChannel("sftp");
        ch.connect(10_000);
        channel = ch;
        channelSession = s;
        LogSink.get().info(TAG, "SFTP channel opened on " + ssh.target());
        return ch;
    }

    public void closeChannel() {
        ChannelSftp ch = channel;
        channel = null;
        channelSession = null;
        if (ch != null) {
            ch.disconnect();
        }
    }

    /** Asks the running transfer to stop after the current block. */
    public void cancelTransfer() {
        cancelRequested = true;
    }

    // --- operations (async, callbacks on the FX thread) -------------------------------

    /** Lists {@code path} ("" or "." = login directory); the callback also receives the resolved absolute path. */
    public void list(String path, Consumer<String> onPath, Consumer<List<Entry>> onEntries, Consumer<String> onError) {
        exec.submit(() -> {
            try {
                ChannelSftp ch = channel();
                String target = path == null || path.isBlank() ? "." : path;
                String resolved = ch.realpath(target);
                @SuppressWarnings("unchecked")
                Vector<ChannelSftp.LsEntry> raw = ch.ls(resolved);
                List<Entry> entries = new ArrayList<>();
                for (ChannelSftp.LsEntry e : raw) {
                    if (".".equals(e.getFilename()) || "..".equals(e.getFilename())) {
                        continue;
                    }
                    SftpATTRS a = e.getAttrs();
                    entries.add(new Entry(e.getFilename(), a.isDir(), a.isLink(), a.getSize(), a.getMTime(), a.getPermissionsString()));
                }
                entries.sort(Comparator.comparing((Entry e) -> !e.directory()).thenComparing(e -> e.name().toLowerCase()));
                FxThread.run(() -> {
                    onPath.accept(resolved);
                    onEntries.accept(entries);
                });
            } catch (JSchException | SftpException e) {
                fail(onError, "List " + path + " failed", e);
            }
        });
    }

    public void download(String remotePath, File local, Consumer<Progress> onProgress, Runnable onDone, Consumer<String> onError) {
        exec.submit(() -> {
            long started = System.currentTimeMillis();
            try {
                cancelRequested = false;
                channel().get(remotePath, local.getAbsolutePath(), monitor(local.getName(), onProgress));
                if (cancelRequested) {
                    LogSink.get().info(TAG, "Download of " + remotePath + " cancelled");
                } else {
                    LogSink.get().info(TAG, "Downloaded " + remotePath + " → " + local + " (" + humanSize(local.length())
                            + ", " + (System.currentTimeMillis() - started) + " ms)");
                }
                FxThread.run(onDone);
            } catch (JSchException | SftpException e) {
                fail(onError, "Download of " + remotePath + " failed", e);
            }
        });
    }

    public void upload(File local, String remoteDir, Consumer<Progress> onProgress, Runnable onDone, Consumer<String> onError) {
        exec.submit(() -> {
            long started = System.currentTimeMillis();
            String remotePath = join(remoteDir, local.getName());
            try {
                cancelRequested = false;
                channel().put(local.getAbsolutePath(), remotePath, monitor(local.getName(), onProgress));
                if (cancelRequested) {
                    LogSink.get().info(TAG, "Upload of " + local.getName() + " cancelled");
                } else {
                    LogSink.get().info(TAG, "Uploaded " + local + " → " + remotePath + " (" + humanSize(local.length())
                            + ", " + (System.currentTimeMillis() - started) + " ms)");
                }
                FxThread.run(onDone);
            } catch (JSchException | SftpException e) {
                fail(onError, "Upload of " + local.getName() + " failed", e);
            }
        });
    }

    public void mkdir(String path, Runnable onDone, Consumer<String> onError) {
        exec.submit(() -> {
            try {
                channel().mkdir(path);
                LogSink.get().info(TAG, "Created directory " + path);
                FxThread.run(onDone);
            } catch (JSchException | SftpException e) {
                fail(onError, "mkdir " + path + " failed", e);
            }
        });
    }

    public void rename(String from, String to, Runnable onDone, Consumer<String> onError) {
        exec.submit(() -> {
            try {
                channel().rename(from, to);
                LogSink.get().info(TAG, "Renamed " + from + " → " + to);
                FxThread.run(onDone);
            } catch (JSchException | SftpException e) {
                fail(onError, "Rename " + from + " failed", e);
            }
        });
    }

    /** Deletes a file, or an empty directory. */
    public void delete(String path, boolean directory, Runnable onDone, Consumer<String> onError) {
        exec.submit(() -> {
            try {
                if (directory) {
                    channel().rmdir(path);
                } else {
                    channel().rm(path);
                }
                LogSink.get().info(TAG, "Deleted " + path);
                FxThread.run(onDone);
            } catch (JSchException | SftpException e) {
                fail(onError, "Delete " + path + " failed", e);
            }
        });
    }

    // --- helpers ----------------------------------------------------------------------

    private SftpProgressMonitor monitor(String name, Consumer<Progress> onProgress) {
        return new SftpProgressMonitor() {
            private long total = -1;
            private long done;
            private long lastReport;

            @Override
            public void init(int op, String src, String dest, long max) {
                total = max;
                done = 0;
                FxThread.run(() -> onProgress.accept(new Progress(name, 0, total, false)));
            }

            @Override
            public boolean count(long count) {
                done += count;
                long now = System.currentTimeMillis();
                if (now - lastReport > 100) {
                    lastReport = now;
                    long d = done;
                    FxThread.run(() -> onProgress.accept(new Progress(name, d, total, false)));
                }
                return !cancelRequested;
            }

            @Override
            public void end() {
                long d = done;
                FxThread.run(() -> onProgress.accept(new Progress(name, d, total, true)));
            }
        };
    }

    private static void fail(Consumer<String> onError, String what, Exception e) {
        String why = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        LogSink.get().error(TAG, what + ": " + why);
        FxThread.run(() -> onError.accept(what + ": " + why));
    }

    public static String join(String dir, String name) {
        return dir.endsWith("/") ? dir + name : dir + "/" + name;
    }

    public static String parent(String path) {
        if (path == null || path.isEmpty() || "/".equals(path)) {
            return "/";
        }
        String p = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        int slash = p.lastIndexOf('/');
        return slash <= 0 ? "/" : p.substring(0, slash);
    }

    public static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB"};
        double v = bytes;
        int i = -1;
        while (v >= 1024 && i < units.length - 1) {
            v /= 1024;
            i++;
        }
        return String.format(v >= 100 ? "%.0f %s" : "%.1f %s", v, units[i]);
    }
}
