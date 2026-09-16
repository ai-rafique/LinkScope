# LinkScope

**Inspect. Decode. Trace.**

LinkScope is a desktop instrument for testing, debugging and exercising data links during
development. It replaces the usual pile of `netcat`, `socat`, a serial terminal, `curl`,
broker CLIs and an SSH client with one window, one log format and one preset system that
work the same way across every transport.

Built with Java 21 and JavaFX. Runs on Windows and Linux with no separate Java install.

## What it does

Modules live in a sidebar, grouped by kind. Every module writes to the same log, saves its
setup as a named preset, and shows its state with an icon and a word, never colour alone.

**Sockets**
- **UDP** – listen on a port, send to any host, broadcast, reply to the last sender.
- **TCP** – client and server in one tab, both live at once. Auto-reconnect on the client;
  many clients on the server with per-client or broadcast send.
- **Multicast** – join a group on a chosen interface with TTL and loopback control.

**Brokers** (one shared panel: broker list, subscriptions, publish box, message table)
- **NATS** – subjects with `*` and `>` wildcards, request/reply.
- **Kafka** – topics with consumer group id, partition selector, partition/offset metadata;
  missing topics are created on first use.
- **MQTT** – `+` and `#` wildcards, QoS 0/1/2 on subscribe and publish, retained messages
  tagged on receipt, optional client id.

**Devices**
- **Serial** – port enumeration, baud, data bits, parity, stop bits, flow control, DTR/RTS.
- **Modbus** – RTU over serial or Modbus TCP: frames split on the inter-frame gap, verified
  with a configurable CRC (presets, custom parameters, or a pasted lookup table from a
  vendor manual), decoded per function code; request builder with polling.

**Web**
- **HTTP** – GET/POST/PUT/PATCH/DELETE/HEAD/OPTIONS, headers, raw/JSON/form bodies, Basic
  and Bearer auth, response viewer with timing and pretty JSON, request history.
- **WebSocket** – text and binary frames, handshake headers, ping with round-trip timing.

**Remote**
- **SSH** – password or key auth, host keys remembered, interactive shell console and
  one-shot exec with exit codes.
- **Tunnels** – local (`-L`) and remote (`-R`) port forwards, started and stopped
  individually, re-established after a reconnect.
- **SFTP** – remote file browser with download, upload, rename, delete and new folder.
- **Telnet** – RFC 854 option negotiation handled, so telnet daemons and old lab gear behave.

Tunnels and SFTP can reuse the SSH tab's login instead of connecting again.

**Tools**
- **Decoder** – paste bytes, describe the layout as field widths (`2 3 5 6 4`, with hints
  like `4f 2i 5s` and repeated groups `[2 4]*3`), and read every field as integers, floats
  and text in both byte orders. Any log line can be sent here.
- **Net Scan** – sweep a /24 to find which addresses answer (ping plus common TCP ports).
- **Ports** – survey local TCP ports on an interface: listening (with owning process),
  in use, reserved by the OS, or free.

**Shared log** – every TX/RX byte from every module in one place with hex/ASCII, absolute
or delta timestamps, per-module and per-kind filter chips, text filter, and export as text
or CSV. Hidden by default; the toolbar button shows it.

**Presets** – each module saves its fields under a name in `~/.linkscope/presets.json`.
Any string field may contain `${VAR}` placeholders, resolved from a `.env` file in the
working directory first and the process environment second. Secrets (passwords, tokens)
are only stored in presets as placeholders. See `.env.example`.

**Keyboard** – `Ctrl+K` jump to a module, `Ctrl+1..9` first nine modules, `Ctrl+B`
collapse the sidebar, `` Ctrl+` `` show/hide the log, `Ctrl+L` clear it, `Ctrl+H` hex,
`Ctrl+I` send the selected log line to the Decoder, `Enter` sends in any payload field,
`Up`/`Down` recall history.

## Install

Download from the Releases page or the CI artifacts:

- **Windows**: `LinkScope-<version>-setup.exe` (installer, per-user by default) or
  `LinkScope-<version>-windows-portable.zip` (unzip and run `LinkScope.exe`).
- **Linux**: `linkscope_<version>_amd64.deb` or `LinkScope-<version>-linux-portable.tar.gz`.
  Serial ports need your user in the `dialout` group.

Both bundle a trimmed Java runtime; nothing else is required.

## Build from source

Requires JDK 21 (Eclipse Temurin recommended). Gradle comes with the wrapper.

```
./gradlew build            # compile and test
./gradlew run              # start the app
./gradlew jpackageImage    # self-contained app image in build/jpackage/LinkScope
./gradlew innoSetup        # Windows setup.exe (needs Inno Setup 6)
./gradlew appImageZip      # Windows portable zip
./gradlew linuxDeb         # Linux .deb (needs dpkg-deb and fakeroot)
./gradlew appImageTar      # Linux portable tarball
```

Outputs land in `build/installer/`. The version comes from `build.gradle.kts`.

## Local brokers for testing

`dev/docker-compose.yml` brings up NATS, a Kafka-compatible Redpanda and Mosquitto on
their default ports: `localhost:4222`, `localhost:9092`, `localhost:1883`. The app's
field defaults and `.env.example` point at these.

```
./gradlew brokersUp      # docker compose -f dev/docker-compose.yml up -d
./gradlew brokersDown    # stop and wipe volumes
```

Host ports can be overridden through `dev/.env` (see `dev/.env.example`), useful where
1883 is inside a reserved range. Details in `dev/README.md`.

## Tests

`./gradlew build` runs the JUnit suite. Loopback tests (UDP, TCP, multicast, HTTP,
WebSocket, Telnet, Modbus framing, Net Scan, Ports) run everywhere. Broker tests run when
the brokers are reachable and skip otherwise; set `SKIP_INTEGRATION=1` to skip them
outright, as CI does. Hardware-dependent tests read their targets from environment
variables: `LINKSCOPE_SERIAL_PAIR=COM5,COM6` for a virtual serial pair and
`LINKSCOPE_SSH_TARGET=user:password@host:22` for SSH, SFTP and tunnel round trips.

## Dependencies

JavaFX, AtlantaFX (theme), Ikonli (icons), jnats, kafka-clients, Eclipse Paho MQTT v5,
jSerialComm, the maintained JSch fork, Jackson, dotenv-java, SLF4J API. HTTP and WebSocket
use the JDK's built-in clients. Java-WebSocket is used only as a test echo server.

## License

Not chosen yet. Until a license file is added, all rights reserved.
