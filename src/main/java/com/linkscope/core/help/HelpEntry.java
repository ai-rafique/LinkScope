package com.linkscope.core.help;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One destination in the app: a tab, a sub-view or a control worth finding by name.
 * Loaded from {@code help/catalogue.json}. {@code module} is the sidebar module id, or empty
 * for shell-level destinations; {@code action} is what to do after selecting the module
 * ({@code view:stream}, {@code focus:needed}, {@code shell:log}, ...) or empty to just open it.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HelpEntry(String id, String module, String action, String path, String title, String synonyms, String about) {

    public HelpEntry {
        module = module == null ? "" : module;
        action = action == null ? "" : action;
        synonyms = synonyms == null ? "" : synonyms;
        about = about == null ? "" : about;
    }

    /** True for the entry that represents a whole tab rather than something inside it. */
    public boolean isModuleRoot() {
        return !module.isEmpty() && action.isEmpty();
    }
}
