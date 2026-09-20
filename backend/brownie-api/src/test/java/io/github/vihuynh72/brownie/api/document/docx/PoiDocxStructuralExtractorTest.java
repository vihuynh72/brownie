package io.github.vihuynh72.brownie.api.document.docx;

import io.github.vihuynh72.brownie.core.document.DocumentPart;
import io.github.vihuynh72.brownie.core.document.DocumentPartKind;
import io.github.vihuynh72.brownie.core.document.DocxExtractionOutcome;
import io.github.vihuynh72.brownie.core.document.DocxParseException;
import io.github.vihuynh72.brownie.core.document.StructuralNode;
import io.github.vihuynh72.brownie.core.document.StructuralNodeKind;
import io.github.vihuynh72.brownie.core.document.UnsupportedDocxFeature;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PoiDocxStructuralExtractorTest {

    private final PoiDocxStructuralExtractor extractor = new PoiDocxStructuralExtractor();

    @Test
    void parserVersionIsStableAndNonBlank() {
        assertEquals(PoiDocxStructuralExtractor.PARSER_VERSION, extractor.parserVersion());
        assertFalse(extractor.parserVersion().isBlank());
    }

    @Test
    void qualifiedDocumentExtractsAsSupportedWithExpectedStructure() throws IOException {
        DocxExtractionOutcome outcome = extract(DocxFixtures.qualifiedDocument());
        assertTrue(outcome instanceof DocxExtractionOutcome.Supported, "expected a supported outcome, got " + outcome);
        var graph = ((DocxExtractionOutcome.Supported) outcome).graph();
        assertEquals(PoiDocxStructuralExtractor.PARSER_VERSION, graph.parserVersion());

        DocumentPart main = partOfKind(graph.parts(), DocumentPartKind.MAIN_DOCUMENT);
        assertEquals("word/document.xml", main.partName());
        DocumentPart header = partOfKind(graph.parts(), DocumentPartKind.HEADER);
        assertEquals("word/header1.xml", header.partName());
        DocumentPart footer = partOfKind(graph.parts(), DocumentPartKind.FOOTER);
        assertEquals("word/footer1.xml", footer.partName());

        StructuralNode headingParagraph = main.root().children().get(0);
        assertEquals(StructuralNodeKind.PARAGRAPH, headingParagraph.kind());
        // "Heading1" is basedOn "Normal" and only sets bold/size directly -- its font family
        // must resolve all the way up to docDefaults, proving the basedOn chain is walked.
        StructuralNode headingRun = headingParagraph.children().get(0);
        assertEquals("Liberation Sans", headingRun.style().fontFamily());
        assertEquals(Boolean.TRUE, headingRun.style().bold());
        assertEquals(32, headingRun.style().fontSizeHalfPoints());

        StructuralNode fieldParagraph = main.root().children().get(1);
        StructuralNode contentControl = fieldParagraph.children().get(1);
        assertEquals(StructuralNodeKind.CONTENT_CONTROL, contentControl.kind());
        assertEquals("meeting.title", contentControl.contentControlTag());
        assertEquals("[meeting title]", contentControl.children().get(0).text());

        StructuralNode numberedParagraph = main.root().children().get(2);
        assertEquals(1, numberedParagraph.style().numberingId());
        assertEquals(0, numberedParagraph.style().numberingLevel());

        StructuralNode table = main.root().children().get(3);
        assertEquals(StructuralNodeKind.TABLE, table.kind());
        StructuralNode firstCell = table.children().get(0).children().get(0);
        assertEquals(StructuralNodeKind.TABLE_CELL, firstCell.kind());

        StructuralNode imageParagraph = main.root().children().get(4);
        StructuralNode image = imageParagraph.children().get(0);
        assertEquals(StructuralNodeKind.IMAGE, image.kind());
        assertNotNull(image.imageRelationshipId());
    }

    /**
     * A package may declare its main document under any name, and the upload inspector reads only parts named as
     * XML. Such a file reaches the library unread; nested this deeply it exhausts the library's stack, which must
     * come out as an ordinary failure to parse and not as an error nothing records.
     */
    @Test
    void aDocumentNestedDeeplyEnoughToExhaustTheLibraryIsAFailureToParseNotACrash() throws IOException {
        int depth = 20_000;
        // Each level carries something that does not compress, or the library's own check on how far an archive
        // expands refuses the file first, and it is the nesting that is being proved here.
        java.util.Random noise = new java.util.Random(1);
        StringBuilder opening = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            opening.append("<w:tbl><w:tr><w:tc w:rsidR=\"").append(Long.toHexString(noise.nextLong())).append("\">");
        }
        String body = opening + "</w:tc></w:tr></w:tbl>".repeat(depth);
        String document = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>" + body
                + "</w:body></w:document>";
        String contentTypes = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Override PartName=\"/word/document.bin\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                + "</Types>";
        String relationships = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\""
                + " Target=\"word/document.bin\"/></Relationships>";
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(bytes)) {
            for (String[] part : new String[][] {
                    {"[Content_Types].xml", contentTypes}, {"_rels/.rels", relationships}, {"word/document.bin", document}}) {
                zip.putNextEntry(new java.util.zip.ZipEntry(part[0]));
                zip.write(part[1].getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }

        // On a thread with a stack of a known, modest size, so that the outcome does not depend on the machine: the
        // default stack differs by operating system and processor, and on a large one this depth might just fit.
        java.util.concurrent.atomic.AtomicReference<Throwable> thrown = new java.util.concurrent.atomic.AtomicReference<>();
        Thread reader = new Thread(null, () -> {
            try {
                extractor.extract(new java.io.ByteArrayInputStream(bytes.toByteArray()));
            } catch (Throwable failure) {
                thrown.set(failure);
            }
        }, "small-stack-reader", 512 * 1024);
        reader.start();
        try {
            reader.join(60_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        io.github.vihuynh72.brownie.core.document.DocxParseException refused = org.junit.jupiter.api.Assertions.assertInstanceOf(
                io.github.vihuynh72.brownie.core.document.DocxParseException.class, thrown.get());
        // Not some other refusal that happens to come first: the stack really was exhausted, and that is what was caught.
        org.junit.jupiter.api.Assertions.assertInstanceOf(StackOverflowError.class, refused.getCause());
    }

    @Test
    void trackedChangeIsFlaggedUnsupported() throws IOException {
        assertUnsupported(DocxFixtures.withTrackedChange(), UnsupportedDocxFeature.TRACKED_CHANGES);
    }

    @Test
    void commentIsFlaggedUnsupported() throws IOException {
        assertUnsupported(DocxFixtures.withComment(), UnsupportedDocxFeature.UNRESOLVED_COMMENT);
    }

    @Test
    void floatingShapeIsFlaggedUnsupported() throws IOException {
        assertUnsupported(DocxFixtures.withFloatingShape(), UnsupportedDocxFeature.FLOATING_SHAPE);
    }

    @Test
    void nestedTableIsFlaggedUnsupported() throws IOException {
        assertUnsupported(DocxFixtures.withNestedTable(), UnsupportedDocxFeature.NESTED_TABLE);
    }

    @Test
    void linkedExternalImageIsFlaggedUnsupported() throws IOException {
        assertUnsupported(DocxFixtures.withLinkedExternalImage(), UnsupportedDocxFeature.LINKED_EXTERNAL_IMAGE);
    }

    @Test
    void embeddedObjectIsFlaggedUnsupported() throws IOException {
        assertUnsupported(DocxFixtures.withEmbeddedObject(), UnsupportedDocxFeature.EMBEDDED_OBJECT);
    }

    @Test
    void nonPageFieldIsFlaggedUnsupported() throws IOException {
        assertUnsupported(DocxFixtures.withUnsupportedField(), UnsupportedDocxFeature.UNSUPPORTED_FIELD);
    }

    @Test
    void pageFieldIsNotFlagged() throws IOException {
        DocxExtractionOutcome outcome = extract(DocxFixtures.withPageField());
        assertTrue(outcome instanceof DocxExtractionOutcome.Supported, "expected supported, got " + outcome);
    }

    @Test
    void repeatedHeadingParagraphsGetDistinctCorrectlyAddressedNodesRatherThanColliding() throws IOException {
        var graph = supported(DocxFixtures.repeatedHeadingsDocument());
        var body = partOfKind(graph.parts(), DocumentPartKind.MAIN_DOCUMENT).root();

        StructuralNode firstHeading = body.children().get(0);
        StructuralNode firstBody = body.children().get(1);
        StructuralNode secondHeading = body.children().get(2);
        StructuralNode secondBody = body.children().get(3);

        // Identical heading text at two different, distinct node paths --
        // each keeps its own correct following content, proving they were
        // never merged or had their content swapped.
        assertEquals("Agenda", firstHeading.children().get(0).text());
        assertEquals("Agenda", secondHeading.children().get(0).text());
        assertEquals("p0", firstHeading.nodeId());
        assertEquals("p2", secondHeading.nodeId());
        assertEquals("Approve last meeting's minutes.", firstBody.children().get(0).text());
        assertEquals("Discuss the budget.", secondBody.children().get(0).text());
    }

    @Test
    void multiRowTableCellsAreDistinctlyAddressedWithCorrectText() throws IOException {
        var graph = supported(DocxFixtures.multiRowTableDocument());
        StructuralNode table = partOfKind(graph.parts(), DocumentPartKind.MAIN_DOCUMENT).root().children().get(0);
        assertEquals(StructuralNodeKind.TABLE, table.kind());
        assertEquals(3, table.children().size(), "expected three rows");

        String[][] expectedText = {{"Task", "Owner"}, {"Draft agenda", "Jordan Lee"}, {"Book the room", "Priya Nair"}};
        java.util.Set<String> nodeIds = new java.util.HashSet<>();
        for (int row = 0; row < 3; row++) {
            StructuralNode rowNode = table.children().get(row);
            assertEquals(StructuralNodeKind.TABLE_ROW, rowNode.kind());
            assertEquals(2, rowNode.children().size(), "expected two cells in row " + row);
            for (int cell = 0; cell < 2; cell++) {
                StructuralNode cellNode = rowNode.children().get(cell);
                assertEquals(StructuralNodeKind.TABLE_CELL, cellNode.kind());
                String cellText = cellNode.children().get(0).children().get(0).text();
                assertEquals(expectedText[row][cell], cellText, "row " + row + " cell " + cell);
                assertTrue(nodeIds.add(cellNode.nodeId()), "cell node id must be unique: " + cellNode.nodeId());
            }
        }
    }

    @Test
    void headerAndFooterTextContentIsCorrectlyExtractedNotJustTheirPartNames() throws IOException {
        var graph = supported(DocxFixtures.qualifiedDocument());
        StructuralNode headerBody = partOfKind(graph.parts(), DocumentPartKind.HEADER).root();
        StructuralNode footerBody = partOfKind(graph.parts(), DocumentPartKind.FOOTER).root();

        String headerText = headerBody.children().get(0).children().get(0).text();
        String footerText = footerBody.children().get(0).children().get(0).text();
        assertEquals("Brownie Meeting Minutes Template", headerText);
        assertEquals("Footer", footerText);
    }

    /**
     * A real, previously-latent bug: {@code CTColor.getVal()} returns
     * {@code Object} for its hex-color-or-"auto" union type, and its
     * actual runtime type is a raw {@code byte[]}; naively calling {@code
     * String.valueOf(val)} on that silently produced {@code
     * Object.toString()}'s own identity-hash-code text (for example
     * {@code "[B@42721fe"}), different on every independent extraction of
     * the identical color, rather than a real hex string. Never caught
     * before because nothing before the layout comparator
     * ever compared two independently extracted {@code ResolvedStyle}
     * values for equality. Asserts both the real hex value and that two
     * separately extracted runs sharing the same color compare equal --
     * the second assertion is what actually would have caught this bug.
     */
    @Test
    void colorHexResolvesToARealHexStringNotObjectToString() throws IOException {
        var graph = supported(DocxFixtures.documentWithTwoIdenticallyColoredRuns());
        StructuralNode paragraph = graph.parts().get(0).root().children().get(0);
        StructuralNode firstRun = paragraph.children().get(0);
        StructuralNode secondRun = paragraph.children().get(1);

        assertEquals("FF0000", firstRun.style().colorHex());
        assertEquals(firstRun.style(), secondRun.style());
    }

    @Test
    void corruptPackageThrowsDocxParseException() {
        assertThrows(DocxParseException.class, () -> extractor.extract(new ByteArrayInputStream(DocxFixtures.corruptPackage())));
    }

    private io.github.vihuynh72.brownie.core.document.DocxStructuralGraph supported(byte[] bytes) throws IOException {
        DocxExtractionOutcome outcome = extract(bytes);
        assertTrue(outcome instanceof DocxExtractionOutcome.Supported, "expected supported, got " + outcome);
        return ((DocxExtractionOutcome.Supported) outcome).graph();
    }

    private void assertUnsupported(byte[] bytes, UnsupportedDocxFeature expected) throws IOException {
        DocxExtractionOutcome outcome = extract(bytes);
        assertTrue(outcome instanceof DocxExtractionOutcome.Unsupported, "expected unsupported, got " + outcome);
        var report = ((DocxExtractionOutcome.Unsupported) outcome).featureReport();
        assertFalse(report.isSupported());
        assertTrue(
                report.findings().stream().anyMatch(f -> f.feature() == expected),
                "expected " + expected + " among " + report.findings());
    }

    private DocxExtractionOutcome extract(byte[] bytes) throws IOException {
        return extractor.extract(new ByteArrayInputStream(bytes));
    }

    private static DocumentPart partOfKind(List<DocumentPart> parts, DocumentPartKind kind) {
        return parts.stream()
                .filter(p -> p.kind() == kind)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no part of kind " + kind + " in " + parts));
    }
}
