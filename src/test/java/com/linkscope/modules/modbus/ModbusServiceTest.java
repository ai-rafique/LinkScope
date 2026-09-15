package com.linkscope.modules.modbus;

import com.linkscope.core.LogSink;
import com.linkscope.core.ModuleStatus;
import com.linkscope.core.protocol.Crc16;
import com.linkscope.core.protocol.ModbusEncoder;
import com.linkscope.modules.modbus.ModbusService.Exchange;
import com.linkscope.modules.modbus.ModbusService.Mode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static com.linkscope.TestSupport.awaitTrue;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModbusServiceTest {

    /** In-memory link: bytes queued here arrive at the service; writes are captured. */
    static final class FakeLink implements ModbusLink {
        final BlockingQueue<byte[]> incoming = new LinkedBlockingQueue<>();
        final List<byte[]> written = new CopyOnWriteArrayList<>();
        volatile boolean closed;

        @Override
        public void open() {
        }

        @Override
        public int read(byte[] buf, int timeoutMs) throws IOException {
            if (closed) {
                return -1;
            }
            try {
                byte[] chunk = incoming.poll(timeoutMs, TimeUnit.MILLISECONDS);
                if (chunk == null) {
                    return closed ? -1 : 0;
                }
                System.arraycopy(chunk, 0, buf, 0, chunk.length);
                return chunk.length;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return -1;
            }
        }

        @Override
        public void write(byte[] data) {
            written.add(data);
        }

        @Override
        public String describe() {
            return "fake";
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static byte[] b(int... v) {
        byte[] out = new byte[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = (byte) v[i];
        }
        return out;
    }

    private final ModbusService service = new ModbusService();
    private final FakeLink link = new FakeLink();

    @BeforeEach
    void setUp() {
        LogSink.get().clear();
        service.setLinkFactory(() -> link);
        service.setFrameGapMs(30);
    }

    @AfterEach
    void tearDown() {
        service.stop();
    }

    private void open() {
        service.start();
        awaitTrue("open", () -> service.status() == ModuleStatus.CONNECTED);
    }

    private static Exchange rx(ModbusService s, int index) {
        List<Exchange> rx = s.exchanges().stream().filter(x -> x.direction().equals("RX")).toList();
        return rx.get(index);
    }

    private static long rxCount(ModbusService s) {
        return s.exchanges().stream().filter(x -> x.direction().equals("RX")).count();
    }

    @Test
    void rtuResponseSplitAcrossReadsIsReassembled() {
        open();
        byte[] response = Crc16.STANDARD_MODBUS.append(b(0x01, 0x03, 0x04, 0x00, 0x0A, 0x01, 0x02));
        link.incoming.add(Arrays.copyOfRange(response, 0, 3));
        link.incoming.add(Arrays.copyOfRange(response, 3, response.length));
        awaitTrue("one RX frame", () -> rxCount(service) == 1);
        Exchange x = rx(service, 0);
        assertTrue(x.crcOk());
        assertEquals("RTU unit 1 · FC 03 Read Holding Registers · response 2 register(s) [10, 258]", x.summary());
        assertArrayEquals(response, x.raw());
    }

    @Test
    void twoRtuFramesInOneReadAreSplit() {
        open();
        byte[] a = ModbusEncoder.rtuFrame(1, ModbusEncoder.readRequest(3, 0, 10), Crc16.STANDARD_MODBUS);
        byte[] c = ModbusEncoder.rtuFrame(2, ModbusEncoder.writeSingleRegister(1, 3), Crc16.STANDARD_MODBUS);
        byte[] both = new byte[a.length + c.length];
        System.arraycopy(a, 0, both, 0, a.length);
        System.arraycopy(c, 0, both, a.length, c.length);
        link.incoming.add(both);
        awaitTrue("two RX frames", () -> rxCount(service) == 2);
        assertEquals(1, rx(service, 0).frame().orElseThrow().unitId());
        assertEquals(2, rx(service, 1).frame().orElseThrow().unitId());
    }

    @Test
    void badCrcIsFlaggedWithExpectedValue() {
        open();
        link.incoming.add(b(0x01, 0x03, 0x00, 0x00, 0x00, 0x0A, 0xC5, 0xCE));
        awaitTrue("bad frame reported", () -> rxCount(service) == 1);
        Exchange x = rx(service, 0);
        assertFalse(x.crcOk());
        assertTrue(x.note().startsWith("CRC BAD (calculated cdc5, received cec5)"), x.note());
        assertTrue(x.note().contains("looks like RTU unit 1"), x.note());
        assertTrue(LogSink.get().entries().stream().anyMatch(e -> e.kind() == com.linkscope.core.LogEntry.Kind.ERROR));
    }

    @Test
    void sendPduAppendsCrcAndRecordsTx() {
        open();
        service.sendPdu(1, ModbusEncoder.readRequest(3, 0, 10));
        awaitTrue("written", () -> !link.written.isEmpty());
        assertArrayEquals(b(0x01, 0x03, 0x00, 0x00, 0x00, 0x0A, 0xC5, 0xCD), link.written.get(0));
        awaitTrue("TX recorded", () -> service.exchanges().stream().anyMatch(x -> x.direction().equals("TX")));
        assertTrue(service.exchanges().get(0).summary().contains("request addr 0 qty 10"));
    }

    @Test
    void customCrcIsUsedBothWays() {
        Crc16 ccitt = Crc16.of(Crc16.CCITT_FALSE, Crc16.ByteOrder.HIGH_FIRST);
        service.setCrc(ccitt);
        open();
        service.sendPdu(5, ModbusEncoder.readRequest(4, 2, 1));
        awaitTrue("written", () -> !link.written.isEmpty());
        byte[] out = link.written.get(0);
        assertTrue(ccitt.verify(out));
        assertFalse(Crc16.STANDARD_MODBUS.verify(out));

        link.incoming.add(ccitt.append(b(0x05, 0x04, 0x02, 0x12, 0x34)));
        awaitTrue("RX decoded with custom CRC", () -> rxCount(service) == 1 && rx(service, 0).crcOk());
        assertTrue(rx(service, 0).summary().contains("response 1 register(s) [4660]"), rx(service, 0).summary());
    }

    @Test
    void tcpModeAgainstALoopbackServer() throws IOException {
        ModbusService tcp = new ModbusService();
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            Thread responder = new Thread(() -> {
                try (Socket s = server.accept()) {
                    InputStream in = s.getInputStream();
                    OutputStream out = s.getOutputStream();
                    byte[] req = in.readNBytes(12);
                    // echo transaction id, reply with two registers
                    byte[] resp = b(req[0], req[1], 0, 0, 0, 7, req[6], 0x03, 0x04, 0x00, 0x2A, 0x00, 0x2B);
                    out.write(resp);
                    out.flush();
                    Thread.sleep(300);
                } catch (IOException | InterruptedException ignored) {
                    // test ends
                }
            });
            responder.setDaemon(true);
            responder.start();

            tcp.setMode(Mode.TCP);
            tcp.setTcp("127.0.0.1", server.getLocalPort());
            tcp.start();
            awaitTrue("tcp open", () -> tcp.status() == ModuleStatus.CONNECTED);
            tcp.sendPdu(1, ModbusEncoder.readRequest(3, 0, 2));
            awaitTrue("tcp response", () -> rxCount(tcp) == 1);
            Exchange x = rx(tcp, 0);
            assertTrue(x.crcOk());
            assertEquals("TCP tid 0 unit 1 · FC 03 Read Holding Registers · response 2 register(s) [42, 43]", x.summary());
        } finally {
            tcp.stop();
        }
    }
}
