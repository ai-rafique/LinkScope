package com.linkscope.modules.telnet;

import com.linkscope.core.AbstractService;
import com.linkscope.core.AnsiText;
import com.linkscope.core.FxThread;
import com.linkscope.core.ModuleStatus;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.function.Consumer;

/**
 * Telnet client: a TCP connection with RFC 854 option negotiation handled so real telnet
 * servers and old lab gear behave. We refuse every option asked of us (WONT), accept the
 * server echoing and suppressing go-ahead (DO for ECHO and SGA), decline anything else it
 * offers (DONT), skip subnegotiations, and unescape IAC IAC. Negotiation is logged, never
 * shown in the console.
 */
public final class TelnetService extends AbstractService {
    public static final String TAG = "TELNET";
    private static final int CONNECT_TIMEOUT_MS = 5000;

    static final int IAC = 255;
    static final int DONT = 254;
    static final int DO = 253;
    static final int WONT = 252;
    static final int WILL = 251;
    static final int SB = 250;
    static final int SE = 240;
    static final int IP = 244;
    static final int OPT_ECHO = 1;
    static final int OPT_SGA = 3;

    private volatile String host = "127.0.0.1";
    private volatile int port = 23;
    private volatile Socket socket;
    private volatile OutputStream out;
    private volatile boolean running;
    private volatile Consumer<String> onOutput = text -> { };
    private final Object writeLock = new Object();

    // negotiation parser state (only touched by the read thread)
    private int state; // 0 data, 1 after IAC, 2 after IAC DO/DONT/WILL/WONT (waiting option), 3 inside SB, 4 SB after IAC
    private int command;
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

    @Override
    public String moduleName() {
        return TAG;
    }

    public void setTarget(String host, int port) {
        this.host = host == null || host.isBlank() ? "127.0.0.1" : host.trim();
        this.port = requirePort(port);
    }

    /** Receives decoded text (negotiation removed, ANSI stripped) on the FX thread. */
    public void setOnOutput(Consumer<String> handler) {
        this.onOutput = handler == null ? text -> { } : handler;
    }

    @Override
    public void start() {
        if (running) {
            return;
        }
        running = true;
        setStatus(ModuleStatus.CONNECTING);
        exec.submit(this::run);
    }

