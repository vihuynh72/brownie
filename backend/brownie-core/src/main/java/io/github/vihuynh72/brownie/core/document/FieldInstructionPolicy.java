package io.github.vihuynh72.brownie.core.document;

import java.util.Locale;
import java.util.Set;

/**
 * What a Word field is allowed to stay as, read from its instruction (the
 * field code, such as {@code PAGE} or {@code MERGEFIELD client.name}). Word
 * works a field out again whenever it likes, so a field that reaches
 * outside the file (another document, a database, a program) could change
 * what a filled copy says after Brownie has checked it, or fetch something
 * when it is opened. Those are frozen to the text they show. The fields Word
 * works out from the document itself (page numbers, dates, references,
 * tables of contents) stay live, and the fields a form uses for its blanks
 * stay until the blanks are turned into fill spots.
 *
 * <p>Anything not named here is frozen: a field this list does not know is
 * treated as one that might reach outside.
 */
public final class FieldInstructionPolicy {

    public enum Treatment {
        /** Word keeps it up to date from the document itself; it stays as it is. */
        KEEP,
        /** A form's own blank (a form text box, a merge field, a prompt); it stays until it becomes a fill spot. */
        TO_SPOT,
        /** A form checkbox. */
        CHECKBOX,
        /** It could reach outside the file, or it is not known; it is replaced by the text it shows. */
        FREEZE
    }

    private static final Set<String> KEEP = Set.of(
            "PAGE", "NUMPAGES", "SECTION", "SECTIONPAGES",
            "DATE", "TIME", "CREATEDATE", "SAVEDATE", "PRINTDATE",
            "TOC", "TC", "XE", "INDEX",
            "REF", "PAGEREF", "NOTEREF", "SEQ", "STYLEREF",
            "HYPERLINK", "SYMBOL", "EQ", "=", "IF", "QUOTE",
            "DOCPROPERTY", "NUMWORDS", "NUMCHARS", "LISTNUM");

    private static final Set<String> TO_SPOT = Set.of("FORMTEXT", "FORMDROPDOWN", "MERGEFIELD", "FILLIN", "ASK", "MACROBUTTON");

    private FieldInstructionPolicy() {
    }

    public static Treatment classify(String instruction) {
        String keyword = keyword(instruction);
        if (KEEP.contains(keyword) || keyword.startsWith("AUTONUM")) {
            return Treatment.KEEP;
        }
        if (TO_SPOT.contains(keyword)) {
            return Treatment.TO_SPOT;
        }
        if ("FORMCHECKBOX".equals(keyword)) {
            return Treatment.CHECKBOX;
        }
        return Treatment.FREEZE;
    }

    /**
     * The field's kind: the first word of its instruction in upper case, or
     * {@code =} for a formula. Empty when the instruction starts with
     * anything else, which no known field does.
     */
    public static String keyword(String instruction) {
        if (instruction == null) {
            return "";
        }
        String trimmed = instruction.strip();
        if (trimmed.startsWith("=")) {
            return "=";
        }
        int end = 0;
        while (end < trimmed.length() && isKeywordCharacter(trimmed.charAt(end))) {
            end++;
        }
        return trimmed.substring(0, end).toUpperCase(Locale.ROOT);
    }

    private static boolean isKeywordCharacter(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
    }
}
