package com.linkscope.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Single JSON preset file shared by every module:
 * <pre>{ "version": 1, "presets": [ { "name", "module", "fields": {...} } ] }</pre>
 * Placeholders are resolved via {@link EnvResolver} when a preset is applied, never when saved.
 */
public final class PresetStore {
    public static final int FORMAT_VERSION = 1;
    private static final String LOG_TAG = "PRESETS";

    /** On-disk shape. */
    public record PresetFile(int version, List<Preset> presets) {
    }

    private final Path path;
    private final EnvResolver resolver;
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final ObservableList<Preset> presets = FXCollections.observableArrayList();
    private final Set<String> warned = new HashSet<>();

    public PresetStore(Path path, EnvResolver resolver) {
        this.path = path;
        this.resolver = resolver;
    }

    /** {@code ~/.linkscope/presets.json} with the default resolver. */
    public static PresetStore defaultStore() {
        Path home = Path.of(System.getProperty("user.home"), ".linkscope", "presets.json");
        return new PresetStore(home, EnvResolver.load());
    }

    public Path path() {
        return path;
    }

    /** Live list of all presets across modules (FX-observable, mutated on the calling thread). */
    public ObservableList<Preset> presets() {
        return presets;
    }

    public List<Preset> forModule(String module) {
        List<Preset> out = new ArrayList<>();
        for (Preset p : presets) {
            if (p.module().equals(module)) {
                out.add(p);
            }
        }
        return out;
    }

    public Preset find(String module, String name) {
        for (Preset p : presets) {
            if (p.module().equals(module) && p.name().equals(name)) {
                return p;
            }
        }
        return null;
    }

    /** Adds or replaces the preset with the same module+name. */
    public void put(Preset preset) {
        for (int i = 0; i < presets.size(); i++) {
            Preset existing = presets.get(i);
            if (existing.module().equals(preset.module()) && existing.name().equals(preset.name())) {
                presets.set(i, preset);
                return;
            }
        }
        presets.add(preset);
    }

    public boolean remove(String module, String name) {
        return presets.removeIf(p -> p.module().equals(module) && p.name().equals(name));
    }

    /** Reads the file if it exists; a missing file is an empty store, not an error. */
    public void load() throws IOException {
        presets.clear();
        if (!Files.exists(path)) {
            return;
        }
        PresetFile file = mapper.readValue(Files.readString(path), PresetFile.class);
        if (file.presets() != null) {
            presets.addAll(file.presets());
        }
    }

    public void save() throws IOException {
        Files.createDirectories(path.getParent());
        String json = mapper.writeValueAsString(new PresetFile(FORMAT_VERSION, new ArrayList<>(presets)));
        Files.writeString(path, json);
    }

    /**
     * Returns a copy of the preset with ${VAR} placeholders substituted. Unresolved
     * names are left literal and logged once per preset as {@code [UNSET: VAR]}.
     */
    public Preset resolve(Preset preset) {
        Map<String, String> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : preset.fields().entrySet()) {
            EnvResolver.Result r = resolver.resolve(e.getValue());
            resolved.put(e.getKey(), r.value());
            for (String name : r.unresolved()) {
                if (warned.add(preset.module() + "/" + preset.name() + "/" + name)) {
                    LogSink.get().error(LOG_TAG, "[UNSET: " + name + "] in preset '" + preset.name()
                            + "' field '" + e.getKey() + "' — add it to .env or the environment");
                }
            }
        }
        return preset.withFields(resolved);
    }
}
