package com.linkscope.modules.kafka;

import com.linkscope.core.AbstractService;
import com.linkscope.core.FxThread;
import com.linkscope.core.ModuleStatus;
import com.linkscope.modules.pubsub.PubSubMessage;
import com.linkscope.modules.pubsub.PubSubModule;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Kafka via the official kafka-clients library, against the shared {@link PubSubModule}
 * contract. The broker list maps to {@code bootstrap.servers}; topics are exact names
 * (no wildcards); there is no request/reply. A missing topic is created on first use so
 * a fresh Redpanda works with zero setup. Consumer group defaults to a per-session id.
 */
public final class KafkaService extends AbstractService implements PubSubModule {
    public static final String TAG = "KAFKA";
    public static final int MAX_MESSAGES = 5_000;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration POLL = Duration.ofMillis(200);

    private final ObservableList<PubSubMessage> messages = FXCollections.observableArrayList();
    private final ObservableList<String> subscriptions = FXCollections.observableArrayList();
    private final Set<String> wanted = new HashSet<>();
    private final Set<String> knownTopics = new HashSet<>();
    /** Topics the consumer is actually subscribed to; only touched on the poll thread. */
    private Set<String> applied = Set.of();

    private volatile String brokerList = "localhost:9092";
    private volatile String groupId = "";
    private volatile Integer partition;
    private volatile boolean fromBeginning;
    private volatile String defaultTopic = "linkscope.test";

    private volatile AdminClient admin;
    private volatile KafkaProducer<byte[], byte[]> producer;
    private volatile KafkaConsumer<byte[], byte[]> consumer;
    private volatile boolean running;
    private volatile boolean subscriptionDirty;
    private volatile boolean connectedOnce;

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

    // --- configuration --------------------------------------------------------------

    /** Consumer group id; blank means a fresh per-session id so runs never fight over offsets. */
    public void setGroupId(String groupId) {
        this.groupId = groupId == null ? "" : groupId.trim();
    }

    /** Partition to publish to, or null to let the client choose. */
    public void setPartition(Integer partition) {
        this.partition = partition;
    }

    /** When true, new subscriptions start from the earliest retained offset instead of the latest. */
    public void setFromBeginning(boolean fromBeginning) {
        this.fromBeginning = fromBeginning;
    }

    public void setDefaultTopic(String topic) {
        this.defaultTopic = topic;
    }

    static String toBootstrapServers(String brokerList) {
        List<String> hosts = new ArrayList<>();
        for (String raw : brokerList.split(",")) {
            String s = raw.trim();
            if (s.isEmpty()) {
                continue;
            }
            int scheme = s.indexOf("://");
            hosts.add(scheme >= 0 ? s.substring(scheme + 3) : s);
        }
        if (hosts.isEmpty()) {
            throw new IllegalArgumentException("Broker list is empty");
        }
        return String.join(",", hosts);
    }

    // --- lifecycle ------------------------------------------------------------------

    @Override
    public void connectToBroker(String brokers) {
        this.brokerList = brokers;
        start();
    }

    @Override
    public void start() {
        if (running) {
            return;
        }
        running = true;
        connectedOnce = false;
        setStatus(ModuleStatus.CONNECTING);
        exec.submit(this::run);
    }

    @Override
    public void stop() {
        running = false;
        KafkaConsumer<byte[], byte[]> c = consumer;
        if (c != null) {
            c.wakeup();
        } else if (status() != ModuleStatus.DISCONNECTED) {
            setStatus(ModuleStatus.DISCONNECTED);
        }
    }

