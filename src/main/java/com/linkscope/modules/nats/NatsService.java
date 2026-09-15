package com.linkscope.modules.nats;

import com.linkscope.core.AbstractService;
import com.linkscope.core.FxThread;
import com.linkscope.core.ModuleStatus;
import com.linkscope.modules.pubsub.PubSubMessage;
import com.linkscope.modules.pubsub.PubSubModule;
import io.nats.client.Connection;
import io.nats.client.ConnectionListener;
import io.nats.client.Dispatcher;
import io.nats.client.ErrorListener;
import io.nats.client.Message;
import io.nats.client.Nats;
import io.nats.client.Options;
import io.nats.client.Subscription;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NATS via the official jnats client. Subjects support {@code *} and {@code >}
 * wildcards; request/reply is available. Subscriptions made before connecting are
 * applied once connected, and the client's own reconnect logic keeps them alive.
 */
public final class NatsService extends AbstractService implements PubSubModule {
    public static final String TAG = "NATS";
    public static final int MAX_MESSAGES = 5_000;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    private final ObservableList<PubSubMessage> messages = FXCollections.observableArrayList();
    private final ObservableList<String> subscriptions = FXCollections.observableArrayList();
    private final List<String> wanted = new ArrayList<>();
    private final Map<String, Subscription> live = new ConcurrentHashMap<>();

    private volatile String brokerList = "localhost:4222";
    private volatile String defaultTopic = "linkscope.test";
    private volatile Connection connection;
    private volatile Dispatcher dispatcher;
    private volatile boolean closing;

    @Override
    public String moduleName() {
        return TAG;
    }

    @Override
    public ObservableList<PubSubMessage> messages() {
        return messages;
    }

    @Override
    public ObservableList<String> subscriptions() {
        return subscriptions;
    }

    @Override
    public boolean supportsRequestReply() {
        return true;
    }

    /** Subject used by the plain {@link #send(byte[])} entry point. */
    public void setDefaultTopic(String topic) {
        this.defaultTopic = topic;
    }

    public Connection connection() {
        return connection;
    }

    /** "localhost:4222, nats://other:4222" to {@code nats://...} URLs. */
    static String[] toServerUrls(String brokerList) {
        List<String> urls = new ArrayList<>();
        for (String raw : brokerList.split(",")) {
            String s = raw.trim();
            if (s.isEmpty()) {
                continue;
            }
            urls.add(s.contains("://") ? s : "nats://" + s);
        }
        if (urls.isEmpty()) {
            throw new IllegalArgumentException("Broker list is empty");
        }
        return urls.toArray(new String[0]);
    }

    // --- lifecycle ------------------------------------------------------------------

    @Override
    public void connectToBroker(String brokers) {
        this.brokerList = brokers;
        start();
    }

    @Override
    public void start() {
        if (connection != null || status() == ModuleStatus.CONNECTING) {
            return;
        }
        closing = false;
        setStatus(ModuleStatus.CONNECTING);
        exec.submit(this::connect);
    }

    private void connect() {
        try {
            Options options = new Options.Builder()
                    .servers(toServerUrls(brokerList))
                    .connectionTimeout(CONNECT_TIMEOUT)
                    .maxReconnects(-1)
                    .reconnectWait(Duration.ofSeconds(2))
                    .connectionListener(this::onConnectionEvent)
                    .errorListener(new ErrorListener() {
                        @Override
                        public void errorOccurred(Connection conn, String error) {
                            logError("Server error: " + error);
                        }

                        @Override
                        public void exceptionOccurred(Connection conn, Exception exp) {
                            if (!closing && connection != null) {
                                logError("Client exception", exp);
                            }
                        }
                    })
                    .build();
            Connection c = Nats.connect(options);
            connection = c;
            dispatcher = c.createDispatcher();
            logInfo("Connected to " + c.getConnectedUrl());
            setStatus(ModuleStatus.CONNECTED);
            synchronized (wanted) {
                for (String subject : wanted) {
                    attach(subject);
                }
            }
        } catch (IllegalArgumentException | IOException e) {
            logError("Connect to " + brokerList + " failed", e);
            setStatus(ModuleStatus.ERROR);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            setStatus(ModuleStatus.DISCONNECTED);
        }
    }

