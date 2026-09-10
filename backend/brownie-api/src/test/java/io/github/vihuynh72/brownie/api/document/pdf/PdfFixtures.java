package io.github.vihuynh72.brownie.api.document.pdf;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Builds real, well-formed PDF byte arrays for tests -- every fixture is
 * written through {@link PDDocument#save} and reloaded by the extractor
 * itself, so tests see exactly the bytes a real upload would produce.
 */
final class PdfFixtures {

    private PdfFixtures() {
    }

    /** Two lines of ordinary text, top line first, on a single unrotated Letter page. */
    static byte[] twoLineDocument() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            PDFont font = helvetica();
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                writeLine(cs, font, 72, 720, "Meeting called to order at 6pm.");
                writeLine(cs, font, 72, 700, "Attendees: Jordan Lee, Priya Nair.");
            }
            return write(doc);
        }
    }

    /** The same two lines of text, but the page itself is marked rotated 90 degrees -- content stream coordinates are unchanged. */
    static byte[] rotatedTwoLineDocument(int rotationDegrees) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            page.setRotation(rotationDegrees);
            doc.addPage(page);
            PDFont font = helvetica();
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                writeLine(cs, font, 72, 720, "Meeting called to order at 6pm.");
                writeLine(cs, font, 72, 700, "Attendees: Jordan Lee, Priya Nair.");
            }
            return write(doc);
        }
    }

    /** Three pages, each with one distinguishable line of text, to prove per-page ordering and one-based page numbers. */
    static byte[] multiPageDocument(int pageCount) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDFont font = helvetica();
            for (int i = 1; i <= pageCount; i++) {
                PDPage page = new PDPage(PDRectangle.LETTER);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    writeLine(cs, font, 72, 720, "This is page " + i + ".");
                }
            }
            return write(doc);
        }
    }

    /** A single page with no text-showing operators at all -- the "scanned page" stand-in this test suite can actually construct. */
    static byte[] noTextPageDocument() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage(PDRectangle.LETTER));
            return write(doc);
        }
    }

    /** One text page followed by one image-only (no text) page, proving a per-page distinction rather than an all-or-nothing one. */
    static byte[] mixedTextAndNoTextPagesDocument() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage textPage = new PDPage(PDRectangle.LETTER);
            doc.addPage(textPage);
            try (PDPageContentStream cs = new PDPageContentStream(doc, textPage)) {
                writeLine(cs, helvetica(), 72, 720, "Cover text page.");
            }
            doc.addPage(new PDPage(PDRectangle.LETTER));
            return write(doc);
        }
    }

    /** Two words on one line with a real inter-word gap achieved via a second showText call rather than a literal space glyph, simulating TJ-array kerning-based spacing. */
    static byte[] gapSpacedWordsDocument() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            PDFont font = helvetica();
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(font, 12);
                cs.newLineAtOffset(72, 720);
                cs.showText("Quorum");
                cs.endText();
                // A second, separate showText positioned with a real gap after
                // "Quorum" but with no literal space character anywhere. The
                // gap (8pt, against this extractor's 0.25*fontSize=3pt word-
                // space threshold at 12pt) is deliberately well clear of that
                // boundary rather than sitting on it, so the test exercises
                // "clearly a word gap" and is not sensitive to small
                // floating-point differences between this computed offset and
                // PDFBox's own internal text-width arithmetic.
                cs.beginText();
                cs.setFont(font, 12);
                cs.newLineAtOffset(72 + font.getStringWidth("Quorum") / 1000f * 12f + 8f, 720);
                cs.showText("met");
                cs.endText();
            }
            return write(doc);
        }
    }

    /** Two short pieces of text placed side by side on the same visual line, wide enough apart to look like two columns. */
    static byte[] sideBySideColumnsDocument() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            PDFont font = helvetica();
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                writeLine(cs, font, 72, 720, "Left column text.");
                writeLine(cs, font, 400, 720, "Right column text.");
            }
            return write(doc);
        }
    }

    /**
     * Two separate rows, each with the same side-by-side-columns shape --
     * a borderless, table-like grid -- proving the ambiguous-reading-order
     * flag is scoped to the one geometric line it actually applies to,
     * not leaking across the whole page once detected on the first line.
     */
    static byte[] twoRowGridDocument() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            PDFont font = helvetica();
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                writeLine(cs, font, 72, 720, "Task");
                writeLine(cs, font, 400, 720, "Owner");
                writeLine(cs, font, 72, 700, "Draft agenda");
                writeLine(cs, font, 400, 700, "Jordan Lee");
            }
            return write(doc);
        }
    }

    static byte[] encryptedDocument() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                writeLine(cs, helvetica(), 72, 720, "Secret minutes.");
            }
            AccessPermission ap = new AccessPermission();
            StandardProtectionPolicy spp = new StandardProtectionPolicy("owner-pw", "user-pw", ap);
            spp.setEncryptionKeyLength(128);
            doc.protect(spp);
            return write(doc);
        }
    }

    static byte[] corruptPdf() {
        return "%PDF-1.4\nthis is not a real PDF body".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }

    private static PDFont helvetica() {
        return new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    }

    private static void writeLine(PDPageContentStream cs, PDFont font, float x, float y, String text) throws IOException {
        cs.beginText();
        cs.setFont(font, 12);
        cs.newLineAtOffset(x, y);
        cs.showText(text);
        cs.endText();
    }

    private static byte[] write(PDDocument doc) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        doc.save(out);
        return out.toByteArray();
    }
}
