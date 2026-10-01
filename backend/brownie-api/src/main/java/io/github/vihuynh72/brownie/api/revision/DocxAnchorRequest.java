package io.github.vihuynh72.brownie.api.revision;

import io.github.vihuynh72.brownie.core.document.DocumentPartKind;
import io.github.vihuynh72.brownie.core.prepare.AnchorPlacement;
import io.github.vihuynh72.brownie.core.prepare.DocxAnchor;

/**
 * A place on a Word form's page, as the browser sends it: the paragraph's
 * node id and anchor hash from the page layout, where in its anchor text
 * (in code points), and how the spot takes the place. See {@link DocxAnchor}
 * for what each part means. {@code start} and {@code end} may be left out
 * for an existing control.
 */
public record DocxAnchorRequest(
        String part,
        String paragraphNodeId,
        String placement,
        Integer start,
        Integer end,
        String anchorTextHash,
        String parserVersion,
        String controlNodeId) {

    private static final int MAX_TEXT = 200;

    /** Throws {@link DocumentRequestValidationException} for anything that is not a well-formed place. */
    public DocxAnchor toDomain() {
        DocumentPartKind partKind = enumValue(DocumentPartKind.class, part, "anchor.part");
        AnchorPlacement placementKind = enumValue(AnchorPlacement.class, placement, "anchor.placement");
        for (String text : new String[] {paragraphNodeId, anchorTextHash, parserVersion, controlNodeId}) {
            if (text != null && (text.isBlank() || text.length() > MAX_TEXT)) {
                throw new DocumentRequestValidationException("An anchor's text parts must be 1 to " + MAX_TEXT + " characters.");
            }
        }
        if (parserVersion == null) {
            throw new DocumentRequestValidationException("anchor.parserVersion is required.");
        }
        try {
            return new DocxAnchor(
                    partKind, paragraphNodeId, placementKind, start == null ? 0 : start, end == null ? 0 : end, anchorTextHash, parserVersion,
                    controlNodeId);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new DocumentRequestValidationException("The anchor is not a place on the page: " + e.getMessage());
        }
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, String field) {
        if (value == null) {
            throw new DocumentRequestValidationException(field + " is required.");
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            throw new DocumentRequestValidationException(field + " must be one of " + java.util.Arrays.toString(type.getEnumConstants()) + ".");
        }
    }
}
