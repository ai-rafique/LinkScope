# LinkScope architecture

> **Inspect. Decode. Trace.** A Java 21 / JavaFX desktop instrument for testing and
> debugging data links: sockets, brokers, serial and Modbus devices, USB, HTTP/WebSocket,
> SSH/SFTP/Telnet, packet capture, and a byte-layout decoder. One window, one log, one
> preset system.

This document describes the code as it is, so a new contributor can build on it without
reverse-engineering the repository first. `README.md` is the user-facing description.

---

## 1. Environment and everyday commands

| Need | Command / fact |
|---|---|
| JDK | Eclipse Temurin 21 (toolchain pinned in `build.gradle.kts`) |
| Build + tests | `./gradlew build` |
| Run from sources | `./gradlew run` (main class `com.linkscope.app.Launcher`) |
| Self-contained app image | `./gradlew jpackageImage` → `build/jpackage/LinkScope/` |
| Windows installer | `./gradlew innoSetup` → `build/installer/LinkScope-<version>-setup.exe` (needs Inno Setup 6) |
| Portable zip | `./gradlew appImageZip` → `build/installer/LinkScope-<version>-windows-portable.zip` |
| Local brokers | `./gradlew brokersUp` / `brokersDown` (Docker Compose in `dev/`) |
| Version | Single source: `version = "x.y.z"` in `build.gradle.kts`; stamped into `com/linkscope/version.properties` by `processResources`, read by `core/AppInfo`, shown in the status bar and used for installer file names |

Windows is the only packaged platform. Linux packaging was removed on purpose; the
Ubuntu leg of CI only runs the unit tests as a portability check.

Runtime drivers that are **not** Java: Npcap (Capture tab) and USBPcap (USB tab). The
installer bundles both and offers them when absent. Everything else works without them.

---

## 2. Big picture

```
┌─ LinkScopeApp (Stage) ───────────────────────────────────────────────────────┐
│ MainController: toolbar · sidebar (groups of modules) · content · log panel   │
│   ModuleRegistry.MODULES → one FXML + Controller (+ Service) per module      │
│   PresetStore (save/load per-module fields) · Preferences (UI state)         │
│   LogSink (one observable log every module writes to)                        │
└──────────────────────────────────────────────────────────────────────────────┘
        Controller (FX thread, FXML-injected)  ⇄  Service (virtual threads, blocking I/O)
                                   FxThread.run(...) marshals every UI update back
```

**Threading rule:** the FX Application Thread never blocks. Services do I/O on virtual
threads (`AbstractService.exec`) and publish through `FxThread.run`. When no JavaFX
toolkit is running (unit tests), `FxThread.run` executes inline under a lock, which is
what makes services testable headless. Tests read observable lists through
`TestSupport.snapshot(list)` for the same reason.

**Logging rule:** all traffic and events go to `LogSink.get()` as `LogEntry`s
(`tx/rx/info/error`, tagged with the module name). `[ERROR]` lines are also surfaced as
toasts automatically. Never `System.out`. Third-party SLF4J output is routed into the
same log by `core/slf4j/LinkScopeSlf4jProvider` (WARN/ERROR only, tag `LIB`).

---

## 3. Package map

```
com.linkscope
├── app/        LinkScopeApp (JavaFX Application), Launcher (plain main),
│               MainController (shell), ModuleRegistry (the sidebar list),
│               LogPanelController, QuickSwitchPopup (Ctrl+K), ThemeManager
├── core/       AbstractService, TransportModule, ModuleController, ModuleStatus,
│               LogSink/LogEntry, FxThread, PresetStore/Preset/EnvResolver,
│               PayloadCodec (hex/ASCII escapes), PayloadDecoder (hex dump, typed views),
│               ByteLayout (decoder grammar), PcapFiles (pure-Java pcap read/tail/write),
│               NetInterfaces (physical-first adapter ranking, IPv4 networks, default gateways),
│               KnownPorts (port → service names), AnsiText, AppInfo
│   ├── help/      HelpEntry + HelpIndex: the feature catalogue (resources/help/catalogue.json)
│   │              ranked with BM25 + synonyms + typo tolerance behind the Ctrl+K palette
│   ├── protocol/  ModbusDecoder, ModbusEncoder, Crc16 (presets, params, pasted tables)
│   ├── slf4j/     bridge into LogSink
│   └── ui/        SendBarController, HistoryListController, StatusBadge, Toasts,
│                  DecoderBridge (any bytes → Decoder tab), Fields (form helpers),
│                  CellGrid (responsive labelled cell grid: hosts, port blocks, ports)
└── modules/    one package per sidebar entry (see §4), plus
                pubsub/ (shared broker panel + PubSubModule interface)
```

