package io.github.vihuynh72.brownie.core.action;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * One way, and only one, of writing an action's payload as text, so that its
 * hash names exactly one payload and a person's approval can be bound to it.
 *
 * <p>The form is the JSON Canonicalization Scheme (RFC 8785) for the values a
 * payload uses: objects with their names in order of UTF-16 code units,
 * arrays, strings, whole numbers, true, false and null. There is no
 * whitespace, a string escapes only what JSON requires ({@code "}, {@code \},
 * and control characters, the common ones by their short forms and the rest
 * as {@code \}{@code u00xx}), and every other character is written as itself.
 * Numbers with a fraction or an exponent are not part of any payload and are
 * refused rather than given a second spelling.
 *
 * <p>Reading accepts only text already in this form: a stored payload that
 * reads back and writes out again to anything but the identical text has been
 * changed by something other than Brownie, and is refused.
 */
public final class CanonicalJson {

    /** Deeper than any payload is; a limit so that reading stored text can never exhaust the stack. */
    private static final int MAX_DEPTH = 16;

    private CanonicalJson() {
    }

    /** Writes a value built from maps with string keys, lists, strings, whole numbers, booleans and null. */
    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out, 0);
        return out.toString();
    }

    /** Lowercase hexadecimal SHA-256 of the text's UTF-8 bytes. */
    public static String sha256Hex(String canonical) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is a JDK-guaranteed algorithm; this should be unreachable.", e);
        }
    }

    /**
     * Reads text that is already canonical. Objects come back as unmodifiable
     * maps in their written order, arrays as unmodifiable lists, numbers as
     * {@link Long}. Anything else is refused with {@link IllegalArgumentException}.
     */
    public static Object read(String text) {
        Reader reader = new Reader(text);
        Object value = reader.value(0);
        if (reader.position != text.length()) {
            throw new IllegalArgumentException("Canonical JSON has text after its value.");
        }
        if (!write(value).equals(text)) {
            throw new IllegalArgumentException("The text is JSON but not in its canonical form.");
        }
        return value;
    }

    private static void write(Object value, StringBuilder out, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("A payload is nested deeper than " + MAX_DEPTH + " levels.");
        }
        switch (value) {
            case null -> out.append("null");
            case String text -> writeString(text, out);
            case Boolean bool -> out.append(bool ? "true" : "false");
            case Long number -> out.append(number.longValue());
            case Integer number -> out.append(number.intValue());
            case Map<?, ?> map -> {
                TreeMap<String, Object> sorted = new TreeMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!(entry.getKey() instanceof String key)) {
                        throw new IllegalArgumentException("An object's names must be strings.");
                    }
                    sorted.put(key, entry.getValue());
                }
                out.append('{');
                boolean first = true;
                for (Map.Entry<String, Object> entry : sorted.entrySet()) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    writeString(entry.getKey(), out);
                    out.append(':');
                    write(entry.getValue(), out, depth + 1);
                }
                out.append('}');
            }
            case List<?> list -> {
                out.append('[');
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) {
                        out.append(',');
                    }
                    write(list.get(i), out, depth + 1);
                }
                out.append(']');
            }
            default -> throw new IllegalArgumentException(
                    "A payload holds only strings, whole numbers, booleans, null, lists and maps, not " + value.getClass().getSimpleName() + ".");
        }
    }

    /** How many characters {@code text} takes in a payload once written as a JSON string, quotes included. */
    static int writtenLength(String text) {
        StringBuilder out = new StringBuilder(text.length() + 2);
        writeString(text, out);
        return out.length();
    }

    private static void writeString(String text, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1))) {
                    throw new IllegalArgumentException("A payload string contains half of a character.");
                }
                out.append(c).append(text.charAt(++i));
                continue;
            }
            if (Character.isLowSurrogate(c)) {
                throw new IllegalArgumentException("A payload string contains half of a character.");
            }
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append("\\u00").append(Character.forDigit(c >> 4, 16)).append(Character.forDigit(c & 0xF, 16));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    /** A strict reader for the canonical form; anything looser is caught by the rewrite check in {@link #read}. */
    private static final class Reader {

        private final String text;
        private int position;

        Reader(String text) {
            this.text = text;
        }

        Object value(int depth) {
            if (depth > MAX_DEPTH) {
                throw new IllegalArgumentException("Canonical JSON is nested deeper than " + MAX_DEPTH + " levels.");
            }
            char c = peek();
            return switch (c) {
                case '{' -> object(depth);
                case '[' -> array(depth);
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        yield number();
                    }
                    throw new IllegalArgumentException("Canonical JSON has an unexpected character at " + position + ".");
                }
            };
        }

        private Map<String, Object> object(int depth) {
            expect('{');
            LinkedHashMap<String, Object> map = new LinkedHashMap<>();
            if (peek() == '}') {
                position++;
                return Collections.unmodifiableMap(map);
            }
            while (true) {
                String key = string();
                if (map.containsKey(key)) {
                    throw new IllegalArgumentException("Canonical JSON names a member twice.");
                }
                expect(':');
                map.put(key, value(depth + 1));
                char next = next();
                if (next == '}') {
                    return Collections.unmodifiableMap(map);
                }
                if (next != ',') {
                    throw new IllegalArgumentException("Canonical JSON has an unexpected character at " + (position - 1) + ".");
                }
            }
        }

        private List<Object> array(int depth) {
            expect('[');
            List<Object> list = new ArrayList<>();
            if (peek() == ']') {
                position++;
                return Collections.unmodifiableList(list);
            }
            while (true) {
                list.add(value(depth + 1));
                char next = next();
                if (next == ']') {
                    return Collections.unmodifiableList(list);
                }
                if (next != ',') {
                    throw new IllegalArgumentException("Canonical JSON has an unexpected character at " + (position - 1) + ".");
                }
            }
        }

        private String string() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') {
                    return out.toString();
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                char escape = next();
                switch (escape) {
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        if (position + 4 > text.length()) {
                            throw new IllegalArgumentException("Canonical JSON ends inside an escape.");
                        }
                        out.append((char) Integer.parseInt(text.substring(position, position + 4), 16));
                        position += 4;
                    }
                    default -> throw new IllegalArgumentException("Canonical JSON has an escape it never writes.");
                }
            }
        }

        private Long number() {
            int start = position;
            if (peek() == '-') {
                position++;
            }
            while (position < text.length() && Character.isDigit(text.charAt(position)) && text.charAt(position) < 128) {
                position++;
            }
            try {
                return Long.parseLong(text.substring(start, position));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Canonical JSON has a number it cannot read.");
            }
        }

        private Object literal(String word, Object value) {
            if (!text.startsWith(word, position)) {
                throw new IllegalArgumentException("Canonical JSON has an unexpected word at " + position + ".");
            }
            position += word.length();
            return value;
        }

        private void expect(char c) {
            if (next() != c) {
                throw new IllegalArgumentException("Canonical JSON expected '" + c + "' at " + (position - 1) + ".");
            }
        }

        private char peek() {
            if (position >= text.length()) {
                throw new IllegalArgumentException("Canonical JSON ends too early.");
            }
            return text.charAt(position);
        }

        private char next() {
            char c = peek();
            position++;
            return c;
        }
    }
}
