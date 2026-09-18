package com.linkscope.modules.capture;

import com.linkscope.TestSupport;
import com.linkscope.core.LogSink;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pcap4j.packet.Packet;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.linkscope.TestSupport.awaitTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * File round trip through the service. Needs the capture driver (Npcap / libpcap) for the
 * pcap writer and reader; skips cleanly when it is not installed.
 */
class CaptureServiceTest {

    private static boolean driverAvailable() {
        try {
            CaptureService.devices();
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    @Test
    void magicNumberCheck(@TempDir Path dir) throws Exception {
        Path pcap = dir.resolve("x.pcap");
        Files.write(pcap, new byte[] {(byte) 0xd4, (byte) 0xc3, (byte) 0xb2, (byte) 0xa1, 0, 0, 0, 0});
        assertTrue(CaptureService.fileLooksLikePcap(pcap));
        Path text = dir.resolve("x.txt");
        Files.writeString(text, "not a capture");
        assertFalse(CaptureService.fileLooksLikePcap(text));
    }

    @Test
    void devicesRankPhysicalAdaptersFirst() {
        CaptureService.Device bluetooth = new CaptureService.Device("\\Device\\NPF_{1}", "Bluetooth Device (Personal Area Network)",
                List.of("169.254.180.12"), false, true);
        CaptureService.Device ethernet = new CaptureService.Device("\\Device\\NPF_{2}", "Realtek PCIe GbE Family Controller",
                List.of("192.168.1.5", "fe80::1"), false, false);
        CaptureService.Device hyperv = new CaptureService.Device("\\Device\\NPF_{3}", "Hyper-V Virtual Ethernet Adapter",
                List.of("172.20.0.1"), false, true);
        CaptureService.Device loop = new CaptureService.Device("\\Device\\NPF_Loopback", "Adapter for loopback traffic capture",
                List.of("127.0.0.1"), true, true);
        CaptureService.Device unplugged = new CaptureService.Device("\\Device\\NPF_{4}", "Intel Wi-Fi 6", List.of(), false, false);
        List<CaptureService.Device> sorted = new java.util.ArrayList<>(List.of(bluetooth, loop, hyperv, unplugged, ethernet));
        sorted.sort(java.util.Comparator.comparingInt(CaptureService.Device::rank));
        assertEquals(List.of(ethernet, unplugged, hyperv, bluetooth, loop), sorted);
        assertTrue(ethernet.hasRoutableIpv4());
        assertFalse(bluetooth.hasRoutableIpv4(), "link-local is not routable");
        assertTrue(hyperv.label().endsWith("[virtual]"), hyperv.label());
        assertFalse(loop.label().contains("[virtual]"), "loopback is obvious enough");
    }

    @Test
    void savesAndReopensPackets(@TempDir Path dir) throws Exception {
        Assumptions.assumeTrue(driverAvailable(), "capture driver not installed");
        LogSink.get().clear();
        CaptureService service = new CaptureService();
        Packet a = PacketDissectorTest.udpPacket("10.0.0.7", "239.192.10.4", 40000, 16004, "one".getBytes());
        Packet b = PacketDissectorTest.tcpSyn("192.168.1.5", "192.168.1.10", 51000, 1883);
        Packet c = PacketDissectorTest.arpRequest("192.168.1.1", "192.168.1.5");
        Instant t0 = Instant.parse("2026-09-18T10:00:00Z");
        List<PacketRow> rows = List.of(
                PacketDissector.dissect(a, 1, t0, 0),
                PacketDissector.dissect(b, 2, t0.plusMillis(250), 0.25),
                PacketDissector.dissect(c, 3, t0.plusMillis(500), 0.5));

        Path file = dir.resolve("roundtrip.pcap");
        AtomicBoolean saved = new AtomicBoolean();
        service.saveFile(file, rows, () -> saved.set(true));
        awaitTrue("saved", Duration.ofSeconds(10), saved::get);
        assertTrue(CaptureService.fileLooksLikePcap(file));

        service.openFile(file);
        awaitTrue("reopened", Duration.ofSeconds(10), () -> TestSupport.snapshot(service.rows()).size() == 3);
        List<PacketRow> back = TestSupport.snapshot(service.rows());
        assertEquals("239.192.10.4", back.get(0).dst());
        assertEquals(16004, back.get(0).dstPort());
        assertEquals("MQTT", back.get(1).app());
        assertEquals("ARP", back.get(2).protocol());
        assertEquals(t0, back.get(0).time());
        assertTrue(back.get(2).relativeSeconds() >= 0.49 && back.get(2).relativeSeconds() <= 0.51, "timestamps preserved");
        assertEquals(3, service.capturedProperty().get());
    }

    @Test
    void liveCaptureOnLoopbackIfAvailable() {
        Assumptions.assumeTrue(driverAvailable(), "capture driver not installed");
        Assumptions.assumeFalse(TestSupport.integrationSkipped(), "SKIP_INTEGRATION set");
        CaptureService.Device loop = CaptureService.devices().stream().filter(CaptureService.Device::loopback).findFirst().orElse(null);
        Assumptions.assumeTrue(loop != null, "no loopback capture device");
        LogSink.get().clear();
        CaptureService service = new CaptureService();
        service.setDevice(loop.name());
        service.setCaptureFilter("udp port 47777");
        service.start();
        awaitTrue("capturing or failed", Duration.ofSeconds(10),
                () -> service.status() == com.linkscope.core.ModuleStatus.CONNECTED || service.status() == com.linkscope.core.ModuleStatus.ERROR);
        Assumptions.assumeTrue(service.status() == com.linkscope.core.ModuleStatus.CONNECTED, "loopback capture not permitted:\n" + TestSupport.dumpLog());
        try (java.net.DatagramSocket s = new java.net.DatagramSocket()) {
            byte[] data = "loopback probe".getBytes();
            for (int i = 0; i < 5; i++) {
                s.send(new java.net.DatagramPacket(data, data.length, java.net.InetAddress.getByName("127.0.0.1"), 47777));
                Thread.sleep(50);
            }
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        awaitTrue("probe seen", Duration.ofSeconds(10), () -> TestSupport.snapshot(service.rows()).stream()
                .anyMatch(r -> r.dstPort() != null && r.dstPort() == 47777));
        service.stop();
        awaitTrue("stopped", Duration.ofSeconds(10), () -> service.status() == com.linkscope.core.ModuleStatus.DISCONNECTED);
    }
}
