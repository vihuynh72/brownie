package io.github.vihuynh72.brownie.core.prepare;

import io.github.vihuynh72.brownie.core.template.FieldIds;
import io.github.vihuynh72.brownie.core.template.FieldType;
import io.github.vihuynh72.brownie.core.text.CodePoints;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The word rules behind a found place's name: which of the words around a
 * blank name it, how the instruction words a form writes around its labels
 * come off ("please write your name here:" names "Name"), when a place is
 * for a date, and when it is for a signature. Pure text rules, shared by
 * the finder and {@link RulesSpotNamer}.
 */
final class SpotLabels {

    /** Words a form puts before a label to ask for it, which are not part of the name. */
    private static final Set<String> LEADING_INSTRUCTIONS = Set.of(
            "please", "kindly", "write", "enter", "print", "insert", "provide", "your", "here");
    private static final Set<String> TRAILING_INSTRUCTIONS = Set.of("here", "below", "above");
    private static final Set<String> DATE_WORDS = Set.of(
            "date", "dated", "dob", "birthday", "fecha", "datum", "ng\u00E0y");
    private static final Pattern SIGNATURE = Pattern.compile(
            "(?iu)\\b(signature|signatures|signed|sign here|sign|initials|initial here|firma|unterschrift|signatur)\\b");
    private static final Pattern CLOSING = Pattern.compile(
            "(?iu)\\b(sincerely|faithfully|regards|yours truly|best wishes|cordially|respectfully)\\W*$");
    private static final Pattern DATE_MASK_WORDS = Pattern.compile(
            "(?i)\\b(dd|mm)\\s*[/.\\-]\\s*(dd|mm)\\s*[/.\\-]\\s*(yy|yyyy)\\b|\\byyyy\\s*[/.\\-]\\s*mm\\s*[/.\\-]\\s*dd\\b");
    private static final Pattern REQUIRED_MARK = Pattern.compile("(?i)\\(\\s*(required|optional)\\s*\\)");
    private static final Pattern WORD_BREAK = Pattern.compile("[\\s\\u00A0]+");
    /** A place where a label stops when read backwards from a blank: a sentence's end, a tab, a line break. */
    private static final Pattern BOUNDARY = Pattern.compile("[.!?](?=\\s)|[\\t\\n]");

    /** The most words a label read from a paragraph may have; more is a sentence, not a name. */
    static final int MAX_LABEL_WORDS = 8;

    private SpotLabels() {
    }

    /**
     * A usable label from raw words, or null: required marks, stars and a
     * closing colon come off, then leading instruction words ("please",
     * "write", "your", "fill in") and trailing ones ("here"); what is left
     * must be a label {@link FieldIds#normalizeLabel} accepts, of at most
     * {@link #MAX_LABEL_WORDS} words. The first letter is capitalised.
     */
    static String clean(String raw) {
        if (raw == null) {
            return null;
        }
        String text = REQUIRED_MARK.matcher(raw).replaceAll(" ");
        text = text.replace('*', ' ').replace('\t', ' ').replace('\n', ' ').strip();
        text = stripTrailing(text, ":\uFF1A.,;-\u2013\u2014 ");
        text = stripOuter(text);
        List<String> words = new ArrayList<>(Arrays.asList(WORD_BREAK.split(text.strip())));
        words.removeIf(String::isEmpty);
        stripLeadingInstructions(words);
        while (!words.isEmpty() && TRAILING_INSTRUCTIONS.contains(lower(words.getLast()))) {
            words.removeLast();
        }
        if (words.isEmpty() || words.size() > MAX_LABEL_WORDS) {
            return null;
        }
        String label = FieldIds.normalizeLabel(stripTrailing(String.join(" ", words), ":\uFF1A.,;- "));
        if (label == null) {
            return null;
        }
        int first = label.codePointAt(0);
        return new StringBuilder().appendCodePoint(Character.toUpperCase(first)).append(label.substring(Character.charCount(first)))
                .toString();
    }

    /** Like {@link #clean}, but a text that is all instruction words is kept as it is rather than lost. */
    static String cleanKeepingInstructions(String raw) {
        String cleaned = clean(raw);
        if (cleaned != null) {
            return cleaned;
        }
        String normalized = raw == null ? null : FieldIds.normalizeLabel(stripTrailing(raw.strip(), ":\uFF1A. "));
        if (normalized == null || WORD_BREAK.split(normalized).length > MAX_LABEL_WORDS) {
            return null;
        }
        return normalized;
    }

