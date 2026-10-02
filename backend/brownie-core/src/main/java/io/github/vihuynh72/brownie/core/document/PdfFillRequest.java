package io.github.vihuynh72.brownie.core.document;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Every value to write into one PDF, each field id at most once. */
public record PdfFillRequest(List<PdfFillItem> items) {

    public PdfFillRequest {
        items = List.copyOf(items);
        Set<String> seen = new HashSet<>();
        for (PdfFillItem item : items) {
            if (!seen.add(item.fieldId())) {
                throw new IllegalArgumentException("A fill request names field " + item.fieldId() + " more than once.");
            }
        }
    }
}
