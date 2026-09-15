package com.linkscope.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnvResolverTest {
    private final EnvResolver resolver = new EnvResolver(
            Map.of("NATS_SERVERS", "nats://dotenv:4222", "TOKEN", "from-file"),
            Map.of("TOKEN", "from-process", "HOME_PORT", "9092"));

    @Test
    void dotenvWinsOverProcessEnvironment() {
        assertEquals("from-file", resolver.resolve("${TOKEN}").value());
    }

    @Test
    void fallsBackToProcessEnvironment() {
        assertEquals("localhost:9092", resolver.resolve("localhost:${HOME_PORT}").value());
    }

    @Test
    void substitutesMultiplePlaceholders() {
        EnvResolver.Result r = resolver.resolve("${NATS_SERVERS},${NATS_SERVERS}?token=${TOKEN}");
        assertEquals("nats://dotenv:4222,nats://dotenv:4222?token=from-file", r.value());
        assertTrue(r.unresolved().isEmpty());
    }

    @Test
    void leavesUnresolvedPlaceholdersLiteralAndReportsThem() {
        EnvResolver.Result r = resolver.resolve("${MISSING}:${HOME_PORT}/${ALSO_MISSING}");
        assertEquals("${MISSING}:9092/${ALSO_MISSING}", r.value());
        assertEquals(List.of("MISSING", "ALSO_MISSING"), r.unresolved());
    }

    @Test
    void plainStringsPassThrough() {
        assertEquals("localhost:4222", resolver.resolve("localhost:4222").value());
        assertEquals("$notaplaceholder", resolver.resolve("$notaplaceholder").value());
    }
}
