package io.github.vihuynh72.brownie.core.batch;

import java.util.List;

/**
 * The template demands a repeated field -- one row of a spreadsheet is one
 * value, so a table cannot fill a list, and this template needs its
 * per-document flow instead.
 */
public class BatchTemplateNeedsListException extends RuntimeException {

    private final List<String> fieldIds;

    public BatchTemplateNeedsListException(List<String> fieldIds) {
        super("This template has required repeated fields a spreadsheet cannot fill: "
                + String.join(", ", fieldIds));
        this.fieldIds = List.copyOf(fieldIds);
    }

    public List<String> fieldIds() {
        return fieldIds;
    }
}
