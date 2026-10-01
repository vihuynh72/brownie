package io.github.vihuynh72.brownie.api.document.docx;

import io.github.vihuynh72.brownie.core.document.DocxFeatureFinding;
import io.github.vihuynh72.brownie.core.document.FieldInstructionPolicy;
import io.github.vihuynh72.brownie.core.document.UnsupportedDocxFeature;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.openxml4j.opc.PackageRelationship;
import org.apache.poi.openxml4j.opc.TargetMode;
import org.apache.xmlbeans.XmlCursor;
import org.apache.xmlbeans.XmlObject;

import javax.xml.namespace.QName;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reads one story part's XML (the body, a header, a footer, the footnotes)
 * for the features worth naming, element by element in document order and
 * everywhere in it: inside smart tags, custom XML, hyperlinks, content
 * controls and text boxes too, which the node walk passes over. Each finding
 * is reported once per place and wording, so a paragraph full of tracked
 * insertions is one finding, not hundreds.
 *
 * <p>A field's code can be split over several runs, and can hold other
 * fields, and its result can run over several paragraphs; fields are
 * therefore followed across the whole part, from each {@code begin} to its
 * {@code separate} (where its instruction is complete) and {@code end}.
 */
final class DocxFeatureScanner {

    private static final String WORDPROCESSING_DRAWING = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing";
    private static final String DRAWING = "http://schemas.openxmlformats.org/drawingml/2006/main";
    private static final String VML = "urn:schemas-microsoft-com:vml";

    private static final QName FLD_CHAR_TYPE = new QName(RunText.W, "fldCharType");
    private static final QName INSTRUCTION = new QName(RunText.W, "instr");
    private static final QName LINK = new QName(EmbeddedObjects.RELATIONSHIPS, "link");
    private static final QName STYLE = new QName("", "style");

    /** Every element a tracked change that changes the words leaves, by what a person would call it. */
    private static final Map<String, String> REVISIONS = Map.ofEntries(
            Map.entry("ins", "A tracked insertion."),
            Map.entry("del", "A tracked deletion."),
            Map.entry("moveFrom", "A tracked move."),
            Map.entry("moveTo", "A tracked move."),
            Map.entry("moveFromRangeStart", "A tracked move."),
            Map.entry("moveToRangeStart", "A tracked move."),
            Map.entry("cellIns", "A tracked table cell change."),
            Map.entry("cellDel", "A tracked table cell change."),
            Map.entry("cellMerge", "A tracked table cell change."),
            Map.entry("customXmlInsRangeStart", "A tracked insertion."),
            Map.entry("customXmlDelRangeStart", "A tracked deletion."),
            Map.entry("customXmlMoveFromRangeStart", "A tracked move."),
            Map.entry("customXmlMoveToRangeStart", "A tracked move."));

    /** Every element a tracked change to formatting alone leaves; the words are the same whichever is shown. */
    private static final Map<String, String> FORMATTING_REVISIONS = Map.ofEntries(
            Map.entry("rPrChange", "A tracked formatting change."),
            Map.entry("pPrChange", "A tracked formatting change."),
            Map.entry("sectPrChange", "A tracked formatting change."),
            Map.entry("tblPrChange", "A tracked formatting change."),
            Map.entry("tblPrExChange", "A tracked formatting change."),
            Map.entry("trPrChange", "A tracked formatting change."),
            Map.entry("tcPrChange", "A tracked formatting change."),
            Map.entry("tblGridChange", "A tracked formatting change."),
            Map.entry("numberingChange", "A tracked numbering change."));

    private final PackagePart part;
    private final List<DocxFeatureFinding> findings;
    private final Set<String> reported = new HashSet<>();
    private final Set<String> externalImageRelationshipIds = new HashSet<>();
    private final Deque<OpenField> openFields = new ArrayDeque<>();

