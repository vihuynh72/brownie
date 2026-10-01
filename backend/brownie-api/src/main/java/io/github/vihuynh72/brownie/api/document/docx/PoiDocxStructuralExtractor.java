package io.github.vihuynh72.brownie.api.document.docx;

import io.github.vihuynh72.brownie.core.document.DocumentPart;
import io.github.vihuynh72.brownie.core.document.DocxExtractionOutcome;
import io.github.vihuynh72.brownie.core.document.DocxFeatureFinding;
import io.github.vihuynh72.brownie.core.document.DocxFeatureReport;
import io.github.vihuynh72.brownie.core.document.DocxParseException;
import io.github.vihuynh72.brownie.core.document.DocxStructuralExtractor;
import io.github.vihuynh72.brownie.core.document.DocxStructuralGraph;
import io.github.vihuynh72.brownie.core.document.ResolvedStyle;
import io.github.vihuynh72.brownie.core.document.StructuralNode;
import io.github.vihuynh72.brownie.core.document.StructuralNodeKind;
import io.github.vihuynh72.brownie.core.document.UnsupportedDocxFeature;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.xwpf.usermodel.XWPFAbstractFootnoteEndnote;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFFooter;
import org.apache.poi.xwpf.usermodel.XWPFHeader;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFStyles;
import org.apache.xmlbeans.XmlCursor;
import org.apache.xmlbeans.XmlObject;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a DOCX's supported package parts (the main document body, headers,
 * footers) into a {@link DocxStructuralGraph} using Apache POI. The node
 * ids come from {@link DocxNodeWalker}, which walks each paragraph's own
 * underlying XML rather than POI's higher-level {@code
 * XWPFParagraph.getRuns()} -- that convenience method silently skips over
 * an inline content control's inner run (confirmed empirically against a
 * real generated and reparsed document, not assumed), which would otherwise
 * make every content-control field invisible to this graph. A run's text is
 * what {@link RunText} reads from it.
 *
 * <p>Alongside the graph, {@link DocxFeatureScanner} reads every story part
 * (the body, headers and footers, and the footnotes and endnotes the graph
 * does not include) for the features worth naming. If any refused feature
 * turns up anywhere, the whole document is reported {@link
 * DocxExtractionOutcome.Unsupported} and the tentative graph is discarded --
 * documents here are small, so the discarded work is cheap, and a single
 * pass is simpler to reason about than separating preflight from
 * extraction. The features a file keeps as it is travel with the graph.
 */
public final class PoiDocxStructuralExtractor implements DocxStructuralExtractor {

    /**
     * Names both this extractor's own graph shape and the POI version it
     * depends on. Bumping either half is a deliberate, versioned change:
     * either one changing what this extractor observes must produce a new
     * {@code ExtractionVersion} rather than silently reinterpreting
     * evidence built against the old behavior. v2 reads a toggle written
     * as {@code w:val="on"} as on (see {@code StyleResolver#isOn}); a v1
     * graph of the same bytes says such a run is not bold or not italic. v3
     * keeps a run's tabs, breaks, non-breaking hyphens and checkbox symbols
     * in its text, where v2 read only its text elements, and accepts a
     * document that keeps floating shapes, tables inside tables, fields Word
     * works out by itself and allowed embedded objects; node ids are the
     * same as v2's.
     */
    static final String PARSER_VERSION = "brownie-docx-graph-v3+poi-5.5.1";

    @Override
    public String parserVersion() {
        return PARSER_VERSION;
    }

    /**
     * The upload inspector refuses a part nested deeper than any real
     * document, but it chooses the parts it reads by name, and a package
     * may declare its main document under any name at all. What is left to
     * such a file is to exhaust the stack of the library that reads it
     * here. That arrives as an error and not an exception, so nothing that
     * records a failed extraction would see it; it is turned into the
     * ordinary failure to parse, which is recorded, so the same file is
     * not read again and again.
     */
    @Override
    public DocxExtractionOutcome extract(InputStream content) throws IOException {
        try {
            return read(content);
        } catch (StackOverflowError e) {
            throw new DocxParseException("The document is nested more deeply than any real document.", e);
        }
    }

