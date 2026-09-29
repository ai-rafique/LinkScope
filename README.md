# LinkScope

**Inspect. Decode. Trace.**

LinkScope is a desktop instrument for testing, debugging and exercising data links during
development. It replaces the usual pile of `netcat`, `socat`, a serial terminal, `curl`,
broker CLIs and an SSH client with one window, one log format and one preset system that
work the same way across every transport.

Built with Java 21 and JavaFX. Packaged for Windows with no separate Java install.

## What it does

Modules live in a sidebar, grouped by kind. Every module writes to the same log, saves its
setup as a named preset, and shows its state with an icon and a word, never colour alone.

**Sockets**
- **UDP** – listen on a port, send to any host, broadcast, reply to the last sender.
- **TCP** – client and server in one tab, both live at once. Auto-reconnect on the client;
  many clients on the server with per-client or broadcast send.
- **Multicast** – join a group on a chosen interface with TTL and loopback control. The
  Auto choice prefers physical adapters over virtual ones (Hyper-V, VPN, WSL, loopback).

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
- **USB** – live USB capture through USBPcap: pick a root hub, tick the devices to watch,
  and every transfer between Windows and those devices appears as it happens, below the
  device driver, so it shows what any program sends to a USB-serial adapter or HID device
  and what comes back. Same table, decoding and `usb.*` filters as USB files in Capture;
  records save as `.pcap` for Wireshark. Needs administrator rights: Windows asks when the
  capture starts unless LinkScope already runs elevated. A warning appears when the ticked
  devices include a keyboard, since that capture would contain keystrokes. The **Stream**
  view turns one device's bulk and interrupt transfers into the two byte streams a serial
  terminal would show, OUT and IN, merging back-to-back transfers, optionally stripping
  FTDI status bytes (detected from the device descriptor), rendered as hex and text or
  decoded as Modbus RTU, with either direction sendable to the Decoder or the clipboard.

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
- **Capture** – a packet sniffer for one interface: frames are read straight off the adapter,
  so traffic shows up even when another program owns the port. Each packet is listed with
  time, source, destination, ports, protocol, length and a one-line summary (ARP, ICMP,
  DNS, TCP flags, Modbus TCP, and well-known ports such as MQTT, NATS, Kafka). A capture
  filter in BPF syntax (`udp port 16004`, `host 192.168.1.10`) limits what the driver
  hands over; a display filter narrows the table as you type, with fields such as
  `ip.addr == 192.168.1.`, `port == 1883`, `len > 100`, `contains "hello"`, bare names
  like `udp`, `arp`, `mqtt`, and `and` / `or` / `not` with parentheses. Right-click a
  packet to filter its conversation or send its payload to the Decoder. Captures open and
  save as `.pcap`, so they round-trip with Wireshark. Needs the Npcap driver (see Install).
  USB captures made with Wireshark's USBPcap interface open too: rows are addressed as
  `bus.device.endpoint`, the protocol column shows the transfer type, control setup packets
  and device, configuration and string descriptors are decoded, and vendor requests carry
  FTDI/CP210x hints. Filter with `usb.addr == 1.5`, `usb.endpoint == 1`, `usb.dir == in`,
  `usb.type == bulk` or bare words such as `bulk`, `control`, `interrupt`.
- **Decoder** – paste bytes, describe the layout as field widths (`2 3 5 6 4`, with hints
  like `4f 2i 5s` and repeated groups `[2 4]*3`), and read every field as integers, floats
  and text in both byte orders. Any log line can be sent here.
- **Net Scan** – pick a network interface and sweep its /24. Every address is a cell on a
  16 × 16 grid that fills as answers arrive: filled for ping or an open port, amber for
  hosts that answered ARP only (present but silent), hollow for no answer, with this PC
  and the gateway marked. The table below lists the hosts that are up with MAC address,
  hostname, ping time and open ports; copy the list with one click.
- **Ports** – survey local TCP ports on an interface. A strip shows every scanned port
  as a coloured slice, state tiles (listening, in use, reserved, unknown, free) give the
  counts and filter the table, and runs of free ports fold into ranges so the listening
  and in-use ports stand out, each with its process and well-known service name.