    /** Findings are added to {@code findings}, in document order. */
    DocxFeatureScanner(PackagePart part, List<DocxFeatureFinding> findings) {
        this.part = part;
        this.findings = findings;
        try {
            for (PackageRelationship relationship : part.getRelationships()) {
                if (relationship.getTargetMode() == TargetMode.EXTERNAL && relationship.getRelationshipType().endsWith("/image")) {
                    externalImageRelationshipIds.add(relationship.getId());
                }
            }
        } catch (InvalidFormatException e) {
            throw new IllegalStateException("The part's relationships could not be read.", e);
        }
    }

    /** Scans one element and everything in it; {@code location} names where it is. */
    void scan(XmlObject element, String location) {
        try (XmlCursor cursor = element.newCursor()) {
            int tableCellDepth = 0;
            Deque<Boolean> openCells = new ArrayDeque<>();
            int depth = 0;
            XmlCursor.TokenType token = cursor.currentTokenType();
            while (true) {
                if (token.isStart()) {
                    depth++;
                    QName name = cursor.getName();
                    boolean cell = RunText.W.equals(name.getNamespaceURI()) && "tc".equals(name.getLocalPart());
                    if (RunText.W.equals(name.getNamespaceURI()) && "tbl".equals(name.getLocalPart()) && tableCellDepth > 0) {
                        report(UnsupportedDocxFeature.NESTED_TABLE, location, "A table cell contains another table.");
                    }
                    openCells.push(cell);
                    if (cell) {
                        tableCellDepth++;
                    }
                    inspect(cursor, name, location);
                } else if (token.isEnd()) {
                    depth--;
                    if (!openCells.isEmpty() && openCells.pop()) {
                        tableCellDepth--;
                    }
                    if (depth == 0) {
                        return;
                    }
                }
                token = cursor.toNextToken();
                if (token == XmlCursor.TokenType.NONE || token == XmlCursor.TokenType.ENDDOC) {
                    return;
                }
            }
        }
    }

    /** A field still open when the part ends is judged by what its code says so far. */
    void finish() {
        while (!openFields.isEmpty()) {
            OpenField field = openFields.pop();
            if (!field.judged) {
                judgeField(field.instruction.toString(), field.location);
            }
        }
    }

    private void inspect(XmlCursor cursor, QName name, String location) {
        String namespace = name.getNamespaceURI();
        String local = name.getLocalPart();
        if (RunText.W.equals(namespace)) {
            String revision = REVISIONS.get(local);
            if (revision != null) {
                report(UnsupportedDocxFeature.TRACKED_CHANGES, location, revision);
                return;
            }
            String formatting = FORMATTING_REVISIONS.get(local);
            if (formatting != null) {
                report(UnsupportedDocxFeature.TRACKED_FORMATTING_CHANGE, location, formatting);
                return;
            }
            switch (local) {
                case "commentReference", "commentRangeStart" -> report(
                        UnsupportedDocxFeature.UNRESOLVED_COMMENT, location, "The document contains one or more comments.");
                case "pict" -> {
                    if (holdsAFloatingShape(cursor)) {
                        report(UnsupportedDocxFeature.FLOATING_SHAPE, location, "The run contains a legacy VML picture or shape.");
                    }
                }
                case "object" -> {
                    EmbeddedObjects.Description object = EmbeddedObjects.describe(cursor);
                    report(EmbeddedObjects.allowedIn(part, object) ? UnsupportedDocxFeature.EMBEDDED_OBJECT
                                    : UnsupportedDocxFeature.UNSAFE_EMBEDDED_OBJECT,
                            location, object.detail());
                }
                case "fldChar" -> fieldCharacter(cursor.getAttributeText(FLD_CHAR_TYPE), location);
                case "instrText" -> {
                    OpenField field = openFields.peek();
                    if (field != null && !field.judged) {
                        field.instruction.append(cursor.getTextValue());
                    }
                }
                case "fldSimple" -> judgeField(cursor.getAttributeText(INSTRUCTION), location);
                default -> {
                    // Everything else in the body is text, structure or formatting.
                }
            }
        } else if (WORDPROCESSING_DRAWING.equals(namespace) && "anchor".equals(local)) {
            report(UnsupportedDocxFeature.FLOATING_SHAPE, location, "The run contains a floating (anchored) drawing.");
        } else if (DRAWING.equals(namespace) && "blip".equals(local) && cursor.getAttributeText(LINK) != null) {
            report(UnsupportedDocxFeature.LINKED_EXTERNAL_IMAGE, location, "The image links to an external resource rather than an embedded one.");
        }
        if (!externalImageRelationshipIds.isEmpty() && refersToAnExternalImage(cursor)) {
            report(UnsupportedDocxFeature.LINKED_EXTERNAL_IMAGE, location, "The image links to an external resource rather than an embedded one.");
        }
    }

