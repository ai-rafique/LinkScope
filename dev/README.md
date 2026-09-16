# Local broker stack

Development aid for LinkScope: three brokers in Docker so the broker modules and their
tests have something to talk to. Not part of the shipped application.

```
docker compose -f dev/docker-compose.yml up -d     # or:  ./gradlew brokersUp
docker compose -f dev/docker-compose.yml down -v   # or:  ./gradlew brokersDown
```

| Service | Image | Host port | Used by |
|---|---|---|---|
| NATS | `nats` | 4222 (client), 8222 (monitoring UI) | NATS module |
| Redpanda | `redpandadata/redpanda` | 9092 (Kafka API), 8082, 9644 | Kafka module |
| Mosquitto | `eclipse-mosquitto` | 1883 (MQTT), 9001 (MQTT over WebSockets) | MQTT module |

Redpanda speaks the Kafka wire protocol and is much lighter than Kafka plus ZooKeeper;
the app's Kafka client cannot tell the difference. Mosquitto's config allows anonymous
connections on both listeners.

## Changing host ports

Copy `.env.example` to `.env` in this folder and edit it. On some Windows machines
port 1883 falls inside a range Hyper-V reserves; set `MQTT_PORT=11883` there, point the
MQTT tab at `localhost:11883`, and run the tests with `MQTT_BROKER=localhost:11883`.

## Tests

With the stack up, `./gradlew build` runs the broker suites for real instead of skipping
them. Set `SKIP_INTEGRATION=1` to skip them regardless, as CI does.
