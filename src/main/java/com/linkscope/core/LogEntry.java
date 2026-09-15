package com.linkscope.core;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * One line in the shared log. {@code payload} is set for TX/RX entries; {@code note}
 * carries the peer address for TX/RX and the message text for INFO/ERROR.
 */
public record LogEntry(LocalTime time, String module, Kind kind, byte[] payload, String note) {

    public enum Kind { TX, RX, INFO, ERROR }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    public static LogEntry now(String module, Kind kind, byte[] payload, String note) {
        return new LogEntry(LocalTime.now(), module, kind, payload, note);
    }

    public boolean hasPayload() {
        return payload != null;
    }

    /** Renders as {@code [HH:mm:ss.SSS] [MODULE] [TX] peer (n B): payload}. */
    public String format(boolean hex, boolean timestamps) {
        StringBuilder sb = new StringBuilder(96);
        if (timestamps) {
            sb.append('[').append(TIME.format(time)).append("] ");
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
}
