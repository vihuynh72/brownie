package io.github.vihuynh72.brownie.core.assist;

import io.github.vihuynh72.brownie.core.template.FieldDefinition;
import io.github.vihuynh72.brownie.core.template.FieldIds;
import io.github.vihuynh72.brownie.core.template.FieldType;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a line typed into the Assist composer into an {@link
 * AssistCommand}, deterministically and without a model: a fixed set of
 * verb patterns, and field names matched against the template's own
 * fields by id or by the same label the workspace shows ("meeting.title"
 * is "Meeting title"). Anything that fits none of the patterns, or names
 * a field the template does not have, is {@link AssistCommand.Unrecognized}.
 */
public final class AssistCommandParser {

    private static final Pattern DRAFT = Pattern.compile(
            "^(?:please\\s+)?(?:draft|fill|generate|extract|start)\\b(?:.*\\b(?:source|sources|transcript|notes|attached)\\b.*|\\s*(?:it|this|the document|everything)?)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CHANGE = Pattern.compile(
            "^(?:please\\s+)?(?:change|set|update|replace|make)\\s+(?:the\\s+)?(?<field>.+?)\\s+(?:to|=)\\s+(?<value>.+)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ASSIGN = Pattern.compile("^(?<field>[^:=]{2,60}?)\\s*[:=]\\s*(?<value>.+)$");
    private static final Pattern SHORTEN = Pattern.compile(
            "^(?:please\\s+)?(?:shorten|condense|tighten|trim)\\s+(?:the\\s+)?(?<field>.+?)(?:\\s+(?:section|field|text))?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern MAKE_SHORTER = Pattern.compile(
            "^(?:please\\s+)?make\\s+(?:the\\s+)?(?<field>.+?)(?:\\s+(?:section|field|text))?\\s+(?:shorter|more concise|briefer)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern REWRITE = Pattern.compile(
            "^(?:please\\s+)?(?:rewrite|reword|rephrase|improve|polish)\\s+(?:the\\s+)?(?<field>.+?)(?:\\s+(?:section|field|text))?(?:\\s+(?:to|so that|so|as|in)\\s+(?<instruction>.+))?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern EXPLAIN = Pattern.compile(
            "^(?:please\\s+)?(?:explain|why)\\b\\s*(?<rest>.*)$", Pattern.CASE_INSENSITIVE);

    /** Words quoted with straight or curly double quotes; the second group is the curly form. */
    private static final String QUOTE = "(?:\"([^\"]{1,200})\"|\u201C([^\u201D]{1,200})\u201D)";
    private static final Pattern QUOTED = Pattern.compile(QUOTE);
    private static final String SPOT_WORD = "(?:fill[\\s-]?in\\s+spot|fill[\\s-]?spot|spot|field|blank)";
    private static final String ADD_VERB = "^(?:please\\s+)?(?:add|put|insert|place|create|make)\\s+(?:a\\s+|an\\s+|the\\s+)?(?:new\\s+)?";
    private static final String NEXT_TO = "right\\s+after|after|next\\s+to|beside|in\\s+place\\s+of|instead\\s+of|replacing|over|at\\s+the\\s+end\\s+of";
    private static final Pattern ADD_HERE = Pattern.compile(
            ADD_VERB + SPOT_WORD + "(?:\\s+(?:for|called|named)\\s+(?<label>.+?))?\\s+(?:right\\s+)?(?:here|on\\s+this\\s+line|in\\s+this\\s+line|at\\s+this\\s+place)"
                    + "(?:\\s+(?:for|called|named)\\s+(?<labelAfter>.+))?$",
            Pattern.CASE_INSENSITIVE);
    /** "put the company name here": the thing to put is the spot's name, when it names a thing (see {@link #namesAThing}). */
    private static final Pattern PUT_THING_HERE = Pattern.compile(
            "^(?:please\\s+)?(?<verb>put|add|insert|place|write)\\s+(?<article>a\\s+|an\\s+|the\\s+)?(?<label>.+?)\\s+(?:right\\s+)?"
                    + "(?:here|on\\s+this\\s+line|in\\s+this\\s+line|at\\s+this\\s+place)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FILL_IN_HERE = Pattern.compile(
            "^(?:please\\s+)?fill\\s+in\\s+(?:here|this\\s+line)(?:\\s*[:,-]?\\s*(?:for|with|as|called)?\\s+(?<label>.+))?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern THIS_LINE = Pattern.compile(
            "^(?:this|the)\\s+line(?:\\s+(?:that\\s+)?says\\s+" + QUOTE + ")?\\s+is\\s+(?:for\\s+)?(?<label>.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ADD_AT = Pattern.compile(
            ADD_VERB + SPOT_WORD + "\\s+(?:for|called|named|labell?ed)\\s+(?<label>.+?)\\s+(?<prep>" + NEXT_TO + ")\\s+(?<where>.+)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ADD_ON_LINE = Pattern.compile(
            ADD_VERB + SPOT_WORD + "\\s+(?:for|called|named|labell?ed)\\s+(?<label>.+?)\\s+(?:on|in)\\s+(?<where>(?:the\\s+)?line\\b.*)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ADD_THING_AT = Pattern.compile(
            "^(?:please\\s+)?(?<verb>add|put|insert|place)\\s+(?<article>a\\s+|an\\s+|the\\s+)?(?<label>.+?)\\s+(?<prep>" + NEXT_TO + ")\\s+(?<where>.+)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ADD_ANYWHERE = Pattern.compile(
            ADD_VERB + SPOT_WORD + "\\s+(?:for|called|named|labell?ed)\\s+(?<label>.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern RENAME = Pattern.compile(
            "^(?:please\\s+)?rename\\s+(?:the\\s+)?(?:" + SPOT_WORD + "\\s+)?(?<field>.+?)\\s+(?:to|as)\\s+(?<label>.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern REMOVE_SPOT_NAMED = Pattern.compile(
            "^(?:please\\s+)?(?:remove|delete|take\\s+(?:away|out))\\s+(?:the\\s+)?" + SPOT_WORD + "\\s+(?:for\\s+|called\\s+|named\\s+)?(?<field>.+)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern REMOVE_NAMED_SPOT = Pattern.compile(
            "^(?:please\\s+)?(?:remove|delete|take\\s+(?:away|out))\\s+(?:the\\s+)?(?<field>.+?)\\s+" + SPOT_WORD + "$", Pattern.CASE_INSENSITIVE);
    private static final Pattern HERE_WORDS = Pattern.compile(
            "^(?:here|(?:this|the\\s+selected|the\\s+current)\\s+(?:line|place|spot))$", Pattern.CASE_INSENSITIVE);
    /** A quote that is itself a place to write: underscores, dots, an ellipsis, or one bracketed prompt. */
    private static final Pattern BLANK_QUOTE = Pattern.compile("^(?:[_\\s]{3,}|[.\\s]{4,}|[\\u2026\\s]+|\\[[^\\[\\]]{1,80}])$");
    private static final Pattern DATE_WORDS = Pattern.compile("\\b(?:date|birthday|dob)\\b", Pattern.CASE_INSENSITIVE);

    private AssistCommandParser() {
    }

    public static AssistCommand parse(String text, List<FieldDefinition> fields) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(fields, "fields");
        String line = text.strip().replaceAll("\\s+", " ");
        if (line.endsWith(".")) {
            line = line.substring(0, line.length() - 1).strip();
        }
        if (line.isEmpty()) {
            return new AssistCommand.Unrecognized(text);
        }

        AssistCommand fillSpot = parseFillSpot(line, fields);
        if (fillSpot != null) {
            return fillSpot.equals(UNRESOLVED) ? new AssistCommand.Unrecognized(text) : fillSpot;
        }
        if (DRAFT.matcher(line).matches()) {
            return new AssistCommand.DraftFromSources();
        }
        Matcher matcher = SHORTEN.matcher(line);
        if (matcher.matches()) {
            return rewriteOrUnrecognized(text, matcher.group("field"), AssistCommand.RewriteMode.SHORTEN, null, fields);
        }
        matcher = MAKE_SHORTER.matcher(line);
        if (matcher.matches()) {
            return rewriteOrUnrecognized(text, matcher.group("field"), AssistCommand.RewriteMode.SHORTEN, null, fields);
        }
        matcher = REWRITE.matcher(line);
        if (matcher.matches()) {
            return rewriteOrUnrecognized(text, matcher.group("field"), AssistCommand.RewriteMode.REWRITE, matcher.group("instruction"), fields);
        }
        matcher = CHANGE.matcher(line);
        if (matcher.matches()) {
            String fieldId = resolveField(matcher.group("field"), fields);
            if (fieldId != null) {
                return new AssistCommand.ChangeField(fieldId, unquote(matcher.group("value")));
            }
        }
        matcher = EXPLAIN.matcher(line);
        if (matcher.matches()) {
            String rest = matcher.group("rest").strip();
            String fieldId = rest.isEmpty() ? null : resolveFieldWithin(rest, fields);
            return new AssistCommand.ExplainFinding(fieldId);
        }
        matcher = ASSIGN.matcher(line);
        if (matcher.matches()) {
            String fieldId = resolveField(matcher.group("field"), fields);
            if (fieldId != null) {
                return new AssistCommand.ChangeField(fieldId, unquote(matcher.group("value")));
            }
        }
        return new AssistCommand.Unrecognized(text);
    }

