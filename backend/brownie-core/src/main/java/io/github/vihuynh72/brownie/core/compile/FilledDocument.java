package io.github.vihuynh72.brownie.core.compile;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A template's original bytes with approved field values bound into it.
 * {@code intendedText} names, per field ID, the exact text this fill pass
 * meant to write -- an empty list for a repeated field with no items, one
 * entry for a scalar. {@code reopenedBodyText} is not read from the fill
 * pass's own in-memory state; it is the complete visible text of {@code
 * docxBytes} as read back by re-parsing the produced bytes a second time,
 * the independent verification this plan's own export contract requires
 * (re-open the generated document, not trust what a writer meant to do).
 */
public record FilledDocument(byte[] docxBytes, Map<String, List<String>> intendedText, String reopenedBodyText) {

    public FilledDocument {
        docxBytes = docxBytes.clone();
        Map<String, List<String>> copied = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : intendedText.entrySet()) {
            copied.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        intendedText = Map.copyOf(copied);
        Objects.requireNonNull(reopenedBodyText, "reopenedBodyText");
    }

    @Override
    public byte[] docxBytes() {
        return docxBytes.clone();
    }
}
