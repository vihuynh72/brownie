package io.github.vihuynh72.brownie.api.document.docx;

import org.apache.poi.common.usermodel.PictureType;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.util.Units;
import org.apache.poi.wp.usermodel.HeaderFooterType;
import org.apache.poi.xwpf.usermodel.XWPFAbstractNum;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFFooter;
import org.apache.poi.xwpf.usermodel.XWPFHeader;
import org.apache.poi.xwpf.usermodel.XWPFNumbering;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFStyles;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTAbstractNum;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTDocDefaults;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTLvl;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRunTrackChange;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSdtContentRun;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSdtPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSdtRun;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTStyle;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTStyles;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STFldCharType;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STNumberFormat;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STStyleType;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.Base64;

/**
 * Builds real, well-formed DOCX byte arrays for tests -- every fixture is
 * written through {@link XWPFDocument#write} and (where a test needs it)
 * reloaded, so tests see exactly the bytes a real upload would produce,
 * never an in-memory, unsaved document the extractor would never actually
 * be given.
 */
final class DocxFixtures {

    /** A minimal real, valid, 1x1 transparent PNG -- just enough for {@code XWPFRun#addPicture} to accept it as real image bytes. */
    private static final byte[] TINY_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");

    private DocxFixtures() {
    }

    /**
     * A qualified document exercising every path the extractor's "supported" branch handles:
     * a header and footer, a paragraph using a style that is itself based on another style
     * (exercising the basedOn resolution chain) with a document-default font, an inline content
     * control, a numbered paragraph, an ordinary table, and an embedded inline image.
     */
    static byte[] qualifiedDocument() throws IOException {
        XWPFDocument doc = new XWPFDocument();
        buildStyles(doc);

        XWPFHeader header = doc.createHeader(HeaderFooterType.DEFAULT);
        header.createParagraph().createRun().setText("Brownie Meeting Minutes Template");
        XWPFFooter footer = doc.createFooter(HeaderFooterType.DEFAULT);
        footer.createParagraph().createRun().setText("Footer");

        XWPFParagraph heading = doc.createParagraph();
        heading.setStyle("Heading1");
        heading.createRun().setText("Meeting Minutes");

        XWPFParagraph fieldParagraph = doc.createParagraph();
        XWPFRun label = fieldParagraph.createRun();
        label.setText("Title: ");
        appendContentControl(fieldParagraph, "meeting.title", "[meeting title]");

        BigInteger numId = buildDecimalNumbering(doc);
        XWPFParagraph numbered = doc.createParagraph();
        numbered.setNumID(numId);
        numbered.createRun().setText("First action item");

        XWPFTable table = doc.createTable(1, 2);
        table.getRow(0).getCell(0).setText("Task");
        table.getRow(0).getCell(1).setText("Owner");

        XWPFParagraph imageParagraph = doc.createParagraph();
        XWPFRun imageRun = imageParagraph.createRun();
        try (ByteArrayInputStream in = new ByteArrayInputStream(TINY_PNG)) {
            imageRun.addPicture(in, PictureType.PNG, "logo.png", Units.pixelToEMU(1), Units.pixelToEMU(1));
        } catch (InvalidFormatException e) {
            throw new IOException("synthetic logo could not be embedded", e);
        }

        return write(doc);
    }

    /**
     * The same heading text, styled identically, appearing twice with
     * different body content under each -- proving repeated headings do
     * not collide or get merged: each must keep its own distinct,
     * correctly addressed node with its own following content, even
     * though their own text is identical.
     */
    static byte[] repeatedHeadingsDocument() throws IOException {
        XWPFDocument doc = new XWPFDocument();
        buildStyles(doc);

        XWPFParagraph firstHeading = doc.createParagraph();
        firstHeading.setStyle("Heading1");
        firstHeading.createRun().setText("Agenda");
        doc.createParagraph().createRun().setText("Approve last meeting's minutes.");

        XWPFParagraph secondHeading = doc.createParagraph();
        secondHeading.setStyle("Heading1");
        secondHeading.createRun().setText("Agenda");
        doc.createParagraph().createRun().setText("Discuss the budget.");

        return write(doc);
    }