    /** Stands for a fill spot request that names a spot this form does not have. */
    private static final AssistCommand UNRESOLVED = new AssistCommand.Unrecognized("");

    /**
     * A request to add, rename or take away a fill spot, or null when the
     * line is not one. Only a request that names the spot kind of thing
     * ("fill spot", "spot", "field", "blank") takes one away, so "remove the
     * title" is never read as taking a spot away when it might mean clearing
     * a value.
     */
    private static AssistCommand parseFillSpot(String line, List<FieldDefinition> fields) {
        Matcher matcher = RENAME.matcher(line);
        if (matcher.matches()) {
            String fieldId = resolveField(withoutSpotWord(matcher.group("field")), fields);
            String label = cleanLabel(matcher.group("label"));
            return fieldId == null || label == null ? UNRESOLVED : new AssistCommand.RenameFillSpot(fieldId, label);
        }
        for (Pattern remove : List.of(REMOVE_SPOT_NAMED, REMOVE_NAMED_SPOT)) {
            matcher = remove.matcher(line);
            if (matcher.matches()) {
                String fieldId = resolveField(withoutSpotWord(matcher.group("field")), fields);
                return fieldId == null ? UNRESOLVED : new AssistCommand.RemoveFillSpot(fieldId);
            }
        }
        matcher = ADD_HERE.matcher(line);
        if (matcher.matches()) {
            String label = matcher.group("label") != null ? matcher.group("label") : matcher.group("labelAfter");
            return add(cleanLabel(label), null, AssistCommand.SpotPlacement.UNSPECIFIED, true);
        }
        matcher = FILL_IN_HERE.matcher(line);
        if (matcher.matches()) {
            return add(cleanLabel(matcher.group("label")), null, AssistCommand.SpotPlacement.UNSPECIFIED, true);
        }
        matcher = PUT_THING_HERE.matcher(line);
        if (matcher.matches() && namesAThing(matcher)) {
            return add(cleanLabel(matcher.group("label")), null, AssistCommand.SpotPlacement.UNSPECIFIED, true);
        }
        matcher = THIS_LINE.matcher(line);
        if (matcher.matches()) {
            String quoted = firstNonNull(matcher.group(1), matcher.group(2));
            return add(cleanLabel(matcher.group("label")), quoted, AssistCommand.SpotPlacement.WHOLE_LINE, quoted == null);
        }
        matcher = ADD_ON_LINE.matcher(line);
        if (matcher.matches()) {
            return addAt(matcher.group("label"), "on", matcher.group("where"));
        }
        matcher = ADD_AT.matcher(line);
        if (matcher.matches()) {
            return addAt(matcher.group("label"), matcher.group("prep"), matcher.group("where"));
        }
        matcher = ADD_THING_AT.matcher(line);
        if (matcher.matches() && namesAThing(matcher)) {
            return addAt(matcher.group("label"), matcher.group("prep"), matcher.group("where"));
        }
        matcher = ADD_ANYWHERE.matcher(line);
        if (matcher.matches()) {
            return add(cleanLabel(matcher.group("label")), null, AssistCommand.SpotPlacement.UNSPECIFIED, false);
        }
        return null;
    }

