package com.linkscope.modules.hid;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.ptr.ShortByReference;
import com.sun.jna.win32.W32APIOptions;

import java.io.Closeable;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Windows bindings for the HID tab, over the JNA that is already on the classpath:
 * SetupAPI to enumerate HID interfaces, hid.dll to read attributes, capabilities and to
 * decode reports with the system's own report-descriptor parser (HidP_*), kernel32 to
 * read and write the device, and XInput for Xbox controllers (state and rumble).
 * Everything here is Windows-only and degrades to "unavailable" elsewhere.
 */
final class HidNative {
    static final int DIGCF_PRESENT = 0x02;
    static final int DIGCF_DEVICEINTERFACE = 0x10;
    static final int GENERIC_READ = 0x80000000;
    static final int GENERIC_WRITE = 0x40000000;
    static final int FILE_SHARE_READ = 0x1;
    static final int FILE_SHARE_WRITE = 0x2;
    static final int OPEN_EXISTING = 3;
    static final int HIDP_STATUS_SUCCESS = 0x00110000;
    static final short HIDP_INPUT = 0;
    static final int CAPS_SIZE = 64;
    static final int CAP_ENTRY_SIZE = 72;
    static final int MAX_BUTTONS = 128;

    interface Kernel32 extends Library {
        Pointer CreateFileW(WString name, int access, int share, Pointer security, int disposition, int flags, Pointer template);

        boolean ReadFile(Pointer handle, Pointer buffer, int count, IntByReference read, Pointer overlapped);

        boolean WriteFile(Pointer handle, Pointer buffer, int count, IntByReference written, Pointer overlapped);

        boolean CancelIoEx(Pointer handle, Pointer overlapped);

        boolean CloseHandle(Pointer handle);
    }

    interface SetupApi extends Library {
        Pointer SetupDiGetClassDevsW(Guid classGuid, WString enumerator, Pointer parent, int flags);

        boolean SetupDiEnumDeviceInterfaces(Pointer devices, Pointer devInfo, Guid classGuid, int index, InterfaceData data);

        boolean SetupDiGetDeviceInterfaceDetailW(Pointer devices, InterfaceData data, Pointer detail, int detailSize,
                                                 IntByReference required, Pointer devInfo);

        boolean SetupDiDestroyDeviceInfoList(Pointer devices);
    }

    interface Hid extends Library {
        void HidD_GetHidGuid(Guid guid);

        boolean HidD_GetAttributes(Pointer handle, Attributes attributes);

        boolean HidD_GetManufacturerString(Pointer handle, Pointer buffer, int length);

        boolean HidD_GetProductString(Pointer handle, Pointer buffer, int length);

        boolean HidD_GetPreparsedData(Pointer handle, PointerByReference preparsed);

        boolean HidD_FreePreparsedData(Pointer preparsed);

        boolean HidD_SetOutputReport(Pointer handle, Pointer buffer, int length);

        int HidP_GetCaps(Pointer preparsed, Pointer caps);

        int HidP_GetButtonCaps(short reportType, Pointer caps, ShortByReference length, Pointer preparsed);

        int HidP_GetValueCaps(short reportType, Pointer caps, ShortByReference length, Pointer preparsed);

        int HidP_MaxUsageListLength(short reportType, short usagePage, Pointer preparsed);

        int HidP_GetUsages(short reportType, short usagePage, short linkCollection, Pointer usageList, IntByReference length,
                           Pointer preparsed, Pointer report, int reportLength);

        int HidP_GetUsageValue(short reportType, short usagePage, short linkCollection, short usage, IntByReference value,
                               Pointer preparsed, Pointer report, int reportLength);
    }

    interface XInput extends Library {
        int XInputGetState(int userIndex, Pointer state);

        int XInputSetState(int userIndex, Pointer vibration);
    }

    @Structure.FieldOrder({"data1", "data2", "data3", "data4"})
    public static class Guid extends Structure {
        public int data1;
        public short data2;
        public short data3;
        public byte[] data4 = new byte[8];
    }

    @Structure.FieldOrder({"cbSize", "interfaceClassGuid", "flags", "reserved"})
    public static class InterfaceData extends Structure {
        public int cbSize;
        public Guid interfaceClassGuid;
        public int flags;
        public Pointer reserved;

