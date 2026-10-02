package io.github.vihuynh72.brownie.core.prepare;

import io.github.vihuynh72.brownie.core.template.FieldType;

import java.util.List;
import java.util.Objects;

/**
 * A place {@link FillSpotCandidateFinder} found that may be meant for
 * filling in. {@code anchor} is where the editor makes it a spot (for a
 * control the form already has, the control itself). {@code keptTag} is set
 * only for a control whose tag is already a safe, unique field id: it is
 * kept exactly as it is and never edited. {@code blankText} is the form's
 * own blank the spot replaces, or null when there was none to keep.
 * {@code rowKey} names the table row it sits in ({@code T1R3}), or null.
 * {@code context} is what the form itself says about the place (a control's
 * title, a form field's help text, a table cell's column and row), or null.
 * {@code tableValues} is the text printed in the filled-in cells of the
 * place's table, which never names it ({@link NamingCandidate#tableValues()}).
 */
public record SpotCandidate(
        String id,
        Kind kind,
        DocxAnchor anchor,
        String keptTag,
        String blankText,
        String rulesLabel,
        FieldType rulesType,
        Tier tier,
        String rowKey,
        boolean signatureLike,
        String context,
        String paragraphKey,
        List<String> tableValues) {

    /** What the finder saw. */
    public enum Kind {
        EXISTING_TAGGED_CONTROL,
        EXISTING_UNTAGGED_CONTROL,
        FORM_FIELD,
        UNDERSCORES,
        UNDERLINED_BLANK,
        TAB_LEADER,
        DOT_LEADER,
        BRACKET,
        LABEL_AT_END,
        EMPTY_CELL
    }

    /**
     * How sure the rules are that the place is meant for filling in. The
     * naming step may leave out a {@code MEDIUM} place but never a {@code
     * HIGH} one.
     */
    public enum Tier {
        HIGH,
        MEDIUM
    }

    public SpotCandidate {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(rulesLabel, "rulesLabel");
        Objects.requireNonNull(rulesType, "rulesType");
        Objects.requireNonNull(tier, "tier");
        Objects.requireNonNull(paragraphKey, "paragraphKey");
        tableValues = tableValues == null ? List.of() : List.copyOf(tableValues);
    }

    /** A place outside any table with values printed in it. */
    public SpotCandidate(String id, Kind kind, DocxAnchor anchor, String keptTag, String blankText, String rulesLabel,
                         FieldType rulesType, Tier tier, String rowKey, boolean signatureLike, String context, String paragraphKey) {
        this(id, kind, anchor, keptTag, blankText, rulesLabel, rulesType, tier, rowKey, signatureLike, context, paragraphKey, List.of());
    }

    /** A control the form already tagged with a usable field id; it is kept as it is and never renamed. */
    public boolean keptAsTagged() {
        return keptTag != null;
    }

    /** A control the form already had, kept or re-tagged; the form's author made it a place to fill. */
    public boolean formControl() {
        return kind == Kind.EXISTING_TAGGED_CONTROL;
    }
}
