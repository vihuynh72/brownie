package io.github.vihuynh72.brownie.core.document;

import java.util.List;

/**
 * Checks a filled PDF against what was meant to be written, reading the
 * output back the way a person's reader would show it: each value written
 * to a field is stored and drawn, the text drawn inside each field and box
 * is the intended text, nothing this fill added lies anywhere else, and no
 * other field changed. Values the filler reported as not written are not
 * checked again. An empty list means the output shows exactly what was
 * asked.
 */
public interface PdfFillVerifier {

    List<PdfFillFinding> verify(byte[] sourcePdf, FilledPdf filled, PdfFillRequest request);
}
