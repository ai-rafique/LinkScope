package com.linkscope.modules.usb;

import com.linkscope.core.PayloadCodec;
import com.linkscope.core.protocol.ModbusDecoder;
import com.linkscope.modules.capture.PacketRow;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Turns the bulk and interrupt transfers of one USB device into two byte streams, host to
 * device (OUT) and device to host (IN), the way a serial terminal would show them. Transfers
 * in the same direction that follow each other closely merge into one segment, FTDI status
 * bytes can be stripped, and each segment can be shown as hex, text, or decoded as Modbus RTU.
 * Pure functions over {@link PacketRow}s so the view and the tests share one implementation.
 */
public final class UsbStream {

    public enum Direction { IN, OUT }

    public enum Format { HEX_ASCII, HEX, ASCII }

    public enum Decode { NONE, MODBUS_RTU }

    /**
     * @param stripFtdiStatus drop the two modem-status bytes FTDI chips prepend to every IN packet
     * @param ftdiPacketSize  the endpoint's max packet size (64 for full speed FT232R/FT231X, 512 for FT232H/FT2232H)
     * @param mergeGapMillis  transfers in the same direction closer than this merge into one segment
     * @param includeIn       keep device-to-host data
     * @param includeOut      keep host-to-device data
     */
    public record Options(boolean stripFtdiStatus, int ftdiPacketSize, long mergeGapMillis, boolean includeIn, boolean includeOut) {
        public static final Options DEFAULT = new Options(false, 64, 10, true, true);
    }

    /** One run of bytes in one direction on one endpoint. */
    public record Segment(Instant time, double relativeSeconds, Direction direction, int endpoint, byte[] data, int transfers) {
    }

    private UsbStream() {
    }

    // --- what is in the capture -----------------------------------------------------------

    /** Device addresses ({@code bus.device}) that carried bulk or interrupt data, in order of first appearance. */
    public static List<String> devices(List<PacketRow> rows) {
        Set<String> out = new LinkedHashSet<>();
        for (PacketRow r : rows) {
            if (isData(r)) {
                out.add(r.field("usb.addr"));
            }
        }
        return new ArrayList<>(out);
    }

    /** Endpoint numbers that carried data for a device, ascending. */
    public static List<Integer> endpoints(List<PacketRow> rows, String addr) {
        Set<Integer> out = new TreeSet<>();
        for (PacketRow r : rows) {
            if (isData(r) && addr.equals(r.field("usb.addr"))) {
                out.add(Integer.parseInt(r.field("usb.endpoint")));
            }
        }
        return new ArrayList<>(out);
    }

    /** {@code vid:pid} from a device descriptor seen for that address, or null. */
    public static String vendorProduct(List<PacketRow> rows, String addr) {
        for (PacketRow r : rows) {
            if (addr.equals(r.field("usb.addr")) && r.field("usb.vid") != null) {
                return r.field("usb.vid") + ":" + r.field("usb.pid");
            }
        }
        return null;
    }

    /** True when a descriptor in the capture identifies the device as an FTDI chip (vendor 0x0403). */
    public static boolean looksLikeFtdi(List<PacketRow> rows, String addr) {
        String vp = vendorProduct(rows, addr);
        return vp != null && vp.startsWith("0403:");
    }

    static boolean isData(PacketRow r) {
        if (!"USB".equals(r.protocol()) || r.payload() == null || r.payload().length == 0) {
            return false;
        }
        String type = r.field("usb.type");
        return ("bulk".equals(type) || "interrupt".equals(type)) && "success".equals(r.field("usb.status"));
    }

    // --- building the stream ---------------------------------------------------------------

    /** Segments for one device; {@code endpoint} null means every data endpoint. */
    public static List<Segment> build(List<PacketRow> rows, String addr, Integer endpoint, Options o) {
        List<Segment> out = new ArrayList<>();
        Instant lastTime = null;
        Direction lastDir = null;
        int lastEp = -1;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        Instant segStart = null;
        double segRel = 0;
        int segTransfers = 0;
        for (PacketRow r : rows) {
            if (!isData(r) || !addr.equals(r.field("usb.addr"))) {
                continue;
            }
            int ep = Integer.parseInt(r.field("usb.endpoint"));
            if (endpoint != null && ep != endpoint) {
                continue;
            }
            Direction dir = "in".equals(r.field("usb.dir")) ? Direction.IN : Direction.OUT;
            if ((dir == Direction.IN && !o.includeIn()) || (dir == Direction.OUT && !o.includeOut())) {
                continue;
            }
            byte[] data = r.payload();
            if (o.stripFtdiStatus() && dir == Direction.IN) {
                data = stripFtdi(data, o.ftdiPacketSize());
                if (data.length == 0) {
                    continue; // a bare status poll, nothing the serial side ever saw
                }
            }
            boolean merge = segStart != null && dir == lastDir && ep == lastEp
                    && r.time().toEpochMilli() - lastTime.toEpochMilli() <= o.mergeGapMillis();
            if (!merge) {
                if (segStart != null) {
                    out.add(new Segment(segStart, segRel, lastDir, lastEp, buf.toByteArray(), segTransfers));
                }
                buf.reset();
                segStart = r.time();
                segRel = r.relativeSeconds();
                segTransfers = 0;
            }
            buf.writeBytes(data);
            segTransfers++;
            lastTime = r.time();
            lastDir = dir;
            lastEp = ep;
        }
        if (segStart != null) {
            out.add(new Segment(segStart, segRel, lastDir, lastEp, buf.toByteArray(), segTransfers));
        }
        return out;
    }

