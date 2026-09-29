package io.github.vihuynh72.brownie.core.action;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The text added to a Google Doc, and what reading the Doc back says about
 * the addition.
 *
 * <p>The text is cleaned the way Google Docs cleans inserted text, so that
 * what the person approves is what the Doc will hold: line breaks made
 * plain (line and paragraph separators included), control characters
 * other than line breaks and tabs taken out, and so are the private-use
 * characters of symbol fonts, which Docs strips.
 * It starts with a line break, so it begins a paragraph of its own; Docs puts
 * text added at the end of a tab just before the tab's last line break.
 *
 * <p>Afterwards, only one reading counts as "added": the Doc moved on from
 * the revision the addition named, everything that was there before is
 * exactly as it was, and exactly the added text follows it. An unchanged
 * revision means nothing was added (Google says an unchanged revision is an
 * unchanged Doc). Anything else cannot be told apart from the person editing
 * the Doc in the meantime, so it is unknown, never "not added".
 */
public final class DocAppendText {

    /** Tens of pages of minutes; more than that is more likely a mistake than an addition. */
    public static final int MAX_LENGTH = 50_000;

    private DocAppendText() {
    }

    /** The text as Docs will hold it, starting on a paragraph of its own; empty when nothing would be left to add. */
    public static String cleaned(String text) {
        String plain = text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n')
                .replace('\u2028', '\n').replace('\u2029', '\n');
        StringBuilder kept = new StringBuilder(plain.length() + 1);
        plain.codePoints()
                .filter(c -> c == '\n' || c == '\t' || !Character.isISOControl(c))
                // Docs strips the private-use characters of symbol fonts from inserted text; they are taken out here so
                // the text approved is the text the Doc will hold.
                .filter(c -> c < 0xE000 || c > 0xF8FF)
                .filter(c -> Character.getType(c) != Character.SURROGATE && Character.getType(c) != Character.UNASSIGNED)
                .forEach(kept::appendCodePoint);
        String body = kept.toString().strip();
        return body.isEmpty() ? "" : "\n" + body;
    }

    /** What reading the Doc back says about an addition of {@code payload} to it. */
    public static Reading read(DocAppendPayload payload, GoogleDocContent doc) {
        if (payload.targetRevision().equals(doc.revisionId())) {
            return Reading.NOT_ADDED;
        }
        String text = doc.text();
        String added = payload.text() + "\n";
        if (!text.endsWith(added)) {
            return Reading.UNKNOWN;
        }
        // Before the addition the Doc ended in its last line break, which now follows the added text.
        String before = text.substring(0, text.length() - added.length()) + "\n";
        return before.length() == payload.targetTextLength() && sha256(before).equals(payload.targetTextSha256())
                ? Reading.ADDED
                : Reading.UNKNOWN;
    }

    public enum Reading {
        ADDED,
        NOT_ADDED,
        UNKNOWN
    }

    public static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is a JDK-guaranteed algorithm; this should be unreachable.", e);
        }
    }
}
