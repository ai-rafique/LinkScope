package com.linkscope.modules.capture;

import com.linkscope.core.PayloadDecoder;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Dissects USBPcap records: pcap link type 249, what Wireshark's USBPcap interface writes on
 * Windows. Each record is a little-endian pseudo-header (bus, device, endpoint, transfer type,
 * URB function, status) followed by the transfer data. Rows use {@code host} and
 * {@code bus.device.endpoint} as addresses, the transfer type as the protocol column, and
 * decode control setup packets and the common descriptors so enumeration reads in plain words.
 */
public final class UsbPcapDissector {
    public static final int DLT_USBPCAP = 249;
    static final int HEADER_MIN = 27;

    public static final int TRANSFER_ISOCH = 0;
    public static final int TRANSFER_INTERRUPT = 1;
    public static final int TRANSFER_CONTROL = 2;
    public static final int TRANSFER_BULK = 3;
    public static final int TRANSFER_IRP_INFO = 0xFE;

    private static final Map<Integer, String> FUNCTIONS = Map.ofEntries(
            Map.entry(0x0000, "SELECT_CONFIGURATION"), Map.entry(0x0001, "SELECT_INTERFACE"), Map.entry(0x0002, "ABORT_PIPE"),
            Map.entry(0x0003, "TAKE_FRAME_LENGTH_CONTROL"), Map.entry(0x0004, "RELEASE_FRAME_LENGTH_CONTROL"),
            Map.entry(0x0005, "GET_FRAME_LENGTH"), Map.entry(0x0006, "SET_FRAME_LENGTH"), Map.entry(0x0007, "GET_CURRENT_FRAME_NUMBER"),
            Map.entry(0x0008, "CONTROL_TRANSFER"), Map.entry(0x0009, "BULK_OR_INTERRUPT_TRANSFER"), Map.entry(0x000A, "ISOCH_TRANSFER"),
            Map.entry(0x000B, "GET_DESCRIPTOR_FROM_DEVICE"), Map.entry(0x000C, "SET_DESCRIPTOR_TO_DEVICE"),
            Map.entry(0x000D, "SET_FEATURE_TO_DEVICE"), Map.entry(0x000E, "SET_FEATURE_TO_INTERFACE"), Map.entry(0x000F, "SET_FEATURE_TO_ENDPOINT"),
            Map.entry(0x0010, "CLEAR_FEATURE_TO_DEVICE"), Map.entry(0x0011, "CLEAR_FEATURE_TO_INTERFACE"), Map.entry(0x0012, "CLEAR_FEATURE_TO_ENDPOINT"),
            Map.entry(0x0013, "GET_STATUS_FROM_DEVICE"), Map.entry(0x0014, "GET_STATUS_FROM_INTERFACE"), Map.entry(0x0015, "GET_STATUS_FROM_ENDPOINT"),
            Map.entry(0x0017, "VENDOR_DEVICE"), Map.entry(0x0018, "VENDOR_INTERFACE"), Map.entry(0x0019, "VENDOR_ENDPOINT"),
            Map.entry(0x001A, "CLASS_DEVICE"), Map.entry(0x001B, "CLASS_INTERFACE"), Map.entry(0x001C, "CLASS_ENDPOINT"),
            Map.entry(0x001E, "SYNC_RESET_PIPE_AND_CLEAR_STALL"), Map.entry(0x001F, "CLASS_OTHER"), Map.entry(0x0020, "VENDOR_OTHER"),
            Map.entry(0x0021, "GET_STATUS_FROM_OTHER"), Map.entry(0x0022, "CLEAR_FEATURE_TO_OTHER"), Map.entry(0x0023, "SET_FEATURE_TO_OTHER"),
            Map.entry(0x0024, "GET_DESCRIPTOR_FROM_ENDPOINT"), Map.entry(0x0025, "SET_DESCRIPTOR_TO_ENDPOINT"),
            Map.entry(0x0026, "GET_CONFIGURATION"), Map.entry(0x0027, "GET_INTERFACE"), Map.entry(0x0028, "GET_DESCRIPTOR_FROM_INTERFACE"),
            Map.entry(0x0029, "SET_DESCRIPTOR_TO_INTERFACE"), Map.entry(0x002A, "GET_MS_FEATURE_DESCRIPTOR"),
            Map.entry(0x0030, "SYNC_RESET_PIPE"), Map.entry(0x0031, "SYNC_CLEAR_STALL"), Map.entry(0x0032, "CONTROL_TRANSFER_EX"),
            Map.entry(0x0035, "OPEN_STATIC_STREAMS"), Map.entry(0x0036, "CLOSE_STATIC_STREAMS"),
            Map.entry(0x0037, "BULK_OR_INTERRUPT_TRANSFER_USING_CHAINED_MDL"), Map.entry(0x0038, "ISOCH_TRANSFER_USING_CHAINED_MDL"));

