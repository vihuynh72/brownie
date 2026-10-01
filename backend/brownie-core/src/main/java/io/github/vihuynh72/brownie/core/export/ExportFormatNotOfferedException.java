package io.github.vihuynh72.brownie.core.export;

/**
 * The format asked for is not one this document can be exported in: a PDF
 * form is filled and exported as a PDF only, so a Word file (alone or with
 * the PDF) is never offered for it.
 */
public class ExportFormatNotOfferedException extends RuntimeException {

    private final ExportFormat format;

    public ExportFormatNotOfferedException(ExportFormat format) {
        super("This is a PDF form, so Brownie fills it and exports it as a PDF. It does not turn it into a Word file.");
        this.format = format;
    }

    public ExportFormat format() {
        return format;
    }
}
