package io.github.vihuynh72.brownie.core.template;

import io.github.vihuynh72.brownie.core.document.PdfRect;
import io.github.vihuynh72.brownie.core.document.PdfTextStyle;

/**
 * Where a box a person asked for at one place on a page should go, how its
 * text should look, and the label the words beside it suggest (null when
 * nothing does).
 */
public record PdfBoxSuggestion(PdfRect box, PdfTextStyle style, String labelGuess) {
}
