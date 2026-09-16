plugins {
    java
    application
    id("org.openjfx.javafxplugin") version "0.1.0"
    id("org.beryx.runtime") version "2.0.1"
}

group = "com.linkscope"
version = "0.1.0-SNAPSHOT"

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

tasks.test {
    useJUnitPlatform()
    // Integration tests (NATS/Kafka/MQTT/serial hardware) check this and skip themselves.
    environment("SKIP_INTEGRATION", System.getenv("SKIP_INTEGRATION") ?: "")
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

tasks.register<Exec>("innoSetup") {
    group = "distribution"
    description = "Builds a Windows setup.exe from the jpackage app image with Inno Setup."
    dependsOn("jpackageImage")
    mustRunAfter("jpackage")
    onlyIf { org.gradle.internal.os.OperatingSystem.current().isWindows }
    inputs.dir(appImageDir)
    inputs.file(layout.projectDirectory.file("packaging/linkscope.iss"))
    inputs.file(layout.projectDirectory.file("packaging/linkscope.ico"))
    outputs.file(installerDir.map { it.file("LinkScope-$distVersion-setup.exe") })
    val iscc = findIscc()
    executable = iscc?.absolutePath ?: "ISCC.exe"
    args(
        "/Q",
        "/DAppVersion=$distVersion",
        "/DSourceDir=${appImageDir.get().asFile.absolutePath}",
        "/DOutputDir=${installerDir.get().asFile.absolutePath}",
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

// Linux portable build: a tarball keeps the launcher's executable bit, which a zip may not.
// `./gradlew appImageTar` -> build/installer/LinkScope-<version>-linux-portable.tar.gz
tasks.register<Tar>("appImageTar") {
    group = "distribution"
    description = "Tars the jpackage app image as a portable Linux build."
    dependsOn("jpackageImage")
    mustRunAfter("jpackage")
    onlyIf { org.gradle.internal.os.OperatingSystem.current().isLinux }
    from(appImageDir)
    into("LinkScope")
    compression = Compression.GZIP
    archiveFileName.set("LinkScope-$distVersion-linux-portable.tar.gz")
    destinationDirectory.set(installerDir)
}

// Copies the jpackage-built .deb next to the other distributables so CI uploads one folder.
tasks.register<Copy>("linuxDeb") {
    group = "distribution"
    description = "Builds the .deb with jpackage and copies it to build/installer."
    dependsOn("jpackage")
    onlyIf { org.gradle.internal.os.OperatingSystem.current().isLinux }
    from(layout.buildDirectory.dir("jpackage")) {
        include("*.deb")
    }
    into(installerDir)
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
        // Branding: .ico on Windows, .png on Linux; macOS wants .icns (not generated yet, so it falls back to the default).
        val os = org.gradle.internal.os.OperatingSystem.current()
        val iconFile = when {
            os.isWindows -> layout.projectDirectory.file("packaging/linkscope.ico")
            os.isLinux -> layout.projectDirectory.file("packaging/linkscope.png")
            else -> null
        }
        val iconOptions = if (iconFile != null && iconFile.asFile.exists()) listOf("--icon", iconFile.asFile.absolutePath) else emptyList()
        imageOptions = listOf("--app-version", appVersion) + iconOptions
        // Installer flags are platform-specific. Linux: a .deb via jpackage (needs dpkg-deb + fakeroot,
        // both present on Debian/Ubuntu). Windows: jpackage's MSI needs WiX; the innoSetup task below
        // is the supported route there instead.
        val common = listOf("--app-version", appVersion, "--vendor", "LinkScope")
        when {
            os.isLinux -> {
                installerType = "deb"
                installerOptions = common + listOf(
                    "--linux-package-name", "linkscope",
                    "--linux-shortcut",
                    "--linux-menu-group", "Development;Network",
                    "--linux-app-category", "net",
                    "--linux-deb-maintainer", "linkscope@localhost"
                )
            }
            os.isWindows -> installerOptions = common + listOf("--win-menu", "--win-shortcut", "--win-dir-chooser")
            else -> installerOptions = common
        }
    }
}