    private static final Map<Integer, String> STATUS = Map.ofEntries(
            Map.entry(0x00000000, "Success"), Map.entry(0x40000000, "Pending"),
            Map.entry(0xC0000001, "CRC"), Map.entry(0xC0000002, "BTSTUFF"), Map.entry(0xC0000003, "DATA_TOGGLE_MISMATCH"),
            Map.entry(0xC0000004, "STALL_PID"), Map.entry(0xC0000005, "DEV_NOT_RESPONDING"), Map.entry(0xC0000006, "PID_CHECK_FAILURE"),
            Map.entry(0xC0000007, "UNEXPECTED_PID"), Map.entry(0xC0000008, "DATA_OVERRUN"), Map.entry(0xC0000009, "DATA_UNDERRUN"),
            Map.entry(0xC000000C, "BUFFER_OVERRUN"), Map.entry(0xC000000D, "BUFFER_UNDERRUN"), Map.entry(0xC000000F, "NOT_ACCESSED"),
            Map.entry(0xC0000010, "FIFO"), Map.entry(0xC0000011, "XACT_ERROR"), Map.entry(0xC0000012, "BABBLE_DETECTED"),
            Map.entry(0xC0000013, "DATA_BUFFER_ERROR"), Map.entry(0xC0000030, "ENDPOINT_HALTED"),
            Map.entry(0x80000200, "INVALID_URB_FUNCTION"), Map.entry(0x80000300, "INVALID_PARAMETER"), Map.entry(0x80000400, "ERROR_BUSY"),
            Map.entry(0x80000600, "INVALID_PIPE_HANDLE"), Map.entry(0x80000700, "NO_BANDWIDTH"), Map.entry(0x80000800, "INTERNAL_HC_ERROR"),
            Map.entry(0x80000900, "ERROR_SHORT_TRANSFER"), Map.entry(0xC0000A00, "BAD_START_FRAME"), Map.entry(0xC0000B00, "ISOCH_REQUEST_FAILED"),
            Map.entry(0xC0000E00, "NOT_SUPPORTED"), Map.entry(0xC0000F00, "INVALID_CONFIGURATION_DESCRIPTOR"),
            Map.entry(0xC0001000, "INSUFFICIENT_RESOURCES"), Map.entry(0xC0002000, "SET_CONFIG_FAILED"), Map.entry(0xC0003000, "BUFFER_TOO_SMALL"),
            Map.entry(0xC0004000, "INTERFACE_NOT_FOUND"), Map.entry(0xC0005000, "INVALID_PIPE_FLAGS"), Map.entry(0xC0006000, "TIMEOUT"),
            Map.entry(0xC0007000, "DEVICE_GONE"), Map.entry(0xC0008000, "STATUS_NOT_MAPPED"), Map.entry(0xC0009000, "HUB_INTERNAL_ERROR"),
            Map.entry(0xC0010000, "CANCELED"), Map.entry(0xC0020000, "ISO_NOT_ACCESSED_BY_HW"), Map.entry(0xC0030000, "ISO_TD_ERROR"),
            Map.entry(0xC0040000, "ISO_NA_LATE_USBPORT"), Map.entry(0xC0050000, "ISO_NOT_ACCESSED_LATE"), Map.entry(0xC0100000, "BAD_DESCRIPTOR"));

