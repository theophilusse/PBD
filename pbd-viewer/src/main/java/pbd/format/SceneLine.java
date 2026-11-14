package pbd.format;

import pbd.net.FetchPolicy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * One line of a scene description file ({@code .pbdscene}), cut into words and read back with the checks that make a typo a message
 * instead of a surprise.
 *
 * <p>A line is a directive, then words without an {@code =} (the "positional" ones: the id of a placement, the time of an
 * {@code at} line), then {@code key=value} pairs:
 * <pre>{@code
 * row  shelf  model=models/shelf.pbd  count=10  step=(2,0,0)   # a comment runs to the end of the line
 * at 12.5 camera pos=(0, 1.7, 8) look=(0, 1.2, 0)
 * }</pre>
 * A value is a bare word, a {@code "quoted text"} (spaces allowed) or a {@code (tuple, in, parentheses)} (spaces allowed). A
 * {@code #} outside quotes and parentheses starts a comment.
 *
 * <p>Every getter says what it was reading when it fails ("line 7: count of 'row': ...") and {@link #allowOnly} turns a key nobody
 * asked for into an error - a scene file is written by hand, and a misspelt {@code stpe=} must not be silently ignored.
 *
 * <p>Everything a getter returns is checked against a closed form (a number is a decimal number, a name is letters and digits...),
 * because the expander writes those values into PBD text: whatever a value is allowed to be, it cannot carry a brace, a quote or a
 * newline into the text that is parsed next.
 */
final class SceneLine {

    /** The longest line read (characters): a line longer than that is a mistake or an attack, not a scene. */
    static final int MAX_LINE_CHARS = 4096;

    /** Coordinates, sizes and times are numbers of this magnitude at most: far beyond any scene, and far from where float arithmetic degrades. */
    static final double MAX_MAGNITUDE = 1.0e6;

    private static final Pattern DECIMAL = Pattern.compile("[-+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][-+]?\\d+)?");
    private static final Pattern INTEGER = Pattern.compile("[0-9]{1,9}");
    private static final Pattern KEY = Pattern.compile("([A-Za-z][A-Za-z0-9]*)=(.*)", Pattern.DOTALL);

    /** The name of a placement, a material, a category: no dot (a dot separates a placement from the parts of its model), no space, no brace. */
    static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,63}");

    /** The id of an instance somewhere in the scene, as {@code parent=} and {@code look=} say it: names separated by dots. */
    static final Pattern INSTANCE_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}(?:\\.[A-Za-z0-9_-]{1,64}){0,15}");

    final int number;
    final String directive;
    final List<String> positional;
    private final Map<String, String> fields;

    private SceneLine(int number, String directive, List<String> positional, Map<String, String> fields) {
        this.number = number;
        this.directive = directive;
        this.positional = positional;
        this.fields = fields;
    }

    static IllegalStateException error(int number, String message) {
        return new IllegalStateException("scene line " + number + ": " + message);
    }

    IllegalStateException error(String message) {
        return error(number, message);
    }

    /** The line cut into words, or null for a blank line or a comment. */
    static SceneLine parse(int number, String raw) {
        if (raw.length() > MAX_LINE_CHARS) throw error(number, "the line is longer than " + MAX_LINE_CHARS + " characters");
        List<String> words = new ArrayList<>();
        int i = 0, n = raw.length();
        while (i < n) {
            char c = raw.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (c == '#') break;
            StringBuilder word = new StringBuilder();
            while (i < n) {
                c = raw.charAt(i);
                if (Character.isWhitespace(c) || c == '#') break;
                if (c == '"') {
                    int close = raw.indexOf('"', i + 1);
                    if (close < 0) throw error(number, "a quotation mark is never closed");
                    word.append(raw, i, close + 1);
                    i = close + 1;
                } else if (c == '(') {
                    int close = raw.indexOf(')', i + 1);
                    if (close < 0) throw error(number, "a parenthesis is never closed");
                    String group = raw.substring(i, close + 1);
                    if (group.indexOf('(', 1) >= 0) throw error(number, "parentheses do not nest: '" + shorten(group) + "'");
                    word.append(group);
                    i = close + 1;
                } else if (c == ')') {
                    throw error(number, "a closing parenthesis with no opening one");
                } else {
                    word.append(c);
                    i++;
                }
            }
            words.add(word.toString());
        }
        if (words.isEmpty()) return null;

        String directive = words.get(0);
        if (!directive.matches("[a-z]+")) throw error(number, "a line starts with a directive written in lower case letters, not '" + shorten(directive) + "'");
        List<String> positional = new ArrayList<>();
        Map<String, String> fields = new LinkedHashMap<>();
        for (int w = 1; w < words.size(); w++) {
            String word = words.get(w);
            java.util.regex.Matcher keyed = KEY.matcher(word);
            if (keyed.matches()) {
                String key = keyed.group(1);
                String value = keyed.group(2);
                if (value.startsWith("\"")) {
                    if (value.length() < 2 || value.indexOf('"', 1) != value.length() - 1) {
                        throw error(number, "'" + key + "': text right after a closing quotation mark");
                    }
                    value = value.substring(1, value.length() - 1);
                } else if (value.indexOf('"') >= 0) {
                    throw error(number, "'" + key + "': a quotation mark in the middle of a word");
                }
                if (value.isEmpty()) throw error(number, "'" + key + "' has no value");
                if (fields.put(key, value) != null) throw error(number, "'" + key + "' is written twice");
            } else {
                if (word.startsWith("\"")) {
                    if (word.length() < 2 || word.indexOf('"', 1) != word.length() - 1) throw error(number, "text right after a closing quotation mark");
                    word = word.substring(1, word.length() - 1);
                } else if (word.indexOf('"') >= 0 || word.indexOf('=') >= 0) {
                    throw error(number, "'" + shorten(word) + "' is neither a word nor key=value");
                }
                positional.add(word);
            }
        }
        return new SceneLine(number, directive, positional, fields);
    }

    static String shorten(String text) {
        return text.length() <= 60 ? text : text.substring(0, 60) + "...";
    }

    // ------------------------------------------------------------------ positional words

    /** The positional words must be exactly {@code names.length} of them; the message names what was expected. */
    void expectPositional(String... names) {
        if (positional.size() != names.length) {
            throw error("'" + directive + "' is written: " + directive + " " + String.join(" ", names) + " [key=value ...] (found "
                + positional.size() + " word(s) before the first key=value)");
        }
    }

    /** A name (placement id) that is safe to write into PBD text. */
    String nameWord(int index, String what) {
        String word = positional.get(index);
        if (!NAME.matcher(word).matches()) {
            throw error(what + " '" + shorten(word) + "' must start with a letter and hold letters, digits, '_' and '-' only (64 characters at most)");
        }
        return word;
    }

    // ------------------------------------------------------------------ fields

    boolean has(String key) {
        return fields.containsKey(key);
    }

    /** The raw value (quotes already removed), or null when the key is absent. */
    String raw(String key) {
        return fields.get(key);
    }

    String requiredRaw(String key) {
        String value = raw(key);
        if (value == null) throw error("'" + directive + "' needs " + key + "=");
        return value;
    }

    /**
     * The keys this directive reads, once and for all: any other key of the line is a typo until proven otherwise, and the message
     * lists the keys that exist.
     */
    void allowOnly(String... allowed) {
        List<String> known = List.of(allowed);
        for (String key : fields.keySet()) {
            if (!known.contains(key)) {
                throw error("'" + directive + "' has no field '" + key + "' (its fields: " + String.join(", ", known) + ")");
            }
        }
    }

    // ------------------------------------------------------------------ typed values

    /** One finite decimal number, magnitude at most {@link #MAX_MAGNITUDE}. */
    double parseNumber(String key, String text) {
        String t = text.trim();
        if (!DECIMAL.matcher(t).matches()) throw error(key + " must be a number, got '" + shorten(text) + "'");
        double v;
        try {
            v = Double.parseDouble(t);
        } catch (NumberFormatException e) {
            throw error(key + " must be a number, got '" + shorten(text) + "'");
        }
        if (Double.isNaN(v) || Double.isInfinite(v) || Math.abs(v) > MAX_MAGNITUDE) {
            throw error(key + " must be a number between -" + (long) MAX_MAGNITUDE + " and " + (long) MAX_MAGNITUDE + ", got '" + shorten(text) + "'");
        }
        return v;
    }

    double number(String key, double fallback) {
        String text = raw(key);
        return text == null ? fallback : parseNumber(key, text);
    }

    double requiredNumber(String key) {
        return parseNumber(key, requiredRaw(key));
    }

    double numberIn(String key, double fallback, double min, double max) {
        double v = number(key, fallback);
        if (v < min || v > max) throw error(key + " must be between " + min + " and " + max + ", got " + v);
        return v;
    }

    int integer(String key, int fallback, int min, int max) {
        String text = raw(key);
        return text == null ? fallback : parseInteger(key, text, min, max);
    }

    int requiredInteger(String key, int min, int max) {
        return parseInteger(key, requiredRaw(key), min, max);
    }

    int parseInteger(String key, String text, int min, int max) {
        String t = text.trim();
        if (!INTEGER.matcher(t).matches()) throw error(key + " must be a whole number, got '" + shorten(text) + "'");
        long v = Long.parseLong(t);
        if (v < min || v > max) throw error(key + " must be between " + min + " and " + max + ", got " + v);
        return (int) v;
    }

    /** {@code (a, b, c)} - exactly {@code arity} numbers. */
    double[] parseTuple(String key, String text, int arity) {
        String t = text.trim();
        if (!t.startsWith("(") || !t.endsWith(")")) throw error(key + " must be written (" + "a, b, c, d".substring(0, arity * 3 - 2) + "), got '" + shorten(text) + "'");
        String inner = t.substring(1, t.length() - 1);
        String[] parts = inner.split(",", -1);
        if (parts.length != arity) throw error(key + " needs " + arity + " numbers in parentheses, got " + parts.length + " in '" + shorten(text) + "'");
        double[] out = new double[arity];
        for (int i = 0; i < arity; i++) out[i] = parseNumber(key, parts[i]);
        return out;
    }

    /** {@code (a, b, c)} of whole numbers, each between {@code min} and {@code max}. */
    int[] requiredIntegers(String key, int arity, int min, int max) {
        String text = requiredRaw(key);
        String t = text.trim();
        if (!t.startsWith("(") || !t.endsWith(")")) throw error(key + " must be written (" + "a, b, c, d".substring(0, arity * 3 - 2) + ") of whole numbers, got '" + shorten(text) + "'");
        String[] parts = t.substring(1, t.length() - 1).split(",", -1);
        if (parts.length != arity) throw error(key + " needs " + arity + " whole numbers in parentheses, got " + parts.length + " in '" + shorten(text) + "'");
        int[] out = new int[arity];
        for (int i = 0; i < arity; i++) out[i] = parseInteger(key, parts[i], min, max);
        return out;
    }

    double[] vec3(String key) {
        String text = raw(key);
        return text == null ? null : parseTuple(key, text, 3);
    }

    double[] requiredVec3(String key) {
        return parseTuple(key, requiredRaw(key), 3);
    }

    /** A vector as a tuple or a single number (the same number three times), as {@code scale=2} is in a .pbd. */
    double[] vec3OrScalar(String key) {
        String text = raw(key);
        if (text == null) return null;
        if (text.trim().startsWith("(")) return parseTuple(key, text, 3);
        double v = parseNumber(key, text);
        return new double[]{v, v, v};
    }

    /** One of a closed list of words. */
    String choice(String key, String fallback, String... allowed) {
        String text = raw(key);
        if (text == null) return fallback;
        for (String option : allowed) if (option.equals(text)) return text;
        throw error(key + " must be one of " + String.join(", ", allowed) + ", got '" + shorten(text) + "'");
    }

    boolean flag(String key, boolean fallback) {
        String text = choice(key, null, "true", "false", "on", "off", "yes", "no");
        if (text == null) return fallback;
        return text.equals("true") || text.equals("on") || text.equals("yes");
    }

    /** A name (material, category) that is safe to write into PBD text, or null when absent. */
    String name(String key) {
        String text = raw(key);
        if (text == null) return null;
        if (!NAME.matcher(text).matches()) {
            throw error(key + " '" + shorten(text) + "' must start with a letter and hold letters, digits, '_' and '-' only (64 characters at most)");
        }
        return text;
    }

    /** The id of an instance of the scene ({@code locker_2.door}), or null when absent. */
    String instanceId(String key) {
        String text = raw(key);
        if (text == null) return null;
        return checkInstanceId(key, text);
    }

    String checkInstanceId(String key, String text) {
        if (!INSTANCE_ID.matcher(text).matches()) {
            throw error(key + " '" + shorten(text) + "' is not an instance id (names of letters, digits, '_' and '-', separated by dots)");
        }
        return text;
    }

    /**
     * A file or folder path as it is written into PBD text between quotation marks: not empty, no quotation mark, no control
     * character, and a path a file of this disk can have ({@link FetchPolicy#refuseLocalReference}: not a network path, not an address).
     */
    String path(String key) {
        String text = raw(key);
        return text == null ? null : checkPath(key, text);
    }

    String requiredPath(String key) {
        return checkPath(key, requiredRaw(key));
    }

    String checkPath(String key, String text) {
        if (text.indexOf('"') >= 0) throw error(key + " '" + shorten(text) + "' holds a quotation mark");
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            throw error(key + " '" + shorten(text) + "' is an address: a scene file places files of this disk - download the model first");
        }
        String refusal = FetchPolicy.refuseLocalReference(text);
        if (refusal != null) throw error(key + " '" + shorten(text) + "': " + refusal);
        return text;
    }
}
