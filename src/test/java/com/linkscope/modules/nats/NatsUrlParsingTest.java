package com.linkscope.modules.nats;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Pure unit tests for broker-list parsing; no broker needed. */
class NatsUrlParsingTest {

    @Test
    void addsSchemeAndTrims() {
        assertArrayEquals(new String[] {"nats://localhost:4222", "tls://other:4223"},
                NatsService.toServerUrls(" localhost:4222 , tls://other:4223,"));
    }

    @Test
    void rejectsEmptyList() {
        assertThrows(IllegalArgumentException.class, () -> NatsService.toServerUrls(" , "));
        assertEquals(1, NatsService.toServerUrls("a:1").length);
    }
}