    /**
     * Whether the words to put somewhere, without the word "spot", name a
     * thing to fill in ("the company name", "a date") rather than a value to
     * write there ("Priya Rao", "12 March 2026", "N/A"). A value read as a
     * name would make a spot called after it, at once, in every new form.
     * Digits or an at sign make a value; so do words with no "the", "a" or
     * "an" after "write", and two or more words that each start with a
     * capital with none of those, which is how a name is written. Saying
     * "add a fill spot for ..." names a spot whatever the words are.
     */
    private static boolean namesAThing(Matcher matcher) {
        String label = matcher.group("label").strip();
        if (label.codePoints().anyMatch(c -> Character.isDigit(c) || c == '@')) {
            return false;
        }
        if (matcher.group("article") != null) {
            return true;
        }
        if (matcher.group("verb").equalsIgnoreCase("write")) {
            return false;
        }
        String[] words = label.split("\\s+");
        return words.length < 2 || !Arrays.stream(words).allMatch(word -> Character.isUpperCase(word.codePointAt(0)));
    }

    /** Where the words say the spot goes: by the first quoted words in them, or the selected place, or left to the model. */
    private static AssistCommand addAt(String rawLabel, String preposition, String where) {
        String label = cleanLabel(rawLabel);
        String place = where.strip();
        if (HERE_WORDS.matcher(place).matches()) {
            return add(label, null, AssistCommand.SpotPlacement.UNSPECIFIED, true);
        }
        Matcher quote = QUOTED.matcher(place);
        String quoted = quote.find() ? firstNonNull(quote.group(1), quote.group(2)) : null;
        String prep = preposition.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        AssistCommand.SpotPlacement placement = switch (prep) {
            case "in place of", "instead of", "replacing", "over" -> AssistCommand.SpotPlacement.REPLACE;
            case "at the end of" -> AssistCommand.SpotPlacement.WHOLE_LINE;
            case "on", "in" -> AssistCommand.SpotPlacement.IN_LINE;
            default -> AssistCommand.SpotPlacement.AFTER;
        };
        if (quoted == null) {
            return add(label, null, AssistCommand.SpotPlacement.UNSPECIFIED, false);
        }
        if (BLANK_QUOTE.matcher(quoted).matches() && placement == AssistCommand.SpotPlacement.AFTER) {
            placement = AssistCommand.SpotPlacement.REPLACE;
        }
        return add(label, quoted, placement, false);
    }