    /**
     * A three-row table (one header row, two data rows), each cell holding
     * distinct text -- proving every row and cell gets its own distinct,
     * correctly addressed node rather than only the first row/cell working.
     */
    static byte[] multiRowTableDocument() throws IOException {
        XWPFDocument doc = new XWPFDocument();
        XWPFTable table = doc.createTable(3, 2);
        table.getRow(0).getCell(0).setText("Task");
        table.getRow(0).getCell(1).setText("Owner");
        table.getRow(1).getCell(0).setText("Draft agenda");
        table.getRow(1).getCell(1).setText("Jordan Lee");
        table.getRow(2).getCell(0).setText("Book the room");
        table.getRow(2).getCell(1).setText("Priya Nair");
        return write(doc);
    }

    /** A single paragraph containing a tracked insertion. */
    static byte[] withTrackedChange() throws IOException {
        XWPFDocument doc = new XWPFDocument();
        XWPFParagraph paragraph = doc.createParagraph();
        CTRunTrackChange ins = paragraph.getCTP().addNewIns();
        ins.setAuthor("Someone");
        ins.setId(BigInteger.ONE);
        CTR insertedRun = ins.addNewR();
        insertedRun.addNewT().setStringValue("inserted text");
        return write(doc);
    }

    /** A document with a real comments part and a paragraph referencing it. */
    static byte[] withComment() throws IOException {
        XWPFDocument doc = new XWPFDocument();
        doc.createComments();
        XWPFParagraph paragraph = doc.createParagraph();
        paragraph.createRun().setText("commented text");
        XWPFRun refRun = paragraph.createRun();
        refRun.getCTR().addNewCommentReference().setId(BigInteger.ZERO);
        return write(doc);
    }

    /** A paragraph with a floating (anchored, not inline) drawing. */
    static byte[] withFloatingShape() throws IOException {
        XWPFDocument doc = new XWPFDocument();
        XWPFParagraph paragraph = doc.createParagraph();
        CTR run = paragraph.createRun().getCTR();
        var drawing = run.addNewDrawing();
        drawing.addNewAnchor();
        return write(doc);
    }

    /** A table whose one cell contains another table. */
    static byte[] withNestedTable() throws IOException {
        XWPFDocument doc = new XWPFDocument();
        XWPFTable outer = doc.createTable(1, 1);
        XWPFTableCell outerCell = outer.getRow(0).getCell(0);
        try (var cursor = outerCell.getParagraphs().get(0).getCTP().newCursor()) {
            outerCell.insertNewTbl(cursor);
        }
        return write(doc);
    }

    /**
     * An inline image whose blip carries both an embedded relationship
     * (real Word behavior for "Insert and Link", and the reason POI can
     * still open the file at all -- a link-only blip with no embedded
     * fallback fails even POI's own document loader) and an {@code r:link}
     * attribute alongside it, which is what this extractor actually keys
     * its detection on.
     *
     * <p>Built by editing the run's own already-generated drawing XML as
     * text and reparsing it, rather than mutating the live XML tree
     * through an {@code XmlCursor} positioned at the blip's start tag:
     * that seemingly equivalent approach was tried first and empirically
     * confirmed (via a throwaway exploration script, not assumed) to
     * insert the new attribute onto the blip's *parent* element instead
     * of the blip itself -- a real, reproducible {@code XmlCursor} quirk
     * around inserting an attribute immediately after landing on a START
     * token from generic token-by-token traversal.
     */
    static byte[] withLinkedExternalImage() throws IOException {
        XWPFDocument doc = new XWPFDocument();
        XWPFParagraph paragraph = doc.createParagraph();
        XWPFRun run = paragraph.createRun();
        try (ByteArrayInputStream in = new ByteArrayInputStream(TINY_PNG)) {
            run.addPicture(in, PictureType.PNG, "logo.png", Units.pixelToEMU(1), Units.pixelToEMU(1));
        } catch (InvalidFormatException e) {
            throw new IOException(e);
        }
        String originalXml = run.getCTR().getDrawingArray(0).xmlText();
        String withLink = originalXml.replaceFirst("(rel:embed=\"[^\"]+\")", "$1 rel:link=\"rId99\"");
        try {
            run.getCTR().setDrawingArray(0, org.openxmlformats.schemas.wordprocessingml.x2006.main.CTDrawing.Factory.parse(withLink));
        } catch (org.apache.xmlbeans.XmlException e) {
            throw new IOException(e);
        }
        return write(doc);
    }

