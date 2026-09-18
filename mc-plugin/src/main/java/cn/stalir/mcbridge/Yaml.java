package cn.stalir.mcbridge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal YAML reader covering the syntax this plugin ships: nested maps of
 * scalars, block lists, quoted strings and comments.
 *
 * The plugin cannot use Bukkit's {@code YamlConfiguration} because Velocity does
 * not have it, and shading a YAML library into a universal jar would risk
 * clashing with the one the server already loads. Only the subset above is
 * supported; anything else (anchors, flow mappings, multi-line scalars) is not
 * parsed and should not be used in config.yml or lang/&lt;language&gt;.yml.
 */
public final class Yaml {

    private final Map<String, Object> root;

    private Yaml(Map<String, Object> root) {
        this.root = root;
    }

    /** An empty document, returned when a file is missing or unreadable. */
    public static Yaml empty() {
        return new Yaml(new LinkedHashMap<>());
    }

    public static Yaml parse(String text) {
        if (text == null || text.isEmpty()) {
            return empty();
        }

        List<String> lines = List.of(text.split("\r\n|\r|\n", -1));
        int[] index = {0};

        return new Yaml(parseBlock(lines, index, 0));
    }

    /** Read a file, returning an empty document when it cannot be read. */
    public static Yaml load(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return empty();
        }

