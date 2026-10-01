package io.github.vihuynh72.brownie.core.document;

/**
 * One occurrence of an unsupported feature, in a form a user can act on:
 * {@code location} is a human-readable description of where it was found
 * (for example {@code "word/document.xml, paragraph 4"}), and {@code
 * detail} is a short, specific reason (for example the field's own
 * instruction text, or the tracked-change author).
 */
public record DocxFeatureFinding(UnsupportedDocxFeature feature, String location, String detail) {

    private static final String FIELD_DETAIL_PREFIX = "Field instruction: ";

    /** Long enough for any real field's keyword and switches; a field code is not otherwise worth storing whole. */
    private static final int MAX_INSTRUCTION_CHARACTERS = 200;

    /**
     * A finding about a field, whose detail carries the field's own
     * instruction (the same wording graph version 2 used), so the kind of
     * field can be read back from a stored report with {@link #fieldKeyword()}.
     */
    public static DocxFeatureFinding field(UnsupportedDocxFeature feature, String location, String instruction) {
        String trimmed = instruction == null ? "" : instruction.strip();
        if (trimmed.length() > MAX_INSTRUCTION_CHARACTERS) {
            int end = Character.isHighSurrogate(trimmed.charAt(MAX_INSTRUCTION_CHARACTERS - 1))
                    ? MAX_INSTRUCTION_CHARACTERS - 1
                    : MAX_INSTRUCTION_CHARACTERS;
            trimmed = trimmed.substring(0, end) + "...";
        }
        return new DocxFeatureFinding(feature, location, FIELD_DETAIL_PREFIX + trimmed);
    }

    /** The field's keyword ({@link FieldInstructionPolicy#keyword}) for a finding about a field, otherwise null. */
    public String fieldKeyword() {
        if (detail == null || !detail.startsWith(FIELD_DETAIL_PREFIX)) {
            return null;
        }
        return FieldInstructionPolicy.keyword(detail.substring(FIELD_DETAIL_PREFIX.length()));
    }
}
