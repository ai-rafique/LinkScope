package com.linkscope.core;

import java.util.Map;

/** A saved connection profile for one module. Field values may contain ${VAR} placeholders. */
public record Preset(String name, String module, Map<String, String> fields) {

    public Preset {
        fields = fields == null ? Map.of() : Map.copyOf(fields);
    }

    public Preset withFields(Map<String, String> newFields) {
        return new Preset(name, module, newFields);
    }
}