    private static final String[] STANDARD_REQUESTS = {
        "GET_STATUS", "CLEAR_FEATURE", null, "SET_FEATURE", null, "SET_ADDRESS", "GET_DESCRIPTOR", "SET_DESCRIPTOR",
        "GET_CONFIGURATION", "SET_CONFIGURATION", "GET_INTERFACE", "SET_INTERFACE", "SYNCH_FRAME"
    };

    private static final Map<Integer, String> CLASS_REQUESTS = Map.ofEntries(
            Map.entry(0x01, "GET_REPORT (HID)"), Map.entry(0x02, "GET_IDLE (HID)"), Map.entry(0x03, "GET_PROTOCOL (HID)"),
            Map.entry(0x09, "SET_REPORT (HID)"), Map.entry(0x0A, "SET_IDLE (HID)"), Map.entry(0x0B, "SET_PROTOCOL (HID)"),
            Map.entry(0x20, "SET_LINE_CODING (CDC)"), Map.entry(0x21, "GET_LINE_CODING (CDC)"),
            Map.entry(0x22, "SET_CONTROL_LINE_STATE (CDC)"), Map.entry(0x23, "SEND_BREAK (CDC)"),
            Map.entry(0xFE, "GET_MAX_LUN (MSC)"), Map.entry(0xFF, "BULK_ONLY_RESET (MSC)"));

    /** Vendor requests are chip-specific; these are the two serial bridges seen most often. */
    private static final Map<Integer, String> VENDOR_HINTS = Map.ofEntries(
            Map.entry(0x00, "FTDI RESET / CP210x IFC_ENABLE"), Map.entry(0x01, "FTDI MODEM_CTRL / CP210x SET_BAUDDIV"),
            Map.entry(0x02, "FTDI SET_FLOW_CTRL"), Map.entry(0x03, "FTDI SET_BAUD_RATE / CP210x SET_LINE_CTL"),
            Map.entry(0x04, "FTDI SET_DATA"), Map.entry(0x05, "FTDI GET_MODEM_STATUS"), Map.entry(0x06, "FTDI SET_EVENT_CHAR"),
            Map.entry(0x07, "FTDI SET_ERROR_CHAR / CP210x SET_MHS"), Map.entry(0x09, "FTDI SET_LATENCY_TIMER"),
            Map.entry(0x0A, "FTDI GET_LATENCY_TIMER"), Map.entry(0x1E, "CP210x SET_BAUDRATE"),
            Map.entry(0x90, "FTDI READ_EEPROM"), Map.entry(0x91, "FTDI WRITE_EEPROM"));

    private static final Map<Integer, String> DESCRIPTOR_TYPES = Map.ofEntries(
            Map.entry(1, "DEVICE"), Map.entry(2, "CONFIGURATION"), Map.entry(3, "STRING"), Map.entry(4, "INTERFACE"),
            Map.entry(5, "ENDPOINT"), Map.entry(6, "DEVICE_QUALIFIER"), Map.entry(7, "OTHER_SPEED_CONFIGURATION"),
            Map.entry(8, "INTERFACE_POWER"), Map.entry(11, "INTERFACE_ASSOCIATION"), Map.entry(15, "BOS"),
            Map.entry(0x21, "HID"), Map.entry(0x22, "REPORT"), Map.entry(0x23, "PHYSICAL"), Map.entry(0x29, "HUB"),
            Map.entry(0x2A, "SUPERSPEED_HUB"), Map.entry(0x30, "SUPERSPEED_ENDPOINT_COMPANION"));