Resources live under `src/main/resources/com/linkscope/`: `fxml/` (one file per module
plus shared fragments `send-bar`, `history-list`, `pubsub-panel`, `ssh-connection`,
`log-panel`, `main`), `css/linkscope.css` (on top of AtlantaFX), `icons/`.

---

## 4. Modules

Sidebar order and grouping come from `ModuleRegistry.MODULES` (`id, title, group, fxml, icon`).

| Group | Module (id) | What it does | Notable internals |
|---|---|---|---|
| Sockets | UDP `udp` | bind/receive, send, broadcast, reply to last sender | `DatagramSocket` |
| | TCP `tcp` | client **and** server at once, auto-reconnect, per-client or broadcast send | `TcpService` owns a `ServerSocket` and a client `Socket`; log tags `TCP/CLIENT`, `TCP/SERVER` |
| | Multicast `multicast` | join group on an interface, TTL, loopback | Auto interface = first of `NetInterfaces.candidates()` (physical, routable IPv4 first; interfaces without IPv4 are excluded because binding them fails with `setsockopt`) |
| Brokers | NATS `nats`, Kafka `kafka`, MQTT `mqtt` | connect, subscribe (wildcards where the broker has them), publish | All implement `pubsub/PubSubModule` and share `pubsub-panel.fxml` via `PubSubPanelController` + `PubSubPanelConfig`; broker-specific options are added with `addOption(label, node, liveEditable)`. Kafka uses a random consumer group when blank. |
| Devices | Serial `serial` | port enumeration, line settings, DTR/RTS | jSerialComm |
| | Modbus `modbus` | RTU over serial or Modbus TCP, gap framing, CRC verification (presets / params / pasted table), request builder, polling | `ModbusLink` abstraction with `SerialLink` and `TcpLink`; decoding in `core/protocol` |
| | USB `usb` | live USB capture through USBPcap, device tick list, records table, **Stream view** | `UsbCaptureService` runs `USBPcapCMD -d <hub> -o - …` and parses its stdout with `PcapFiles.Stream`; the helper elevates itself (spawns an elevated worker, UAC prompt). `UsbStream` (pure) builds OUT/IN byte streams with FTDI status-byte stripping and Modbus RTU decoding. |
| | HID `hid` | live view of game controllers and other HID devices: buttons, sticks, triggers, hats, raw report; XInput rumble; HID output reports | `HidNative` (JNA over setupapi, hid.dll HidP_* parser, kernel32, xinput1_4; Windows only), `HidService` (blocking reads on a virtual thread, XInput polled at 125 Hz, 60 Hz UI flush), `ControllerView` built from `HidLayout`; pure model in `HidReport`, `XInputState`, `HidUsages` |
| Web | HTTP `http` | requests with headers/body/auth, response viewer, history | JDK `HttpClient`; `HttpRequestSpec` parses the form |
| | WebSocket `websocket` | text/binary frames, ping timing | JDK `WebSocket` |
| Remote | SSH `ssh` | password/key auth, shell console, exec | JSch fork; `SshSessions` lets other tabs reuse the login |
| | Tunnels `tunnels` | `-L`/`-R` forwards, re-established after reconnect | shares SSH via `ssh-connection.fxml` (`SshConnectionController.bind(own, allowShared)`) |
| | SFTP `sftp` | remote browser: download/upload/rename/delete/mkdir | `SftpClient` over the shared or own session |
| | Telnet `telnet` | RFC 854 option negotiation, console | negotiation replies are written synchronously on the read thread (ordering matters) |
| Tools | Capture `capture` | Wireshark-style packet table over Npcap, BPF capture filter, display filter language, pcap open/save | pcap4j; `PacketDissector` (Ethernet/ARP/IP/TCP/UDP/ICMP/DNS/Modbus TCP), `UsbPcapDissector` (link type 249), `DisplayFilter` (recursive-descent parser → `Predicate<PacketRow>`), `PacketRow.fields` for protocol-specific filter fields (`usb.*`) |
| | Decoder `decoder` | paste bytes + a layout (`2 3 5 6 4`, hints `4f 2i 5s`, groups `[2 4]*3`) → typed fields in both byte orders | `ByteLayout`, `PayloadDecoder`; any bytes anywhere reach it through `DecoderBridge.open(bytes)` |
| | Net Scan `netscan` | pick an interface, sweep its /24, 16 × 16 host grid + table | `HostGrid` (256 cells on `core/ui/CellGrid`, the width-filling grid), `ArpTable` (neighbour table after the sweep: MACs, ARP-only hosts promoted to up), gateway and self tagged via `NetInterfaces.defaultGateways()` |
| | Ports `ports` | local TCP ports: listening (owner process), in use, reserved, free; two-level grid + state tiles + collapsed ranges + "ports I need" verdicts | `PortOwners` snapshots the socket table (Windows lets a bind succeed beside an established connection, so bind-probing alone is not enough); `PortGrid` (blocks of 256 → ports) on `core/ui/CellGrid`, `PortBlocks` (aggregation, verdicts), `PortRows.collapse` folds free/reserved/unknown runs; service names from `core/KnownPorts` |

