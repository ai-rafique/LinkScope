package com.linkscope.core;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Classic pcap (libpcap) file format in pure Java: a writer, a whole-file reader and a
 * {@link Tail} that follows a file another process is still appending to. No capture driver
 * involved, so it works for link types the driver never touches (USBPcap's 249, for one).
 */
public final class PcapFiles {
    public static final int HEADER_LENGTH = 24;
    public static final int RECORD_HEADER_LENGTH = 16;
    private static final int MAGIC_MICROS = 0xa1b2c3d4;
    private static final int MAGIC_NANOS = 0xa1b23c4d;
    private static final int MAX_RECORD = 256 * 1024 * 1024;

    /** Global header: link type, byte order and timestamp resolution. */
    public record Header(int linkType, ByteOrder order, boolean nanos, int snaplen) {
    }

    /** One captured record. {@code originalLength} may exceed {@code data.length} when the capture was truncated. */
    public record Record(Instant time, byte[] data, int originalLength) {
    }

    private PcapFiles() {
    }

    /** Parses the 24-byte global header; throws when the magic number is not pcap's. */
    public static Header parseHeader(byte[] head) throws IOException {
        if (head.length < HEADER_LENGTH) {
            throw new IOException("pcap header truncated (" + head.length + " bytes)");
        }
        ByteBuffer b = ByteBuffer.wrap(head);
        int magicBig = b.order(ByteOrder.BIG_ENDIAN).getInt(0);
        int magicLittle = b.order(ByteOrder.LITTLE_ENDIAN).getInt(0);
        ByteOrder order;
        boolean nanos;
        if (magicBig == MAGIC_MICROS || magicBig == MAGIC_NANOS) {
            order = ByteOrder.BIG_ENDIAN;
            nanos = magicBig == MAGIC_NANOS;
        } else if (magicLittle == MAGIC_MICROS || magicLittle == MAGIC_NANOS) {
            order = ByteOrder.LITTLE_ENDIAN;
            nanos = magicLittle == MAGIC_NANOS;
        } else {
            throw new IOException(String.format("Not a pcap file (magic 0x%08x); pcapng is not supported here", magicBig));
        }
        b.order(order);
        return new Header(b.getInt(20), order, nanos, b.getInt(16));
    }

    /** Reads a whole file. */
    public static List<Record> readAll(Path file) throws IOException {
        try (Tail t = new Tail(file)) {
            List<Record> out = new ArrayList<>(t.poll());
            if (t.header() == null) {
                throw new IOException("pcap header truncated");
            }
            return out;
        }
    }

    public static Header readHeader(Path file) throws IOException {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            ByteBuffer head = ByteBuffer.allocate(HEADER_LENGTH);
            readFully(ch, head, 0);
            return parseHeader(head.array());
        }
    }

    /** Writes a little-endian, microsecond-resolution pcap file. */
    public static void write(Path file, int linkType, List<Record> records) throws IOException {
        int size = HEADER_LENGTH;
        for (Record r : records) {
            size += RECORD_HEADER_LENGTH + r.data().length;
        }
        ByteBuffer out = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(MAGIC_MICROS).putShort((short) 2).putShort((short) 4).putInt(0).putInt(0).putInt(65535).putInt(linkType);
        for (Record r : records) {
            long micros = r.time().getEpochSecond() * 1_000_000L + r.time().getNano() / 1000;
            out.putInt((int) (micros / 1_000_000L)).putInt((int) (micros % 1_000_000L));
            out.putInt(r.data().length).putInt(Math.max(r.originalLength(), r.data().length));
            out.put(r.data());
        }
        Files.write(file, out.array());
    }

    private static void readFully(FileChannel ch, ByteBuffer buf, long position) throws IOException {
        long pos = position;
        while (buf.hasRemaining()) {
            int n = ch.read(buf, pos);
            if (n < 0) {
                throw new IOException("Unexpected end of file");
            }
            pos += n;
        }
        buf.flip();
    }

    /**
     * Reads pcap records from a live byte stream (a process's stdout, a pipe). Blocking:
     * {@link #next()} waits for a whole record and returns null at end of stream.
     */
    public static final class Stream implements Closeable {
        private final java.io.InputStream in;
        private Header header;

        public Stream(java.io.InputStream in) {
            this.in = in;
        }

        /** Null until the global header has been read. */
        public Header header() {
            return header;
        }

        /** Reads the global header if not yet read; null when the stream ends before it. */
        public Header readHeader() throws IOException {
            if (header == null) {
                byte[] head = readExactly(HEADER_LENGTH, true);
                if (head == null) {
                    return null;
                }
                header = parseHeader(head);
            }
            return header;
        }

        public Record next() throws IOException {
            if (readHeader() == null) {
                return null;
            }
            byte[] rec = readExactly(RECORD_HEADER_LENGTH, true);
            if (rec == null) {
                return null;
            }
            ByteBuffer b = ByteBuffer.wrap(rec).order(header.order());
            long seconds = b.getInt(0) & 0xFFFFFFFFL;
            long fraction = b.getInt(4) & 0xFFFFFFFFL;
            int included = b.getInt(8);
            int original = b.getInt(12);
            if (included < 0 || included > MAX_RECORD) {
                throw new IOException("Corrupt pcap record (length " + included + ")");
            }
            byte[] data = readExactly(included, false);
            if (data == null) {
                throw new IOException("Stream ended inside a record");
            }
            Instant time = header.nanos() ? Instant.ofEpochSecond(seconds, fraction) : Instant.ofEpochSecond(seconds, fraction * 1000);
            return new Record(time, data, original);
        }

        /** Null on a clean end of stream before any byte when {@code eofOk}; throws on a partial read otherwise. */
        private byte[] readExactly(int n, boolean eofOk) throws IOException {
            byte[] out = new byte[n];
            int off = 0;
            while (off < n) {
                int r = in.read(out, off, n - off);
                if (r < 0) {
                    if (off == 0) {
                        return null;
                    }
                    throw new IOException("Stream ended after " + off + " of " + n + " bytes" + (eofOk ? "" : " of record data"));
                }
                off += r;
            }
            return out;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }

    /**
     * Follows a growing pcap file. {@link #poll()} returns the records that are complete on
     * disk since the previous call and leaves a partially written trailing record for later.
     */
    public static final class Tail implements Closeable {
        private final FileChannel channel;
        private Header header;
        private long position;

        public Tail(Path file) throws IOException {
            this.channel = FileChannel.open(file, StandardOpenOption.READ);
        }

        /** Null until the first 24 bytes are on disk. */
        public Header header() {
            return header;
        }

        public long position() {
            return position;
        }

        public List<Record> poll() throws IOException {
            List<Record> out = new ArrayList<>();
            long size = channel.size();
            if (header == null) {
                if (size < HEADER_LENGTH) {
                    return out;
                }
                ByteBuffer head = ByteBuffer.allocate(HEADER_LENGTH);
                readFully(channel, head, 0);
                header = parseHeader(head.array());
                position = HEADER_LENGTH;
            }
            ByteBuffer rec = ByteBuffer.allocate(RECORD_HEADER_LENGTH).order(header.order());
            while (size - position >= RECORD_HEADER_LENGTH) {
                rec.clear();
                readFully(channel, rec, position);
                long seconds = rec.getInt(0) & 0xFFFFFFFFL;
                long fraction = rec.getInt(4) & 0xFFFFFFFFL;
                int included = rec.getInt(8);
                int original = rec.getInt(12);
                if (included < 0 || included > MAX_RECORD) {
                    throw new IOException("Corrupt pcap record at offset " + position + " (length " + included + ")");
                }
                if (size - position - RECORD_HEADER_LENGTH < included) {
                    break;
                }
                ByteBuffer data = ByteBuffer.allocate(included);
                readFully(channel, data, position + RECORD_HEADER_LENGTH);
                position += RECORD_HEADER_LENGTH + included;
                Instant time = header.nanos() ? Instant.ofEpochSecond(seconds, fraction) : Instant.ofEpochSecond(seconds, fraction * 1000);
                out.add(new Record(time, data.array(), original));
            }
            return out;
        }

        @Override
        public void close() throws IOException {
            channel.close();
        }
    }
}
