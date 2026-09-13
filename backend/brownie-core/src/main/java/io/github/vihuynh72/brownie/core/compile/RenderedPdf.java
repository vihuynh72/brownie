package io.github.vihuynh72.brownie.core.compile;

import java.util.Objects;

/**
 * One PDF rendered from an exact DOCX byte sequence. {@code extractedText}
 * is the renderer's own independent text extraction from the produced PDF
 * bytes -- not a copy of what the DOCX fill pass intended -- so a later
 * content-integrity check compares two independently observed texts rather
 * than trusting the writer's own claim about itself.
 */
public record RenderedPdf(byte[] pdfBytes, String rendererVersion, String extractedText) {

    public RenderedPdf {
        pdfBytes = pdfBytes.clone();
        if (rendererVersion == null || rendererVersion.isBlank()) {
            throw new IllegalArgumentException("rendererVersion must not be blank.");
        }
        Objects.requireNonNull(extractedText, "extractedText");
    }

    @Override
    public byte[] pdfBytes() {
        return pdfBytes.clone();
    }
}
