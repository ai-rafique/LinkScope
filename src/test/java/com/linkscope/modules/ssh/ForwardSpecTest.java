package com.linkscope.modules.ssh;

import com.linkscope.modules.ssh.SshService.Forward;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForwardSpecTest {

    @Test
    void parsesLocalAndRemoteWithOptionalBindAddress() {
        Forward l = Forward.parse("L 14222 nats-box 4222");
        assertTrue(l.local());
        assertEquals("127.0.0.1", l.bindAddress());
        assertEquals(14222, l.bindPort());
        assertEquals("nats-box", l.targetHost());
        assertEquals(4222, l.targetPort());

        Forward r = Forward.parse("r 0.0.0.0:9000 127.0.0.1 5000");
        assertFalse(r.local());
        assertEquals("0.0.0.0", r.bindAddress());
        assertEquals(9000, r.bindPort());
    }

    @Test
    void specRoundTripsAndDescribes() {
        Forward f = new Forward(true, "127.0.0.1", 14222, "nats-box", 4222);
        assertEquals("L 127.0.0.1:14222 nats-box 4222", f.toSpec());
        assertEquals(f, Forward.parse(f.toSpec()));
        assertEquals("L 127.0.0.1:14222 → nats-box:4222", f.describe());
    }

    @Test
    void rejectsMalformedSpecs() {
        assertThrows(IllegalArgumentException.class, () -> Forward.parse("X 1 h 2"));
        assertThrows(IllegalArgumentException.class, () -> Forward.parse("L 1 h"));
        assertThrows(IllegalArgumentException.class, () -> Forward.parse("L abc h 2"));
    }
}
