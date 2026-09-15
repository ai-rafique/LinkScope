package com.linkscope.core;

import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * One line in the shared log. {@code payload} is set for TX/RX entries; {@code note}
 * carries the peer address for TX/RX and the message text for INFO/ERROR.
 */
public record LogEntry(LocalTime time, String module, Kind kind, byte[] payload, String note) {

    public enum Kind { TX, RX, INFO, ERROR }

    /** How the leading timestamp is rendered. */
    public enum TimeMode { NONE, ABSOLUTE, DELTA }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    public static final String CSV_HEADER = "time,module,kind,note,bytes,hex,ascii";

    public static LogEntry now(String module, Kind kind, byte[] payload, String note) {
        return new LogEntry(LocalTime.now(), module, kind, payload, note);
    }

    public boolean hasPayload() {
        return payload != null;
    }

    /** Renders as {@code [HH:mm:ss.SSS] [MODULE] [TX] peer (n B): payload}. */
    public String format(boolean hex, boolean timestamps) {
        return format(hex, timestamps ? TimeMode.ABSOLUTE : TimeMode.NONE, null);
    }

    /**
     * Renders with the chosen timestamp mode. In DELTA mode the prefix is the time since
     * {@code previous} (the line shown above this one), e.g. {@code [+  0.012s]}.
     */
    public String format(boolean hex, TimeMode mode, LocalTime previous) {
        StringBuilder sb = new StringBuilder(96);
        switch (mode) {
            case ABSOLUTE -> sb.append('[').append(TIME.format(time)).append("] ");
            case DELTA -> sb.append(String.format("[+%7.3fs] ", deltaSeconds(previous)));
            default -> { }
        }
        sb.append('[').append(module).append("] [").append(kind).append("] ");
        if (payload != null) {
            if (note != null && !note.isEmpty()) {
                sb.append(note).append(' ');
            }
            sb.append('(').append(payload.length).append(" B): ");
            sb.append(PayloadCodec.format(payload, hex));
        } else if (note != null) {
            sb.append(note);
        }
        return sb.toString();
    }

    /** Seconds since {@code previous}; 0 for the first line or if the clock wrapped past midnight. */
    public double deltaSeconds(LocalTime previous) {
        if (previous == null) {
            return 0;
        }
        Duration d = Duration.between(previous, time);
        return d.isNegative() ? 0 : d.toNanos() / 1_000_000_000.0;
    }

    /** One CSV row matching {@link #CSV_HEADER}; every text field is quoted. */
    public String toCsvRow() {
        return String.join(",",
                csv(TIME.format(time)),
                csv(module),
                csv(kind.name()),
                csv(note == null ? "" : note),
                payload == null ? "" : String.valueOf(payload.length),
                csv(payload == null ? "" : PayloadCodec.toHex(payload)),
                csv(payload == null ? "" : PayloadCodec.toAscii(payload)));
    }

    private static String csv(String s) {
        return '"' + s.replace("\"", "\"\"") + '"';
    }
}