    private DocxExtractionOutcome read(InputStream content) throws IOException {
        XWPFDocument document;
        try {
            document = new XWPFDocument(content);
        } catch (RuntimeException e) {
            throw new DocxParseException("Could not parse the package as a DOCX document.", e);
        }
        try (document) {
            List<DocxFeatureFinding> findings = new ArrayList<>();
            checkPackageSignature(document, findings);
            if (document.getDocComments() != null) {
                findings.add(new DocxFeatureFinding(
                        UnsupportedDocxFeature.UNRESOLVED_COMMENT, "package", "The document contains one or more comments."));
            }

            XWPFStyles styles = document.getStyles();
            List<DocxNodeWalker.Part> walked = DocxNodeWalker.walk(document);
            List<DocumentPart> parts = new ArrayList<>();
            for (DocxNodeWalker.Part part : walked) {
                parts.add(new DocumentPart(part.partName(), part.kind(), bodyOf(part.blocks(), styles)));
            }
            scanStoryParts(document, walked, findings);

            DocxFeatureReport report = new DocxFeatureReport(List.copyOf(findings));
            if (!report.isSupported()) {
                return new DocxExtractionOutcome.Unsupported(report);
            }
            return new DocxExtractionOutcome.Supported(
                    new DocxStructuralGraph(PARSER_VERSION, List.copyOf(parts)), new DocxFeatureReport(report.keptAsIs()));
        }
    }

    private static StructuralNode bodyOf(List<DocxNodeWalker.Block> blocks, XWPFStyles styles) {
        List<StructuralNode> children = new ArrayList<>();
        for (DocxNodeWalker.Block block : blocks) {
            children.add(switch (block) {
                case DocxNodeWalker.Paragraph paragraph -> paragraphNode(paragraph, styles);
                case DocxNodeWalker.Table table -> tableNode(table, styles);
                // A content control around whole paragraphs: its content has no ids, so it reads as an empty paragraph.
                case DocxNodeWalker.OtherBlock other ->
                        new StructuralNode(other.nodeId(), StructuralNodeKind.PARAGRAPH, null, null, null, null, List.of());
            });
        }
        return new StructuralNode("", StructuralNodeKind.BODY, null, null, null, null, List.copyOf(children));
    }

    private static StructuralNode tableNode(DocxNodeWalker.Table table, XWPFStyles styles) {
        List<StructuralNode> rows = new ArrayList<>();
        for (DocxNodeWalker.Row row : table.rows()) {
            List<StructuralNode> cells = new ArrayList<>();
            for (DocxNodeWalker.Cell cell : row.cells()) {
                List<StructuralNode> paragraphs = new ArrayList<>();
                for (DocxNodeWalker.Paragraph paragraph : cell.paragraphs()) {
                    paragraphs.add(paragraphNode(paragraph, styles));
                }
                cells.add(new StructuralNode(cell.nodeId(), StructuralNodeKind.TABLE_CELL, null, null, null, null, List.copyOf(paragraphs)));
            }
            rows.add(new StructuralNode(row.nodeId(), StructuralNodeKind.TABLE_ROW, null, null, null, null, List.copyOf(cells)));
        }
        return new StructuralNode(table.nodeId(), StructuralNodeKind.TABLE, null, null, null, null, List.copyOf(rows));
    }

    private static StructuralNode paragraphNode(DocxNodeWalker.Paragraph walked, XWPFStyles styles) {
        XWPFParagraph paragraph = walked.paragraph();
        List<StructuralNode> children = new ArrayList<>();
        for (DocxNodeWalker.Inline inline : walked.inlines()) {
            children.add(switch (inline) {
                case DocxNodeWalker.Run run -> runNode(run, paragraph, styles);
                case DocxNodeWalker.Control control -> {
                    var sdt = control.sdt();
                    String tag = sdt.isSetSdtPr() && sdt.getSdtPr().isSetTag() ? sdt.getSdtPr().getTag().getVal() : null;
                    List<StructuralNode> runs = new ArrayList<>();
                    for (DocxNodeWalker.Run run : control.runs()) {
                        runs.add(runNode(run, paragraph, styles));
                    }
                    yield new StructuralNode(control.nodeId(), StructuralNodeKind.CONTENT_CONTROL, null, null, tag, null, List.copyOf(runs));
                }
            });
        }
        ResolvedStyle style = StyleResolver.resolveParagraph(paragraph, styles);
        return new StructuralNode(walked.nodeId(), StructuralNodeKind.PARAGRAPH, style, null, null, null, List.copyOf(children));
    }

    private static StructuralNode runNode(DocxNodeWalker.Run walked, XWPFParagraph paragraph, XWPFStyles styles) {
        CTR run = walked.run();
        if (run.sizeOfDrawingArray() > 0) {
            return new StructuralNode(walked.nodeId(), StructuralNodeKind.IMAGE, null, null, null, firstImageRelationshipId(run), List.of());
        }
        ResolvedStyle style = StyleResolver.resolveRun(run.isSetRPr() ? run.getRPr() : null, paragraph, styles);
        return new StructuralNode(walked.nodeId(), StructuralNodeKind.RUN, style, RunText.of(run), null, null, List.of());
    }

