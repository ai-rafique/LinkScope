package com.linkscope.modules.hid;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * An XINPUT_STATE as returned by XInputGetState: a packet counter and the gamepad block
 * (button mask, two 8-bit triggers, four signed 16-bit stick axes). Little-endian, 16 bytes.
 */
public record XInputState(long packet, int buttons, int leftTrigger, int rightTrigger, int leftX, int leftY, int rightX, int rightY) {
    public static final int SIZE = 16;

    public static final int DPAD_UP = 0x0001;
    public static final int DPAD_DOWN = 0x0002;
    public static final int DPAD_LEFT = 0x0004;
    public static final int DPAD_RIGHT = 0x0008;
    public static final int START = 0x0010;
    public static final int BACK = 0x0020;
    public static final int LEFT_THUMB = 0x0040;
    public static final int RIGHT_THUMB = 0x0080;
    public static final int LEFT_SHOULDER = 0x0100;
    public static final int RIGHT_SHOULDER = 0x0200;
    public static final int GUIDE = 0x0400;
    public static final int A = 0x1000;
    public static final int B = 0x2000;
    public static final int X = 0x4000;
    public static final int Y = 0x8000;

    /** Button names in display order, with the mask each one reads. */
    public static final List<String> BUTTON_NAMES = List.of("A", "B", "X", "Y", "LB", "RB", "View", "Menu", "LS", "RS",
            "D-pad up", "D-pad down", "D-pad left", "D-pad right", "Guide");
    private static final int[] BUTTON_MASKS = {A, B, X, Y, LEFT_SHOULDER, RIGHT_SHOULDER, BACK, START, LEFT_THUMB, RIGHT_THUMB,
        DPAD_UP, DPAD_DOWN, DPAD_LEFT, DPAD_RIGHT, GUIDE};

    public static XInputState parse(byte[] bytes) {
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        return new XInputState(b.getInt(0) & 0xFFFFFFFFL, b.getShort(4) & 0xFFFF, b.get(6) & 0xFF, b.get(7) & 0xFF,
                b.getShort(8), b.getShort(10), b.getShort(12), b.getShort(14));
    }

    public byte[] toBytes() {
        ByteBuffer b = ByteBuffer.allocate(SIZE).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt((int) packet).putShort((short) buttons).put((byte) leftTrigger).put((byte) rightTrigger)
                .putShort((short) leftX).putShort((short) leftY).putShort((short) rightX).putShort((short) rightY);
        return b.array();
    }

    public boolean has(int mask) {
        return (buttons & mask) != 0;
    }

    public Set<String> pressed() {
        Set<String> out = new LinkedHashSet<>();
        for (int i = 0; i < BUTTON_NAMES.size(); i++) {
            if (has(BUTTON_MASKS[i])) {
                out.add(BUTTON_NAMES.get(i));
            }
        }
        return out;
    }

    public HidReport toReport(Instant time) {
        List<HidReport.Axis> axes = new ArrayList<>();
        axes.add(new HidReport.Axis(HidLayout.LX, leftX, -32768, 32767, true, false));
        axes.add(new HidReport.Axis(HidLayout.LY, leftY, -32768, 32767, true, false));
        axes.add(new HidReport.Axis(HidLayout.RX, rightX, -32768, 32767, true, false));
        axes.add(new HidReport.Axis(HidLayout.RY, rightY, -32768, 32767, true, false));
        axes.add(new HidReport.Axis(HidLayout.LT, leftTrigger, 0, 255, false, false));
        axes.add(new HidReport.Axis(HidLayout.RT, rightTrigger, 0, 255, false, false));
        return new HidReport(time, 0, toBytes(), pressed(), axes);
    }
}