    /** A run containing an embedded OLE object. */
    static byte[] withEmbeddedObject() throws IOException {
        XWPFDocument doc = new XWPFDocument();
        XWPFParagraph paragraph = doc.createParagraph();
        CTR run = paragraph.createRun().getCTR();
        run.addNewObject();
        return write(doc);
    }

    /** A complex field (fldChar begin/instrText/separate/end) whose instruction is a merge field, one of a form's own blanks. */
    static byte[] withUnsupportedField() throws IOException {
        return withComplexField(" MERGEFIELD meeting.title ");
    }

    /** A complex field (fldChar begin/instrText/separate/end) with the given instruction, showing "placeholder". */
    static byte[] withComplexField(String instruction) throws IOException {
        XWPFDocument doc = new XWPFDocument();
        XWPFParagraph paragraph = doc.createParagraph();
        XWPFRun begin = paragraph.createRun();
        begin.getCTR().addNewFldChar().setFldCharType(STFldCharType.BEGIN);
        XWPFRun instr = paragraph.createRun();
        instr.getCTR().addNewInstrText().setStringValue(instruction);
        XWPFRun sep = paragraph.createRun();
        sep.getCTR().addNewFldChar().setFldCharType(STFldCharType.SEPARATE);
        XWPFRun result = paragraph.createRun();
        result.setText("placeholder");
        XWPFRun end = paragraph.createRun();
        end.getCTR().addNewFldChar().setFldCharType(STFldCharType.END);
        return write(doc);
    }

    /** A supported PAGE field, which must NOT be flagged. */
    static byte[] withPageField() throws IOException {
        XWPFDocument doc = new XWPFDocument();
        XWPFFooter footer = doc.createFooter(HeaderFooterType.DEFAULT);
        XWPFParagraph paragraph = footer.createParagraph();
        var fldSimple = paragraph.getCTP().addNewFldSimple();
        fldSimple.setInstr(" PAGE ");
        fldSimple.addNewR().addNewT().setStringValue("1");
        return write(doc);
    }

