package com.linkscope.modules.hid;

import com.linkscope.TestSupport;
import com.linkscope.core.LogSink;
import com.linkscope.core.ModuleStatus;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static com.linkscope.TestSupport.awaitTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opens real devices. Opt-in with LINKSCOPE_HID_LIVE=1: it depends on what is plugged into
 * the machine and prints what it found. Devices Windows keeps exclusive are skipped.
 */
class HidLiveTest {

    @Test
    void opensReadsAndClosesEveryOpenableDevice() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("LINKSCOPE_HID_LIVE")), "LINKSCOPE_HID_LIVE not set");
        Assumptions.assumeTrue(HidService.available(), "no HID support here");
        List<HidDevice> devices = HidService.devices();
        Assumptions.assumeTrue(!devices.isEmpty(), "no HID devices");
        int opened = 0;
        for (HidDevice d : devices) {
            if (d.inputLength() == 0 || d.kind().equals("Mouse") || d.kind().equals("Keyboard")) {
                continue;
            }
            LogSink.get().clear();
            HidService s = new HidService();
            s.setDevice(d);
            s.setLogReports(true);
            s.start();
            awaitTrue("open or fail: " + d.label(), Duration.ofSeconds(10),
                    () -> s.status() == ModuleStatus.CONNECTED || s.status() == ModuleStatus.ERROR);
            System.out.println("=== " + d.label() + " -> " + s.status());
            if (s.status() == ModuleStatus.CONNECTED) {
                opened++;
                HidLayout l = s.layoutProperty().get();
                System.out.println("    buttons " + l.buttons().stream().map(HidLayout.Button::name).toList());
                System.out.println("    values  " + l.values().stream().map(v -> v.name() + "[" + v.min() + ".." + v.max() + "]").toList());
                Thread.sleep(1500);
                s.stop();
                awaitTrue("closed: " + d.label(), Duration.ofSeconds(10), () -> s.status() == ModuleStatus.DISCONNECTED);
                System.out.println("    reports " + s.reportCountProperty().get());
            }
            System.out.println(TestSupport.dumpLog().lines().limit(6).reduce("", (a, b) -> a + "    " + b + "\n"));
            if (s.status() == ModuleStatus.ERROR) {
                assertTrue(TestSupport.dumpLog().contains("[ERROR]"), "an error status comes with a log line");
            } else {
                assertEquals(ModuleStatus.DISCONNECTED, s.status());
            }
        }
        System.out.println("opened " + opened + " of " + devices.size() + " device(s)");
    }
}