### 4.1 The module contract (how every tab is built)

Each module is an FXML view + a Controller + usually a Service.

- **Service** `extends AbstractService` (implements `TransportModule`): `moduleName()`,
  `start()`, `stop()`, `send(byte[])`, status via `setStatus(ModuleStatus)`, logging via
  `logInfo/logError`, blocking work on `exec`. Configure with setters, then `start()`.
  Never touch a JavaFX node from a service.
- **Controller** implements `core/ModuleController`: `moduleId()`, `statusProperty()`,
  `captureFields()` / `applyFields(Map)` for presets, `shutdown()`, optional
  `statusLabels()` for per-module wording (e.g. Idle / Capturing). It binds a
  `StatusBadge` to the service status (icon + text, never colour alone), wires buttons,
  and marshals nothing itself because the service already publishes on the FX thread.
- **FXML** under `fxml/<id>.fxml`, root styled `ls-module`, panels `ls-panel`, tooltips on
  every non-obvious control, sensible defaults so "Connect" works with zero typing.

- **Findable**: add the tab and its notable sub-targets to `help/catalogue.json` and implement
  `navigate(String)` in the controller for each `view:`/`focus:` action (flash the control with
  `Highlight.flash`). `HelpIndexTest` checks every module has a root entry.
### 4.2 Adding a module (checklist)

1. `modules/<id>/<Name>Service.java` and `<Name>Controller.java`, `fxml/<id>.fxml`.
2. One line in `ModuleRegistry.MODULES` (pick the group and a Feather icon `fth-*`).
3. Presets: implement `captureFields`/`applyFields`; string values may contain `${VAR}`
   placeholders, resolved by `PresetStore.resolve` from `.env` then the environment.
4. Tests: a loopback-style JUnit test where possible (see §6). Gate hardware/broker
   tests with `TestSupport.integrationSkipped()` or an environment variable.
5. `README.md` module list, then the installer (§7). No `MainController` special-casing.

For a new broker, implement `PubSubModule` and reuse `pubsub-panel.fxml`; do not fork
the panel. For anything that produces bytes, offer "send to Decoder".

---

## 5. Shared state and files

| What | Where |
|---|---|
| Presets | `~/.linkscope/presets.json` (`PresetStore`, Jackson, format version 1). One list of `{module, name, fields}`. Secrets belong in presets only as `${VAR}`. |
| `.env` | project working directory, loaded once by `EnvResolver` (dotenv-java); unresolved placeholders are logged as `[UNSET: VAR]`. `.env.example` is committed, `.env` is ignored. |
| UI preferences | `java.util.prefs` node `com/linkscope`: `module` (last selected id), `theme`, `sidebarCollapsed`, `logVisible`, `collapsedGroups`. On Windows this is `HKCU\Software\JavaSoft\Prefs\com\linkscope` (capitals are stored escaped, e.g. `sidebar/Collapsed`). |
| Log | in memory only, capped at `LogSink.MAX_ENTRIES` (20 000); export as text/CSV from the log panel. Hidden by default, toggled from the toolbar or Ctrl+`. |
| Keyboard | Ctrl+K find a feature (palette over the help catalogue), Ctrl+1..9 modules, Ctrl+B sidebar, Ctrl+` log, Ctrl+L clear log, Ctrl+H hex, Ctrl+I selected log line → Decoder, Enter sends, Up/Down history. |
| Theme | AtlantaFX Primer light/dark, switched at runtime by `ThemeManager`; app CSS tokens in `linkscope.css` (`ls-mono`, `ls-hint`, `ls-error-text`, `ls-warning-text`, …). |

---

## 6. Tests

- JUnit 5 (`junit-bom`), run by `./gradlew build`. Suite size at 1.1.1: 242 tests.
- `src/test/java/com/linkscope/TestSupport.java`: `awaitTrue`, `snapshot`, `logged`,
  `dumpLog`, `integrationSkipped` (`SKIP_INTEGRATION=1`, set on CI).
- Loopback tests run anywhere: UDP, TCP, multicast, HTTP, WebSocket, Telnet, Modbus
  framing, Net Scan, Ports, pcap file reader/writer, dissectors, display filters, USB
  stream model.
- Broker tests (`PubSubModuleContractTest` run against NATS, Kafka, MQTT) need the
  Docker stack and skip otherwise. Ports 1805–1904 can sit inside a Windows reserved
  range, so MQTT tests may need a container on another host port (`dev/.env`
  `MQTT_PORT`).