    private static final Map<Integer, String> CLASSES = Map.ofEntries(
            Map.entry(0x00, "Defined at interface level"), Map.entry(0x01, "Audio"), Map.entry(0x02, "CDC Control"),
            Map.entry(0x03, "HID"), Map.entry(0x05, "Physical"), Map.entry(0x06, "Image"), Map.entry(0x07, "Printer"),
            Map.entry(0x08, "Mass Storage"), Map.entry(0x09, "Hub"), Map.entry(0x0A, "CDC Data"), Map.entry(0x0B, "Smart Card"),
            Map.entry(0x0D, "Content Security"), Map.entry(0x0E, "Video"), Map.entry(0x0F, "Personal Healthcare"),
            Map.entry(0x10, "Audio/Video"), Map.entry(0xDC, "Diagnostic"), Map.entry(0xE0, "Wireless Controller"),
            Map.entry(0xEF, "Miscellaneous"), Map.entry(0xFE, "Application Specific"), Map.entry(0xFF, "Vendor specific"));

    private static final String[] STAGES = {"setup", "data", "status", "complete"};
    private static final String[] ENDPOINT_TYPES = {"Control", "Isochronous", "Bulk", "Interrupt"};

    private UsbPcapDissector() {
    }

    public static PacketRow dissect(byte[] raw, long number, Instant time, double relativeSeconds) {
        if (raw.length < HEADER_MIN) {
            return new PacketRow(number, time, relativeSeconds, "", "", null, null, "USB", "", raw.length,
                    "Truncated USBPcap header (" + raw.length + " bytes)", raw, new byte[0],
                    "USBPcap record shorter than the 27-byte header", Map.of());
        }
        ByteBuffer b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        int headerLen = b.getShort(0) & 0xFFFF;
        long irpId = b.getLong(2);
        int status = b.getInt(10);
        int function = b.getShort(14) & 0xFFFF;
        int infoBits = b.get(16) & 0xFF;
        int bus = b.getShort(17) & 0xFFFF;
        int device = b.getShort(19) & 0xFFFF;
        int endpointRaw = b.get(21) & 0xFF;
        int transfer = b.get(22) & 0xFF;
        long dataLength = b.getInt(23) & 0xFFFFFFFFL;

        boolean fromDevice = (infoBits & 1) == 1;
        boolean endpointIn = (endpointRaw & 0x80) != 0;
        int endpoint = endpointRaw & 0x7F;
        int dataStart = Math.min(Math.max(headerLen, HEADER_MIN), raw.length);
        byte[] payload = Arrays.copyOfRange(raw, dataStart, raw.length);

        String addr = bus + "." + device;
        String ep = addr + "." + endpoint;
        String src = fromDevice ? ep : "host";
        String dst = fromDevice ? "host" : ep;
        String type = transferName(transfer);
        String direction = endpointIn ? "in" : "out";
        String functionName = FUNCTIONS.getOrDefault(function, String.format("0x%04X", function));
        String statusName = STATUS.getOrDefault(status, String.format("0x%08X", status));

        List<String> details = new ArrayList<>();
        Map<String, String> fields = new LinkedHashMap<>();
        details.add("USB: bus " + bus + ", device " + device + ", endpoint " + endpoint + " (" + direction.toUpperCase(Locale.ROOT) + ")"
                + ", " + (fromDevice ? "device → host" : "host → device"));
        details.add("URB: " + type + " transfer, function " + functionName + String.format(" (0x%04X)", function)
                + ", status " + statusName + String.format(" (0x%08X)", status));
        details.add(String.format("IRP 0x%016X, header %d byte(s), data %d byte(s)", irpId, headerLen, dataLength));

        fields.put("usb.bus", String.valueOf(bus));
        fields.put("usb.device", String.valueOf(device));
        fields.put("usb.addr", addr);
        fields.put("usb.endpoint", String.valueOf(endpoint));
        fields.put("usb.dir", direction);
        fields.put("usb.type", type.toLowerCase(Locale.ROOT).replace(" ", "_"));
        fields.put("usb.function", functionName.toLowerCase(Locale.ROOT));
        fields.put("usb.status", statusName.toLowerCase(Locale.ROOT));
        fields.put("usb.irp", String.format("0x%x", irpId));

        String info;
        switch (transfer) {
            case TRANSFER_CONTROL -> {
                int stage = headerLen >= 28 ? raw[27] & 0xFF : -1;
                String stageName = stage >= 0 && stage < STAGES.length ? STAGES[stage] : "stage " + stage;
                if (stage >= 0) {
                    details.add("Control stage: " + stageName);
                    fields.put("usb.stage", stageName);
                }
                info = "URB_CONTROL " + direction;
                if (stage == 0 && payload.length >= 8) {
                    String summary = decodeSetup(payload, details, fields);
                    if (summary != null) {
                        info = summary;
                    }
                } else if (fromDevice && endpoint == 0 && payload.length >= 2) {
                    String summary = decodeDescriptors(payload, details);
                    if (summary != null) {
                        info = summary;
                    }
                }
            }
            case TRANSFER_BULK -> info = "URB_BULK " + direction;
            case TRANSFER_INTERRUPT -> info = "URB_INTERRUPT " + direction;
            case TRANSFER_ISOCH -> {
                info = "URB_ISOCHRONOUS " + direction;
                if (headerLen >= 39) {
                    long startFrame = b.getInt(27) & 0xFFFFFFFFL;
                    long packets = b.getInt(31) & 0xFFFFFFFFL;
                    long errors = b.getInt(35) & 0xFFFFFFFFL;
                    details.add("Isochronous: start frame " + startFrame + ", " + packets + " packet(s), " + errors + " error(s)");
                    info += " " + packets + " pkt";
                }
            }
            case TRANSFER_IRP_INFO -> info = "URB_FUNCTION_" + functionName;
            default -> info = "USB " + type + " " + direction;
        }
        if (transfer != TRANSFER_IRP_INFO) {
            info += " Len=" + payload.length;
        }
        if (status != 0) {
            info += " [" + statusName + "]";
        }
        if (payload.length > 0) {
            details.add("Payload: " + payload.length + " byte(s)\n" + PayloadDecoder.hexDump(payload));
        }
        return new PacketRow(number, time, relativeSeconds, src, dst, null, null, "USB", "USB " + type, raw.length, info, raw,
                payload, String.join("\n", details), fields);
    }

