package com.linkscope.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PresetStoreTest {

    private static PresetStore store(Path dir) {
        EnvResolver resolver = new EnvResolver(Map.of("NATS_SERVERS", "localhost:4222"), Map.of());
        return new PresetStore(dir.resolve("nested").resolve("presets.json"), resolver);
    }

    @Test
    void missingFileLoadsAsEmpty(@TempDir Path dir) throws IOException {
        PresetStore s = store(dir);
        s.load();
        assertTrue(s.presets().isEmpty());
    }

    @Test
    void saveAndReloadRoundTrip(@TempDir Path dir) throws IOException {
        PresetStore s = store(dir);
        s.put(new Preset("lab", "udp", Map.of("bindPort", "5000", "targetHost", "10.0.0.7")));
        s.put(new Preset("broker", "nats", Map.of("servers", "${NATS_SERVERS}")));
        s.save();

        String json = Files.readString(s.path());
        assertTrue(json.contains("\"version\" : 1"), json);
        assertTrue(json.contains("${NATS_SERVERS}"), "placeholders must be saved unresolved");

        PresetStore reloaded = store(dir);
        reloaded.load();
        assertEquals(2, reloaded.presets().size());
        assertEquals(List.of("lab"), reloaded.forModule("udp").stream().map(Preset::name).toList());
        assertEquals("10.0.0.7", reloaded.find("udp", "lab").fields().get("targetHost"));
    }

    @Test
    void putReplacesSameModuleAndName(@TempDir Path dir) {
        PresetStore s = store(dir);
        s.put(new Preset("a", "udp", Map.of("bindPort", "1")));
        s.put(new Preset("a", "udp", Map.of("bindPort", "2")));
        s.put(new Preset("a", "tcp", Map.of("clientPort", "3")));
        assertEquals(2, s.presets().size());
        assertEquals("2", s.find("udp", "a").fields().get("bindPort"));
        assertTrue(s.remove("udp", "a"));
        assertNull(s.find("udp", "a"));
    }

    @Test
    void resolveSubstitutesAndFlagsUnset(@TempDir Path dir) {
        PresetStore s = store(dir);
        Preset p = new Preset("broker", "nats", Map.of("servers", "${NATS_SERVERS}", "token", "${NATS_TOKEN}"));
        int before = LogSink.get().entries().size();
        Preset resolved = s.resolve(p);
        assertEquals("localhost:4222", resolved.fields().get("servers"));
        assertEquals("${NATS_TOKEN}", resolved.fields().get("token"));
        assertEquals("broker", resolved.name());

        List<LogEntry> entries = LogSink.get().entries();
        assertEquals(before + 1, entries.size());
        LogEntry warning = entries.get(entries.size() - 1);
        assertEquals(LogEntry.Kind.ERROR, warning.kind());
        assertTrue(warning.note().contains("[UNSET: NATS_TOKEN]"), warning.note());

        s.resolve(p);
        assertEquals(before + 1, LogSink.get().entries().size(), "warn only once per preset+var");
    }
}