        public InterfaceData() {
            cbSize = size();
        }
    }

    @Structure.FieldOrder({"size", "vendorId", "productId", "versionNumber"})
    public static class Attributes extends Structure {
        public int size;
        public short vendorId;
        public short productId;
        public short versionNumber;

        public Attributes() {
            size = size();
        }
    }

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    private static Kernel32 kernel32;
    private static SetupApi setupApi;
    private static Hid hid;
    private static XInput xinput;
    private static String loadError;

    private HidNative() {
    }

    static synchronized boolean available() {
        if (!WINDOWS) {
            loadError = "HID devices can be read on Windows only.";
            return false;
        }
        if (kernel32 != null) {
            return true;
        }
        try {
            kernel32 = Native.load("kernel32", Kernel32.class, W32APIOptions.DEFAULT_OPTIONS);
            setupApi = Native.load("setupapi", SetupApi.class, W32APIOptions.DEFAULT_OPTIONS);
            hid = Native.load("hid", Hid.class, W32APIOptions.DEFAULT_OPTIONS);
            return true;
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            loadError = "Could not load the Windows HID libraries: " + e.getMessage();
            kernel32 = null;
            return false;
        }
    }

    static synchronized boolean xinputAvailable() {
        if (!WINDOWS) {
            return false;
        }
        if (xinput != null) {
            return true;
        }
        for (String lib : new String[] {"xinput1_4", "xinput9_1_0", "xinput1_3"}) {
            try {
                xinput = Native.load(lib, XInput.class, W32APIOptions.DEFAULT_OPTIONS);
                return true;
            } catch (UnsatisfiedLinkError | RuntimeException ignored) {
                // try the next name
            }
        }
        return false;
    }

    static String loadError() {
        return loadError == null ? "" : loadError;
    }

    // --- enumeration ----------------------------------------------------------------------

    /** Every present HID interface, plus a pseudo device per connected XInput slot (listed first). */
    static List<HidDevice> enumerate() {
        List<HidDevice> out = new ArrayList<>();
        if (xinputAvailable()) {
            for (int slot = 0; slot < 4; slot++) {
                if (xinputState(slot) != null) {
                    out.add(new HidDevice("xinput:" + slot, 0x045E, 0, "Microsoft", "Xbox controller", HidUsages.PAGE_GENERIC_DESKTOP,
                            HidUsages.USAGE_GAMEPAD, XInputState.SIZE, 4, XInputState.BUTTON_NAMES.size(), 6, slot));
                }
            }
        }
        if (!available()) {
            return out;
        }
        Guid guid = new Guid();
        hid.HidD_GetHidGuid(guid);
        Pointer devices = setupApi.SetupDiGetClassDevsW(guid, null, null, DIGCF_PRESENT | DIGCF_DEVICEINTERFACE);
        if (devices == null || Pointer.nativeValue(devices) == -1) {
            return out;
        }
        try {
            for (int index = 0; ; index++) {
                InterfaceData data = new InterfaceData();
                if (!setupApi.SetupDiEnumDeviceInterfaces(devices, null, guid, index, data)) {
                    break;
                }
                IntByReference required = new IntByReference();
                setupApi.SetupDiGetDeviceInterfaceDetailW(devices, data, null, 0, required, null);
                int size = required.getValue();
                if (size <= 0) {
                    continue;
                }
                Memory detail = new Memory(size);
                detail.clear();
                detail.setInt(0, Native.POINTER_SIZE == 8 ? 8 : 6);
                if (!setupApi.SetupDiGetDeviceInterfaceDetailW(devices, data, detail, size, required, null)) {
                    continue;
                }
                String path = detail.getWideString(4);
                HidDevice d = describe(path);
                if (d != null) {
                    out.add(d);
                }
            }
        } finally {
            setupApi.SetupDiDestroyDeviceInfoList(devices);
        }
        // Game controllers first, then everything else in enumeration order.
        List<HidDevice> sorted = new ArrayList<>();
        out.stream().filter(HidDevice::isXInput).forEach(sorted::add);
        out.stream().filter(d -> !d.isXInput() && d.isGameController()).forEach(sorted::add);
        out.stream().filter(d -> !d.isXInput() && !d.isGameController()).forEach(sorted::add);
        return sorted;
    }

