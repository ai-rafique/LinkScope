package com.linkscope.modules.capture;

import com.linkscope.core.ui.CompletionPopup;
import javafx.scene.control.TextField;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Wires {@link FilterCompletion} to a display-filter field, feeding it the addresses, ports,
 * applications and USB devices seen in a row list. The context is rebuilt only when the row
 * count changes and looks at the newest rows, so typing stays instant on big captures.
 */
public final class FilterContexts {
    private static final int SAMPLE = 5000;
    private static final String STYLESHEET = Objects.requireNonNull(
            FilterContexts.class.getResource("/com/linkscope/css/linkscope.css")).toExternalForm();

    private FilterContexts() {
    }

    public static CompletionPopup<FilterCompletion.Suggestion> attach(TextField field, List<PacketRow> rows) {
        LiveContext ctx = new LiveContext(rows);
        return new CompletionPopup<>(field,
                (text, caret) -> FilterCompletion.suggest(text, caret, () -> ctx.refresh()),
                new CompletionPopup.Renderer<>() {
                    public String label(FilterCompletion.Suggestion s) {
                        return s.label();
                    }

                    public String detail(FilterCompletion.Suggestion s) {
                        return s.detail();
                    }
                },
                FilterCompletion::apply,
                STYLESHEET);
    }

    /** Distinct values from the newest rows, cached per row count. */
    static final class LiveContext implements FilterCompletion.Context {
        private final List<PacketRow> rows;
        private int builtFor = -1;
        private final Set<String> addresses = new TreeSet<>();
        private final Set<Integer> ports = new TreeSet<>();
        private final Set<String> apps = new TreeSet<>();
        private final Set<String> usbAddresses = new TreeSet<>();
        private final Set<String> protocols = new TreeSet<>();

        LiveContext(List<PacketRow> rows) {
            this.rows = rows;
        }

        FilterCompletion.Context refresh() {
            int size = rows.size();
            if (size == builtFor) {
                return this;
            }
            builtFor = size;
            addresses.clear();
            ports.clear();
            apps.clear();
            usbAddresses.clear();
            protocols.clear();
            int from = Math.max(0, size - SAMPLE);
            for (int i = from; i < size; i++) {
                PacketRow r = rows.get(i);
                if ("USB".equals(r.protocol())) {
                    String a = r.field("usb.addr");
                    if (a != null) {
                        usbAddresses.add(a);
                    }
                    protocols.add("USB");
                    continue;
                }
                if (!r.src().isEmpty()) {
                    addresses.add(r.src());
                }
                if (!r.dst().isEmpty()) {
                    addresses.add(r.dst());
                }
                if (r.srcPort() != null) {
                    ports.add(r.srcPort());
                }
                if (r.dstPort() != null) {
                    ports.add(r.dstPort());
                }
                if (!r.app().isEmpty()) {
                    apps.add(r.app());
                }
                if (!r.protocol().isEmpty() && !r.protocol().equals("?")) {
                    protocols.add(r.protocol());
                }
            }
            return this;
        }

        public Set<String> addresses() {
            return addresses;
        }

        public Set<Integer> ports() {
            return ports;
        }

        public Set<String> apps() {
            return apps;
        }

        public Set<String> usbAddresses() {
            return usbAddresses;
        }

        public Set<String> protocols() {
            return protocols;
        }
    }
}
