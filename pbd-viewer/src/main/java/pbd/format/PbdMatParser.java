package pbd.format;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * .pbdmat -> Map&lt;materialName, Map&lt;field, rawValue&gt;&gt; parser.
 *
 * Grammar:
 *   material <name> { key=value ... }
 *
 * Deliberately returns raw strings, not typed values - the caller (a
 * MaterialCatalog.Entry consumer in pbd.render) already knows how to parse
 * a color/float from a raw string for the hard-coded catalog, so this
 * reuses that instead of duplicating float/vec3 parsing here.
 */
public final class PbdMatParser {

    private String src;
    private int pos;
    private int line;

    public Map<String, Map<String, String>> parseFile(Path path) throws IOException {
        return parse(Files.readString(path));
    }

    public Map<String, Map<String, String>> parse(String text) {
        this.src = text;
        this.pos = 0;
        this.line = 1;

        Map<String, Map<String, String>> materials = new LinkedHashMap<>();

        skipWhitespaceAndComments();
        while (!atEnd()) {
            String keyword = readIdentifier();
            if (!keyword.equals("material")) {
                throw error("Expected 'material', found '" + keyword + "'");
            }
            String name = readIdentifier();
            Map<String, String> fields = parseBlock();
            materials.put(name, fields);
            skipWhitespaceAndComments();
        }
        return materials;
    }

    private Map<String, String> parseBlock() {
        expect('{');
        Map<String, String> fields = new LinkedHashMap<>();
        skipWhitespaceAndComments();
        while (peek() != '}') {
            String key = readIdentifier();
            expect('=');
            fields.put(key, readRawValue());
            skipWhitespaceAndComments();
        }
        expect('}');
        return fields;
    }

    private String readRawValue() {
        skipWhitespaceAndComments();
        char c = peek();
        if (c == '"') {
            pos++;
            int start = pos;
            while (!atEnd() && peek() != '"') pos++;
            String s = src.substring(start, pos);
            if (!atEnd()) pos++; // closing quote
            return s;
        }
        int start = pos;
        if (c == '(') {
            skipBalanced('(', ')');
        } else {
            while (!atEnd() && !isStructural(peek()) && !Character.isWhitespace(peek())) pos++;
        }
        return src.substring(start, pos).trim();
    }

    private boolean atEnd() { return pos >= src.length(); }
    private char peek() { return src.charAt(pos); }

    private void skipWhitespaceAndComments() {
        while (!atEnd()) {
            char c = peek();
            if (c == '\n') { line++; pos++; }
            else if (Character.isWhitespace(c)) { pos++; }
            else if (c == '#') { while (!atEnd() && peek() != '\n') pos++; }
            else break;
        }
    }

    private boolean isStructural(char c) {
        return c == '{' || c == '}' || c == '(' || c == ')' || c == '=';
    }

    private String readIdentifier() {
        skipWhitespaceAndComments();
        int start = pos;
        while (!atEnd() && !isStructural(peek()) && !Character.isWhitespace(peek())) pos++;
        if (pos == start) throw error("Expected an identifier");
        return src.substring(start, pos);
    }

    private void expect(char c) {
        skipWhitespaceAndComments();
        if (atEnd() || peek() != c) {
            throw error("Expected '" + c + "'" + (atEnd() ? " (end of file)" : ", found '" + peek() + "'"));
        }
        pos++;
    }

    private void skipBalanced(char open, char close) {
        int depth = 0;
        do {
            char c = src.charAt(pos++);
            if (c == open) depth++;
            else if (c == close) depth--;
        } while (depth > 0 && !atEnd());
    }

    private RuntimeException error(String message) {
        return new IllegalStateException("Parse error in .pbdmat (line " + line + "): " + message);
    }
}