    private void run() {
        String bootstrap;
        String group = groupId.isEmpty() ? "linkscope-" + UUID.randomUUID().toString().substring(0, 8) : groupId;
        try {
            bootstrap = toBootstrapServers(brokerList);
        } catch (IllegalArgumentException e) {
            logError(e.getMessage());
            setStatus(ModuleStatus.ERROR);
            running = false;
            return;
        }
        try {
            Properties adminProps = new Properties();
            adminProps.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
            adminProps.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, (int) CONNECT_TIMEOUT.toMillis());
            adminProps.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, (int) CONNECT_TIMEOUT.toMillis());
            AdminClient a = AdminClient.create(adminProps);
            DescribeClusterResult cluster = a.describeCluster();
            int nodes = cluster.nodes().get(CONNECT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).size();
            String clusterId = cluster.clusterId().get(CONNECT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            admin = a;

            Properties producerProps = new Properties();
            producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
            producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
            producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
            producerProps.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, (int) CONNECT_TIMEOUT.toMillis());
            producerProps.put(ProducerConfig.LINGER_MS_CONFIG, 0);
            producer = new KafkaProducer<>(producerProps);

            Properties consumerProps = new Properties();
            consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
            consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, group);
            consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
            consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
            consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, fromBeginning ? "earliest" : "latest");
            consumerProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, true);
            consumerProps.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, true);
            KafkaConsumer<byte[], byte[]> c = new KafkaConsumer<>(consumerProps);
            consumer = c;
            connectedOnce = true;

            logInfo("Connected to " + bootstrap + " (cluster " + clusterId + ", " + nodes + " broker(s)), group "
                    + group + ", offsets from " + (fromBeginning ? "beginning" : "latest"));
            setStatus(ModuleStatus.CONNECTED);
            subscriptionDirty = true;
            pollLoop(c);
        } catch (ExecutionException | TimeoutException e) {
            Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
            logError("Connect to " + bootstrap + " failed", cause);
            setStatus(ModuleStatus.ERROR);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            if (running || !connectedOnce) {
                logError("Kafka client error", e);
                setStatus(ModuleStatus.ERROR);
            }
        } finally {
            running = false;
            closeClients();
            if (status() != ModuleStatus.ERROR) {
                logInfo("Disconnected");
                setStatus(ModuleStatus.DISCONNECTED);
            }
        }
    }

    private void pollLoop(KafkaConsumer<byte[], byte[]> c) {
        try {
            while (running) {
                if (subscriptionDirty) {
                    applySubscriptions(c);
                }
                if (c.subscription().isEmpty()) {
                    // poll() throws when nothing is subscribed; idle until a subscription arrives
                    Thread.sleep(POLL.toMillis());
                    continue;
                }
                ConsumerRecords<byte[], byte[]> records = c.poll(POLL);
                for (ConsumerRecord<byte[], byte[]> r : records) {
                    record(r);
                }
            }
        } catch (WakeupException expected) {
            // stop() requested
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void applySubscriptions(KafkaConsumer<byte[], byte[]> c) {
        subscriptionDirty = false;
        Set<String> topics;
        synchronized (wanted) {
            topics = new HashSet<>(wanted);
        }
        Set<String> removed = new HashSet<>(applied);
        removed.removeAll(topics);
        applied = topics;
        if (topics.isEmpty()) {
            c.unsubscribe();
            for (String t : removed) {
                logInfo("Unsubscribed from " + t);
            }
            return;
        }
        for (String t : removed) {
            logInfo("Unsubscribed from " + t);
        }
        for (String t : topics) {
            ensureTopic(t);
        }
        c.subscribe(topics, new ConsumerRebalanceListener() {
            @Override
            public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
                // nothing to clean up; offsets auto-commit
            }

            @Override
            public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                // Materialize positions now so a message published right after "Subscribed" is not skipped.
                for (TopicPartition tp : partitions) {
                    c.position(tp);
                }
                Set<String> assignedTopics = new HashSet<>();
                for (TopicPartition tp : partitions) {
                    assignedTopics.add(tp.topic());
                }
                for (String t : assignedTopics) {
                    logInfo("Subscribed to " + t + " (" + partitions.stream().filter(p -> p.topic().equals(t)).count()
                            + " partition(s) assigned)");
                }
            }
        });
    }

    /** Creates the topic (1 partition, RF 1) if the cluster does not have it yet. */
    private void ensureTopic(String topic) {
        AdminClient a = admin;
        if (a == null || knownTopics.contains(topic)) {
            return;
        }
        try {
            if (a.listTopics().names().get(CONNECT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).contains(topic)) {
                knownTopics.add(topic);
                return;
            }
            a.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all()
                    .get(CONNECT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            knownTopics.add(topic);
            logInfo("Created topic " + topic + " (1 partition)");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof TopicExistsException) {
                knownTopics.add(topic);
            } else {
                logError("Could not create topic " + topic, e.getCause() == null ? e : e.getCause());
            }
        } catch (TimeoutException e) {
            logError("Timed out checking topic " + topic);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void closeClients() {
        KafkaConsumer<byte[], byte[]> c = consumer;
        consumer = null;
        if (c != null) {
            try {
                c.close(Duration.ofSeconds(2));
            } catch (RuntimeException ignored) {
                // closing
            }
        }
        KafkaProducer<byte[], byte[]> p = producer;
        producer = null;
        if (p != null) {
            try {
                p.close(Duration.ofSeconds(2));
            } catch (RuntimeException ignored) {
                // closing
            }
        }
        AdminClient a = admin;
        admin = null;
        if (a != null) {
            try {
                a.close(Duration.ofSeconds(2));
            } catch (RuntimeException ignored) {
                // closing
            }
        }
        knownTopics.clear();
        applied = Set.of();
    }

    // --- pub/sub --------------------------------------------------------------------

    @Override
    public void subscribe(String topic) {
        synchronized (wanted) {
            if (!wanted.add(topic)) {
                return;
            }
        }
        FxThread.run(() -> {
            if (!subscriptions.contains(topic)) {
                subscriptions.add(topic);
            }
        });
        if (consumer != null) {
            subscriptionDirty = true;
        } else {
            logInfo("Will subscribe to " + topic + " once connected");
        }
    }

    @Override
    public void unsubscribe(String topic) {
        synchronized (wanted) {
            wanted.remove(topic);
        }
        FxThread.run(() -> subscriptions.remove(topic));
        if (consumer != null) {
            subscriptionDirty = true; // the poll thread applies it and logs "Unsubscribed from"
        }
    }

    @Override
    public void publish(String topic, byte[] payload) {
        KafkaProducer<byte[], byte[]> p = producer;
        if (p == null) {
            logError("Not connected — connect first");
            return;
        }
        Integer part = partition;
        exec.submit(() -> {
            ensureTopic(topic);
            try {
                ProducerRecord<byte[], byte[]> rec = new ProducerRecord<>(topic, part, null, payload);
                p.send(rec, (meta, ex) -> {
                    if (ex != null) {
                        logError("Publish to " + topic + " failed", ex);
                    } else {
                        log.tx(TAG, payload, topic + " (p" + meta.partition() + " o" + meta.offset() + ")");
                    }
                });
                p.flush();
            } catch (RuntimeException e) {
                logError("Publish to " + topic + " failed", e);
            }
        });
    }

    @Override
    public void send(byte[] payload) {
        publish(defaultTopic, payload);
    }

    private void record(ConsumerRecord<byte[], byte[]> r) {
        byte[] data = r.value() == null ? new byte[0] : r.value();
        StringBuilder meta = new StringBuilder("p").append(r.partition()).append(" o").append(r.offset());
        if (r.key() != null) {
            meta.append(", key ").append(new String(r.key(), java.nio.charset.StandardCharsets.UTF_8));
        }
        if (r.headers() != null && r.headers().iterator().hasNext()) {
            int n = 0;
            for (var ignored : r.headers()) {
                n++;
            }
            meta.append(", ").append(n).append(" header(s)");
        }
        PubSubMessage m = PubSubMessage.now(r.topic(), data, meta.toString());
        FxThread.run(() -> {
            messages.add(m);
            if (messages.size() > MAX_MESSAGES) {
                messages.remove(0, messages.size() - MAX_MESSAGES);
            }
        });
        log.rx(TAG, data, r.topic() + " (" + meta + ")");
    }
}
