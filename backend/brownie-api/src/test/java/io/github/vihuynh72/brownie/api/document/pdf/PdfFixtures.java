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

    /**
     * One page whose content is a few kilobytes on disk and {@code expandedBytes} once inflated: comment lines,
     * which a content stream may hold any number of and which mean nothing. The shape of a decompression bomb,
     * at a size a test can afford.
     */
    static byte[] documentWhoseContentExpandsTo(int expandedBytes) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            org.apache.pdfbox.pdmodel.common.PDStream stream = new org.apache.pdfbox.pdmodel.common.PDStream(doc);
            byte[] block = "% nothing to see here, many times over\n".repeat(1024).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            try (java.io.OutputStream out = stream.createOutputStream(org.apache.pdfbox.cos.COSName.FLATE_DECODE)) {
                for (int written = 0; written < expandedBytes; written += block.length) {
                    out.write(block);
                }
            }
            page.setContents(stream);
            return write(doc);
        }
    }

    /**
     * One page that draws one form {@code times} times. The form is small on disk and {@code formExpandedBytes}
     * once expanded. With {@code inherited}, the page has no resources of its own and finds the form through its
     * parent in the page tree, which is where a reader that looks only at the page itself never looks.
     */
    static byte[] documentDrawingOneForm(int formExpandedBytes, int times, boolean inherited) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject form =
                    new org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject(doc);
            form.setBBox(new PDRectangle(10, 10));
            form.setResources(new org.apache.pdfbox.pdmodel.PDResources());
            writeExpandingTo(form.getCOSObject(), "", formExpandedBytes);

            org.apache.pdfbox.pdmodel.PDResources resources = new org.apache.pdfbox.pdmodel.PDResources();
            String name = resources.add(form).getName();
            if (inherited) {
                doc.getPages().getCOSObject().setItem(org.apache.pdfbox.cos.COSName.RESOURCES, resources);
                page.getCOSObject().removeItem(org.apache.pdfbox.cos.COSName.RESOURCES);
            } else {
                page.setResources(resources);
            }
            org.apache.pdfbox.pdmodel.common.PDStream contents = new org.apache.pdfbox.pdmodel.common.PDStream(doc);
            try (java.io.OutputStream out = contents.createOutputStream(org.apache.pdfbox.cos.COSName.FLATE_DECODE)) {
                out.write(("q /" + name + " Do Q\n").repeat(times).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            }
            page.setContents(contents);
            return write(doc);
        }
    }

    /**
     * One page that shows one letter in a font whose glyphs are drawn by the file itself, the procedure for that
     * letter being small on disk and {@code glyphExpandedBytes} once expanded.
     */
    static byte[] documentWhoseDrawnFontExpandsTo(int glyphExpandedBytes) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            org.apache.pdfbox.cos.COSStream glyph = doc.getDocument().createCOSStream();
            writeExpandingTo(glyph, "1000 0 d0\n", glyphExpandedBytes);
            org.apache.pdfbox.cos.COSDictionary glyphs = new org.apache.pdfbox.cos.COSDictionary();
            glyphs.setItem(org.apache.pdfbox.cos.COSName.getPDFName("a"), glyph);

            org.apache.pdfbox.cos.COSArray differences = new org.apache.pdfbox.cos.COSArray();
            differences.add(org.apache.pdfbox.cos.COSInteger.get(97));
            differences.add(org.apache.pdfbox.cos.COSName.getPDFName("a"));
            org.apache.pdfbox.cos.COSDictionary encoding = new org.apache.pdfbox.cos.COSDictionary();
            encoding.setItem(org.apache.pdfbox.cos.COSName.TYPE, org.apache.pdfbox.cos.COSName.ENCODING);
            encoding.setItem(org.apache.pdfbox.cos.COSName.DIFFERENCES, differences);

            org.apache.pdfbox.cos.COSArray matrix = new org.apache.pdfbox.cos.COSArray();
            for (float value : new float[] {0.001f, 0, 0, 0.001f, 0, 0}) {
                matrix.add(new org.apache.pdfbox.cos.COSFloat(value));
            }
            org.apache.pdfbox.cos.COSDictionary font = new org.apache.pdfbox.cos.COSDictionary();
            font.setItem(org.apache.pdfbox.cos.COSName.TYPE, org.apache.pdfbox.cos.COSName.FONT);
            font.setItem(org.apache.pdfbox.cos.COSName.SUBTYPE, org.apache.pdfbox.cos.COSName.TYPE3);
            font.setItem(org.apache.pdfbox.cos.COSName.FONT_BBOX, new PDRectangle(0, 0, 1000, 1000));
            font.setItem(org.apache.pdfbox.cos.COSName.FONT_MATRIX, matrix);
            font.setItem(org.apache.pdfbox.cos.COSName.CHAR_PROCS, glyphs);
            font.setItem(org.apache.pdfbox.cos.COSName.ENCODING, encoding);
            font.setInt(org.apache.pdfbox.cos.COSName.FIRST_CHAR, 97);
            font.setInt(org.apache.pdfbox.cos.COSName.LAST_CHAR, 97);

            org.apache.pdfbox.cos.COSDictionary fonts = new org.apache.pdfbox.cos.COSDictionary();
            fonts.setItem(org.apache.pdfbox.cos.COSName.getPDFName("T3"), font);
            org.apache.pdfbox.pdmodel.PDResources resources = new org.apache.pdfbox.pdmodel.PDResources();
            resources.getCOSObject().setItem(org.apache.pdfbox.cos.COSName.FONT, fonts);
            page.setResources(resources);

            org.apache.pdfbox.pdmodel.common.PDStream contents = new org.apache.pdfbox.pdmodel.common.PDStream(doc);
            try (java.io.OutputStream out = contents.createOutputStream(org.apache.pdfbox.cos.COSName.FLATE_DECODE)) {
                out.write("BT /T3 12 Tf 72 700 Td (a) Tj ET\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            }
            page.setContents(contents);
            return write(doc);
        }
    }

    /** A document of {@code realPages} pages whose page tree says it has {@code declaredPages}. */
    static byte[] documentDeclaringAPageCountItDoesNotHave(int realPages, int declaredPages) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDFont font = helvetica();
            for (int i = 1; i <= realPages; i++) {
                PDPage page = new PDPage(PDRectangle.LETTER);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    writeLine(cs, font, 72, 700, "Page " + i);
                }
            }
            doc.getPages().getCOSObject().setInt(org.apache.pdfbox.cos.COSName.COUNT, declaredPages);
            return write(doc);
        }
    }

    private static void writeExpandingTo(org.apache.pdfbox.cos.COSStream stream, String firstLine, int expandedBytes)
            throws IOException {
        byte[] block = "% nothing to see here, many times over\n".repeat(1024).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        try (java.io.OutputStream out = stream.createOutputStream(org.apache.pdfbox.cos.COSName.FLATE_DECODE)) {
            out.write(firstLine.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            for (int written = 0; written < expandedBytes; written += block.length) {
                out.write(block);
            }
        }
    }

    /** The same small file that expands far, with its content labelled a picture, which is the file's own claim and nothing more. */
    static byte[] documentWhoseContentCallsItselfAPicture(int expandedBytes) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            org.apache.pdfbox.pdmodel.common.PDStream stream = new org.apache.pdfbox.pdmodel.common.PDStream(doc);
            writeExpandingTo(stream.getCOSObject(), "", expandedBytes);
            stream.getCOSObject().setItem(org.apache.pdfbox.cos.COSName.SUBTYPE, org.apache.pdfbox.cos.COSName.IMAGE);
            page.setContents(stream);
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
