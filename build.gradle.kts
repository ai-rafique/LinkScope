import java.net.HttpURLConnection
import java.net.URI

plugins {
    java
    application
    id("org.openjfx.javafxplugin") version "0.1.0"
    id("org.beryx.runtime") version "2.0.1"
}

group = "com.linkscope"
// major.minor.patch; drives the status bar text, installer file names and jpackage's app version.
version = "0.2.1"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

javafx {
    version = "21.0.12"
    modules = listOf("javafx.controls", "javafx.fxml")
}

application {
    // Launcher is a plain class (does not extend javafx.application.Application) so the
    // app starts correctly from the classpath, which is how badass-runtime/jpackage run it.
    mainClass.set("com.linkscope.app.Launcher")
    applicationName = "LinkScope"
}

dependencies {
    // Theme + icons
    implementation("io.github.mkpaz:atlantafx-base:2.1.0")
    implementation("org.kordamp.ikonli:ikonli-javafx:12.4.0")
    implementation("org.kordamp.ikonli:ikonli-feather-pack:12.4.0")

    // Transports (v1)
    implementation("io.nats:jnats:2.26.3")
    implementation("com.fazecast:jSerialComm:2.11.4")

    // Transports (v2)
    implementation("org.apache.kafka:kafka-clients:4.3.0")
    implementation("org.eclipse.paho:org.eclipse.paho.mqttv5.client:1.2.5")

    // Remote tools: SSH via the maintained JSch fork (pure Java; ed25519 and rsa-sha2 supported).
    implementation("com.github.mwiede:jsch:0.2.25")

    // Packet capture (Capture tool): pcap4j over JNA; needs the Npcap driver at runtime.
    implementation("org.pcap4j:pcap4j-core:1.8.2")
    implementation("org.pcap4j:pcap4j-packetfactory-static:1.8.2")

    // Logging: kafka-clients and Paho log via SLF4J; LinkScope ships its own provider
    // (core/slf4j) that forwards WARN/ERROR into the app log. Already transitive, pinned here
    // so the provider's REQUESTED_API_VERSION stays in step with the API on the classpath.
    implementation("org.slf4j:slf4j-api:2.0.17")

    // Presets / config
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.2")
    implementation("io.github.cdimascio:dotenv-java:3.2.0")

    // Testing
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    // WebSocket echo server for WebSocketServiceTest only; the app itself uses java.net.http.WebSocket.
    testImplementation("org.java-websocket:Java-WebSocket:1.6.0")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

// Stamp the build version into the resource the status bar reads (see core/AppInfo).
tasks.processResources {
    val appVersion = project.version.toString()
    inputs.property("appVersion", appVersion)
    filesMatching("com/linkscope/version.properties") {
        expand("version" to appVersion)
    }
}

tasks.test {
    useJUnitPlatform()
    // Integration tests (NATS/Kafka/MQTT/serial hardware) check this and skip themselves.
    environment("SKIP_INTEGRATION", System.getenv("SKIP_INTEGRATION") ?: "")
}

// --- Local broker stack for development and integration tests (dev/docker-compose.yml) ------
// `./gradlew brokersUp` starts NATS, Redpanda and Mosquitto; `brokersDown` stops them and wipes volumes.

tasks.register<Exec>("brokersUp") {
    group = "development"
    description = "Starts the local NATS, Redpanda and Mosquitto brokers with Docker Compose."
    commandLine("docker", "compose", "-f", "dev/docker-compose.yml", "up", "-d")
}

tasks.register<Exec>("brokersDown") {
    group = "development"
    description = "Stops the local brokers and removes their volumes."
    commandLine("docker", "compose", "-f", "dev/docker-compose.yml", "down", "-v")
}

// --- Windows distribution without WiX: Inno Setup installer + portable zip -------------------
// `./gradlew innoSetup` -> build/installer/LinkScope-<version>-setup.exe (needs Inno Setup 6;
// looked up via INNO_SETUP_HOME, the default install folders, then PATH).
// `./gradlew appImageZip` -> build/installer/LinkScope-<version>-windows-portable.zip (no tools needed).

fun findIscc(): File? {
    val candidates = listOfNotNull(
        System.getenv("INNO_SETUP_HOME")?.let { File(it, "ISCC.exe") },
        File(System.getenv("ProgramFiles(x86)") ?: "C:/Program Files (x86)", "Inno Setup 6/ISCC.exe"),
        File(System.getenv("ProgramFiles") ?: "C:/Program Files", "Inno Setup 6/ISCC.exe"),
        System.getenv("LOCALAPPDATA")?.let { File(it, "Programs/Inno Setup 6/ISCC.exe") }
    )
    val onPath = (System.getenv("PATH") ?: "").split(File.pathSeparator).map { File(it, "ISCC.exe") }
    return (candidates + onPath).firstOrNull { it.isFile }
}

val distVersion = project.version.toString().substringBefore("-")
val appImageDir = layout.buildDirectory.dir("jpackage/LinkScope")
val installerDir = layout.buildDirectory.dir("installer")

// Npcap installer bundled into the Windows setup and offered as an optional task when the
// machine has no capture driver. A copy dropped into packaging/npcap/ is used as-is; otherwise
// the official build is fetched from npcap.com once into build/npcap/.
val npcapVersion = "1.89"
val npcapFileName = "npcap-$npcapVersion.exe"
val npcapLocal = layout.projectDirectory.file("packaging/npcap/$npcapFileName")
val npcapCached = layout.buildDirectory.file("npcap/$npcapFileName")

tasks.register("fetchNpcap") {
    group = "distribution"
    description = "Fetches the Npcap installer for bundling into the Windows setup (skipped if packaging/npcap/ has it)."
    onlyIf { org.gradle.internal.os.OperatingSystem.current().isWindows }
    outputs.file(npcapCached)
    doLast {
        val target = npcapCached.get().asFile
        if (npcapLocal.asFile.isFile) {
            npcapLocal.asFile.copyTo(target, overwrite = true)
            return@doLast
        }
        if (target.isFile && target.length() > 500_000) {
            return@doLast
        }
        target.parentFile.mkdirs()
        val url = URI("https://npcap.com/dist/$npcapFileName").toURL()
        logger.lifecycle("Downloading $url")
        val conn = url.openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        conn.instanceFollowRedirects = true
        conn.inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }
        if (target.length() < 500_000) {
            target.delete()
            throw GradleException("Npcap download looks wrong (too small). Place $npcapFileName under packaging/npcap/ manually.")
        }
    }
}

