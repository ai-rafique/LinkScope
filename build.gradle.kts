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

    // Presets / config
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.2")
    implementation("io.github.cdimascio:dotenv-java:3.2.0")

    // Testing
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
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

// badass-runtime: trimmed jlink image from the jdeps-suggested JDK module list,
// then jpackage in classpath mode. No module-info.java anywhere (see CLAUDE.md §5).
runtime {
    options.set(listOf("--strip-debug", "--compress", "zip-6", "--no-header-files", "--no-man-pages"))
    // jdeps misses modules that are only reached reflectively or via service loaders.
    additive.set(true)
    modules.set(listOf("java.naming", "java.management", "jdk.crypto.ec", "jdk.unsupported"))

    launcher {
        noConsole = true
    }

    jpackage {
        imageName = "LinkScope"
        installerName = "LinkScope"
        // jpackage (and MSI) require a purely numeric version: strip any -SNAPSHOT/-rc suffix.
        val appVersion = project.version.toString().substringBefore("-")
        imageOptions = listOf("--app-version", appVersion)
        // Leave installerType unset: jpackage picks the platform default(s).
        // Windows .msi/.exe requires the WiX toolset on PATH.
        installerOptions = listOf("--app-version", appVersion, "--vendor", "LinkScope", "--win-menu", "--win-shortcut", "--win-dir-chooser")
    }
}
