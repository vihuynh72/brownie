package io.github.vihuynh72.brownie.core.action;

import java.util.List;
import java.util.Map;

/**
 * Reading a stored payload back into its typed form. Every field a payload
 * names must be present with exactly its type; anything else means the text
 * is not a payload Brownie wrote, and nothing is sent for it.
 */
final class Payloads {

    /** The version of the payload's own shape; a later shape would be a different string. */
    static final String SCHEMA = "brownie.action/1";

    /** How much of a title a payload carries, to name what it is about: far more than any page shows on one line. */
    static final int MAX_SHOWN_TITLE_LENGTH = 500;

    private Payloads() {
    }

    /** A title as a payload carries it: as it is, cut at a whole character past the length a payload shows. */
    static String shownTitle(String title) {
        if (title.codePointCount(0, title.length()) <= MAX_SHOWN_TITLE_LENGTH) {
            return title;
        }
        return title.substring(0, title.offsetByCodePoints(0, MAX_SHOWN_TITLE_LENGTH));
    }

    static Map<String, Object> object(Object value, String name) {
        if (value instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) map;
            return typed;
        }
        throw new IllegalStateException("A stored payload's " + name + " is not an object.");
    }

    static Map<String, Object> object(Map<String, Object> parent, String name) {
        return object(parent.get(name), name);
    }

    static String string(Map<String, Object> parent, String name) {
        if (parent.get(name) instanceof String text) {
            return text;
        }
        throw new IllegalStateException("A stored payload's " + name + " is not text.");
    }

    static String stringOrNull(Map<String, Object> parent, String name) {
        Object value = parent.get(name);
        if (value == null && parent.containsKey(name)) {
            return null;
        }
        return string(parent, name);
    }

    static long number(Map<String, Object> parent, String name) {
        if (parent.get(name) instanceof Long number) {
            return number;
        }
        throw new IllegalStateException("A stored payload's " + name + " is not a whole number.");
    }

    static boolean bool(Map<String, Object> parent, String name) {
        if (parent.get(name) instanceof Boolean flag) {
            return flag;
        }
        throw new IllegalStateException("A stored payload's " + name + " is not true or false.");
    }

    static List<String> strings(Map<String, Object> parent, String name) {
        if (parent.get(name) instanceof List<?> list && list.stream().allMatch(String.class::isInstance)) {
            return list.stream().map(String.class::cast).toList();
        }
        throw new IllegalStateException("A stored payload's " + name + " is not a list of text.");
    }

    /** The parts every payload has, checked against what it says it is. */
    static Map<String, Object> read(String canonical, ActionType type) {
        Map<String, Object> root = object(CanonicalJson.read(canonical), "payload");
        if (!SCHEMA.equals(string(root, "schema")) || !type.name().equals(string(root, "type"))) {
            throw new IllegalStateException("A stored payload is not a " + type + " payload of this version.");
        }
        return root;
    }
}