// USBPcap installer (GPL-2, from https://github.com/desowin/usbpcap) bundled the same way and offered
// when the machine has no USBPcap; it needs a restart before the USB tool can capture.
val usbpcapVersion = "1.5.4.0"
val usbpcapFileName = "USBPcapSetup-$usbpcapVersion.exe"
val usbpcapLocal = layout.projectDirectory.file("packaging/usbpcap/$usbpcapFileName")
val usbpcapCached = layout.buildDirectory.file("usbpcap/$usbpcapFileName")

tasks.register("fetchUsbPcap") {
    group = "distribution"
    description = "Fetches the USBPcap installer for bundling into the Windows setup (skipped if packaging/usbpcap/ has it)."
    onlyIf { org.gradle.internal.os.OperatingSystem.current().isWindows }
    outputs.file(usbpcapCached)
    doLast {
        val target = usbpcapCached.get().asFile
        if (usbpcapLocal.asFile.isFile) {
            usbpcapLocal.asFile.copyTo(target, overwrite = true)
            return@doLast
        }
        if (target.isFile && target.length() > 500_000) {
            return@doLast
        }
        target.parentFile.mkdirs()
        val url = URI("https://github.com/desowin/usbpcap/releases/download/$usbpcapVersion/$usbpcapFileName").toURL()
        logger.lifecycle("Downloading $url")
        val conn = url.openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        conn.instanceFollowRedirects = true
        conn.inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }
        if (target.length() < 500_000) {
            target.delete()
            throw GradleException("USBPcap download looks wrong (too small). Place $usbpcapFileName under packaging/usbpcap/ manually.")
        }
    }
}