    private static AssistCommand add(String label, String quoted, AssistCommand.SpotPlacement placement, boolean here) {
        FieldType type = label != null && DATE_WORDS.matcher(label).find() ? FieldType.DATE : FieldType.TEXT;
        return new AssistCommand.AddFillSpot(label, quoted, placement, here, type);
    }

    /**
     * A spot's name as a person would see it: quotes, a leading "the"/"a"
     * and a trailing "fill spot" or "field" come off, and the first letter is
     * a capital ("company name" is "Company name"). Null when nothing usable
     * is left.
     */
    static String cleanLabel(String raw) {
        if (raw == null) {
            return null;
        }
        String label = unquote(raw)
                .replaceFirst("(?i)^(?:the|a|an)\\s+", "")
                .replaceFirst("(?i)\\s+" + SPOT_WORD + "$", "")
                .strip();
        label = FieldIds.normalizeLabel(label);
        if (label == null) {
            return null;
        }
        int first = label.codePointAt(0);
        return Character.isLowerCase(first)
                ? new StringBuilder().appendCodePoint(Character.toUpperCase(first)).append(label.substring(Character.charCount(first))).toString()
                : label;
    }

    private static String withoutSpotWord(String fieldText) {
        return fieldText.strip().replaceFirst("(?i)\\s+" + SPOT_WORD + "$", "");
    }