    /** FTDI IN packets start with two modem-status bytes per USB packet; drop them. */
    static byte[] stripFtdi(byte[] data, int packetSize) {
        int size = Math.max(3, packetSize);
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
        for (int off = 0; off < data.length; off += size) {
            int start = off + 2;
            int end = Math.min(off + size, data.length);
            if (end > start) {
                out.write(data, start, end - start);
            }
        }
        return out.toByteArray();
    }

    /** All bytes of one direction, in order: what a terminal on that side would have seen. */
    public static byte[] concat(List<Segment> segments, Direction direction) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Segment s : segments) {
            if (s.direction() == direction) {
                out.writeBytes(s.data());
            }
        }
        return out.toByteArray();
    }

    public static String summary(List<Segment> segments) {
        long inBytes = 0;
        long outBytes = 0;
        int inTransfers = 0;
        int outTransfers = 0;
        for (Segment s : segments) {
            if (s.direction() == Direction.IN) {
                inBytes += s.data().length;
                inTransfers += s.transfers();
            } else {
                outBytes += s.data().length;
                outTransfers += s.transfers();
            }
        }
        return "OUT " + outBytes + " B in " + outTransfers + " transfer(s) · IN " + inBytes + " B in " + inTransfers
                + " transfer(s) · " + segments.size() + " segment(s)";
    }

    // --- rendering -----------------------------------------------------------------------

    private static final int HEX_PER_LINE = 32;

    /** One block per segment: a header line with time, direction, endpoint and size, then the bytes. */
    public static String render(List<Segment> segments, Format format, Decode decode, int maxSegments) {
        StringBuilder sb = new StringBuilder();
        int from = Math.max(0, segments.size() - maxSegments);
        if (from > 0) {
            sb.append("… ").append(from).append(" earlier segment(s) not shown\n\n");
        }
        for (int i = from; i < segments.size(); i++) {
            Segment s = segments.get(i);
            sb.append(String.format(Locale.ROOT, "+%011.6f  %-3s  ep%-2d  %5d B%s%n", s.relativeSeconds(),
                    s.direction() == Direction.OUT ? "OUT" : "IN", s.endpoint(), s.data().length,
                    s.transfers() > 1 ? "  (" + s.transfers() + " transfers)" : ""));
            appendBody(sb, s.data(), format);
            if (decode == Decode.MODBUS_RTU) {
                ModbusDecoder.decodeRtu(s.data()).ifPresentOrElse(
                        f -> sb.append("    ⇒ Modbus RTU: ").append(f.summary()).append('\n'),
                        () -> {
                            if (s.data().length >= 4) {
                                sb.append("    ⇒ not a single valid Modbus RTU frame (CRC or length)\n");
                            }
                        });
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static void appendBody(StringBuilder sb, byte[] data, Format format) {
        switch (format) {
            case ASCII -> sb.append("    ").append(PayloadCodec.toAscii(data)).append('\n');
            case HEX -> {
                for (int off = 0; off < data.length; off += HEX_PER_LINE) {
                    int end = Math.min(off + HEX_PER_LINE, data.length);
                    sb.append("    ").append(hex(data, off, end)).append('\n');
                }
            }
            case HEX_ASCII -> {
                for (int off = 0; off < data.length; off += HEX_PER_LINE) {
                    int end = Math.min(off + HEX_PER_LINE, data.length);
                    String h = hex(data, off, end);
                    sb.append("    ").append(h);
                    sb.append(" ".repeat(Math.max(1, HEX_PER_LINE * 3 - h.length() + 2)));
                    sb.append('|').append(printable(data, off, end)).append("|\n");
                }
            }
            default -> throw new IllegalArgumentException("format " + format);
        }
    }

    private static String hex(byte[] data, int from, int to) {
        StringBuilder sb = new StringBuilder((to - from) * 3);
        for (int i = from; i < to; i++) {
            if (i > from) {
                sb.append(' ');
            }
            sb.append(String.format("%02x", data[i] & 0xFF));
        }
        return sb.toString();
    }

    private static String printable(byte[] data, int from, int to) {
        StringBuilder sb = new StringBuilder(to - from);
        for (int i = from; i < to; i++) {
            int c = data[i] & 0xFF;
            sb.append(c >= 0x20 && c < 0x7F ? (char) c : '.');
        }
        return sb.toString();
    }
}