    /**
     * The first drawing's picture, by its {@code a:blip}'s embedded
     * relationship. {@code a:blip} sits inside generic {@code any}-content
     * DrawingML elements with no convenient typed POI accessor down to that
     * depth, so a targeted descendant search is simpler and more robust than
     * importing the full DrawingML/Picture schema types for one attribute.
     * The search stays inside the drawing: graph version 2 searched from the
     * start of the whole part, so every picture after the first was given
     * the first picture's relationship.
     */
    private static String firstImageRelationshipId(CTR run) {
        for (var drawing : run.getDrawingArray()) {
            try (XmlCursor cursor = drawing.newCursor()) {
                if (findDescendant(cursor, "blip")) {
                    return cursor.getAttributeText(new javax.xml.namespace.QName(EmbeddedObjects.RELATIONSHIPS, "embed"));
                }
            }
        }
        return null;
    }

    /** A depth-first search for the first element with the given local name inside the element the cursor is on. */
    private static boolean findDescendant(XmlCursor cursor, String localName) {
        int depth = 0;
        while (true) {
            XmlCursor.TokenType token = cursor.toNextToken();
            if (token == XmlCursor.TokenType.NONE || token == XmlCursor.TokenType.ENDDOC) {
                return false;
            }
            if (token.isStart()) {
                depth++;
                if (localName.equals(cursor.getName().getLocalPart())) {
                    return true;
                }
            } else if (token.isEnd()) {
                depth--;
                if (depth < 0) {
                    return false;
                }
            }
        }
    }

    /**
     * Scans the body, headers and footers element by element, each top-level
     * element named by its node id when it has one, then the footnotes and
     * endnotes, which have no node ids and are named by their part.
     */
    private static void scanStoryParts(XWPFDocument document, List<DocxNodeWalker.Part> walked, List<DocxFeatureFinding> findings) {
        Map<XmlObject, String> nodeIds = new IdentityHashMap<>();
        for (DocxNodeWalker.Part part : walked) {
            for (DocxNodeWalker.Block block : part.blocks()) {
                switch (block) {
                    case DocxNodeWalker.Paragraph paragraph -> nodeIds.put(paragraph.paragraph().getCTP(), block.nodeId());
                    case DocxNodeWalker.Table table -> nodeIds.put(table.table().getCTTbl(), block.nodeId());
                    case DocxNodeWalker.OtherBlock ignored -> {
                        // Named by its part alone.
                    }
                }
            }
        }
        scanChildren(document.getPackagePart(), document.getDocument().getBody(), nodeIds, findings);
        for (XWPFHeader header : document.getHeaderList()) {
            scanChildren(header.getPackagePart(), header._getHdrFtr(), nodeIds, findings);
        }
        for (XWPFFooter footer : document.getFooterList()) {
            scanChildren(footer.getPackagePart(), footer._getHdrFtr(), nodeIds, findings);
        }
        scanNotes(document.getFootnotes(), findings);
        scanNotes(document.getEndnotes(), findings);
    }

    private static void scanChildren(PackagePart part, XmlObject container, Map<XmlObject, String> nodeIds, List<DocxFeatureFinding> findings) {
        String partName = DocxNodeWalker.partName(part);
        DocxFeatureScanner scanner = new DocxFeatureScanner(part, findings);
        try (XmlCursor cursor = container.newCursor()) {
            if (cursor.toFirstChild()) {
                do {
                    XmlObject child = cursor.getObject();
                    String nodeId = nodeIds.get(child);
                    scanner.scan(child, nodeId == null ? partName : partName + ", " + nodeId);
                } while (cursor.toNextSibling());
            }
        }
        scanner.finish();
    }

    private static void scanNotes(List<? extends XWPFAbstractFootnoteEndnote> notes, List<DocxFeatureFinding> findings) {
        if (notes.isEmpty()) {
            return;
        }
        PackagePart part = notes.getFirst().getPart().getPackagePart();
        String partName = DocxNodeWalker.partName(part);
        DocxFeatureScanner scanner = new DocxFeatureScanner(part, findings);
        for (XWPFAbstractFootnoteEndnote note : notes) {
            scanner.scan(note.getCTFtnEdn(), partName);
        }
        scanner.finish();
    }

    /**
     * A full digital signature is made of several related package parts and
     * relationships; checking for any relationship whose type contains
     * "digital-signature" is a robust, if approximate, single check for
     * their presence, rather than enumerating each exact part.
     */
    private static void checkPackageSignature(XWPFDocument document, List<DocxFeatureFinding> findings) {
        var relationships = document.getPackagePart().getPackage().getRelationships();
        for (int i = 0; i < relationships.size(); i++) {
            String type = relationships.getRelationship(i).getRelationshipType();
            if (type != null && type.contains("digital-signature")) {
                findings.add(new DocxFeatureFinding(
                        UnsupportedDocxFeature.PACKAGE_SIGNATURE, "package", "The package contains a digital signature relationship."));
                return;
            }
        }
    }
}