- Hardware/environment-gated: `LINKSCOPE_SERIAL_PAIR=COM5,COM6`,
  `LINKSCOPE_SSH_TARGET=user:pass@host:22`, Npcap present (Capture round trips and
  loopback capture), `LINKSCOPE_USB_LIVE=1` for the live USB capture (it can raise a UAC
  prompt on the desktop running the tests), `LINKSCOPE_HID_LIVE=1` to open, read and close
  every non-exclusive HID device on the machine.

---

## 7. Packaging details

- badass-runtime builds a jlink image from the jdeps module list plus pinned extras
  (`java.net.http`, `java.naming`, `java.management`, `java.security.sasl`, `jdk.charsets`,
  `jdk.crypto.ec`, `jdk.unsupported`) and runs jpackage in classpath mode. No
  `module-info.java` anywhere; several dependencies are automatic modules only.
- Inno Setup script: `packaging/linkscope.iss`. The `innoSetup` task passes version,
  folders and the two driver installers as defines. Tasks `fetchNpcap` and `fetchUsbPcap`
  copy from `packaging/npcap/` and `packaging/usbpcap/` (git-ignored) or download the
  pinned versions. The installer offers each driver only when it is not already present;
  USBPcap needs a Windows restart afterwards.
- Icons: `packaging/linkscope.ico` for jpackage/Inno, `icons/linkscope-*.png` in the app.
- CI: `.github/workflows/build.yml` runs the tests on Ubuntu and Windows and builds the
  installer and zip on Windows.

**Feature-done checklist**: `./gradlew build innoSetup appImageZip`, update `README.md`
and this document if the architecture moved, then commit with a Conventional Commits
message (§9).

---

## 8. Things that bit us (read before debugging)

- **Multicast join fails with `Invalid argument: setsockopt`**: Java lists NDIS
  filter-driver shadow interfaces on Windows (names ending `…-Npcap Packet Driver-0000`,
  `…-QoS Packet Scheduler-0000`). They look up and multicast-capable but have no IPv4.
  `NetInterfaces.candidates()` filters them out; keep it that way.
- **Windows reports stale sizes for files another process holds open**, and
  `USBPcapCMD -o file` opens its output exclusively. Hence USB capture streams through
  stdout (`-o -`); do not go back to tailing a file.
- **Observable lists in headless tests**: read through `TestSupport.snapshot`, or a
  `ConcurrentModificationException` will appear only on CI.
- **Gradle implicit dependencies**: archive tasks that read jpackage output declare
  `mustRunAfter("jpackage")`.
- **`java.net` in `build.gradle.kts`** resolves to the `java` extension; import
  `java.net.URI` at the top of the script instead of qualifying inline.
- **PowerShell quoting** in helper launches: `psString()` in `UsbCaptureService` quotes
  for PowerShell; keep using it for `-Verb RunAs` launches.
- **Serial and Telnet ordering**: protocol replies that must precede the next read are
  written on the read thread, not via the executor.
- **Screenshots while developing** (Windows): `PrintWindow` on the `java` process whose
  title is `LinkScope`; IntelliJ's window carries the same title, so filter by process.
  Set the `module` preference before launch to open on a given tab.

---

## 9. Contributing conventions

- **Commits**: Conventional Commits, one module or one concern per commit, README
  updated alongside. Types `feat`, `fix`, `refactor`, `test`, `docs`, `chore`, `build`,
  `perf`; scope = module id (`udp`, `tcp`, `usb`, …) or `core`, `ui`, `presets`, `build`,
  `ci`. Branches `type/scope-short-description`.
- **Never committed**: `.env`, `build/`, `packaging/npcap/`, `packaging/usbpcap/`, any
  file holding a real broker address, token or credential. Presets reference `${VAR}`
  placeholders, not literal secrets.
- **Dependencies**: keep the footprint intentional; discuss before adding anything beyond
  the current set (JavaFX, AtlantaFX, Ikonli Feather, jnats, kafka-clients, Paho MQTT v5,
  jSerialComm, JSch fork, pcap4j, Jackson, dotenv-java, SLF4J API; Java-WebSocket
  test-only).
- **Licences of bundled drivers**: Npcap's free licence does not allow redistribution;
  bundling it is a deliberate choice for a tool that stays in-house. USBPcap is GPL-2.
- **UI principles**: status is never colour-only, errors are toasts not dialogs, every
  non-obvious control has a tooltip, defaults let a loopback test run with zero typing,
  empty states say what to do, one 8 px spacing unit, no one-off pixel values.
- **Threading and logging rules** in §2 are not optional; document any exception here
  before adding it.