    static String transferName(int transfer) {
        return switch (transfer) {
            case TRANSFER_ISOCH -> "Isoch";
            case TRANSFER_INTERRUPT -> "Interrupt";
            case TRANSFER_CONTROL -> "Control";
            case TRANSFER_BULK -> "Bulk";
            case TRANSFER_IRP_INFO -> "IRP info";
            default -> "Unknown";
        };
    }

    // --- control setup packets ---------------------------------------------------------

    /** Decodes the 8-byte setup packet; returns the one-line summary for the info column. */
    private static String decodeSetup(byte[] p, List<String> details, Map<String, String> fields) {
        int bmRequestType = p[0] & 0xFF;
        int bRequest = p[1] & 0xFF;
        int wValue = le16(p, 2);
        int wIndex = le16(p, 4);
        int wLength = le16(p, 6);
        boolean deviceToHost = (bmRequestType & 0x80) != 0;
        int typeBits = (bmRequestType >> 5) & 3;
        int recipient = bmRequestType & 0x1F;
        String typeName = new String[] {"Standard", "Class", "Vendor", "Reserved"}[typeBits];
        String recipientName = switch (recipient) {
            case 0 -> "Device";
            case 1 -> "Interface";
            case 2 -> "Endpoint";
            case 3 -> "Other";
            default -> "Reserved";
        };
        String name;
        String extra = "";
        if (typeBits == 0) {
            name = bRequest < STANDARD_REQUESTS.length && STANDARD_REQUESTS[bRequest] != null
                    ? STANDARD_REQUESTS[bRequest] : String.format("REQUEST_0x%02X", bRequest);
        } else if (typeBits == 1) {
            name = CLASS_REQUESTS.getOrDefault(bRequest, String.format("CLASS_REQUEST_0x%02X", bRequest));
        } else {
            name = String.format("VENDOR_REQUEST_0x%02X", bRequest);
            String hint = VENDOR_HINTS.get(bRequest);
            extra = hint == null ? "" : " (" + hint + "?)";
        }
        String summary = name.replace('_', ' ') + " Request";
        if (typeBits == 0) {
            switch (bRequest) {
                case 6, 7 -> {
                    int descType = wValue >> 8;
                    int index = wValue & 0xFF;
                    String descName = DESCRIPTOR_TYPES.getOrDefault(descType, String.format("type 0x%02X", descType));
                    summary += " " + descName + (index != 0 || descType == 3 ? " idx " + index : "");
                    if (descType == 3 && wIndex != 0) {
                        summary += String.format(" lang 0x%04X", wIndex);
                    }
                    fields.put("usb.descriptor", descName.toLowerCase(Locale.ROOT));
                }
                case 5 -> summary += " " + wValue;
                case 9 -> summary += " " + wValue;
                case 11 -> summary += " interface " + wIndex + " alt " + wValue;
                case 1, 3 -> summary += " " + featureName(wValue, recipient) + " on " + recipientName.toLowerCase(Locale.ROOT) + " " + wIndex;
                default -> {
                    // GET_STATUS and friends: nothing more to say on the request side
                }
            }
        }
        details.add(String.format("Setup: bmRequestType 0x%02X (%s, %s, %s), bRequest 0x%02X %s%s, wValue 0x%04X, wIndex 0x%04X, wLength %d",
                bmRequestType, deviceToHost ? "device-to-host" : "host-to-device", typeName, recipientName, bRequest, name, extra,
                wValue, wIndex, wLength));
        String key = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        fields.put("usb.request", key);
        fields.put("usb.request_type", typeName.toLowerCase(Locale.ROOT));
        if (p.length > 8) {
            details.add("Setup data: " + (p.length - 8) + " byte(s) follow the setup packet");
        }
        return summary;
    }

