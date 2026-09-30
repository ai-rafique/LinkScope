package com.linkscope.modules.hid;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure parts of the HID tab: usage names, XInput decoding, axis maths and the log line. */
class HidModelTest {

    @Test
    void usageNamesAndClasses() {
        assertEquals("X", HidUsages.name(1, 0x30));
        assertEquals("Hat switch", HidUsages.name(1, 0x39));
        assertEquals("Button 7", HidUsages.name(9, 7));
        assertEquals("Volume up", HidUsages.name(0x0C, 0xE9));
        assertEquals("Vendor 0x01", HidUsages.name(0xFF00, 1));
        assertEquals("Generic Desktop 0xC7", HidUsages.name(1, 0xC7));
        assertTrue(HidUsages.isCentered(1, 0x30));
        assertFalse(HidUsages.isCentered(1, 0x36), "a slider rests at one end");
        assertTrue(HidUsages.isHat(1, 0x39));
        assertTrue(HidUsages.isGameController(1, 5));
        assertFalse(HidUsages.isGameController(1, 6));
        assertEquals("Gamepad", HidUsages.kind(1, 5));
        assertEquals("Keyboard", HidUsages.kind(1, 6));
        assertEquals("Consumer control", HidUsages.kind(0x0C, 1));
    }

    @Test
    void xinputStateParsesAndMapsToAReport() {
        ByteBuffer b = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(42);
        b.putShort((short) (XInputState.A | XInputState.DPAD_UP | XInputState.RIGHT_SHOULDER));
        b.put((byte) 0).put((byte) 255);
        b.putShort((short) -32768).putShort((short) 0).putShort((short) 16384).putShort((short) 32767);
        XInputState s = XInputState.parse(b.array());
        assertEquals(42, s.packet());
        assertEquals(Set.of("A", "RB", "D-pad up"), s.pressed());
        assertEquals(255, s.rightTrigger());
        assertEquals(-32768, s.leftX());
        assertArrayEquals(b.array(), s.toBytes());

        HidReport r = s.toReport(Instant.EPOCH);
        assertTrue(r.isPressed("A"));
        assertFalse(r.isPressed("B"));
        assertEquals(-1.0, r.axis(HidLayout.LX).normalized(), 1e-9);
        assertEquals(0.0, r.axis(HidLayout.LY).normalized(), 0.001);
        assertEquals(0.5, r.axis(HidLayout.RX).normalized(), 0.001);
        assertEquals(1.0, r.axis(HidLayout.RT).fraction(), 1e-9);
        assertEquals(0.0, r.axis(HidLayout.LT).fraction(), 1e-9);
        assertEquals(6, HidLayout.forXInput().values().size());
        assertEquals(XInputState.BUTTON_NAMES.size(), HidLayout.forXInput().buttons().size());
    }

    @Test
    void axisMathsAndHats() {
        HidReport.Axis stick = new HidReport.Axis("X", 0, -32768, 32767, true, false);
        assertEquals(0.0, stick.normalized(), 0.001);
        HidReport.Axis trigger = new HidReport.Axis("Z", 128, 0, 255, false, false);
        assertEquals(0.502, trigger.fraction(), 0.001);
        HidReport.Axis hat8 = new HidReport.Axis("Hat switch", 2, 0, 7, false, true);
        assertEquals(90, hat8.hatDegrees(), "8-way hat: index 2 is east");
        assertEquals("90°", hat8.display());
        HidReport.Axis hatReleased = new HidReport.Axis("Hat switch", 8, 0, 7, false, true);
        assertEquals(-1, hatReleased.hatDegrees());
        assertEquals("released", hatReleased.display());
        HidReport.Axis hat4 = new HidReport.Axis("Hat switch", 3, 1, 4, false, true);
        assertEquals(180, hat4.hatDegrees(), "4-way hat starting at 1: value 3 is south");
        assertEquals("Y", HidLayout.partnerOf("X"));
        assertEquals(HidLayout.RY, HidLayout.partnerOf(HidLayout.RX));
        assertTrue(HidLayout.isHorizontal("Rx"));
        assertFalse(HidLayout.isHorizontal("Ry"));
    }

    @Test
    void logLineNamesWhatMoved() {
        HidReport r = new HidReport(Instant.EPOCH, 0, new byte[0], Set.of("A"), List.of(
                new HidReport.Axis(HidLayout.LX, 0, -32768, 32767, true, false),
                new HidReport.Axis(HidLayout.RT, 200, 0, 255, false, false),
                new HidReport.Axis("Hat switch", 8, 0, 7, false, true)));
        String line = HidService.describe(r);
        assertTrue(line.startsWith("A"), line);
        assertTrue(line.contains("Right trigger 200"), line);
        assertFalse(line.contains("Left stick X"), "a centred stick is not reported");
        assertFalse(line.contains("Hat"), "a released hat is not reported");
    }

    @Test
    void shortButtonLabels() {
        assertEquals("3", ControllerView.shortName("Button 3"));
        assertEquals("▲", ControllerView.shortName("D-pad up"));
        assertEquals("LB", ControllerView.shortName("LB"));
        assertEquals("Guide", ControllerView.shortName("Guide"));
    }

    /** Enumeration must not throw wherever it runs; on Windows it returns the machine's HID interfaces. */
    @Test
    void enumerationNeverThrows() {
        List<HidDevice> devices = HidService.devices();
        assertNotNull(devices);
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        Assumptions.assumeTrue(windows, "HID enumeration is Windows-only");
        assertTrue(HidService.available());
        for (HidDevice d : devices) {
            assertNotNull(d.label());
            assertTrue(d.isXInput() || d.path().toLowerCase(Locale.ROOT).startsWith("\\\\?\\hid"), d.path());
        }
    }
}