    /** Attributes, strings and capability counts, read with a zero-access handle so keyboards and mice answer too. */
    private static HidDevice describe(String path) {
        Pointer h = kernel32.CreateFileW(new WString(path), 0, FILE_SHARE_READ | FILE_SHARE_WRITE, null, OPEN_EXISTING, 0, null);
        if (h == null || Pointer.nativeValue(h) == -1) {
            return null;
        }
        try {
            Attributes attributes = new Attributes();
            if (!hid.HidD_GetAttributes(h, attributes)) {
                return null;
            }
            String manufacturer = readString(h, true);
            String product = readString(h, false);
            PointerByReference ppd = new PointerByReference();
            int usagePage = 0;
            int usage = 0;
            int inputLen = 0;
            int outputLen = 0;
            int buttons = 0;
            int values = 0;
            if (hid.HidD_GetPreparsedData(h, ppd)) {
                try {
                    Memory caps = new Memory(CAPS_SIZE);
                    caps.clear();
                    if (hid.HidP_GetCaps(ppd.getValue(), caps) == HIDP_STATUS_SUCCESS) {
                        usage = caps.getShort(0) & 0xFFFF;
                        usagePage = caps.getShort(2) & 0xFFFF;
                        inputLen = caps.getShort(4) & 0xFFFF;
                        outputLen = caps.getShort(6) & 0xFFFF;
                        buttons = caps.getShort(46) & 0xFFFF;
                        values = caps.getShort(48) & 0xFFFF;
                    }
                } finally {
                    hid.HidD_FreePreparsedData(ppd.getValue());
                }
            }
            return new HidDevice(path, attributes.vendorId & 0xFFFF, attributes.productId & 0xFFFF, manufacturer, product,
                    usagePage, usage, inputLen, outputLen, buttons, values, -1);
        } finally {
            kernel32.CloseHandle(h);
        }
    }

    private static String readString(Pointer h, boolean manufacturer) {
        Memory buf = new Memory(256);
        buf.clear();
        boolean ok = manufacturer ? hid.HidD_GetManufacturerString(h, buf, 256) : hid.HidD_GetProductString(h, buf, 256);
        return ok ? buf.getWideString(0).trim() : "";
    }

    // --- an open HID device ---------------------------------------------------------------

    /** An open device: reads and decodes input reports, writes output reports. */
    static final class Handle implements Closeable {
        private final Pointer handle;
        private final Pointer preparsed;
        private final HidLayout layout;
        private final boolean writable;
        private final Set<Integer> buttonPages = new LinkedHashSet<>();
        private volatile boolean closed;

        private Handle(Pointer handle, Pointer preparsed, HidLayout layout, boolean writable) {
            this.handle = handle;
            this.preparsed = preparsed;
            this.layout = layout;
            this.writable = writable;
            layout.buttons().forEach(b -> buttonPages.add(b.page()));
        }

        static Handle open(String path) throws IOException {
            if (!available()) {
                throw new IOException(loadError());
            }
            boolean writable = true;
            Pointer h = kernel32.CreateFileW(new WString(path), GENERIC_READ | GENERIC_WRITE, FILE_SHARE_READ | FILE_SHARE_WRITE,
                    null, OPEN_EXISTING, 0, null);
            if (h == null || Pointer.nativeValue(h) == -1) {
                writable = false;
                h = kernel32.CreateFileW(new WString(path), GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE, null, OPEN_EXISTING, 0, null);
            }
            if (h == null || Pointer.nativeValue(h) == -1) {
                int err = Native.getLastError();
                throw new IOException(switch (err) {
                    case 5 -> "Windows keeps this device exclusive (keyboards and mice cannot be opened for reading; error 5)";
                    case 32 -> "Another driver or program holds this device exclusively (sharing violation, error 32); "
                            + "precision touchpads and some vendor tools do this";
                    case 2 -> "The device is gone (error 2); refresh the list";
                    default -> "Could not open the device (Windows error " + err + ")";
                });
            }
            PointerByReference ppd = new PointerByReference();
            if (!hid.HidD_GetPreparsedData(h, ppd)) {
                kernel32.CloseHandle(h);
                throw new IOException("The device has no report descriptor Windows can parse");
            }
            HidLayout layout;
            try {
                layout = readLayout(ppd.getValue());
            } catch (RuntimeException e) {
                hid.HidD_FreePreparsedData(ppd.getValue());
                kernel32.CloseHandle(h);
                throw new IOException("Could not read the device's capabilities: " + e.getMessage(), e);
            }
            return new Handle(h, ppd.getValue(), layout, writable);
        }