    /**
     * Every place a paragraph or a table can hold something that does not
     * get a node of its own, beside the things that do: runs directly in a
     * paragraph, in a hyperlink and in an inline content control; bookmarks,
     * proofing marks, a smart tag, a custom XML wrapper and a simple field,
     * which the walk passes over; a content control around a whole
     * paragraph, in the body, in a table cell and in the header; content
     * controls around a whole table row and a whole cell; and runs holding
     * tabs, breaks, a non-breaking hyphen and checkbox symbols between
     * their text. Written as XML because the library has no way to build
     * most of these.
     */
    static byte[] walkerEdgeCasesDocument() throws IOException {
        String body = "<w:p>"
                + "<w:bookmarkStart w:id=\"0\" w:name=\"start\"/>"
                + "<w:r><w:t xml:space=\"preserve\">Name:</w:t><w:tab/><w:t>here</w:t></w:r>"
                + "<w:proofErr w:type=\"spellStart\"/>"
                + "<w:hyperlink r:id=\"rIdLink\"><w:r><w:t>link one</w:t></w:r><w:r><w:t>link two</w:t></w:r></w:hyperlink>"
                + "<w:proofErr w:type=\"spellEnd\"/>"
                + "<w:smartTag w:uri=\"urn:example\" w:element=\"place\"><w:r><w:t>Hidden city</w:t></w:r></w:smartTag>"
                + "<w:customXml w:element=\"note\"><w:r><w:t>hidden custom</w:t></w:r></w:customXml>"
                + "<w:sdt><w:sdtPr><w:tag w:val=\"client.name\"/></w:sdtPr><w:sdtContent>"
                + "<w:r><w:t>[client]</w:t></w:r><w:r><w:br/><w:t>second</w:t></w:r></w:sdtContent></w:sdt>"
                + "<w:fldSimple w:instr=\" PAGE \"><w:r><w:t>1</w:t></w:r></w:fldSimple>"
                + "<w:bookmarkEnd w:id=\"0\"/>"
                + "<w:r><w:sym w:font=\"Wingdings\" w:char=\"F0A8\"/><w:t xml:space=\"preserve\"> Yes </w:t>"
                + "<w:sym w:font=\"Wingdings\" w:char=\"F0FE\"/><w:t xml:space=\"preserve\"> No</w:t></w:r>"
                + "<w:r><w:t>well</w:t><w:noBreakHyphen/><w:t>known</w:t><w:cr/><w:t>end</w:t>"
                + "<w:ptab w:relativeTo=\"margin\" w:alignment=\"right\" w:leader=\"none\"/><w:t>x</w:t></w:r>"
                + "</w:p>"
                + "<w:sdt><w:sdtPr><w:tag w:val=\"block.one\"/></w:sdtPr><w:sdtContent>"
                + "<w:p><w:r><w:t>Inside a block control</w:t></w:r></w:p></w:sdtContent></w:sdt>"
                + "<w:tbl><w:tblPr/><w:tblGrid><w:gridCol w:w=\"2000\"/><w:gridCol w:w=\"2000\"/></w:tblGrid>"
                + "<w:tr><w:tc><w:p><w:r><w:t>A1</w:t></w:r></w:p>"
                + "<w:sdt><w:sdtContent><w:p><w:r><w:t>block in cell</w:t></w:r></w:p></w:sdtContent></w:sdt>"
                + "<w:p><w:r><w:t>A1 second</w:t></w:r></w:p></w:tc>"
                + "<w:sdt><w:sdtContent><w:tc><w:p><w:r><w:t>cell control</w:t></w:r></w:p></w:tc></w:sdtContent></w:sdt>"
                + "<w:tc><w:p><w:r><w:t>C1</w:t></w:r></w:p></w:tc></w:tr>"
                + "<w:sdt><w:sdtContent><w:tr><w:tc><w:p><w:r><w:t>row control</w:t></w:r></w:p></w:tc></w:tr></w:sdtContent></w:sdt>"
                + "<w:tr><w:tc><w:p><w:r><w:t>B1</w:t></w:r></w:p></w:tc><w:tc><w:p/></w:tc></w:tr>"
                + "</w:tbl>"
                + "<w:p><w:r><w:t>closing</w:t></w:r></w:p>"
                + "<w:sectPr><w:headerReference w:type=\"default\" r:id=\"rIdHeader\"/></w:sectPr>";
        String header = "<w:sdt><w:sdtContent><w:p><w:r><w:t>header control</w:t></w:r></w:p></w:sdtContent></w:sdt>"
                + "<w:p><w:r><w:t>Header text</w:t><w:tab/><w:t>right</w:t></w:r></w:p>";
        return RawDocx.builder()
                .document(body)
                .part("word/header1.xml", RawDocx.HEADER_CONTENT_TYPE, RawDocx.wordRoot("hdr", header))
                .documentRelationship("rIdHeader", RawDocx.RELATIONSHIP_TYPE_BASE + "header", "header1.xml", false)
                .documentRelationship("rIdLink", RawDocx.RELATIONSHIP_TYPE_BASE + "hyperlink", "https://example.com/", true)
                .build();
    }

