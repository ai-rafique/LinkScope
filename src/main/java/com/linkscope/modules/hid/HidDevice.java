package com.linkscope.modules.hid;

/**
 * One selectable input device: a HID interface (device path from SetupAPI) or an XInput
 * slot ({@code xinputSlot} 0-3, path {@code xinput:N}).
 */
public record HidDevice(String path, int vendorId, int productId, String manufacturer, String product,
                        int usagePage, int usage, int inputLength, int outputLength, int buttonCount, int valueCount,
                        int xinputSlot) {

    public boolean isXInput() {
        return xinputSlot >= 0;
    }

    public boolean isGameController() {
        return isXInput() || HidUsages.isGameController(usagePage, usage);
    }

    public String kind() {
        return isXInput() ? "Xbox controller" : HidUsages.kind(usagePage, usage);
    }

    public String ids() {
        return String.format("%04X:%04X", vendorId, productId);
    }

    public String label() {
        String name = product == null || product.isBlank() ? kind() : product;
        StringBuilder sb = new StringBuilder(name);
        if (isXInput()) {
            sb.append("   XInput slot ").append(xinputSlot + 1).append("  ·  rumble");
        } else {
            sb.append("   ").append(ids()).append("  ·  ").append(kind());
            if (inputLength == 0) {
                sb.append("  ·  no input reports");
            }
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return label();
    }
}