tasks.register<Exec>("innoSetup") {
    group = "distribution"
    description = "Builds a Windows setup.exe from the jpackage app image with Inno Setup."
    dependsOn("jpackageImage", "fetchNpcap", "fetchUsbPcap")
    mustRunAfter("jpackage")
    onlyIf { org.gradle.internal.os.OperatingSystem.current().isWindows }
    inputs.dir(appImageDir)
    inputs.file(layout.projectDirectory.file("packaging/linkscope.iss"))
    inputs.file(layout.projectDirectory.file("packaging/linkscope.ico"))
    inputs.file(npcapCached)
    inputs.file(usbpcapCached)
    outputs.file(installerDir.map { it.file("LinkScope-$distVersion-setup.exe") })
    val iscc = findIscc()
    executable = iscc?.absolutePath ?: "ISCC.exe"
    args(
        "/Q",
        "/DAppVersion=$distVersion",
        "/DSourceDir=${appImageDir.get().asFile.absolutePath}",
        "/DOutputDir=${installerDir.get().asFile.absolutePath}",
        "/DNpcapInstaller=${npcapCached.get().asFile.absolutePath}",
        "/DNpcapVersion=$npcapVersion",
        "/DUsbPcapInstaller=${usbpcapCached.get().asFile.absolutePath}",
        "/DUsbPcapVersion=$usbpcapVersion",
        layout.projectDirectory.file("packaging/linkscope.iss").asFile.absolutePath
    )
    doFirst {
        if (iscc == null) {
            throw GradleException("Inno Setup 6 not found. Install it from https://jrsoftware.org/isinfo.php or set INNO_SETUP_HOME to its folder.")
        }
        installerDir.get().asFile.mkdirs()
    }
    doLast {
        logger.lifecycle("Installer: ${installerDir.get().asFile}/LinkScope-$distVersion-setup.exe")
    }
}

tasks.register<Zip>("appImageZip") {
    group = "distribution"
    description = "Zips the jpackage app image as a portable Windows build."
    dependsOn("jpackageImage")
    mustRunAfter("jpackage")
    onlyIf { org.gradle.internal.os.OperatingSystem.current().isWindows }
    from(appImageDir)
    into("LinkScope")
    archiveFileName.set("LinkScope-$distVersion-windows-portable.zip")
    destinationDirectory.set(installerDir)
}

// badass-runtime: trimmed jlink image from the jdeps-suggested JDK module list,
// then jpackage in classpath mode. No module-info.java anywhere: the app stays non-modular
// because several dependencies only ship as automatic modules.
runtime {
    options.set(listOf("--strip-debug", "--compress", "zip-6", "--no-header-files", "--no-man-pages"))
    // jdeps misses modules that are only reached reflectively or via service loaders.
    additive.set(true)
    // java.net.http (HTTP/WebSocket modules) is not reported by jdeps here; jdk.charsets keeps
    // exotic serial-device encodings available; the rest cover JNDI/JMX/TLS used by the broker clients.
    modules.set(listOf("java.net.http", "java.naming", "java.management", "java.security.sasl",
            "jdk.charsets", "jdk.crypto.ec", "jdk.unsupported"))

    launcher {
        noConsole = true
    }

    jpackage {
        imageName = "LinkScope"
        installerName = "LinkScope"
        // jpackage (and MSI) require a purely numeric version: strip any -SNAPSHOT/-rc suffix.
        val appVersion = project.version.toString().substringBefore("-")
        // Windows is the only packaged platform. Branding comes from packaging/linkscope.ico.
        val iconFile = layout.projectDirectory.file("packaging/linkscope.ico")
        val iconOptions = if (iconFile.asFile.exists()) listOf("--icon", iconFile.asFile.absolutePath) else emptyList()
        imageOptions = listOf("--app-version", appVersion) + iconOptions
        // jpackage's own MSI needs WiX; the innoSetup task above is the supported installer route.
        // These options only matter if someone runs `jpackage` directly.
        installerOptions = listOf("--app-version", appVersion, "--vendor", "LinkScope",
                "--win-menu", "--win-shortcut", "--win-dir-chooser")
    }
}
