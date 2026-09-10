package io.github.vihuynh72.brownie.core.document;

/**
 * Formatting actually in effect after walking Word's real inheritance chain
 * -- a run or paragraph's own direct formatting, then the paragraph style
 * it references (and that style's own {@code basedOn} ancestors), then the
 * document's style-part defaults -- not just whatever a single run or
 * paragraph happens to set directly. A null field means the chain never
 * set a value anywhere this extractor looked, not that the property is
 * "off": Word's own final fallback for an unset toggle property is off,
 * but that last, implicit step is not independently recorded here, so a
 * consumer should not treat null as false.
 *
 * <p>Toggle properties ({@code bold}/{@code italic}/{@code underline}) are
 * resolved by simple first-value-found precedence, not the OOXML
 * specification's toggle-XOR behavior across repeated style layers. That
 * is a deliberate, documented simplification for the qualified subset this
 * extractor supports, not a claim of complete OOXML style-resolution
 * fidelity.
 */
public record ResolvedStyle(
        Boolean bold,
        Boolean italic,
        Boolean underline,
        String fontFamily,
        Integer fontSizeHalfPoints,
        String colorHex,
        String alignment,
        Integer numberingId,
        Integer numberingLevel) {
}