    private void fieldCharacter(String type, String location) {
        if (type == null) {
            return;
        }
        switch (type) {
            case "begin" -> openFields.push(new OpenField(location));
            case "separate" -> {
                OpenField field = openFields.peek();
                if (field != null && !field.judged) {
                    field.judged = true;
                    judgeField(field.instruction.toString(), field.location);
                }
            }
            case "end" -> {
                OpenField field = openFields.poll();
                if (field != null && !field.judged) {
                    judgeField(field.instruction.toString(), field.location);
                }
            }
            default -> {
                // No other kind of field character exists.
            }
        }
    }

    private void judgeField(String instruction, String location) {
        String trimmed = instruction == null ? "" : instruction.strip();
        UnsupportedDocxFeature feature = FieldInstructionPolicy.classify(trimmed) == FieldInstructionPolicy.Treatment.FREEZE
                ? UnsupportedDocxFeature.UNSUPPORTED_FIELD
                : UnsupportedDocxFeature.DYNAMIC_FIELD;
        DocxFeatureFinding finding = DocxFeatureFinding.field(feature, location, trimmed);
        report(finding.feature(), finding.location(), finding.detail());
    }

    /** A VML shape floats when it is positioned absolutely; an inline one (an old picture) sits in the line like text. */
    private static boolean holdsAFloatingShape(XmlCursor pict) {
        try (XmlCursor cursor = pict.newCursor()) {
            int depth = 0;
            while (true) {
                XmlCursor.TokenType token = cursor.toNextToken();
                if (token == XmlCursor.TokenType.NONE || token == XmlCursor.TokenType.ENDDOC) {
                    return false;
                }
                if (token.isEnd()) {
                    if (depth == 0) {
                        return false;
                    }
                    depth--;
                } else if (token.isStart()) {
                    depth++;
                    String style = VML.equals(cursor.getName().getNamespaceURI()) ? cursor.getAttributeText(STYLE) : null;
                    if (style != null && style.replace(" ", "").toLowerCase(Locale.ROOT).contains("position:absolute")) {
                        return true;
                    }
                }
            }
        }
    }

    private boolean refersToAnExternalImage(XmlCursor element) {
        try (XmlCursor cursor = element.newCursor()) {
            if (!cursor.toFirstAttribute()) {
                return false;
            }
            do {
                QName name = cursor.getName();
                if (EmbeddedObjects.RELATIONSHIPS.equals(name.getNamespaceURI())
                        && externalImageRelationshipIds.contains(cursor.getTextValue())) {
                    return true;
                }
            } while (cursor.toNextAttribute());
            return false;
        }
    }

    private void report(UnsupportedDocxFeature feature, String location, String detail) {
        if (reported.add(feature + "\n" + location + "\n" + detail)) {
            findings.add(new DocxFeatureFinding(feature, location, detail));
        }
    }

    private static final class OpenField {
        private final String location;
        private final StringBuilder instruction = new StringBuilder();
        private boolean judged;

        private OpenField(String location) {
            this.location = location;
        }
    }
}
