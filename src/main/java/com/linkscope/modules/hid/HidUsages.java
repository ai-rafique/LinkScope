package com.linkscope.modules.hid;

import java.util.Map;

/** Names for the HID usage pages and usages that matter for input devices. */
public final class HidUsages {
    public static final int PAGE_GENERIC_DESKTOP = 0x01;
    public static final int PAGE_SIMULATION = 0x02;
    public static final int PAGE_GAME = 0x05;
    public static final int PAGE_KEYBOARD = 0x07;
    public static final int PAGE_LED = 0x08;
    public static final int PAGE_BUTTON = 0x09;
    public static final int PAGE_CONSUMER = 0x0C;
    public static final int PAGE_DIGITIZER = 0x0D;

    private static final Map<Integer, String> DIGITIZER = Map.ofEntries(
            Map.entry(0x01, "Digitizer"), Map.entry(0x02, "Pen"), Map.entry(0x04, "Touch screen"), Map.entry(0x05, "Touch pad"),
            Map.entry(0x0E, "Device configuration"), Map.entry(0x22, "Finger"), Map.entry(0x30, "Tip pressure"),
            Map.entry(0x32, "In range"), Map.entry(0x42, "Tip switch"), Map.entry(0x44, "Barrel switch"), Map.entry(0x47, "Confidence"),
            Map.entry(0x48, "Width"), Map.entry(0x49, "Height"), Map.entry(0x51, "Contact id"), Map.entry(0x54, "Contact count"),
            Map.entry(0x55, "Contact count max"), Map.entry(0x56, "Scan time"), Map.entry(0x59, "Button type"));

    public static final int USAGE_JOYSTICK = 0x04;
    public static final int USAGE_GAMEPAD = 0x05;
    public static final int USAGE_KEYBOARD = 0x06;
    public static final int USAGE_MOUSE = 0x02;
    public static final int USAGE_MULTI_AXIS = 0x08;
    public static final int USAGE_HAT = 0x39;

    private static final Map<Integer, String> GENERIC_DESKTOP = Map.ofEntries(
            Map.entry(0x01, "Pointer"), Map.entry(0x02, "Mouse"), Map.entry(0x04, "Joystick"), Map.entry(0x05, "Gamepad"),
            Map.entry(0x06, "Keyboard"), Map.entry(0x07, "Keypad"), Map.entry(0x08, "Multi-axis controller"),
            Map.entry(0x30, "X"), Map.entry(0x31, "Y"), Map.entry(0x32, "Z"), Map.entry(0x33, "Rx"), Map.entry(0x34, "Ry"),
            Map.entry(0x35, "Rz"), Map.entry(0x36, "Slider"), Map.entry(0x37, "Dial"), Map.entry(0x38, "Wheel"),
            Map.entry(0x39, "Hat switch"), Map.entry(0x3D, "Start"), Map.entry(0x3E, "Select"), Map.entry(0x40, "Vx"),
            Map.entry(0x41, "Vy"), Map.entry(0x42, "Vz"), Map.entry(0x43, "Vbrx"), Map.entry(0x44, "Vbry"), Map.entry(0x45, "Vbrz"),
            Map.entry(0x80, "System control"), Map.entry(0x81, "Power"), Map.entry(0x82, "Sleep"), Map.entry(0x83, "Wake"),
            Map.entry(0x90, "D-pad up"), Map.entry(0x91, "D-pad down"), Map.entry(0x92, "D-pad right"), Map.entry(0x93, "D-pad left"));

    private static final Map<Integer, String> SIMULATION = Map.ofEntries(
            Map.entry(0xB0, "Aileron"), Map.entry(0xB8, "Throttle"), Map.entry(0xBA, "Rudder"), Map.entry(0xBB, "Throttle"),
            Map.entry(0xC4, "Accelerator"), Map.entry(0xC5, "Brake"), Map.entry(0xC6, "Clutch"), Map.entry(0xC8, "Steering"));

    private static final Map<Integer, String> CONSUMER = Map.ofEntries(
            Map.entry(0xB0, "Play"), Map.entry(0xB1, "Pause"), Map.entry(0xB5, "Next track"), Map.entry(0xB6, "Previous track"),
            Map.entry(0xB7, "Stop"), Map.entry(0xCD, "Play/Pause"), Map.entry(0xE2, "Mute"), Map.entry(0xE9, "Volume up"),
            Map.entry(0xEA, "Volume down"), Map.entry(0x223, "Home"), Map.entry(0x224, "Back"));

    private HidUsages() {
    }

    public static String pageName(int page) {
        return switch (page) {
            case PAGE_GENERIC_DESKTOP -> "Generic Desktop";
            case PAGE_SIMULATION -> "Simulation";
            case PAGE_GAME -> "Game";
            case PAGE_KEYBOARD -> "Keyboard";
            case PAGE_LED -> "LED";
            case PAGE_BUTTON -> "Button";
            case PAGE_CONSUMER -> "Consumer";
            case PAGE_DIGITIZER -> "Digitizer";
            default -> page >= 0xFF00 ? String.format("Vendor 0x%04X", page) : String.format("Page 0x%02X", page);
        };
    }

    /** Short name for a usage: "X", "Hat switch", "Button 3", "Volume up", or a hex fallback. */
    public static String name(int page, int usage) {
        String n = switch (page) {
            case PAGE_GENERIC_DESKTOP -> GENERIC_DESKTOP.get(usage);
            case PAGE_SIMULATION -> SIMULATION.get(usage);
            case PAGE_CONSUMER -> CONSUMER.get(usage);
            case PAGE_DIGITIZER -> DIGITIZER.get(usage);
            case PAGE_BUTTON -> "Button " + usage;
            default -> null;
        };
        if (n != null) {
            return n;
        }
        return page >= 0xFF00 ? String.format("Vendor 0x%02X", usage) : String.format("%s 0x%02X", pageName(page), usage);
    }

    /** Axes whose rest position is the middle of the range (sticks, steering) rather than one end (triggers, sliders). */
    public static boolean isCentered(int page, int usage) {
        if (page == PAGE_GENERIC_DESKTOP) {
            return usage >= 0x30 && usage <= 0x35 || usage == 0x37 || usage == 0x38;
        }
        return page == PAGE_SIMULATION && (usage == 0xB0 || usage == 0xBA || usage == 0xC8);
    }

    public static boolean isHat(int page, int usage) {
        return page == PAGE_GENERIC_DESKTOP && usage == USAGE_HAT;
    }

    /** True for joysticks, gamepads and multi-axis controllers: the devices the tab is mostly about. */
    public static boolean isGameController(int page, int usage) {
        return page == PAGE_GENERIC_DESKTOP && (usage == USAGE_JOYSTICK || usage == USAGE_GAMEPAD || usage == USAGE_MULTI_AXIS);
    }

    /** What the device is, for labels: Gamepad, Keyboard, Mouse, Consumer control, ... */
    public static String kind(int page, int usage) {
        if (page == PAGE_GENERIC_DESKTOP) {
            String n = GENERIC_DESKTOP.get(usage);
            if (n != null && usage < 0x30) {
                return n;
            }
        }
        if (page == PAGE_CONSUMER) {
            return "Consumer control";
        }
        if (page == PAGE_DIGITIZER) {
            String n = DIGITIZER.get(usage);
            return n != null && usage < 0x20 ? n : "Digitizer";
        }
        if (page >= 0xFF00) {
            return "Vendor-defined";
        }
        return pageName(page);
    }
}
