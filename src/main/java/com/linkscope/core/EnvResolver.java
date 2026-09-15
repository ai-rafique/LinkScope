package com.linkscope.core;

import io.github.cdimascio.dotenv.Dotenv;
import io.github.cdimascio.dotenv.DotenvEntry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Substitutes {@code ${VAR_NAME}} placeholders from a project-local .env file first,
 * then the process environment. Unresolved placeholders are left untouched and reported.
 */
public final class EnvResolver {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");

    private final Map<String, String> dotenv;
    private final Map<String, String> processEnv;

    public EnvResolver(Map<String, String> dotenv, Map<String, String> processEnv) {
        this.dotenv = Map.copyOf(dotenv);
        this.processEnv = Map.copyOf(processEnv);
    }

    /** Loads {@code .env} from the working directory (if present) plus the process environment. */
    public static EnvResolver load() {
        Map<String, String> fromFile = new HashMap<>();
        try {
            Dotenv env = Dotenv.configure().ignoreIfMissing().ignoreIfMalformed().load();
            for (DotenvEntry e : env.entries(Dotenv.Filter.DECLARED_IN_ENV_FILE)) {
                fromFile.put(e.getKey(), e.getValue());
            }
        } catch (RuntimeException e) {
            LogSink.get().error("ENV", "Could not read .env", e);
        }
        return new EnvResolver(fromFile, System.getenv());
    }

    public Optional<String> lookup(String name) {
        String v = dotenv.get(name);
        if (v == null) {
            v = processEnv.get(name);
        }
        return Optional.ofNullable(v);
    }

    /** Result of resolving one string: the substituted value and the names that had no value. */
    public record Result(String value, List<String> unresolved) {
    }

    public Result resolve(String input) {
        if (input == null || input.indexOf("${") < 0) {
            return new Result(input, List.of());
        }
        List<String> unresolved = new ArrayList<>();
        Matcher m = PLACEHOLDER.matcher(input);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            Optional<String> value = lookup(name);
            if (value.isPresent()) {
                m.appendReplacement(sb, Matcher.quoteReplacement(value.get()));
            } else {
                unresolved.add(name);
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
            }
        }
        m.appendTail(sb);
        return new Result(sb.toString(), List.copyOf(unresolved));
    }
}
