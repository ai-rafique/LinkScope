package com.linkscope.modules.usb;

import com.linkscope.TestSupport;
import com.linkscope.core.LogSink;
import com.linkscope.core.ModuleStatus;
import com.linkscope.core.PcapFiles;
import com.linkscope.modules.capture.PacketRow;
import com.linkscope.modules.capture.UsbPcapDissector;
import com.linkscope.modules.capture.UsbPcapDissectorTest;
import com.linkscope.modules.usb.UsbCaptureService.Hub;
import com.linkscope.modules.usb.UsbCaptureService.UsbDevice;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.linkscope.TestSupport.awaitTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UsbCaptureServiceTest {

    /** What USBPcapCMD --extcap-interfaces prints. */
    private static final String INTERFACES = """
            extcap {version=1.5.4.0}{help=https://desowin.org/usbpcap/}
            interface {value=\\\\.\\USBPcap1}{display=USBPcap1}
            interface {value=\\\\.\\USBPcap2}{display=USBPcap2}
            """;

    /** Trimmed --extcap-config output from a real machine. */
    private static final String CONFIG = """
            arg {number=0}{call=--snaplen}{display=Snapshot length}{tooltip=Snapshot length}{type=unsigned}{default=65535}
            arg {number=99}{call=--devices}{display=Attached USB Devices}{tooltip=Select individual devices to capture from}{type=multicheck}
            value {arg=99}{value=6}{display=[6] USB Composite Device}{enabled=true}
            value {arg=99}{value=6_1}{display=USB Input Device}{enabled=false}{parent=6}
            value {arg=99}{value=6_2}{display=HID Keyboard Device}{enabled=false}{parent=6_1}
            value {arg=99}{value=5}{display=[5] USB Composite Device}{enabled=true}
            value {arg=99}{value=5_1}{display=USB Input Device}{enabled=false}{parent=5}
            value {arg=99}{value=5_2}{display=HID-compliant mouse}{enabled=false}{parent=5_1}
            value {arg=99}{value=5_12}{display=Razer Basilisk V3 X HyperSpeed}{enabled=false}{parent=5}
            value {arg=99}{value=1}{display=[1] USB Composite Device}{enabled=true}
            value {arg=99}{value=1_1}{display=HP Wide Vision HD Camera}{enabled=false}{parent=1}
            value {arg=99}{value=2}{display=[2] Intel(R) Wireless Bluetooth(R)}{enabled=true}
            value {arg=99}{value=7}{display=[7] USB Serial Converter}{enabled=true}
            """;

    @Test
    void parsesRootHubs() {
        List<Hub> hubs = UsbCaptureService.parseInterfaces(INTERFACES);
        assertEquals(2, hubs.size());
        assertEquals("\\\\.\\USBPcap1", hubs.get(0).name());
        assertEquals("USBPcap2", hubs.get(1).display());
    }

    @Test
    void parsesDeviceTreeIntoTopLevelDevices() {
        List<UsbDevice> devices = UsbCaptureService.parseDevices(CONFIG);
        assertEquals(List.of("6", "5", "1", "2", "7"), devices.stream().map(UsbDevice::address).toList());
        UsbDevice six = devices.get(0);
        assertEquals("USB Composite Device", six.display());
        assertEquals(List.of("USB Input Device", "HID Keyboard Device"), six.children(), "grandchildren fold into the root device");
        assertTrue(six.looksLikeKeyboard());
        UsbDevice five = devices.get(1);
        assertEquals(List.of("USB Input Device", "HID-compliant mouse", "Razer Basilisk V3 X HyperSpeed"), five.children());
        assertFalse(five.looksLikeKeyboard());
        assertTrue(five.summary().contains("HID-compliant mouse"), five.summary());
        UsbDevice serial = devices.get(4);
        assertEquals("USB Serial Converter", serial.summary());
        assertTrue(serial.children().isEmpty());
    }

    @Test
    void buildsTheHelperCommandLine() {
        Path helper = Path.of("C:\\Program Files\\USBPcap\\USBPcapCMD.exe");
        List<String> selected = UsbCaptureService.buildCommand(helper, "\\\\.\\USBPcap1", false, List.of("5", "7"), true, 65535);
        assertEquals(List.of(helper.toString(), "-d", "\\\\.\\USBPcap1", "-o", "-", "-s", "65535",
                "--devices", "5,7", "--inject-descriptors"), selected);
        List<String> all = UsbCaptureService.buildCommand(helper, "\\\\.\\USBPcap2", true, List.of(), false, 4096);
        assertEquals(List.of(helper.toString(), "-d", "\\\\.\\USBPcap2", "-o", "-", "-s", "4096", "-A"), all);
    }

    @Test
    void streamReaderParsesRecordsFromAPipe() throws Exception {
        byte[] bulk = UsbPcapDissectorTest.record(1, 7, 0x81, UsbPcapDissector.TRANSFER_BULK, 0x0009, true, 0, new byte[0], new byte[] {1, 2});
        java.nio.file.Path tmp = java.nio.file.Files.createTempFile("ls-usb", ".pcap");
        try {
            PcapFiles.write(tmp, UsbPcapDissector.DLT_USBPCAP, List.of(new PcapFiles.Record(Instant.EPOCH, bulk, bulk.length),
                    new PcapFiles.Record(Instant.EPOCH.plusMillis(3), bulk, bulk.length)));
            try (PcapFiles.Stream s = new PcapFiles.Stream(new java.io.ByteArrayInputStream(java.nio.file.Files.readAllBytes(tmp)))) {
                assertEquals(UsbPcapDissector.DLT_USBPCAP, s.readHeader().linkType());
                assertEquals(Instant.EPOCH, s.next().time());
                assertEquals(Instant.EPOCH.plusMillis(3), s.next().time());
                assertEquals(null, s.next(), "clean end of stream");
            }
        } finally {
            java.nio.file.Files.deleteIfExists(tmp);
        }
    }

    @Test
    void powerShellLiteralsQuoteSpacesAndApostrophes() {
        assertEquals("'-d'", UsbCaptureService.psString("-d"));
        assertEquals("'\"C:\\Program Files\\USBPcap\\USBPcapCMD.exe\"'", UsbCaptureService.psString("C:\\Program Files\\USBPcap\\USBPcapCMD.exe"));
        assertEquals("'it''s'", UsbCaptureService.psString("it's"));
    }

    @Test
    void opensAndSavesUsbPcapFilesWithoutADriver(@TempDir Path dir) throws Exception {
        LogSink.get().clear();
        byte[] bulk = UsbPcapDissectorTest.record(1, 7, 0x81, UsbPcapDissector.TRANSFER_BULK, 0x0009, true, 0, new byte[0],
                new byte[] {1, 3, 0, 0, 0, 10, (byte) 0xC5, (byte) 0xCD});
        byte[] setup = UsbPcapDissectorTest.record(1, 7, 0x80, UsbPcapDissector.TRANSFER_CONTROL, 0x000B, false, 0, new byte[] {0},
                UsbPcapDissectorTest.setup(0x80, 6, 0x0100, 0, 18));
        Instant t0 = Instant.parse("2026-09-21T09:00:00Z");
        Path file = dir.resolve("usb.pcap");
        PcapFiles.write(file, UsbPcapDissector.DLT_USBPCAP, List.of(
                new PcapFiles.Record(t0, bulk, bulk.length), new PcapFiles.Record(t0.plusMillis(1500), setup, setup.length)));

        UsbCaptureService service = new UsbCaptureService();
        service.openFile(file);
        awaitTrue("opened", Duration.ofSeconds(10), () -> TestSupport.snapshot(service.rows()).size() == 2);
        List<PacketRow> rows = TestSupport.snapshot(service.rows());
        assertEquals("1.7.1", rows.get(0).src());
        assertEquals("URB_BULK in Len=8", rows.get(0).info());
        assertEquals(1.5, rows.get(1).relativeSeconds(), 0.0001);
        assertEquals(2, service.capturedProperty().get());
        assertEquals(16, service.bytesProperty().get(), "payload bytes of both records (8 data + 8 setup), not the headers");

        Path copy = dir.resolve("copy.pcap");
        AtomicBoolean saved = new AtomicBoolean();
        service.saveFile(copy, rows, () -> saved.set(true));
        awaitTrue("saved", Duration.ofSeconds(10), saved::get);
        assertEquals(UsbPcapDissector.DLT_USBPCAP, PcapFiles.readHeader(copy).linkType());
        assertEquals(2, PcapFiles.readAll(copy).size());
    }

    @Test
    void refusesNonUsbCaptures(@TempDir Path dir) throws Exception {
        LogSink.get().clear();
        Path file = dir.resolve("eth.pcap");
        PcapFiles.write(file, 1, List.of(new PcapFiles.Record(Instant.EPOCH, new byte[60], 60)));
        UsbCaptureService service = new UsbCaptureService();
        service.openFile(file);
        awaitTrue("refusal logged", Duration.ofSeconds(10), () -> TestSupport.dumpLog().contains("not a USB capture (link type 1)"));
        assertTrue(TestSupport.snapshot(service.rows()).isEmpty());
    }

    @Test
    void startWithoutDevicesOrHubIsAnError() {
        Assumptions.assumeTrue(UsbCaptureService.helper() != null, "USBPcap not installed");
        LogSink.get().clear();
        UsbCaptureService service = new UsbCaptureService();
        service.start();
        assertEquals(ModuleStatus.ERROR, service.status());
        assertTrue(TestSupport.dumpLog().contains("Pick a root hub first"));
        service.setHub("\\\\.\\USBPcap1");
        service.start();
        assertTrue(TestSupport.dumpLog().contains("Tick at least one device"));
    }

    @Test
    void listsHubsAndDevicesWhenInstalled() throws Exception {
        Assumptions.assumeTrue(UsbCaptureService.helper() != null, "USBPcap not installed");
        List<Hub> hubs = UsbCaptureService.hubs();
        Assumptions.assumeFalse(hubs.isEmpty(), "driver installed but no root hubs (restart pending?)");
        assertTrue(hubs.get(0).name().startsWith("\\\\.\\USBPcap"), hubs.get(0).name());
        List<UsbDevice> devices = UsbCaptureService.devices(hubs.get(0).name());
        for (UsbDevice d : devices) {
            assertTrue(d.address().chars().allMatch(Character::isDigit), "top-level addresses are numeric: " + d.address());
        }
    }

    /**
     * Real capture through the helper. Opt-in with LINKSCOPE_USB_LIVE=1 because the helper
     * spawns an elevated worker, which may raise a UAC prompt on the desktop running the tests.
     */
    @Test
    void liveCaptureWhenOptedIn() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("LINKSCOPE_USB_LIVE")), "LINKSCOPE_USB_LIVE not set");
        Assumptions.assumeTrue(UsbCaptureService.helper() != null, "USBPcap not installed");
        List<Hub> hubs = UsbCaptureService.hubs();
        Assumptions.assumeFalse(hubs.isEmpty(), "no root hubs");
        LogSink.get().clear();
        UsbCaptureService service = new UsbCaptureService();
        service.setHub(hubs.get(0).name());
        service.setAllDevices(true);
        service.start();
        awaitTrue("started or failed", Duration.ofSeconds(60),
                () -> service.status() == ModuleStatus.CONNECTED || service.status() == ModuleStatus.ERROR);
        assertEquals(ModuleStatus.CONNECTED, service.status(), TestSupport.dumpLog());
        awaitTrue("descriptors or traffic arrived", Duration.ofSeconds(20), () -> service.capturedProperty().get() > 0);
        List<PacketRow> rows = TestSupport.snapshot(service.rows());
        assertEquals("USB", rows.get(0).protocol());
        service.stop();
        awaitTrue("stopped", Duration.ofSeconds(15), () -> service.status() == ModuleStatus.DISCONNECTED);
        assertTrue(TestSupport.dumpLog().contains("USB capture stopped"), TestSupport.dumpLog());
    }
}