    private static String featureName(int feature, int recipient) {
        if (recipient == 2 && feature == 0) {
            return "ENDPOINT_HALT";
        }
        return switch (feature) {
            case 1 -> "DEVICE_REMOTE_WAKEUP";
            case 2 -> "TEST_MODE";
            default -> "feature " + feature;
        };
    }

    // --- descriptors --------------------------------------------------------------------

    /** Recognises device, configuration and string descriptors in IN data; returns the info summary or null. */
    private static String decodeDescriptors(byte[] p, List<String> details) {
        int len = p[0] & 0xFF;
        int type = p[1] & 0xFF;
        if (len < 2 || len > p.length) {
            return null;
        }
        switch (type) {
            case 1 -> {
                if (len != 18 || p.length < 18) {
                    return null;
                }
                int deviceClass = p[4] & 0xFF;
                int vid = le16(p, 8);
                int pid = le16(p, 10);
                details.add(String.format("DEVICE descriptor: USB %s, class 0x%02X %s (sub 0x%02X, proto 0x%02X), max packet 0 %d, "
                                + "idVendor 0x%04X, idProduct 0x%04X, device %s, strings mfr %d / product %d / serial %d, %d configuration(s)",
                        bcd(le16(p, 2)), deviceClass, CLASSES.getOrDefault(deviceClass, ""), p[5] & 0xFF, p[6] & 0xFF, p[7] & 0xFF,
                        vid, pid, bcd(le16(p, 12)), p[14] & 0xFF, p[15] & 0xFF, p[16] & 0xFF, p[17] & 0xFF));
                return String.format("GET DESCRIPTOR Response DEVICE (%04x:%04x)", vid, pid);
            }
            case 2, 7 -> {
                if (len != 9 || p.length < 9) {
                    return null;
                }
                walkConfiguration(p, details);
                return "GET DESCRIPTOR Response " + (type == 2 ? "CONFIGURATION" : "OTHER_SPEED_CONFIGURATION");
            }
            case 3 -> {
                if (len == 4 && p.length == 4) {
                    details.add(String.format("STRING descriptor 0: language 0x%04X", le16(p, 2)));
                } else {
                    String text = new String(p, 2, len - 2, StandardCharsets.UTF_16LE);
                    details.add("STRING descriptor: \"" + text + "\"");
                }
                return "GET DESCRIPTOR Response STRING";
            }
            case 6 -> {
                if (len != 10 || p.length < 10) {
                    return null;
                }
                details.add(String.format("DEVICE_QUALIFIER descriptor: USB %s, class 0x%02X, max packet 0 %d, %d configuration(s)",
                        bcd(le16(p, 2)), p[4] & 0xFF, p[7] & 0xFF, p[8] & 0xFF));
                return "GET DESCRIPTOR Response DEVICE_QUALIFIER";
            }
            default -> {
                return null;
            }
        }
    }

