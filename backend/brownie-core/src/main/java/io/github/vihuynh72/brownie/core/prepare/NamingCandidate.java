package io.github.vihuynh72.brownie.core.prepare;

import io.github.vihuynh72.brownie.core.template.FieldType;

import java.util.List;
import java.util.Objects;

/**
 * A place Brownie's rules found that might be meant for filling in, with
 * the name and type the rules gave it. {@code kind} says what the rules saw
 * (a run of underscores, a bracketed prompt, a form field); {@code context}
 * is nearby text that helps name it when the outline alone does not (a
 * form field's tooltip, a table cell's column and row), and may be null.
 * {@code rowKey} is the table row the place sits in, or null.
 *
 * <p>{@code sure} says the rules are sure the place is meant for filling
 * in (a run of underscores, a bracketed prompt, a field the form made): the
 * naming step names it but may not leave it out. A weaker guess (a label
 * at the end of a line, a lone line or box) it may leave out. {@code
 * groupKey} names places kept or left out only together, such as the cells
 * of one table row or the boxes of one grid on a page, or is null. {@code
 * tableValues} is the text printed in the filled-in cells of the place's
 * table: a value of the table, which never names one of its places, the
 * way a course printed in one row does not name the year beside it.
 */
public record NamingCandidate(String id, String kind, String rulesLabel, FieldType rulesType, String context,
                              boolean signatureLike, String rowKey, boolean sure, String groupKey, List<String> tableValues) {

    public NamingCandidate {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(rulesLabel, "rulesLabel");
        Objects.requireNonNull(rulesType, "rulesType");
        tableValues = tableValues == null ? List.of() : List.copyOf(tableValues);
    }

    /** A guess the naming step may leave out, kept or left out on its own, in no table with values printed in it. */
    public NamingCandidate(String id, String kind, String rulesLabel, FieldType rulesType, String context, boolean signatureLike,
                           String rowKey) {
        this(id, kind, rulesLabel, rulesType, context, signatureLike, rowKey, false, null, List.of());
    }
}
