package io.github.vihuynh72.brownie.core.revision;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/** Produces a stable SHA-256 digest from the typed, ordered content shape. */
public final class DocumentContentHasher {

    private DocumentContentHasher() {
    }

    public static String sha256Hex(DocumentContent content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateString(digest, "brownie-document-content-v1");
            for (Map.Entry<String, FieldValue> entry : new TreeMap<>(content.fields()).entrySet()) {
                updateString(digest, entry.getKey());
                switch (entry.getValue()) {
                    case FieldValue.TextValue(String value) -> {
                        updateString(digest, "TEXT");
                        updateString(digest, "SCALAR");
                        updateString(digest, value);
                    }
                    case FieldValue.DateValue(java.time.LocalDate value) -> {
                        updateString(digest, "DATE");
                        updateString(digest, "SCALAR");
                        updateString(digest, value.toString());
                    }
                    case FieldValue.RepeatedTextValue(java.util.List<String> values) -> {
                        updateString(digest, "TEXT");
                        updateString(digest, "REPEATED");
                        updateList(digest, values);
                    }
                    case FieldValue.RepeatedDateValue(java.util.List<java.time.LocalDate> values) -> {
                        updateString(digest, "DATE");
                        updateString(digest, "REPEATED");
                        updateList(digest, values.stream().map(Object::toString).toList());
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is a JDK-guaranteed algorithm; this should be unreachable.", e);
        }
    }

    private static void updateList(MessageDigest digest, java.util.List<String> values) {
        updateString(digest, Integer.toString(values.size()));
        for (String value : values) {
            updateString(digest, value);
        }
    }

    private static void updateString(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) ':');
        digest.update(bytes);
        digest.update((byte) ';');
    }
}