    /**
     * The words just before a blank that name it: the text between
     * {@code from} and {@code to}, back to the last sentence end, tab or
     * line break in it. When the blank follows straight after a sentence
     * ("How did you hear about us? ____"), the sentence itself names it.
     */
    static String before(String text, int from, int to) {
        String segment = CodePoints.substring(text, from, to);
        String tail = lastSegment(segment);
        String cleaned = clean(tail);
        if (cleaned != null) {
            return cleaned;
        }
        if (tail.isBlank()) {
            String trimmed = segment.stripTrailing();
            if (!trimmed.isEmpty()) {
                String sentence = lastSegment(trimmed.substring(0, trimmed.length() - 1));
                return clean(sentence);
            }
        }
        return null;
    }

    /** The words just after a blank, up to the next sentence end, tab or line break, with brackets around them taken off. */
    static String after(String text, int from, int to) {
        String segment = CodePoints.substring(text, from, to);
        var matcher = BOUNDARY.matcher(segment);
        String head = matcher.find() ? segment.substring(0, matcher.start()) : segment;
        return clean(head);
    }

    /** Whether a label names a date: one of its words is a date word, or it spells a date's layout ("DD/MM/YYYY"). */
    static boolean namesADate(String label) {
        if (label == null) {
            return false;
        }
        if (DATE_MASK_WORDS.matcher(label).find()) {
            return true;
        }
        for (String word : label.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (DATE_WORDS.contains(word)) {
                return true;
            }
        }
        return false;
    }

    static FieldType typeFor(String label, boolean dateMask) {
        return dateMask || namesADate(label) ? FieldType.DATE : FieldType.TEXT;
    }

    /** Whether words around a blank ask for a signature or initials rather than something to type. */
    static boolean signatureLike(String words) {
        return words != null && SIGNATURE.matcher(words).find();
    }

    /** Whether a line closes a letter ("Yours sincerely,"), so an unnamed line under it is where the writer signs. */
    static boolean closesALetter(String line) {
        return line != null && CLOSING.matcher(line.strip()).find();
    }

    /**
     * A merge field's or form box's own name as words: "FirstName",
     * "first_name" and "first.name" all read "First name".
     */
    static String fromName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String spaced = name.strip()
                .replaceAll("(?<=\\p{Ll})(?=\\p{Lu})", " ")
                .replaceAll("(?<=\\p{L})(?=\\p{N})", " ")
                .replaceAll("[_.\\-]+", " ");
        String cleaned = clean(spaced.toLowerCase(Locale.ROOT));
        return cleaned != null ? cleaned : cleanKeepingInstructions(spaced);
    }

    private static String lastSegment(String segment) {
        var matcher = BOUNDARY.matcher(segment);
        int start = 0;
        while (matcher.find()) {
            start = matcher.end();
        }
        return segment.substring(start);
    }

    private static void stripLeadingInstructions(List<String> words) {
        while (!words.isEmpty()) {
            String first = lower(words.getFirst());
            if (first.equals("fill") && words.size() > 1 && lower(words.get(1)).equals("in")) {
                words.removeFirst();
                words.removeFirst();
            } else if (LEADING_INSTRUCTIONS.contains(first) && words.size() > 1) {
                words.removeFirst();
            } else {
                return;
            }
        }
    }

    /** Takes off one pair of brackets or quotes around the whole text. */
    private static String stripOuter(String text) {
        if (text.length() >= 2) {
            char open = text.charAt(0);
            char close = text.charAt(text.length() - 1);
            if ((open == '(' && close == ')') || (open == '"' && close == '"') || (open == '\u201C' && close == '\u201D')) {
                return text.substring(1, text.length() - 1).strip();
            }
        }
        return text;
    }

    private static String stripTrailing(String text, String characters) {
        int end = text.length();
        while (end > 0 && characters.indexOf(text.charAt(end - 1)) >= 0) {
            end--;
        }
        return text.substring(0, end);
    }

    private static String lower(String word) {
        return word.toLowerCase(Locale.ROOT);
    }
}
