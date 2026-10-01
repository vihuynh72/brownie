package io.github.vihuynh72.brownie.core.template;

import java.text.Normalizer;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The rules for a field's two names: the stable ID everything addresses a
 * field by, and the label a person reads. An ID made here from a label is
 * always plain ASCII that {@link #isSafeId} accepts, because an ID is
 * inlined into a model's JSON schema and into a Word control's tag, while
 * a label can be in any language and is only ever shown, or handed to a
 * model as quoted data.
 */
public final class FieldIds {

    /** The longest ID {@link #fromLabel} makes: long enough to read, short enough for a Word tag and a URL. */
    public static final int MAX_ID_LENGTH = 48;
    /** The longest label, in characters, {@link #normalizeLabel} keeps. */
    public static final int MAX_LABEL_LENGTH = 60;
    /** The longest blank, in characters, {@link #isValidBlankText} accepts. */
    public static final int MAX_BLANK_TEXT_LENGTH = 200;

    private static final Pattern SAFE_ID = Pattern.compile("[a-zA-Z][a-zA-Z0-9._-]*");
    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern NOT_ID_CHARACTERS = Pattern.compile("[^a-z0-9]+");
    private static final String FALLBACK_ID = "spot";
    private static final char SMALL_D_WITH_STROKE = '\u0111';
    private static final char CAPITAL_D_WITH_STROKE = '\u0110';
    private static final int ZERO_WIDTH_NON_JOINER = 0x200C;
    private static final int ZERO_WIDTH_JOINER = 0x200D;

    private FieldIds() {
    }

    /**
     * A new field ID for a label: accents come off ("H\u1ecd v\u00e0 t\u00ean"
     * becomes "ho.va.ten"), a d with a stroke becomes d, everything is
     * lower-cased, and each run of anything else becomes one dot. A label
     * with nothing left (one written only in CJK, say) gives {@code spot},
     * and one that starts with a digit is prefixed {@code spot.}, since an
     * ID must start with a letter. The result is at most {@link
     * #MAX_ID_LENGTH} characters.
     *
     * <p>{@code taken} must hold every ID any version of the template has
     * ever used, not only the current one's: an ID is never reused, or
     * going back to an old version could put an old, unrelated value into
     * a new spot. A taken ID gets {@code .2}, {@code .3}, ... added. The
     * comparison ignores case, because two IDs that differ only in case
     * would read as the same field to a person naming it in the chat.
     */
    public static String fromLabel(String label, Set<String> taken) {
        Objects.requireNonNull(taken, "taken");
        Set<String> takenIgnoringCase = new HashSet<>();
        for (String id : taken) {
            takenIgnoringCase.add(id.toLowerCase(Locale.ROOT));
        }
        String base = baseIdFor(label == null ? "" : label);
        if (!takenIgnoringCase.contains(base)) {
            return base;
        }
        for (int n = 2; ; n++) {
            String suffix = "." + n;
            String candidate = trimDots(truncate(base, MAX_ID_LENGTH - suffix.length())) + suffix;
            if (!takenIgnoringCase.contains(candidate)) {
                return candidate;
            }
        }
    }

    /**
     * A label as it is stored, or null when the text cannot be one. The
     * text is composed (NFC); tabs and every kind of space collapse to one
     * space and the ends are trimmed; invisible formatting characters
     * (zero-width spaces, direction overrides) are dropped, except the two
     * joiners some scripts spell words with. A label is one line of 1 to
     * {@link #MAX_LABEL_LENGTH} characters with at least one letter or
     * digit, so a line break, any other control character or a broken
     * character makes the whole text unusable rather than silently mended.
     */
    public static String normalizeLabel(String text) {
        if (text == null) {
            return null;
        }
        String composed = Normalizer.normalize(text, Normalizer.Form.NFC);
        StringBuilder label = new StringBuilder(composed.length());
        boolean spaceBefore = false;
        for (int i = 0; i < composed.length(); ) {
            int codePoint = composed.codePointAt(i);
            i += Character.charCount(codePoint);
            int kind = Character.getType(codePoint);
            if (isLineBreak(codePoint)) {
                return null;
            }
            if (codePoint == '\t' || kind != Character.CONTROL && (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint))) {
                spaceBefore = true;
                continue;
            }
            if (kind == Character.CONTROL || kind == Character.SURROGATE) {
                return null;
            }
            if (kind == Character.FORMAT && codePoint != ZERO_WIDTH_NON_JOINER && codePoint != ZERO_WIDTH_JOINER) {
                continue;
            }
            if (spaceBefore && !label.isEmpty()) {
                label.append(' ');
            }
            spaceBefore = false;
            label.appendCodePoint(codePoint);
        }
        String result = label.toString();
        int length = result.codePointCount(0, result.length());
        if (length < 1 || length > MAX_LABEL_LENGTH || result.codePoints().noneMatch(Character::isLetterOrDigit)) {
            return null;
        }
        return result;
    }

    /**
     * Whether text can be a field's blank: printed back into the document
     * exactly as given, so it must be one line of 1 to {@link
     * #MAX_BLANK_TEXT_LENGTH} characters with no control characters (a tab
     * included). Only spaces is fine: some forms' blanks are underlined
     * spaces.
     */
    public static boolean isValidBlankText(String text) {
        if (text == null || text.isEmpty() || text.codePointCount(0, text.length()) > MAX_BLANK_TEXT_LENGTH) {
            return false;
        }
        return text.codePoints().noneMatch(codePoint -> isLineBreak(codePoint)
                || Character.getType(codePoint) == Character.CONTROL
                || Character.getType(codePoint) == Character.SURROGATE);
    }

    /** The label worked out from a field ID when none is stored: separators become spaces and the first letter is capitalised ("meeting.title" is "Meeting title"). */
    public static String labelFor(String fieldId) {
        String words = fieldId.replaceAll("[._-]+", " ").strip();
        if (words.isEmpty()) {
            return fieldId;
        }
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    /** Whether an ID is plain enough to inline into a hand-built JSON schema or a Word tag: a letter, then letters, digits, dots, underscores and hyphens. */
    public static boolean isSafeId(String fieldId) {
        return fieldId != null && SAFE_ID.matcher(fieldId).matches();
    }

    private static String baseIdFor(String label) {
        String decomposed = Normalizer.normalize(label, Normalizer.Form.NFKD);
        String unaccented = COMBINING_MARKS.matcher(decomposed).replaceAll("")
                .replace(SMALL_D_WITH_STROKE, 'd')
                .replace(CAPITAL_D_WITH_STROKE, 'D');
        String id = trimDots(NOT_ID_CHARACTERS.matcher(unaccented.toLowerCase(Locale.ROOT)).replaceAll("."));
        if (id.isEmpty()) {
            return FALLBACK_ID;
        }
        if (Character.isDigit(id.charAt(0))) {
            id = FALLBACK_ID + "." + id;
        }
        return trimDots(truncate(id, MAX_ID_LENGTH));
    }

    private static String truncate(String text, int maxLength) {
        return text.length() > maxLength ? text.substring(0, maxLength) : text;
    }

    private static String trimDots(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && text.charAt(start) == '.') {
            start++;
        }
        while (end > start && text.charAt(end - 1) == '.') {
            end--;
        }
        return text.substring(start, end);
    }

    private static boolean isLineBreak(int codePoint) {
        return codePoint == '\n' || codePoint == '\r' || codePoint == 0x0B || codePoint == 0x0C
                || codePoint == 0x85 || codePoint == 0x2028 || codePoint == 0x2029;
    }
}
