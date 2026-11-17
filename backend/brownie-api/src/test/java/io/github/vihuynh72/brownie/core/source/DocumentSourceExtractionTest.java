package io.github.vihuynh72.brownie.core.source;

import io.github.vihuynh72.brownie.api.document.docx.PoiDocxStructuralExtractor;
import io.github.vihuynh72.brownie.api.document.pdf.PdfBoxStructuralExtractor;
import io.github.vihuynh72.brownie.core.document.*;
import io.github.vihuynh72.brownie.core.evidence.*;
import io.github.vihuynh72.brownie.core.generation.ExtractionService;
import io.github.vihuynh72.brownie.core.generation.SourceNotExtractableException;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DocumentSourceExtractionTest {
    private final DocumentExtractionService documents = mock(DocumentExtractionService.class);
    private final SourceService sources = mock(SourceService.class);
    private final ExtractionService extraction = new ExtractionService(documents, sources, null, null);
    private final SourceSnapshot snapshot = new SourceSnapshot(4, 1, 3, SourceKind.ARTIFACT, OffsetDateTime.now());

    @Test
    void docxUsesRealRunTextAndUnicodeCodePointLocators() throws Exception {
        byte[] bytes;
        try (var doc = new XWPFDocument(); var out = new ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText("😀" + "a".repeat(2001));
            doc.write(out);
            bytes = out.toByteArray();
        }
        var outcome = (DocxExtractionOutcome.Supported) new PoiDocxStructuralExtractor().extract(new ByteArrayInputStream(bytes));
        var graph = outcome.graph();
        when(documents.extract(1, 2, 3)).thenReturn(new ExtractionResult.Docx(
                new ExtractionVersion(1, 1, 3, graph.parserVersion(), ExtractionStatus.COMPLETE, outcome.keptAsIs(), graph, null, OffsetDateTime.now())));
        List<String> resolved = new ArrayList<>();
        when(sources.createSpan(eq(1L), eq(2L), eq(4L), any())).thenAnswer(call -> {
            EvidenceLocator.Docx locator = call.getArgument(3);
            resolved.add(SourceService.resolveDocx(graph, locator));
            return new SourceSpan(resolved.size(), 1, 4, graph.parserVersion(), locator, "", OffsetDateTime.now());
        });
        var excerpts = extraction.segmentAndCiteSource(1, 2, snapshot);
        assertEquals(2, excerpts.size());
        assertEquals(excerpts.stream().map(e -> e.text()).toList(), resolved);
        assertEquals("😀" + "a".repeat(2001), String.join("", resolved));
    }

    @Test
    void pdfUsesRealPageAndLineLocatorsAndRejectsEmptyPdf() throws Exception {
        byte[] bytes;
        try (var doc = new PDDocument(); var out = new ByteArrayOutputStream()) {
            doc.addPage(new PDPage());
            var page = new PDPage();
            doc.addPage(page);
            try (var content = new PDPageContentStream(doc, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 700);
                content.showText("Name: Alice");
                content.endText();
            }
            doc.save(out);
            bytes = out.toByteArray();
        }
        var outcome = (PdfExtractionOutcome.Supported) new PdfBoxStructuralExtractor().extract(new ByteArrayInputStream(bytes));
        var graph = outcome.graph();
        when(documents.extract(1, 2, 3)).thenReturn(new ExtractionResult.Pdf(
                new PdfExtractionVersion(1, 1, 3, graph.parserVersion(), ExtractionStatus.COMPLETE, null, null, graph, null, OffsetDateTime.now())));
        List<String> resolved = new ArrayList<>();
        when(sources.createSpan(eq(1L), eq(2L), eq(4L), any())).thenAnswer(call -> {
            EvidenceLocator.Pdf locator = call.getArgument(3);
            assertEquals(2, locator.pageNumber());
            resolved.add(SourceService.resolvePdf(graph, locator));
            return new SourceSpan(resolved.size(), 1, 4, graph.parserVersion(), locator, "", OffsetDateTime.now());
        });
        var excerpts = extraction.segmentAndCiteSource(1, 2, snapshot);
        assertEquals(List.of("Name: Alice"), resolved);
        assertEquals(resolved, excerpts.stream().map(e -> e.text()).toList());

        byte[] blank;
        try (var doc = new PDDocument(); var out = new ByteArrayOutputStream()) {
            doc.addPage(new PDPage());
            doc.save(out);
            blank = out.toByteArray();
        }
        var unsupported = (PdfExtractionOutcome.Unsupported) new PdfBoxStructuralExtractor().extract(new ByteArrayInputStream(blank));
        assertEquals(UnsupportedPdfReason.NO_EXTRACTABLE_TEXT, unsupported.reason());
        when(documents.extract(1, 2, 3)).thenReturn(new ExtractionResult.Pdf(
                new PdfExtractionVersion(1, 1, 3, "v1", ExtractionStatus.UNSUPPORTED, unsupported.reason(), unsupported.detail(), null, null, OffsetDateTime.now())));
        assertTrue(assertThrows(SourceNotExtractableException.class, () -> extraction.segmentAndCiteSource(1, 2, snapshot))
                .getMessage().contains("no extractable text"));
        when(documents.extract(1, 2, 3)).thenReturn(new ExtractionResult.Pdf(
                new PdfExtractionVersion(1, 1, 3, "v1", ExtractionStatus.FAILED, null, null, null, "invalid PDF", OffsetDateTime.now())));
        assertTrue(assertThrows(SourceNotExtractableException.class, () -> extraction.segmentAndCiteSource(1, 2, snapshot))
                .getMessage().contains("could not be read"));
    }
}
