package io.github.vihuynh72.brownie.core.source;

import io.github.vihuynh72.brownie.core.document.DocumentPart;
import io.github.vihuynh72.brownie.core.document.DocumentPartKind;
import io.github.vihuynh72.brownie.core.document.DocxStructuralGraph;
import io.github.vihuynh72.brownie.core.document.PdfPage;
import io.github.vihuynh72.brownie.core.document.PdfStructuralGraph;
import io.github.vihuynh72.brownie.core.document.PdfTextLine;
import io.github.vihuynh72.brownie.core.document.PlainTextStructuralGraph;
import io.github.vihuynh72.brownie.core.document.StructuralNode;
import io.github.vihuynh72.brownie.core.document.StructuralNodeKind;
import io.github.vihuynh72.brownie.core.evidence.EvidenceLocator;
import io.github.vihuynh72.brownie.core.evidence.InvalidEvidenceLocatorException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests {@code SourceService}'s per-format resolution logic directly
 * against hand-built graphs -- no {@code ArtifactService}/{@code
 * DocumentExtractionService} fake chain needed, since these methods take
 * an already-fetched graph and have no dependency of their own.
 */
class SourceServiceResolutionTest {

    @Test
    void resolvesADocxRunByItsExactNodeIdAndCodePointRange() {
        StructuralNode run = new StructuralNode("p0/r0", StructuralNodeKind.RUN, null, "Meeting called to order.", null, null, List.of());
        StructuralNode paragraph = new StructuralNode("p0", StructuralNodeKind.PARAGRAPH, null, null, null, null, List.of(run));
        StructuralNode body = new StructuralNode("", StructuralNodeKind.BODY, null, null, null, null, List.of(paragraph));
        DocxStructuralGraph graph =
                new DocxStructuralGraph("v1", List.of(new DocumentPart("word/document.xml", DocumentPartKind.MAIN_DOCUMENT, body)));

        String excerpt = SourceService.resolveDocx(graph, new EvidenceLocator.Docx("word/document.xml", "p0/r0", 0, 8));
        assertEquals("Meeting ", excerpt);
    }

    @Test
    void findsADeeplyNestedDocxNodeInsideAContentControl() {
        StructuralNode innerRun = new StructuralNode("p0/sdt0/r0", StructuralNodeKind.RUN, null, "Jordan Lee", null, null, List.of());
        StructuralNode contentControl =
                new StructuralNode("p0/sdt0", StructuralNodeKind.CONTENT_CONTROL, null, null, "meeting.owner", null, List.of(innerRun));
        StructuralNode paragraph = new StructuralNode("p0", StructuralNodeKind.PARAGRAPH, null, null, null, null, List.of(contentControl));
        StructuralNode body = new StructuralNode("", StructuralNodeKind.BODY, null, null, null, null, List.of(paragraph));
        DocxStructuralGraph graph =
                new DocxStructuralGraph("v1", List.of(new DocumentPart("word/document.xml", DocumentPartKind.MAIN_DOCUMENT, body)));

        String excerpt = SourceService.resolveDocx(graph, new EvidenceLocator.Docx("word/document.xml", "p0/sdt0/r0", 0, 10));
        assertEquals("Jordan Lee", excerpt);
    }

    @Test
    void resolvesARunInsideATableCellReachedThroughTheFullTableRowCellPath() {
        // TABLE -> TABLE_ROW -> TABLE_CELL -> PARAGRAPH -> RUN: the one
        // node shape no earlier resolution test exercised, even though
        // real qualified documents always have a table.
        StructuralNode run = new StructuralNode("tbl0/row1/cell0/p0/r0", StructuralNodeKind.RUN, null, "Draft agenda", null, null, List.of());
        StructuralNode paragraph =
                new StructuralNode("tbl0/row1/cell0/p0", StructuralNodeKind.PARAGRAPH, null, null, null, null, List.of(run));
        StructuralNode cell = new StructuralNode("tbl0/row1/cell0", StructuralNodeKind.TABLE_CELL, null, null, null, null, List.of(paragraph));
        StructuralNode row = new StructuralNode("tbl0/row1", StructuralNodeKind.TABLE_ROW, null, null, null, null, List.of(cell));
        StructuralNode table = new StructuralNode("tbl0", StructuralNodeKind.TABLE, null, null, null, null, List.of(row));
        StructuralNode body = new StructuralNode("", StructuralNodeKind.BODY, null, null, null, null, List.of(table));
        DocxStructuralGraph graph =
                new DocxStructuralGraph("v1", List.of(new DocumentPart("word/document.xml", DocumentPartKind.MAIN_DOCUMENT, body)));

        String excerpt = SourceService.resolveDocx(graph, new EvidenceLocator.Docx("word/document.xml", "tbl0/row1/cell0/p0/r0", 0, 5));
        assertEquals("Draft", excerpt);
    }

