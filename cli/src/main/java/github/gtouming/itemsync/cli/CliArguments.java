package github.gtouming.itemsync.cli;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CliArguments {

    private static final Set<String> FLAGS = Set.of("json", "yes");

    private final String command;
    private final Map<String, List<String>> options;
    private final List<String> positionals;

    private CliArguments(String command, Map<String, List<String>> options,
                         List<String> positionals) {
        this.command = command;
        this.options = options;
        this.positionals = positionals;
    }

    public static CliArguments parse(String[] args) {
        if (args == null || args.length == 0) {
            throw new IllegalArgumentException("command is required");
        }
        String command = args[0];
        Map<String, List<String>> options = new LinkedHashMap<>();
        List<String> positionals = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            String argument = args[i];
            if (!argument.startsWith("--") || argument.length() <= 2) {
                positionals.add(argument);
                continue;
            }
            String key = argument.substring(2);
            String value;
            int equals = key.indexOf('=');
            if (equals >= 0) {
                value = key.substring(equals + 1);
                key = key.substring(0, equals);
            } else if (FLAGS.contains(key)) {
                value = "true";
            } else {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("missing value for --" + key);
                }
                value = args[++i];
            }
            if (key.isEmpty()) {
                throw new IllegalArgumentException("empty option name");
            }
            options.computeIfAbsent(key, ignored -> new ArrayList<>()).add(value);
        }

        Map<String, List<String>> immutable = new LinkedHashMap<>();
        options.forEach((key, values) -> immutable.put(key, List.copyOf(values)));
        return new CliArguments(command, Map.copyOf(immutable), List.copyOf(positionals));
    }

    public String command() {
        return command;
    }

    public List<String> positionals() {
        return positionals;
    }

    public boolean flag(String key) {
        return Boolean.parseBoolean(optional(key, "false"));
    }

    public String required(String key) {
        String value = optional(key, null);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing required option: --" + key);
        }
        return value;
    }

    public String optional(String key, String defaultValue) {
        List<String> values = options.get(key);
        if (values == null || values.isEmpty()) {
            return defaultValue;
        }
        return values.get(values.size() - 1);
    }

    public List<String> all(String key) {
        return options.getOrDefault(key, List.of());
    }
}