    /** Bytes that look enough like an OOXML package to be classified as DOCX, but whose main document part is not well-formed XML. */
    static byte[] corruptPackage() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (var zip = new java.util.zip.ZipOutputStream(out)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("[Content_Types].xml"));
            zip.write("<Types/>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new java.util.zip.ZipEntry("word/document.xml"));
            zip.write("<not-well-formed-xml".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return out.toByteArray();
    }

    private static void appendContentControl(XWPFParagraph paragraph, String tag, String text) {
        CTP ctp = paragraph.getCTP();
        CTSdtRun sdt = ctp.addNewSdt();
        CTSdtPr sdtPr = sdt.addNewSdtPr();
        sdtPr.addNewTag().setVal(tag);
        sdtPr.addNewAlias().setVal(tag);
        CTSdtContentRun content = sdt.addNewSdtContent();
        CTR run = content.addNewR();
        run.addNewT().setStringValue(text);
    }

    private static BigInteger buildDecimalNumbering(XWPFDocument doc) {
        XWPFNumbering numbering = doc.createNumbering();
        CTAbstractNum ctAbstractNum = CTAbstractNum.Factory.newInstance();
        ctAbstractNum.setAbstractNumId(BigInteger.ZERO);
        CTLvl level = ctAbstractNum.addNewLvl();
        level.setIlvl(BigInteger.ZERO);
        level.addNewNumFmt().setVal(STNumberFormat.DECIMAL);
        BigInteger abstractNumId = numbering.addAbstractNum(new XWPFAbstractNum(ctAbstractNum));
        return numbering.addNum(abstractNumId);
    }

    /**
     * A style hierarchy exercising basedOn resolution: docDefaults sets an
     * 11pt Liberation Sans baseline; "Heading1" is basedOn "Normal" and
     * itself only sets bold and a larger size, so its font family must
     * resolve from docDefaults, not from either style directly.
     */
    private static void buildStyles(XWPFDocument doc) {
        XWPFStyles styles = doc.createStyles();
        CTStyles ctStyles = styles.getCtStyles();

        CTDocDefaults docDefaults = ctStyles.addNewDocDefaults();
        CTRPr defaultRPr = docDefaults.addNewRPrDefault().addNewRPr();
        defaultRPr.addNewRFonts().setAscii("Liberation Sans");
        defaultRPr.addNewSz().setVal(BigInteger.valueOf(22));

        CTStyle normal = ctStyles.addNewStyle();
        normal.setStyleId("Normal");
        normal.setType(STStyleType.PARAGRAPH);
        normal.addNewName().setVal("Normal");

        CTStyle heading = ctStyles.addNewStyle();
        heading.setStyleId("Heading1");
        heading.setType(STStyleType.PARAGRAPH);
        heading.addNewName().setVal("heading 1");
        heading.addNewBasedOn().setVal("Normal");
        CTRPr headingRPr = heading.addNewRPr();
        headingRPr.addNewB();
        headingRPr.addNewSz().setVal(BigInteger.valueOf(32));
    }

    /** A single paragraph with two runs sharing the identical explicit red color -- exercises {@code colorHex} resolution and its own cross-run equality, independent of every other style property this fixture set intentionally leaves untouched. */
    static byte[] documentWithTwoIdenticallyColoredRuns() throws IOException {
        XWPFDocument doc = new XWPFDocument();
        XWPFParagraph paragraph = doc.createParagraph();
        XWPFRun first = paragraph.createRun();
        first.setText("Red text one");
        first.setColor("FF0000");
        XWPFRun second = paragraph.createRun();
        second.setText("Red text two");
        second.setColor("FF0000");
        return write(doc);
    }

    private static byte[] write(XWPFDocument doc) throws IOException {
        try (doc) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.write(out);
            return out.toByteArray();
        }
    }
}