    @Test
    void resolvesTextFromAHeaderPartNotOnlyTheMainDocumentPart() {
        // Every earlier DOCX resolution test addressed word/document.xml;
        // nothing before this proved a locator naming a HEADER/FOOTER part
        // actually resolves -- a real, previously unexercised path.
        StructuralNode run = new StructuralNode("p0/r0", StructuralNodeKind.RUN, null, "Brownie Meeting Minutes Template", null, null, List.of());
        StructuralNode paragraph = new StructuralNode("p0", StructuralNodeKind.PARAGRAPH, null, null, null, null, List.of(run));
        StructuralNode body = new StructuralNode("", StructuralNodeKind.BODY, null, null, null, null, List.of(paragraph));
        DocxStructuralGraph graph =
                new DocxStructuralGraph("v1", List.of(new DocumentPart("word/header1.xml", DocumentPartKind.HEADER, body)));

        String excerpt = SourceService.resolveDocx(graph, new EvidenceLocator.Docx("word/header1.xml", "p0/r0", 0, 7));
        assertEquals("Brownie", excerpt);
    }

    @Test
    void aDocxLocatorNamingAMissingPartThrows() {
        DocxStructuralGraph graph = new DocxStructuralGraph(
                "v1",
                List.of(new DocumentPart(
                        "word/document.xml",
                        DocumentPartKind.MAIN_DOCUMENT,
                        new StructuralNode("", StructuralNodeKind.BODY, null, null, null, null, List.of()))));

        assertThrows(
                InvalidEvidenceLocatorException.class,
                () -> SourceService.resolveDocx(graph, new EvidenceLocator.Docx("word/header1.xml", "p0", 0, 1)));
    }

    @Test
    void aDocxLocatorNamingAMissingNodeThrows() {
        StructuralNode body = new StructuralNode("", StructuralNodeKind.BODY, null, null, null, null, List.of());
        DocxStructuralGraph graph =
                new DocxStructuralGraph("v1", List.of(new DocumentPart("word/document.xml", DocumentPartKind.MAIN_DOCUMENT, body)));

        assertThrows(
                InvalidEvidenceLocatorException.class,
                () -> SourceService.resolveDocx(graph, new EvidenceLocator.Docx("word/document.xml", "p99/r0", 0, 1)));
    }

    @Test
    void aDocxLocatorPointingAtAStructuralNodeWithNoTextThrows() {
        // A PARAGRAPH node carries no text of its own -- only its RUN children do.
        StructuralNode paragraph = new StructuralNode("p0", StructuralNodeKind.PARAGRAPH, null, null, null, null, List.of());
        StructuralNode body = new StructuralNode("", StructuralNodeKind.BODY, null, null, null, null, List.of(paragraph));
        DocxStructuralGraph graph =
                new DocxStructuralGraph("v1", List.of(new DocumentPart("word/document.xml", DocumentPartKind.MAIN_DOCUMENT, body)));

        assertThrows(
                InvalidEvidenceLocatorException.class,
                () -> SourceService.resolveDocx(graph, new EvidenceLocator.Docx("word/document.xml", "p0", 0, 1)));
    }

    @Test
    void anOutOfRangeDocxCodePointRangeThrowsRatherThanCorrupting() {
        StructuralNode run = new StructuralNode("p0/r0", StructuralNodeKind.RUN, null, "short", null, null, List.of());
        StructuralNode body = new StructuralNode("", StructuralNodeKind.BODY, null, null, null, null, List.of(run));
        DocxStructuralGraph graph =
                new DocxStructuralGraph("v1", List.of(new DocumentPart("word/document.xml", DocumentPartKind.MAIN_DOCUMENT, body)));

        assertThrows(
                InvalidEvidenceLocatorException.class,
                () -> SourceService.resolveDocx(graph, new EvidenceLocator.Docx("word/document.xml", "p0/r0", 0, 999)));
    }

