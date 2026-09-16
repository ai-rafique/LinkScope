package com.linkscope.core.ui;

import java.util.function.Consumer;

/**
 * Lets any panel hand a payload to the Decoder tool without knowing about the shell.
 * The shell registers a handler that switches to the Decoder module and loads the bytes.
 */
public final class DecoderBridge {
    private static volatile Consumer<byte[]> handler = bytes -> { };

    private DecoderBridge() {
    }

    public static void setHandler(Consumer<byte[]> h) {
        handler = h == null ? bytes -> { } : h;
    }

    /** Opens the given bytes in the Decoder tool. Call on the FX thread. */
    public static void open(byte[] bytes) {
        if (bytes != null) {
            handler.accept(bytes);
        }
    }
}