    @Override
    public void stop() {
        running = false;
        Socket s = socket;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // closing
            }
        }
    }

    private void run() {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            s.setTcpNoDelay(true);
            socket = s;
            out = s.getOutputStream();
            state = 0;
            logInfo("Connected to " + host + ":" + port);
            setStatus(ModuleStatus.CONNECTED);
            InputStream in = s.getInputStream();
            byte[] buf = new byte[8192];
            int n;
            while (running && (n = in.read(buf)) != -1) {
                byte[] chunk = Arrays.copyOf(buf, n);
                log.rx(TAG, chunk, host + ":" + port);
                String text = feed(chunk);
                if (!text.isEmpty()) {
                    FxThread.run(() -> onOutput.accept(text));
                }
            }
            if (running) {
                logInfo("Connection closed by " + host + ":" + port);
                FxThread.run(() -> onOutput.accept("\n[connection closed by peer]\n"));
            }
        } catch (IOException e) {
            if (running) {
                logError(socket == null ? "Connect to " + host + ":" + port + " failed" : "Connection error", e);
                setStatus(ModuleStatus.ERROR);
            }
        } finally {
            running = false;
            socket = null;
            out = null;
            try {
                s.close();
            } catch (IOException ignored) {
                // closing
            }
            if (status() != ModuleStatus.ERROR) {
                logInfo("Disconnected");
                setStatus(ModuleStatus.DISCONNECTED);
            }
        }
    }

    /** Runs incoming bytes through the option parser; returns the user-visible text. */
    String feed(byte[] chunk) {
        return AnsiText.strip(new String(feedBytes(chunk), StandardCharsets.UTF_8));
    }

    /** The data bytes left after removing negotiation (IAC IAC becomes one 0xFF). */
    byte[] feedBytes(byte[] chunk) {
        ByteArrayOutputStream text = new ByteArrayOutputStream(chunk.length);
        for (byte value : chunk) {
            int b = value & 0xFF;
            switch (state) {
                case 0 -> {
                    if (b == IAC) {
                        state = 1;
                    } else {
                        text.write(b);
                    }
                }
                case 1 -> {
                    if (b == IAC) {
                        text.write(IAC);
                        state = 0;
                    } else if (b == DO || b == DONT || b == WILL || b == WONT) {
                        command = b;
                        state = 2;
                    } else if (b == SB) {
                        state = 3;
                    } else {
                        state = 0; // NOP, GA, etc.
                    }
                }
                case 2 -> {
                    negotiate(command, b);
                    state = 0;
                }
                case 3 -> {
                    if (b == IAC) {
                        state = 4;
                    }
                }
                case 4 -> state = b == SE ? 0 : 3;
                default -> state = 0;
            }
        }
        return text.toByteArray();
    }

    private void negotiate(int cmd, int option) {
        int reply;
        switch (cmd) {
            case DO -> reply = WONT;                                         // we do nothing special
            case WILL -> reply = option == OPT_ECHO || option == OPT_SGA ? DO : DONT;
            case DONT, WONT -> {
                logInfo("Server " + name(cmd) + " " + optionName(option));
                return;                                                      // nothing to answer
            }
            default -> {
                return;
            }
        }
        logInfo("Server " + name(cmd) + " " + optionName(option) + " → " + name(reply));
        // Replies must leave in the order the requests arrived, so write them right here on the read thread.
        OutputStream o = out;
        if (o != null) {
            try {
                synchronized (writeLock) {
                    o.write(new byte[] {(byte) IAC, (byte) reply, (byte) option});
                    o.flush();
                }
            } catch (IOException e) {
                logError("Negotiation reply failed", e);
            }
        }
    }

    static String name(int cmd) {
        return switch (cmd) {
            case DO -> "DO";
            case DONT -> "DONT";
            case WILL -> "WILL";
            case WONT -> "WONT";
            default -> String.valueOf(cmd);
        };
    }

    static String optionName(int option) {
        return switch (option) {
            case 0 -> "BINARY";
            case 1 -> "ECHO";
            case 3 -> "SUPPRESS-GO-AHEAD";
            case 5 -> "STATUS";
            case 24 -> "TERMINAL-TYPE";
            case 31 -> "NAWS";
            case 32 -> "TERMINAL-SPEED";
            case 33 -> "REMOTE-FLOW-CONTROL";
            case 34 -> "LINEMODE";
            case 36 -> "ENVIRON";
            case 39 -> "NEW-ENVIRON";
            default -> "option " + option;
        };
    }

    /** Sends user data; any 0xFF byte is escaped as IAC IAC. */
    @Override
    public void send(byte[] payload) {
        if (out == null) {
            logError("Not connected — connect first");
            return;
        }
        ByteArrayOutputStream escaped = new ByteArrayOutputStream(payload.length + 4);
        for (byte b : payload) {
            escaped.write(b);
            if ((b & 0xFF) == IAC) {
                escaped.write(IAC);
            }
        }
        writeRaw(escaped.toByteArray(), true);
    }

    /** Sends the telnet Interrupt Process command (IAC IP). */
    public void interrupt() {
        logInfo("Sent IAC IP (interrupt)");
        writeRaw(new byte[] {(byte) IAC, (byte) IP}, false);
    }

    private void writeRaw(byte[] bytes, boolean logAsTx) {
        OutputStream o = out;
        if (o == null) {
            logError("Not connected — connect first");
            return;
        }
        exec.submit(() -> {
            try {
                synchronized (writeLock) {
                    o.write(bytes);
                    o.flush();
                }
                if (logAsTx) {
                    log.tx(TAG, bytes, host + ":" + port);
                }
            } catch (IOException e) {
                logError("Send failed", e);
            }
        });
    }
}