Interface dropdowns (Multicast, Capture, Net Scan, Ports) rescan the adapters whenever
they are opened, and each has a refresh button, so a cable plugged in after launch shows
up without restarting.

**Shared log** – every TX/RX byte from every module in one place with hex/ASCII, absolute
or delta timestamps, per-module and per-kind filter chips, text filter, and export as text
or CSV. Hidden by default; the toolbar button shows it.

**Presets** – each module saves its fields under a name in `~/.linkscope/presets.json`.
Any string field may contain `${VAR}` placeholders, resolved from a `.env` file in the
working directory first and the process environment second. Secrets (passwords, tokens)
are only stored in presets as placeholders. See `.env.example`.

**Status bar** – the selected module and its state on the left; the version
(major.minor.patch) and rights line on the right, with build details in its tooltip.

**Keyboard** – `Ctrl+K` jump to a module, `Ctrl+1..9` first nine modules, `Ctrl+B`
collapse the sidebar, `` Ctrl+` `` show/hide the log, `Ctrl+L` clear it, `Ctrl+H` hex,
`Ctrl+I` send the selected log line to the Decoder, `Enter` sends in any payload field,
`Up`/`Down` recall history.

## Install

Download from the Releases page or the CI artifacts:

- **Windows**: `LinkScope-<version>-setup.exe` (installer, per-user by default) or
  `LinkScope-<version>-windows-portable.zip` (unzip and run `LinkScope.exe`).
Both bundle a trimmed Java runtime; nothing else is required.

The Capture tool is the one exception: it needs the [Npcap](https://npcap.com/) packet
capture driver. The setup.exe bundles the Npcap installer and offers to run it when the
machine has none (its own wizard appears; keep the defaults). The portable zip does not
include it; install Npcap yourself, or Wireshark, which ships it. The USB tool likewise needs
the [USBPcap](https://github.com/desowin/usbpcap) driver, which the setup.exe also bundles and
offers when missing; Windows must restart once after installing it. Every other module works
without either driver; the Capture and USB tabs just report what is missing.

## Contributing

`docs/ARCHITECTURE.md` explains how the code is organised, the module contract, where
shared state lives, how tests are gated, packaging, and the pitfalls already met. Read it
before adding a module.

## Build from source

Requires JDK 21 (Eclipse Temurin recommended). Gradle comes with the wrapper.

```
./gradlew build            # compile and test
./gradlew run              # start the app
./gradlew jpackageImage    # self-contained app image in build/jpackage/LinkScope
./gradlew innoSetup        # Windows setup.exe (needs Inno Setup 6)
./gradlew appImageZip      # Windows portable zip
```

Outputs land in `build/installer/`. The version comes from `build.gradle.kts`. The
`innoSetup` task fetches the Npcap and USBPcap installers once into `build/npcap/` and
`build/usbpcap/`; to build offline, put them under `packaging/npcap/` and `packaging/usbpcap/`
instead.

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
WebSocket, Telnet, Modbus framing, Net Scan, Ports, packet dissection and display filters)
run everywhere, as do the pcap file reader/writer and USBPcap dissector tests. The pcap
round-trip and loopback capture tests skip without Npcap; the live USB capture test skips
unless USBPcap is installed and the test process runs elevated.
Broker tests run when
the brokers are reachable and skip otherwise; set `SKIP_INTEGRATION=1` to skip them
outright, as CI does. Hardware-dependent tests read their targets from environment
variables: `LINKSCOPE_SERIAL_PAIR=COM5,COM6` for a virtual serial pair and
`LINKSCOPE_SSH_TARGET=user:password@host:22` for SSH, SFTP and tunnel round trips.

## Dependencies

JavaFX, AtlantaFX (theme), Ikonli (icons), jnats, kafka-clients, Eclipse Paho MQTT v5,
jSerialComm, the maintained JSch fork, pcap4j (packet capture, over Npcap),
Jackson, dotenv-java, SLF4J API. HTTP and WebSocket use the JDK's built-in clients. Java-WebSocket is used only as a test echo server.

## License

Not chosen yet. Until a license file is added, all rights reserved.
