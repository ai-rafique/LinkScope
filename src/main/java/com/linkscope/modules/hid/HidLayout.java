package com.linkscope.modules.hid;

import java.util.ArrayList;
import java.util.List;

/**
 * What a device can report: its buttons and its value fields (axes, hats, sliders), as
 * learned from the HID report descriptor through the Windows HID parser, or fixed for XInput.
 */
public record HidLayout(List<Button> buttons, List<Value> values, int inputLength, int outputLength, boolean xinput) {

    public record Button(int page, int usage, String name) {
    }

    /**
     * A value field. {@code linkCollection} and {@code page}/{@code usage} are what the HID
     * parser needs to extract it; {@code min}/{@code max} is the logical range.
     */
    public record Value(int page, int usage, int linkCollection, String name, long min, long max, int bitSize, boolean hasNull) {
        public boolean centered() {
            return HidUsages.isCentered(page, usage);
        }

        public boolean hat() {
            return HidUsages.isHat(page, usage);
        }
    }

    public static final String LX = "Left stick X";
    public static final String LY = "Left stick Y";
    public static final String RX = "Right stick X";
    public static final String RY = "Right stick Y";
    public static final String LT = "Left trigger";
    public static final String RT = "Right trigger";

    /** The fixed shape of an XInput controller: 15 buttons (D-pad as four), two sticks, two triggers. */
    public static HidLayout forXInput() {
        List<Button> buttons = new ArrayList<>();
        for (String name : XInputState.BUTTON_NAMES) {
            buttons.add(new Button(HidUsages.PAGE_BUTTON, buttons.size() + 1, name));
        }
        List<Value> values = List.of(
                new Value(HidUsages.PAGE_GENERIC_DESKTOP, 0x30, 0, LX, -32768, 32767, 16, false),
                new Value(HidUsages.PAGE_GENERIC_DESKTOP, 0x31, 0, LY, -32768, 32767, 16, false),
                new Value(HidUsages.PAGE_GENERIC_DESKTOP, 0x33, 0, RX, -32768, 32767, 16, false),
                new Value(HidUsages.PAGE_GENERIC_DESKTOP, 0x34, 0, RY, -32768, 32767, 16, false),
                new Value(HidUsages.PAGE_SIMULATION, 0xC5, 0, LT, 0, 255, 8, false),
                new Value(HidUsages.PAGE_SIMULATION, 0xC4, 0, RT, 0, 255, 8, false));
        return new HidLayout(buttons, values, 16, 4, true);
    }

    /** Name of the value that pairs with this one as a stick's other axis, or null. */
    public static String partnerOf(String axisName) {
        return switch (axisName) {
            case "X" -> "Y";
            case "Y" -> "X";
            case "Rx" -> "Ry";
            case "Ry" -> "Rx";
            case LX -> LY;
            case LY -> LX;
            case RX -> RY;
            case RY -> RX;
            default -> null;
        };
    }

    /** True when this axis is the horizontal half of a stick pair. */
    public static boolean isHorizontal(String axisName) {
        return axisName.equals("X") || axisName.equals("Rx") || axisName.equals(LX) || axisName.equals(RX);
    }
}