    private static String firstNonNull(String first, String second) {
        return first != null ? first : second;
    }

    /** The label the workspace shows for a field id that has no stored label; see {@link FieldIds#labelFor}. */
    public static String labelFor(String fieldId) {
        return FieldIds.labelFor(fieldId);
    }

    private static AssistCommand rewriteOrUnrecognized(
            String original, String fieldText, AssistCommand.RewriteMode mode, String instruction, List<FieldDefinition> fields) {
        String fieldId = resolveField(fieldText, fields);
        if (fieldId == null) {
            return new AssistCommand.Unrecognized(original);
        }
        return new AssistCommand.RewriteField(fieldId, mode, instruction == null ? null : instruction.strip());
    }

    /**
     * The field the text names: an exact label match, else an exact id
     * match (ignoring case, quotes and a trailing "field"/"section"), or
     * else the one field whose label ends with the text ("decisions" for
     * "Meeting decisions") when exactly one does. Two candidates mean the
     * person has to say which, so that returns null. The label is the one
     * the workspace shows: the field's stored label ("Date of birth") when
     * it has one. Labels come first because an id keeps its value when its
     * spot is renamed: after "Full name" (full.name) is renamed "Name" and a
     * new spot takes the name "Full name" (full.name.2), the words "Full
     * name" mean the new spot, whichever comes first in the form.
     */
    private static String resolveField(String fieldText, List<FieldDefinition> fields) {
        String wanted = normalize(unquote(fieldText)).replaceAll("\\s+(?:field|section)$", "");
        if (wanted.isEmpty()) {
            return null;
        }
        for (FieldDefinition field : fields) {
            if (normalize(field.displayLabel()).equals(wanted)) {
                return field.fieldId();
            }
        }
        for (FieldDefinition field : fields) {
            if (normalize(field.fieldId()).equals(wanted)) {
                return field.fieldId();
            }
        }
        String bySuffix = null;
        for (FieldDefinition field : fields) {
            if (normalize(field.displayLabel()).endsWith(" " + wanted)) {
                if (bySuffix != null) {
                    return null;
                }
                bySuffix = field.fieldId();
            }
        }
        return bySuffix;
    }

    /**
     * The longest field label that appears inside the text, else the longest
     * field id, or null -- for "explain the meeting date finding". Labels
     * come first, as in {@link #resolveField}.
     */
    private static String resolveFieldWithin(String text, List<FieldDefinition> fields) {
        String haystack = " " + normalize(text) + " ";
        List<FieldDefinition> byLongestLabel = new ArrayList<>(fields);
        byLongestLabel.sort(Comparator.comparingInt((FieldDefinition field) -> field.displayLabel().length()).reversed());
        for (FieldDefinition field : byLongestLabel) {
            if (haystack.contains(" " + normalize(field.displayLabel()) + " ")) {
                return field.fieldId();
            }
        }
        List<FieldDefinition> byLongestId = new ArrayList<>(fields);
        byLongestId.sort(Comparator.comparingInt((FieldDefinition field) -> field.fieldId().length()).reversed());
        for (FieldDefinition field : byLongestId) {
            if (haystack.contains(" " + normalize(field.fieldId()) + " ")) {
                return field.fieldId();
            }
        }
        return null;
    }

    /** Composed first, so a label typed with separate accent marks still matches the same label stored composed. */
    private static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFC).strip().toLowerCase(Locale.ROOT).replaceAll("[._-]+", " ").replaceAll("\\s+", " ");
    }

    private static String unquote(String value) {
        String trimmed = value.strip();
        if (trimmed.length() >= 2) {
            char first = trimmed.charAt(0);
            char last = trimmed.charAt(trimmed.length() - 1);
            boolean quoted = (first == '"' && last == '"') || (first == '\'' && last == '\'')
                    || (first == '“' && last == '”') || (first == '‘' && last == '’');
            if (quoted) {
                return trimmed.substring(1, trimmed.length() - 1).strip();
            }
        }
        return trimmed;
    }
}
