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
    void corruptPackageThrowsDocxParseException() {
        assertThrows(DocxParseException.class, () -> extractor.extract(new ByteArrayInputStream(DocxFixtures.corruptPackage())));
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