    @Test
    void resolvesAPdfLineByPageAndLineIndexAndCodePointRange() {
        PdfTextLine line = new PdfTextLine(0, "Attendees: Jordan Lee, Priya Nair.", 72, 72, 200, 12, false);
        PdfPage page = new PdfPage(1, 612, 792, 0, true, List.of(line));
        PdfStructuralGraph graph = new PdfStructuralGraph("v1", List.of(page));

        String excerpt = SourceService.resolvePdf(graph, new EvidenceLocator.Pdf(1, 0, 11, 21));
        assertEquals("Jordan Lee", excerpt);
    }

    @Test
    void resolvesAPdfLineTheSameWayRegardlessOfThePagesOwnRotation() {
        // A page's rotationDegrees is a display-time fact recorded
        // alongside its geometry, never baked into the line's own text or
        // position (see PdfBoxStructuralExtractor's own reasoning) -- so
        // resolution must not care about it at all. Proven, not assumed:
        // the exact same locator against otherwise-identical rotated pages
        // (0, 90, 180, 270) must all resolve to the same excerpt.
        PdfTextLine line = new PdfTextLine(0, "Meeting called to order.", 72, 72, 200, 12, false);
        for (int rotation : new int[] {0, 90, 180, 270}) {
            PdfStructuralGraph graph = new PdfStructuralGraph("v1", List.of(new PdfPage(1, 612, 792, rotation, true, List.of(line))));
            String excerpt = SourceService.resolvePdf(graph, new EvidenceLocator.Pdf(1, 0, 0, 7));
            assertEquals("Meeting", excerpt, "rotation " + rotation);
        }
    }

    @Test
    void aPdfLocatorNamingAMissingPageThrows() {
        PdfStructuralGraph graph = new PdfStructuralGraph("v1", List.of(new PdfPage(1, 612, 792, 0, false, List.of())));
        assertThrows(InvalidEvidenceLocatorException.class, () -> SourceService.resolvePdf(graph, new EvidenceLocator.Pdf(2, 0, 0, 1)));
    }

    @Test
    void aPdfLocatorNamingAMissingLineThrows() {
        PdfStructuralGraph graph = new PdfStructuralGraph("v1", List.of(new PdfPage(1, 612, 792, 0, false, List.of())));
        assertThrows(InvalidEvidenceLocatorException.class, () -> SourceService.resolvePdf(graph, new EvidenceLocator.Pdf(1, 0, 0, 1)));
    }

    @Test
    void resolvesPlainTextAgainstTheNormalizedOffsetButReturnsTheOriginalText() {
        // Original has \r\n; the locator addresses the NORMALIZED text
        // (where that pair is a single \n), and resolution must recover
        // the true ORIGINAL substring, including both original characters.
        PlainTextStructuralGraph graph = new PlainTextStructuralGraph("v1", "Line one\r\nLine two", "Line one\nLine two");

        String excerpt = SourceService.resolvePlainText(graph, new EvidenceLocator.PlainText(8, 9));
        assertEquals("\r\n", excerpt, "the single normalized newline must resolve back to both original characters");
    }

    @Test
    void resolvesAnOrdinaryPlainTextRange() {
        PlainTextStructuralGraph graph = new PlainTextStructuralGraph("v1", "Meeting called to order.", "Meeting called to order.");
        String excerpt = SourceService.resolvePlainText(graph, new EvidenceLocator.PlainText(0, 7));
        assertEquals("Meeting", excerpt);
    }

    @Test
    void anOutOfRangePlainTextCodePointRangeThrows() {
        PlainTextStructuralGraph graph = new PlainTextStructuralGraph("v1", "short", "short");
        assertThrows(InvalidEvidenceLocatorException.class, () -> SourceService.resolvePlainText(graph, new EvidenceLocator.PlainText(0, 999)));
    }
}