        HidLayout layout() {
            return layout;
        }

        boolean writable() {
            return writable;
        }

        /** Blocks until one input report arrives; null when the handle was cancelled or closed. */
        HidReport read() {
            int len = Math.max(1, layout.inputLength());
            Memory buf = new Memory(len);
            IntByReference got = new IntByReference();
            if (!kernel32.ReadFile(handle, buf, len, got, null) || closed) {
                return null;
            }
            int n = got.getValue();
            if (n <= 0) {
                return null;
            }
            byte[] raw = buf.getByteArray(0, n);
            return decode(buf, n, raw, Instant.now());
        }

        /** Decodes a report buffer with the system parser: buttons by usage page, then every value field. */
        HidReport decode(Pointer buf, int length, byte[] raw, Instant time) {
            Set<String> pressed = new LinkedHashSet<>();
            for (int page : buttonPages) {
                int max = hid.HidP_MaxUsageListLength(HIDP_INPUT, (short) page, preparsed);
                if (max <= 0) {
                    continue;
                }
                Memory usages = new Memory(2L * max);
                IntByReference count = new IntByReference(max);
                int status = hid.HidP_GetUsages(HIDP_INPUT, (short) page, (short) 0, usages, count, preparsed, buf, length);
                if (status != HIDP_STATUS_SUCCESS) {
                    continue;
                }
                for (int i = 0; i < count.getValue(); i++) {
                    pressed.add(HidUsages.name(page, usages.getShort(2L * i) & 0xFFFF));
                }
            }
            List<HidReport.Axis> axes = new ArrayList<>();
            for (HidLayout.Value v : layout.values()) {
                IntByReference value = new IntByReference();
                int status = hid.HidP_GetUsageValue(HIDP_INPUT, (short) v.page(), (short) v.linkCollection(), (short) v.usage(), value,
                        preparsed, buf, length);
                if (status != HIDP_STATUS_SUCCESS) {
                    continue; // belongs to another report id
                }
                long rawValue = value.getValue() & 0xFFFFFFFFL;
                if (v.bitSize() < 32) {
                    rawValue &= (1L << v.bitSize()) - 1;
                    if (v.min() < 0 && (rawValue & (1L << (v.bitSize() - 1))) != 0) {
                        rawValue -= 1L << v.bitSize();
                    }
                }
                axes.add(new HidReport.Axis(v.name(), rawValue, v.min(), v.max(), v.centered(), v.hat()));
            }
            return new HidReport(time, raw.length > 0 ? raw[0] & 0xFF : 0, raw, pressed, axes);
        }

        /** Sends an output report (first byte is the report id, 0 when the device uses none). */
        void write(byte[] report) throws IOException {
            Memory buf = new Memory(report.length);
            buf.write(0, report, 0, report.length);
            if (writable) {
                IntByReference written = new IntByReference();
                if (kernel32.WriteFile(handle, buf, report.length, written, null)) {
                    return;
                }
            }
            if (!hid.HidD_SetOutputReport(handle, buf, report.length)) {
                throw new IOException("The device rejected the output report (Windows error " + Native.getLastError() + ")");
            }
        }

        /** Unblocks a pending read from another thread. */
        void cancel() {
            closed = true;
            kernel32.CancelIoEx(handle, null);
        }

        @Override
        public void close() {
            closed = true;
            hid.HidD_FreePreparsedData(preparsed);
            kernel32.CloseHandle(handle);
        }
    }

