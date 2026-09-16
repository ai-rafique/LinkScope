package com.linkscope.core;

import com.linkscope.core.ByteLayout.Field;
import com.linkscope.core.ByteLayout.Type;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ByteLayoutTest {

    private static byte[] seq(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) (i + 1);
        }
        return b;
    }

    @Test
    void plainWidthsCutInOrderAndReportTheTail() {
        List<Field> f = ByteLayout.parse("2 3 5 6 4").apply(seq(23));
        assertEquals(6, f.size());
        assertEquals(0, f.get(0).offset());
        assertEquals(2, f.get(0).length());
        assertEquals(2, f.get(1).offset());
        assertEquals(3, f.get(1).length());
        assertEquals(16, f.get(4).offset());
        assertEquals(4, f.get(4).length());
        Field tail = f.get(5);
        assertTrue(tail.unassigned());
        assertEquals(20, tail.offset());
        assertEquals(3, tail.length());
        assertFalse(f.get(4).truncated());
    }

    @Test
    void shortDataMarksTruncatedFields() {
        List<Field> f = ByteLayout.parse("2 4 8").apply(seq(5));
        assertEquals(3, f.size());
        assertFalse(f.get(0).truncated());
        assertTrue(f.get(1).truncated());
        assertEquals(3, f.get(1).length());
        assertTrue(f.get(2).truncated());
        assertEquals(0, f.get(2).length());
    }

    @Test
    void typeHintsRestAndSkip() {
        List<Field> f = ByteLayout.parse("1u 2i 4f 3s 1x *").apply(seq(15));
        assertEquals(Type.UINT, f.get(0).spec().type());
        assertEquals(Type.INT, f.get(1).spec().type());
        assertEquals(Type.FLOAT, f.get(2).spec().type());
        assertEquals(Type.STRING, f.get(3).spec().type());
        assertEquals(Type.SKIP, f.get(4).spec().type());
        assertTrue(f.get(5).spec().isRest());
        assertEquals(4, f.get(5).length());
        assertEquals(6, f.size(), "rest consumes everything, so no unassigned tail");
    }

    @Test
    void groupsRepeatCountedAndUnbounded() {
        List<Field> counted = ByteLayout.parse("1 [2 4]*2").apply(seq(13));
        assertEquals(5, counted.size());
        assertEquals(1, counted.get(1).offset());
        assertEquals(7, counted.get(3).offset());
        List<Field> open = ByteLayout.parse("[1 2]*").apply(seq(7));
        assertEquals(6, open.size(), "two full records, then a third whose second field is truncated to 0 bytes");
        assertEquals(6, open.get(4).offset());
        assertTrue(open.get(5).truncated());
        // A "*" right after "]" always means repeat; an explicit *1 lets a rest field follow a group.
        List<Field> withRest = ByteLayout.parse("[2 4]*1 *").apply(seq(10));
        assertEquals(3, withRest.size());
        assertTrue(withRest.get(2).spec().isRest());
        assertEquals(4, ByteLayout.parse("[2 4] *").apply(seq(10)).size(), "space before * does not change the meaning");
    }

    @Test
    void badLayoutsAreRejectedWithMessages() {
        assertThrows(IllegalArgumentException.class, () -> ByteLayout.parse(""));
        assertThrows(IllegalArgumentException.class, () -> ByteLayout.parse("2 abc"));
        assertThrows(IllegalArgumentException.class, () -> ByteLayout.parse("3f"));
        assertThrows(IllegalArgumentException.class, () -> ByteLayout.parse("[2 4"));
        assertThrows(IllegalArgumentException.class, () -> ByteLayout.parse("2 ]"));
        assertThrows(IllegalArgumentException.class, () -> ByteLayout.parse("0"));
        assertThrows(IllegalArgumentException.class, () -> ByteLayout.parse("16u"));
    }

    @Test
    void summariesAndDecodersReadValuesBothWays() {
        byte[] data = {0x01, 0x02, (byte) 0xff, (byte) 0xff, (byte) 0xdb, 0x0f, 0x49, 0x40, 'h', 'i'};
        List<Field> f = ByteLayout.parse("2u 2i 4f 2s").apply(data);
        assertEquals("u16 513 / 258", ByteLayout.summarize(f.get(0), true));
        assertEquals("u16 258 / 513", ByteLayout.summarize(f.get(0), false));
        assertEquals("i16 -1 / -1", ByteLayout.summarize(f.get(1), true));
        assertTrue(ByteLayout.summarize(f.get(2), true).startsWith("f32 3.1415927 /"));
        assertEquals("\"hi\"", ByteLayout.summarize(f.get(3), true));

        List<Field> auto = ByteLayout.parse("1 2 4 8 3").apply(seq(18));
        assertTrue(ByteLayout.summarize(auto.get(0), true).startsWith("u8 1 · i8 1"));
        assertTrue(ByteLayout.summarize(auto.get(1), true).startsWith("u16 770 / 515"));
        assertTrue(ByteLayout.summarize(auto.get(2), true).contains("f32"));
        assertTrue(ByteLayout.summarize(auto.get(3), true).contains("f64"));
        assertEquals("\"\\x10\\x11\\x12\"", ByteLayout.summarize(auto.get(4), true));

        List<PayloadDecoder.Decoded> detail = ByteLayout.decode(auto.get(2), true);
        assertTrue(detail.stream().anyMatch(d -> d.name().equals("uint32 LE / BE") && d.value().equals("117835012 / 67438087")));
        assertTrue(detail.stream().anyMatch(d -> d.name().startsWith("unix time")));
    }

    @Test
    void signedAndUnsignedHelpers() {
        assertEquals(255, ByteLayout.unsigned(new byte[] {(byte) 0xff}, true));
        assertEquals(-1, ByteLayout.signed(new byte[] {(byte) 0xff}, true));
        assertEquals(-2, ByteLayout.signed(new byte[] {(byte) 0xfe, (byte) 0xff}, true));
        assertEquals(-2, ByteLayout.signed(new byte[] {(byte) 0xff, (byte) 0xfe}, false));
    }
}
