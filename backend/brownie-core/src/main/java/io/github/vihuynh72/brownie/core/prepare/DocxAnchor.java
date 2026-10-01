package io.github.vihuynh72.brownie.core.prepare;

import io.github.vihuynh72.brownie.core.document.DocumentPartKind;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * A place in one paragraph of a Word file. {@code paragraphNodeId} is the
 * paragraph's node id in the structural graph of {@code parserVersion}
 * (for example {@code p3}, or {@code tbl1/row2/cell0/p0}); {@code start} and
 * {@code end} count Unicode code points into the paragraph's anchor text,
 * which is the text of its direct runs as the graph reads them (a control's
 * own text is not part of it). {@code anchorTextHash} is {@link #hashOf} of
 * that text when the place was chosen, so a place chosen on a page that has
 * since changed is refused rather than landing somewhere else.
 *
 * <p>{@link AnchorPlacement#AT} is a point ({@code start == end});
 * {@link AnchorPlacement#REPLACE} makes the text between {@code start} and
 * {@code end} the spot's contents (a line of underscores, a bracketed
 * prompt); {@link AnchorPlacement#WHOLE_LINE} replaces the whole line when
 * it holds only a blank, and otherwise adds the spot at its end;
 * {@link AnchorPlacement#EXISTING_CONTROL} makes the control at
 * {@code controlNodeId} the spot.
 */
public record DocxAnchor(
        DocumentPartKind part,
        String paragraphNodeId,
        AnchorPlacement placement,
        int start,
        int end,
        String anchorTextHash,
        String parserVersion,
        String controlNodeId) {

    public DocxAnchor {
        Objects.requireNonNull(part, "part");
        Objects.requireNonNull(placement, "placement");
        if (placement == AnchorPlacement.EXISTING_CONTROL) {
            Objects.requireNonNull(controlNodeId, "controlNodeId");
        } else {
            Objects.requireNonNull(paragraphNodeId, "paragraphNodeId");
            if (start < 0 || end < start) {
                throw new IllegalArgumentException("A place needs 0 <= start <= end.");
            }
        }
    }

    /** The first 16 hex digits of the SHA-256 of {@code anchorText} in UTF-8. */
    public static String hashOf(String anchorText) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(anchorText.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available.", e);
        }
    }
}