    /** Buttons and values from the preparsed data. */
    static HidLayout readLayout(Pointer ppd) {
        Memory caps = new Memory(CAPS_SIZE);
        caps.clear();
        if (hid.HidP_GetCaps(ppd, caps) != HIDP_STATUS_SUCCESS) {
            throw new IllegalStateException("HidP_GetCaps failed");
        }
        int inputLen = caps.getShort(4) & 0xFFFF;
        int outputLen = caps.getShort(6) & 0xFFFF;
        int buttonCaps = caps.getShort(46) & 0xFFFF;
        int valueCaps = caps.getShort(48) & 0xFFFF;

        List<HidLayout.Button> buttons = new ArrayList<>();
        if (buttonCaps > 0) {
            Memory mem = new Memory((long) CAP_ENTRY_SIZE * buttonCaps);
            mem.clear();
            ShortByReference n = new ShortByReference((short) buttonCaps);
            if (hid.HidP_GetButtonCaps(HIDP_INPUT, mem, n, ppd) == HIDP_STATUS_SUCCESS) {
                for (int i = 0; i < (n.getValue() & 0xFFFF); i++) {
                    long off = (long) CAP_ENTRY_SIZE * i;
                    int page = mem.getShort(off) & 0xFFFF;
                    boolean range = mem.getByte(off + 12) != 0;
                    int min = mem.getShort(off + 56) & 0xFFFF;
                    int max = range ? mem.getShort(off + 58) & 0xFFFF : min;
                    for (int u = min; u <= max && buttons.size() < MAX_BUTTONS; u++) {
                        buttons.add(new HidLayout.Button(page, u, HidUsages.name(page, u)));
                    }
                }
            }
        }
        List<HidLayout.Value> values = new ArrayList<>();
        if (valueCaps > 0) {
            Memory mem = new Memory((long) CAP_ENTRY_SIZE * valueCaps);
            mem.clear();
            ShortByReference n = new ShortByReference((short) valueCaps);
            if (hid.HidP_GetValueCaps(HIDP_INPUT, mem, n, ppd) == HIDP_STATUS_SUCCESS) {
                for (int i = 0; i < (n.getValue() & 0xFFFF); i++) {
                    long off = (long) CAP_ENTRY_SIZE * i;
                    int page = mem.getShort(off) & 0xFFFF;
                    int link = mem.getShort(off + 6) & 0xFFFF;
                    boolean range = mem.getByte(off + 12) != 0;
                    boolean hasNull = mem.getByte(off + 16) != 0;
                    int bitSize = mem.getShort(off + 18) & 0xFFFF;
                    long logicalMin = mem.getInt(off + 40);
                    long logicalMax = mem.getInt(off + 44);
                    if (logicalMax < logicalMin) {
                        // unsigned field whose maximum came back sign-extended (65535 as -1, for one)
                        logicalMax = (1L << Math.max(1, Math.min(bitSize, 32))) - 1;
                        if (logicalMin < 0) {
                            logicalMin = 0;
                        }
                    }
                    if (logicalMin == 0 && logicalMax == 0 && bitSize > 0) {
                        logicalMax = (1L << Math.min(bitSize, 32)) - 1;
                    }
                    int min = mem.getShort(off + 56) & 0xFFFF;
                    int max = range ? mem.getShort(off + 58) & 0xFFFF : min;
                    for (int u = min; u <= max && values.size() < 64; u++) {
                        values.add(new HidLayout.Value(page, u, link, HidUsages.name(page, u), logicalMin, logicalMax, bitSize, hasNull));
                    }
                }
            }
        }
        return new HidLayout(buttons, values, inputLen, outputLen, false);
    }

    // --- XInput ---------------------------------------------------------------------------

    /** Current state of an XInput slot, or null when nothing is connected there. */
    static XInputState xinputState(int slot) {
        if (!xinputAvailable()) {
            return null;
        }
        Memory state = new Memory(XInputState.SIZE);
        state.clear();
        int result = xinput.XInputGetState(slot, state);
        if (result != 0) {
            return null;
        }
        return XInputState.parse(state.getByteArray(0, XInputState.SIZE));
    }

    /** Sets the two rumble motors, 0..65535 each. */
    static boolean xinputRumble(int slot, int left, int right) {
        if (!xinputAvailable()) {
            return false;
        }
        Memory v = new Memory(4);
        v.setShort(0, (short) Math.max(0, Math.min(65535, left)));
        v.setShort(2, (short) Math.max(0, Math.min(65535, right)));
        return xinput.XInputSetState(slot, v) == 0;
    }
}