    private void onConnectionEvent(Connection conn, ConnectionListener.Events type) {
        if (connection == null && type != ConnectionListener.Events.CONNECTED) {
            return; // events fired during a failed initial connect; connect() reports the failure
        }
        switch (type) {
            case DISCONNECTED -> {
                if (!closing) {
                    logError("Disconnected from " + brokerList + " — reconnecting");
                    setStatus(ModuleStatus.CONNECTING);
                }
            }
            case RECONNECTED -> {
                logInfo("Reconnected to " + conn.getConnectedUrl());
                setStatus(ModuleStatus.CONNECTED);
            }
            case RESUBSCRIBED -> logInfo("Subscriptions restored");
            case LAME_DUCK -> logInfo("Server entering lame duck mode; client will migrate");
            case CLOSED -> {
                connection = null;
                dispatcher = null;
                live.clear();
                logInfo("Connection closed");
                setStatus(ModuleStatus.DISCONNECTED);
            }
            default -> { }
        }
    }

    @Override
    public void stop() {
        Connection c = connection;
        if (c == null) {
            if (status() != ModuleStatus.DISCONNECTED) {
                setStatus(ModuleStatus.DISCONNECTED);
            }
            return;
        }
        closing = true;
        exec.submit(() -> {
            try {
                c.close();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                connection = null;
                dispatcher = null;
                live.clear();
                if (status() != ModuleStatus.DISCONNECTED) {
                    setStatus(ModuleStatus.DISCONNECTED);
                }
            }
        });
    }

    // --- pub/sub --------------------------------------------------------------------

    @Override
    public void subscribe(String subject) {
        synchronized (wanted) {
            if (wanted.contains(subject)) {
                return;
            }
            wanted.add(subject);
        }
        FxThread.run(() -> {
            if (!subscriptions.contains(subject)) {
                subscriptions.add(subject);
            }
        });
        if (dispatcher != null) {
            exec.submit(() -> attach(subject));
        } else {
            logInfo("Will subscribe to " + subject + " once connected");
        }
    }

    private void attach(String subject) {
        Dispatcher d = dispatcher;
        if (d == null || live.containsKey(subject)) {
            return;
        }
        try {
            live.put(subject, d.subscribe(subject, this::onMessage));
            logInfo("Subscribed to " + subject);
        } catch (RuntimeException e) {
            logError("Subscribe to " + subject + " failed", e);
        }
    }

    @Override
    public void unsubscribe(String subject) {
        synchronized (wanted) {
            wanted.remove(subject);
        }
        FxThread.run(() -> subscriptions.remove(subject));
        Subscription sub = live.remove(subject);
        Dispatcher d = dispatcher;
        if (sub != null && d != null) {
            exec.submit(() -> {
                try {
                    d.unsubscribe(sub);
                    logInfo("Unsubscribed from " + subject);
                } catch (RuntimeException e) {
                    logError("Unsubscribe from " + subject + " failed", e);
                }
            });
        }
    }

    @Override
    public void publish(String subject, byte[] payload) {
        Connection c = connection;
        if (c == null) {
            logError("Not connected — connect first");
            return;
        }
        exec.submit(() -> {
            try {
                c.publish(subject, payload);
                log.tx(TAG, payload, subject);
            } catch (RuntimeException e) {
                logError("Publish to " + subject + " failed", e);
            }
        });
    }

    @Override
    public void request(String subject, byte[] payload, Duration timeout) {
        Connection c = connection;
        if (c == null) {
            logError("Not connected — connect first");
            return;
        }
        exec.submit(() -> {
            try {
                log.tx(TAG, payload, subject + " (request)");
                Message reply = c.request(subject, payload, timeout);
                if (reply == null) {
                    logError("No reply on " + subject + " within " + timeout.toMillis() + " ms");
                } else {
                    record(reply, "reply to " + subject);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                logError("Request on " + subject + " failed", e);
            }
        });
    }

    @Override
    public void send(byte[] payload) {
        publish(defaultTopic, payload);
    }

    private void onMessage(Message msg) {
        String meta = msg.getReplyTo() != null ? "reply-to: " + msg.getReplyTo() : "";
        record(msg, meta);
    }

    private void record(Message msg, String metadata) {
        byte[] data = msg.getData() == null ? new byte[0] : msg.getData();
        if (msg.hasHeaders()) {
            metadata = (metadata.isEmpty() ? "" : metadata + ", ") + msg.getHeaders().size() + " header(s)";
        }
        PubSubMessage m = PubSubMessage.now(msg.getSubject(), data, metadata);
        FxThread.run(() -> {
            messages.add(m);
            if (messages.size() > MAX_MESSAGES) {
                messages.remove(0, messages.size() - MAX_MESSAGES);
            }
        });
        log.rx(TAG, data, metadata.isEmpty() ? msg.getSubject() : msg.getSubject() + " (" + metadata + ")");
    }
}
