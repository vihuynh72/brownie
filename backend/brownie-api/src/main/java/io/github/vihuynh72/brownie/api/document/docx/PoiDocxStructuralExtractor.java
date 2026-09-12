package io.github.vihuynh72.brownie.api.document.docx;

import io.github.vihuynh72.brownie.core.document.DocumentPart;
import io.github.vihuynh72.brownie.core.document.DocumentPartKind;
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
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFFooter;
import org.apache.poi.xwpf.usermodel.XWPFHeader;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFStyles;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.apache.xmlbeans.XmlCursor;
import org.apache.xmlbeans.XmlObject;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTHyperlink;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSdtContentRun;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSdtRun;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STFldCharType;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads a DOCX's supported package parts (the main document body, headers,
 * footers) into a {@link DocxStructuralGraph} using Apache POI, walking
 * each paragraph's own underlying XML directly via {@link XmlCursor}
 * rather than POI's higher-level {@code XWPFParagraph.getRuns()} -- that
 * convenience method silently skips over an inline content control's
 * inner run (confirmed empirically against a real generated and reparsed
 * document, not assumed), which would otherwise make every content-control
 * field invisible to this graph.
 *
 * <p>A single pass both builds the tentative graph and collects unsupported-
 * feature findings; if any finding turns up anywhere, the whole document is
 * reported {@link DocxExtractionOutcome.Unsupported} and the tentative
 * graph is discarded -- documents here are small (a pilot-scale meeting-
 * minutes template), so the discarded work is cheap, and a single pass is
 * simpler to reason about than separating preflight from extraction.
 */
public final class PoiDocxStructuralExtractor implements DocxStructuralExtractor {

    /**
     * Names both this extractor's own graph shape and the POI version it
     * depends on. Bumping either half is a deliberate, versioned change:
     * either one changing what this extractor observes must produce a new
     * {@code ExtractionVersion} rather than silently reinterpreting
     * evidence built against the old behavior.
     */
    static final String PARSER_VERSION = "brownie-docx-graph-v1+poi-5.5.1";

    private static final String RELATIONSHIP_NAMESPACE = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

    @Override
    public String parserVersion() {
        return PARSER_VERSION;
    }