        try {
            return parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            return empty();
        }
    }

    // ------------------------------------------------------------------
    // Typed accessors
    // ------------------------------------------------------------------

    public String getString(String path, String fallback) {
        Object value = lookup(path);

        if (value instanceof String text) {
            return text;
        }

        return value == null ? fallback : String.valueOf(value);
    }

    public int getInt(String path, int fallback) {
        Object value = lookup(path);

        if (value instanceof Number number) {
            return number.intValue();
        }

        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }

        return fallback;
    }

    public boolean getBoolean(String path, boolean fallback) {
        Object value = lookup(path);

        if (value instanceof Boolean flag) {
            return flag;
        }

        if (value instanceof String text) {
            String normalised = text.trim();

            if (normalised.equalsIgnoreCase("true")) {
                return true;
            }

            if (normalised.equalsIgnoreCase("false")) {
                return false;
            }
        }

        return fallback;
    }

    @SuppressWarnings("unchecked")
    public List<String> getStringList(String path) {
        Object value = lookup(path);

        if (value instanceof List<?> list) {
            List<String> values = new ArrayList<>(list.size());

            for (Object element : list) {
                if (element != null) {
                    values.add(String.valueOf(element));
                }
            }

            return Collections.unmodifiableList(values);
        }

        if (value instanceof String text && !text.isBlank()) {
            return List.of(text);
        }

        return List.of();
    }

    /**
     * Flatten the document into "dotted.key" to value pairs, the same shape
     * Bukkit's {@code YamlConfiguration#getValues(true)} produces.
     */
    public Map<String, String> flattened() {
        Map<String, String> values = new LinkedHashMap<>();
        flatten(root, "", values);

        return values;
    }

    /** True when the document contains no keys at all. */
    public boolean isEmpty() {
        return root.isEmpty();
    }

    // ------------------------------------------------------------------
    // Parsing
    // ------------------------------------------------------------------

    private Object lookup(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }

        Object current = root;

        for (String segment : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }

            current = map.get(segment);
        }

        return current;
    }

    private static void flatten(Object node, String prefix, Map<String, String> out) {
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = prefix.isEmpty() ? String.valueOf(entry.getKey()) : prefix + "." + entry.getKey();
                flatten(entry.getValue(), key, out);
            }

            return;
        }

        if (node == null) {
            return;
        }

        if (node instanceof List<?> list) {
            List<String> parts = new ArrayList<>(list.size());

            for (Object element : list) {
                parts.add(String.valueOf(element));
            }

            out.put(prefix, String.join(", ", parts));
            return;
        }

        out.put(prefix, String.valueOf(node));
    }

    private static Map<String, Object> parseBlock(List<String> lines, int[] index, int indent) {
        Map<String, Object> map = new LinkedHashMap<>();

        while (index[0] < lines.size()) {
            String raw = lines.get(index[0]);
            String line = stripComment(raw).strip();

            if (line.isEmpty()) {
                index[0]++;
                continue;
            }

            int lineIndent = indentOf(raw);

            if (lineIndent < indent || line.startsWith("- ")) {
                break;
            }

            int colon = indexOfColon(line);

            if (colon < 0) {
                // Not a mapping entry; skip rather than fail the whole file.
                index[0]++;
                continue;
            }

            String key = unquote(line.substring(0, colon).strip());
            String rest = line.substring(colon + 1).strip();
            index[0]++;

            if (!rest.isEmpty()) {
                map.put(key, unquote(rest));
                continue;
            }

            map.put(key, parseChild(lines, index, lineIndent));
        }

        return map;
    }

    /** Parse the block that belongs to a key written without a value. */
    private static Object parseChild(List<String> lines, int[] index, int parentIndent) {
        while (index[0] < lines.size() && stripComment(lines.get(index[0])).strip().isEmpty()) {
            index[0]++;
        }

        if (index[0] >= lines.size()) {
            return new LinkedHashMap<String, Object>();
        }

        String next = stripComment(lines.get(index[0])).strip();
        int nextIndent = indentOf(lines.get(index[0]));

        if (next.startsWith("- ") && nextIndent >= parentIndent) {
            return parseList(lines, index, nextIndent);
        }

        if (nextIndent > parentIndent) {
            return parseBlock(lines, index, nextIndent);
        }

        // A key with neither a scalar nor an indented block: an empty section.
        return new LinkedHashMap<String, Object>();
    }

    private static List<String> parseList(List<String> lines, int[] index, int indent) {
        List<String> list = new ArrayList<>();

        while (index[0] < lines.size()) {
            String raw = lines.get(index[0]);
            String line = stripComment(raw).strip();

            if (line.isEmpty()) {
                index[0]++;
                continue;
            }

            if (indentOf(raw) < indent || !line.startsWith("- ")) {
                break;
            }

            list.add(unquote(line.substring(2).strip()));
            index[0]++;
        }

        return list;
    }

    private static int indentOf(String line) {
        int count = 0;

        while (count < line.length() && line.charAt(count) == ' ') {
            count++;
        }

        return count;
    }

    /** Remove a trailing comment, ignoring '#' inside quotes. */
    private static String stripComment(String line) {
        boolean single = false;
        boolean doubleQuoted = false;

        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);

            if (character == '\\' && doubleQuoted) {
                // \" and \\ inside a double-quoted scalar must not toggle the state.
                index++;
            } else if (character == '\'' && !doubleQuoted) {
                single = !single;
            } else if (character == '"' && !single) {
                doubleQuoted = !doubleQuoted;
            } else if (character == '#' && !single && !doubleQuoted
                    && (index == 0 || Character.isWhitespace(line.charAt(index - 1)))) {
                return line.substring(0, index);
            }
        }

        return line;
    }

    private static int indexOfColon(String line) {
        boolean single = false;
        boolean doubleQuoted = false;

        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);

            if (character == '\\' && doubleQuoted) {
                index++;
            } else if (character == '\'' && !doubleQuoted) {
                single = !single;
            } else if (character == '"' && !single) {
                doubleQuoted = !doubleQuoted;
            } else if (character == ':' && !single && !doubleQuoted) {
                return index;
            }
        }

        return -1;
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);

            if (first == '"' && last == '"') {
                return value.substring(1, value.length() - 1)
                        .replace("\\\"", "\"")
                        .replace("\\\\", "\\");
            }

            if (first == '\'' && last == '\'') {
                return value.substring(1, value.length() - 1).replace("''", "'");
            }
        }

        return value;
    }
}
