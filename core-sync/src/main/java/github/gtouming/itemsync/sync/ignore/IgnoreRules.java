package github.gtouming.itemsync.sync.ignore;

import github.gtouming.itemsync.domain.compat.Compat;

import github.gtouming.itemsync.domain.identity.LogicalPath;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

public final class IgnoreRules {

    public static final String FILE_NAME = ".isignore";

    private static final IgnoreRules EMPTY = new IgnoreRules(Compat.list());

    private final List<Rule> rules;

    private IgnoreRules(List<Rule> rules) {
        this.rules = Compat.copyList(rules);
    }

    public static IgnoreRules empty() {
        return EMPTY;
    }

    public static IgnoreRules parse(List<String> lines) {
        Objects.requireNonNull(lines, "lines");
        List<Rule> parsed = new ArrayList<>();
        for (String line : lines) {
            Rule rule = Rule.parse(Objects.requireNonNull(line, "line"));
            if (rule != null) {
                parsed.add(rule);
            }
        }
        return parsed.isEmpty() ? EMPTY : new IgnoreRules(parsed);
    }

    public boolean isEmpty() {
        return rules.isEmpty();
    }

    public boolean isIgnored(LogicalPath path, boolean directory) {
        Objects.requireNonNull(path, "path");
        if (FILE_NAME.equals(path.value())) {
            return false;
        }
        boolean ignored = false;
        for (Rule rule : rules) {
            if (rule.matches(path, directory)) {
                ignored = !rule.negated;
            }
        }
        return ignored;
    }

    private static final class Rule {

        private final Pattern pattern;
        private final boolean negated;
        private final boolean directoryOnly;
        private final boolean anchored;

        private Rule(Pattern pattern, boolean negated, boolean directoryOnly, boolean anchored) {
            this.pattern = pattern;
            this.negated = negated;
            this.directoryOnly = directoryOnly;
            this.anchored = anchored;
        }

        static Rule parse(String rawLine) {
            String line = stripUnescapedTrailingSpaces(rawLine);
            if (line.isEmpty() || line.charAt(0) == '#') {
                return null;
            }

            boolean negated = false;
            if (line.startsWith("\\!") || line.startsWith("\\#")) {
                line = line.substring(1);
            } else if (line.charAt(0) == '!') {
                negated = true;
                line = line.substring(1);
                if (line.isEmpty()) {
                    return null;
                }
            }

            boolean directoryOnly = line.endsWith("/") && !line.endsWith("\\/");
            if (directoryOnly) {
                line = line.substring(0, line.length() - 1);
            }

            boolean anchored = false;
            if (line.startsWith("/")) {
                anchored = true;
                line = line.substring(1);
            } else if (line.contains("/")) {
                anchored = true;
            }

            if (line.isEmpty()) {
                return null;
            }
            return new Rule(Pattern.compile(toRegex(line)), negated, directoryOnly, anchored);
        }

        boolean matches(LogicalPath path, boolean directory) {
            List<String> segments = path.segments();
            if (!anchored) {
                int limit = directoryOnly && !directory ? segments.size() - 1 : segments.size();
                for (int i = 0; i < limit; i++) {
                    if (pattern.matcher(segments.get(i)).matches()) {
                        return true;
                    }
                }
                return false;
            }

            if ((!directoryOnly || directory) && pattern.matcher(path.value()).matches()) {
                return true;
            }
            StringBuilder ancestor = new StringBuilder();
            for (int i = 0; i < segments.size() - 1; i++) {
                if (i > 0) {
                    ancestor.append('/');
                }
                ancestor.append(segments.get(i));
                if (pattern.matcher(ancestor).matches()) {
                    return true;
                }
            }
            return false;
        }
    }

    private static String stripUnescapedTrailingSpaces(String line) {
        int end = line.length();
        while (end > 0 && line.charAt(end - 1) == ' ') {
            int backslashes = 0;
            int i = end - 2;
            while (i >= 0 && line.charAt(i) == '\\') {
                backslashes++;
                i--;
            }
            if (backslashes % 2 == 1) {
                break;
            }
            end--;
        }
        return line.substring(0, end);
    }

    private static String toRegex(String glob) {
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '\\' -> {
                    if (i + 1 < glob.length()) {
                        regex.append(Pattern.quote(String.valueOf(glob.charAt(++i))));
                    } else {
                        regex.append(Pattern.quote("\\"));
                    }
                }
                case '*' -> {
                    if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                        boolean followedBySlash = i + 2 < glob.length() && glob.charAt(i + 2) == '/';
                        if (followedBySlash) {
                            regex.append("(?:.*/)?");
                            i += 2;
                        } else {
                            regex.append(".*");
                            i += 1;
                        }
                    } else {
                        regex.append("[^/]*");
                    }
                }
                case '?' -> regex.append("[^/]");
                case '[' -> {
                    int end = findClassEnd(glob, i);
                    if (end < 0) {
                        regex.append(Pattern.quote("["));
                    } else {
                        regex.append(convertClass(glob.substring(i + 1, end)));
                        i = end;
                    }
                }
                default -> regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return regex.append('$').toString();
    }

    private static int findClassEnd(String glob, int start) {
        int i = start + 1;
        if (i < glob.length() && (glob.charAt(i) == '!' || glob.charAt(i) == '^')) {
            i++;
        }
        if (i < glob.length() && glob.charAt(i) == ']') {
            i++;
        }
        for (; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '\\') {
                i++;
                continue;
            }
            if (c == ']') {
                return i;
            }
        }
        return -1;
    }

    private static String convertClass(String body) {
        StringBuilder out = new StringBuilder("[");
        int i = 0;
        if (!body.isEmpty() && (body.charAt(0) == '!' || body.charAt(0) == '^')) {
            out.append('^');
            i = 1;
        }
        for (; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '\\' && i + 1 < body.length()) {
                out.append(Pattern.quote(String.valueOf(body.charAt(++i))));
                continue;
            }
            if (c == ']' || c == '[' || c == '^' || c == '&') {
                out.append('\\');
            }
            out.append(c);
        }
        return out.append(']').toString();
    }
}