    @Override
    public DocxExtractionOutcome extract(InputStream content) throws IOException {
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
            List<DocumentPart> parts = new ArrayList<>();
            parts.add(extractPart(
                    partName(document.getPackagePart()),
                    DocumentPartKind.MAIN_DOCUMENT,
                    document.getBodyElements(),
                    styles,
                    findings));
            for (XWPFHeader header : document.getHeaderList()) {
                parts.add(extractPart(
                        partName(header.getPackagePart()), DocumentPartKind.HEADER, header.getBodyElements(), styles, findings));
            }
            for (XWPFFooter footer : document.getFooterList()) {
                parts.add(extractPart(
                        partName(footer.getPackagePart()), DocumentPartKind.FOOTER, footer.getBodyElements(), styles, findings));
            }

            DocxFeatureReport report = new DocxFeatureReport(List.copyOf(findings));
            if (!report.isSupported()) {
                return new DocxExtractionOutcome.Unsupported(report);
            }
            return new DocxExtractionOutcome.Supported(new DocxStructuralGraph(PARSER_VERSION, List.copyOf(parts)));
        }
    }

    private static String partName(PackagePart packagePart) {
        String name = packagePart.getPartName().getName();
        return name.startsWith("/") ? name.substring(1) : name;
    }

    private DocumentPart extractPart(
            String partName,
            DocumentPartKind kind,
            List<IBodyElement> bodyElements,
            XWPFStyles styles,
            List<DocxFeatureFinding> findings) {
        List<StructuralNode> children = new ArrayList<>();
        int index = 0;
        for (IBodyElement element : bodyElements) {
            children.add(switch (element.getElementType()) {
                case PARAGRAPH -> extractParagraph((XWPFParagraph) element, "p" + index, styles, findings, partName);
                case TABLE -> extractTable((XWPFTable) element, "tbl" + index, styles, findings, partName);
                default -> new StructuralNode("body" + index, StructuralNodeKind.PARAGRAPH, null, null, null, null, List.of());
            });
            index++;
        }
        StructuralNode root = new StructuralNode("", StructuralNodeKind.BODY, null, null, null, null, List.copyOf(children));
        return new DocumentPart(partName, kind, root);
    }

    private StructuralNode extractTable(
            XWPFTable table, String nodeId, XWPFStyles styles, List<DocxFeatureFinding> findings, String partName) {
        List<StructuralNode> rows = new ArrayList<>();
        int rowIndex = 0;
        for (XWPFTableRow row : table.getRows()) {
            rows.add(extractTableRow(row, nodeId + "/row" + rowIndex, styles, findings, partName));
            rowIndex++;
        }
        return new StructuralNode(nodeId, StructuralNodeKind.TABLE, null, null, null, null, List.copyOf(rows));
    }

    private StructuralNode extractTableRow(
            XWPFTableRow row, String nodeId, XWPFStyles styles, List<DocxFeatureFinding> findings, String partName) {
        List<StructuralNode> cells = new ArrayList<>();
        int cellIndex = 0;
        for (XWPFTableCell cell : row.getTableCells()) {
            cells.add(extractTableCell(cell, nodeId + "/cell" + cellIndex, styles, findings, partName));
            cellIndex++;
        }
        return new StructuralNode(nodeId, StructuralNodeKind.TABLE_ROW, null, null, null, null, List.copyOf(cells));
    }

    private StructuralNode extractTableCell(
            XWPFTableCell cell, String nodeId, XWPFStyles styles, List<DocxFeatureFinding> findings, String partName) {
        if (!cell.getTables().isEmpty()) {
            findings.add(new DocxFeatureFinding(
                    UnsupportedDocxFeature.NESTED_TABLE, partName + ", " + nodeId, "A table cell contains another table."));
        }
        List<StructuralNode> paragraphs = new ArrayList<>();
        int index = 0;
        for (XWPFParagraph paragraph : cell.getParagraphs()) {
            paragraphs.add(extractParagraph(paragraph, nodeId + "/p" + index, styles, findings, partName));
            index++;
        }
        return new StructuralNode(nodeId, StructuralNodeKind.TABLE_CELL, null, null, null, null, List.copyOf(paragraphs));
    }

    /**
     * Walks a paragraph's own {@code CTP} XML children in document order --
     * not {@code XWPFParagraph.getRuns()}, which omits an inline content
     * control's inner run entirely. Direct children handled: {@code w:r}
     * (a run), {@code w:sdt} (an inline content control), {@code
     * w:hyperlink} (its own inner runs, unwrapped -- a hyperlink's target
     * is not evidence-addressable content here). Anything else (bookmarks,
     * proofing-error markers, and so on) is not structural content and is
     * skipped without comment.
     */
    private StructuralNode extractParagraph(
            XWPFParagraph paragraph, String nodeId, XWPFStyles styles, List<DocxFeatureFinding> findings, String partName) {
        CTP ctp = paragraph.getCTP();
        String location = partName + ", " + nodeId;

        if (ctp.getInsArray().length > 0 || ctp.getDelArray().length > 0) {
            findings.add(new DocxFeatureFinding(
                    UnsupportedDocxFeature.TRACKED_CHANGES, location, "The paragraph contains a tracked insertion or deletion."));
        }
        for (var fldSimple : ctp.getFldSimpleArray()) {
            checkFieldInstruction(fldSimple.getInstr(), location, findings);
        }

        List<StructuralNode> children = new ArrayList<>();
        int index = 0;
        try (XmlCursor cursor = ctp.newCursor()) {
            if (cursor.toFirstChild()) {
                do {
                    XmlObject child = cursor.getObject();
                    if (child instanceof CTR run) {
                        children.add(extractRun(run, nodeId + "/r" + index, paragraph, styles, findings, location));
                        index++;
                    } else if (child instanceof CTSdtRun sdt) {
                        children.add(extractContentControl(sdt, nodeId + "/sdt" + index, paragraph, styles, findings, location));
                        index++;
                    } else if (child instanceof CTHyperlink hyperlink) {
                        for (CTR run : hyperlink.getRArray()) {
                            children.add(extractRun(run, nodeId + "/r" + index, paragraph, styles, findings, location));
                            index++;
                        }
                    }
                } while (cursor.toNextSibling());
            }
        }

        ResolvedStyle style = StyleResolver.resolveParagraph(paragraph, styles);
        return new StructuralNode(nodeId, StructuralNodeKind.PARAGRAPH, style, null, null, null, List.copyOf(children));
    }

    private StructuralNode extractContentControl(
            CTSdtRun sdt,
            String nodeId,
            XWPFParagraph paragraph,
            XWPFStyles styles,
            List<DocxFeatureFinding> findings,
            String location) {
        String tag = sdt.isSetSdtPr() && sdt.getSdtPr().isSetTag() ? sdt.getSdtPr().getTag().getVal() : null;
        List<StructuralNode> children = new ArrayList<>();
        if (sdt.isSetSdtContent()) {
            CTSdtContentRun sdtContent = sdt.getSdtContent();
            int index = 0;
            for (CTR run : sdtContent.getRArray()) {
                children.add(extractRun(run, nodeId + "/r" + index, paragraph, styles, findings, location));
                index++;
            }
        }
        return new StructuralNode(nodeId, StructuralNodeKind.CONTENT_CONTROL, null, null, tag, null, List.copyOf(children));
    }

    private StructuralNode extractRun(
            CTR run,
            String nodeId,
            XWPFParagraph paragraph,
            XWPFStyles styles,
            List<DocxFeatureFinding> findings,
            String location) {
        checkRunForUnsupportedFeatures(run, location, findings);

        if (run.sizeOfDrawingArray() > 0) {
            String relationshipId = firstEmbeddedImageRelationshipId(run, location, findings);
            return new StructuralNode(nodeId, StructuralNodeKind.IMAGE, null, null, null, relationshipId, List.of());
        }

        for (var fldChar : run.getFldCharArray()) {
            if (fldChar.getFldCharType() == STFldCharType.BEGIN) {
                // The instruction text itself lives on the *next* run in the fldChar
                // begin/instrText/separate/end sequence -- checked when that run is visited.
                continue;
            }
        }
        for (var instrText : run.getInstrTextArray()) {
            checkFieldInstruction(instrText.getStringValue(), location, findings);
        }

        StringBuilder text = new StringBuilder();
        for (var t : run.getTArray()) {
            text.append(t.getStringValue());
        }
        ResolvedStyle style = StyleResolver.resolveRun(run.isSetRPr() ? run.getRPr() : null, paragraph, styles);
        return new StructuralNode(nodeId, StructuralNodeKind.RUN, style, text.toString(), null, null, List.of());
    }

    /**
     * VML pictures ({@code w:pict}, legacy text boxes and shapes) and OLE
     * objects ({@code w:object}) are checked directly through POI's typed
     * accessors. A floating (as opposed to inline) drawing is detected via
     * {@code CTDrawing}'s own inline/anchor counts, and an externally
     * linked (as opposed to embedded) image via a cursor search for the
     * drawing's {@code a:blip} element's {@code r:link} attribute --
     * {@code a:blip} sits inside generic {@code any}-content DrawingML
     * elements with no convenient typed POI accessor down to that depth,
     * so a targeted descendant search is simpler and more robust than
     * importing the full DrawingML/Picture schema types for one attribute.
     */
    private void checkRunForUnsupportedFeatures(CTR run, String location, List<DocxFeatureFinding> findings) {
        if (run.sizeOfPictArray() > 0) {
            findings.add(new DocxFeatureFinding(
                    UnsupportedDocxFeature.FLOATING_SHAPE, location, "The run contains a legacy VML picture or shape."));
        }
        if (run.sizeOfObjectArray() > 0) {
            findings.add(new DocxFeatureFinding(
                    UnsupportedDocxFeature.EMBEDDED_OBJECT, location, "The run contains an embedded OLE object."));
        }
        for (var drawing : run.getDrawingArray()) {
            if (drawing.sizeOfAnchorArray() > 0) {
                findings.add(new DocxFeatureFinding(
                        UnsupportedDocxFeature.FLOATING_SHAPE, location, "The run contains a floating (anchored) drawing."));
            }
        }
    }

    private String firstEmbeddedImageRelationshipId(CTR run, String location, List<DocxFeatureFinding> findings) {
        for (var drawing : run.getDrawingArray()) {
            try (XmlCursor cursor = drawing.newCursor()) {
                boolean found = cursor.toChild(
                        "http://schemas.openxmlformats.org/drawingml/2006/main", "blip");
                if (!found) {
                    // Some producers nest the blip one level differently; fall back to a full subtree search.
                    cursor.toStartDoc();
                    found = findDescendant(cursor, "blip");
                }
                if (found) {
                    String link = cursor.getAttributeText(new javax.xml.namespace.QName(RELATIONSHIP_NAMESPACE, "link"));
                    String embed = cursor.getAttributeText(new javax.xml.namespace.QName(RELATIONSHIP_NAMESPACE, "embed"));
                    if (link != null) {
                        findings.add(new DocxFeatureFinding(
                                UnsupportedDocxFeature.LINKED_EXTERNAL_IMAGE, location, "The image links to an external resource rather than an embedded one."));
                    }
                    return embed;
                }
            }
        }
        return null;
    }

    /** A simple depth-first search for the first descendant element with the given local name, from the cursor's current position. */
    private static boolean findDescendant(XmlCursor cursor, String localName) {
        int depth = 0;
        while (true) {
            org.apache.xmlbeans.XmlCursor.TokenType token = cursor.toNextToken();
            if (token == org.apache.xmlbeans.XmlCursor.TokenType.NONE) {
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

    /** Only a bare {@code PAGE} field is within the qualified subset; every other field code is flagged. */
    private static void checkFieldInstruction(String instruction, String location, List<DocxFeatureFinding> findings) {
        if (instruction == null) {
            return;
        }
        String trimmed = instruction.trim();
        String keyword = trimmed.isEmpty() ? "" : trimmed.split("\\s+", 2)[0].toUpperCase(java.util.Locale.ROOT);
        if (!"PAGE".equals(keyword)) {
            findings.add(new DocxFeatureFinding(
                    UnsupportedDocxFeature.UNSUPPORTED_FIELD, location, "Field instruction: " + trimmed));
        }
    }

    /**
     * A full digital signature is made of several related package parts and
     * relationships; checking for any relationship whose type contains
     * "digital-signature" is a robust, if approximate, single check for
     * their presence, rather than enumerating each exact part. Not
     * exercised against a real signed fixture -- constructing one is well
     * beyond what a synthetic test fixture can produce -- so this is
     * implemented from the OOXML relationship-type convention, not proven
     * empirically the way the rest of this class's checks are.
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
