package com.linkscope.modules.hid;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * One decoded input report: which buttons are down and where every axis sits, plus the raw
 * bytes it came from. Built either from a HID report through the Windows HID parser or
 * from an XInput state.
 */
public record HidReport(Instant time, int reportId, byte[] raw, Set<String> pressed, List<Axis> axes) {

    /**
     * An axis value with its logical range. {@code centered} axes rest in the middle (sticks);
     * others rest at {@code min} (triggers, sliders). A {@code hat} carries a direction index.
     */
    public record Axis(String name, long value, long min, long max, boolean centered, boolean hat) {

        /** Position within the range, 0..1; a hat outside its range (released) gives -1. */
        public double fraction() {
            if (max <= min) {
                return 0;
            }
            if (hat && (value < min || value > max)) {
                return -1;
            }
            return Math.max(0, Math.min(1, (double) (value - min) / (max - min)));
        }

        /** -1..1 for centered axes, 0..1 otherwise. */
        public double normalized() {
            double f = fraction();
            return centered ? f * 2 - 1 : f;
        }

        /** Hat direction in degrees clockwise from up, or -1 when released. Handles 4- and 8-way hats. */
        public int hatDegrees() {
            if (!hat || value < min || value > max) {
                return -1;
            }
            long positions = max - min + 1;
            return (int) Math.round((value - min) * 360.0 / positions);
        }

        public String display() {
            if (hat) {
                int deg = hatDegrees();
                return deg < 0 ? "released" : deg + "°";
            }
            return String.valueOf(value);
        }
    }

    public Axis axis(String name) {
        for (Axis a : axes) {
            if (a.name().equals(name)) {
                return a;
            }
        }
        return null;
    }

    public boolean isPressed(String button) {
        return pressed.contains(button);
    }
}