    private static void walkConfiguration(byte[] p, List<String> details) {
        int off = 0;
        while (off + 2 <= p.length) {
            int l = p[off] & 0xFF;
            int t = p[off + 1] & 0xFF;
            if (l < 2) {
                break;
            }
            int end = Math.min(off + l, p.length);
            switch (t) {
                case 2, 7 -> {
                    if (end - off >= 9) {
                        int attributes = p[off + 7] & 0xFF;
                        details.add(String.format("CONFIGURATION descriptor: value %d, %d interface(s), total %d byte(s), attributes 0x%02X%s%s, max power %d mA",
                                p[off + 5] & 0xFF, p[off + 4] & 0xFF, le16(p, off + 2), attributes,
                                (attributes & 0x40) != 0 ? " self-powered" : " bus-powered",
                                (attributes & 0x20) != 0 ? " remote-wakeup" : "", (p[off + 8] & 0xFF) * 2));
                    }
                }
                case 4 -> {
                    if (end - off >= 9) {
                        int cls = p[off + 5] & 0xFF;
                        details.add(String.format("  INTERFACE %d alt %d: %s (class 0x%02X, sub 0x%02X, proto 0x%02X), %d endpoint(s)",
                                p[off + 2] & 0xFF, p[off + 3] & 0xFF, CLASSES.getOrDefault(cls, "class " + cls), cls,
                                p[off + 6] & 0xFF, p[off + 7] & 0xFF, p[off + 4] & 0xFF));
                    }
                }
                case 5 -> {
                    if (end - off >= 7) {
                        int address = p[off + 2] & 0xFF;
                        int attributes = p[off + 3] & 0xFF;
                        details.add(String.format("    ENDPOINT 0x%02X %s %s, max packet %d, interval %d",
                                address, (address & 0x80) != 0 ? "IN" : "OUT", ENDPOINT_TYPES[attributes & 3],
                                le16(p, off + 4) & 0x7FF, p[off + 6] & 0xFF));
                    }
                }
                case 0x21 -> {
                    if (end - off >= 9) {
                        details.add(String.format("    HID descriptor: HID %s, country %d, %d descriptor(s), report descriptor %d byte(s)",
                                bcd(le16(p, off + 2)), p[off + 4] & 0xFF, p[off + 5] & 0xFF, le16(p, off + 7)));
                    }
                }
                case 0x24 -> {
                    if (end - off >= 3) {
                        details.add(String.format("    CDC functional descriptor subtype 0x%02X, %d byte(s)", p[off + 2] & 0xFF, l));
                    }
                }
                case 11 -> {
                    if (end - off >= 8) {
                        int cls = p[off + 4] & 0xFF;
                        details.add(String.format("  INTERFACE ASSOCIATION: interfaces %d..%d, %s (class 0x%02X)",
                                p[off + 2] & 0xFF, (p[off + 2] & 0xFF) + (p[off + 3] & 0xFF) - 1, CLASSES.getOrDefault(cls, "class " + cls), cls));
                    }
                }
                default -> details.add(String.format("  descriptor type 0x%02X (%s), %d byte(s)", t,
                        DESCRIPTOR_TYPES.getOrDefault(t, "unknown"), l));
            }
            off += l;
        }
    }

    private static int le16(byte[] p, int off) {
        return (p[off] & 0xFF) | ((p[off + 1] & 0xFF) << 8);
    }

    private static String bcd(int v) {
        return String.format("%x.%02x", v >> 8, v & 0xFF);
    }
}
