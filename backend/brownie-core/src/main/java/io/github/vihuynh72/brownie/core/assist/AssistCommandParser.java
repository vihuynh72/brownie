package io.github.vihuynh72.brownie.core.assist;

import io.github.vihuynh72.brownie.core.template.FieldDefinition;

import java.util.ArrayList;
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

    /** The label the workspace shows for a field id: separators become spaces and the first letter is capitalised. */
    public static String labelFor(String fieldId) {
        String words = fieldId.replaceAll("[._-]+", " ").strip();
        if (words.isEmpty()) {
            return fieldId;
        }
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
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
     * The field the text names: an exact id or label match (ignoring case,
     * quotes and a trailing "field"/"section"), or else the one field
     * whose label ends with the text ("decisions" for "Meeting decisions")
     * when exactly one does. Two candidates mean the person has to say
     * which, so that returns null.
     */
    private static String resolveField(String fieldText, List<FieldDefinition> fields) {
        String wanted = normalize(unquote(fieldText)).replaceAll("\\s+(?:field|section)$", "");
        if (wanted.isEmpty()) {
            return null;
        }
        for (FieldDefinition field : fields) {
            if (normalize(field.fieldId()).equals(wanted) || normalize(labelFor(field.fieldId())).equals(wanted)) {
                return field.fieldId();
            }
        }
        String bySuffix = null;
        for (FieldDefinition field : fields) {
            if (normalize(labelFor(field.fieldId())).endsWith(" " + wanted)) {
                if (bySuffix != null) {
                    return null;
                }
                bySuffix = field.fieldId();
            }
        }
        return bySuffix;
    }

    /** The longest field id or label that appears inside the text, or null -- for "explain the meeting date finding". */
    private static String resolveFieldWithin(String text, List<FieldDefinition> fields) {
        String haystack = " " + normalize(text) + " ";
        List<FieldDefinition> byLongestLabel = new ArrayList<>(fields);
        byLongestLabel.sort(Comparator.comparingInt((FieldDefinition field) -> labelFor(field.fieldId()).length()).reversed());
        for (FieldDefinition field : byLongestLabel) {
            String label = " " + normalize(labelFor(field.fieldId())) + " ";
            String id = " " + normalize(field.fieldId()) + " ";
            if (haystack.contains(label) || haystack.contains(id)) {
                return field.fieldId();
            }
        }
        return null;
    }

    private static String normalize(String text) {
        return text.strip().toLowerCase(Locale.ROOT).replaceAll("[._-]+", " ").replaceAll("\\s+", " ");
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
