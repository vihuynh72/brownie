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

    /** A complex field (fldChar begin/instrText/separate/end) whose instruction is not PAGE. */
    static byte[] withUnsupportedField() throws IOException {
        XWPFDocument doc = new XWPFDocument();
        XWPFParagraph paragraph = doc.createParagraph();
        XWPFRun begin = paragraph.createRun();
        begin.getCTR().addNewFldChar().setFldCharType(STFldCharType.BEGIN);
        XWPFRun instr = paragraph.createRun();
        instr.getCTR().addNewInstrText().setStringValue(" MERGEFIELD meeting.title ");
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

    private static byte[] write(XWPFDocument doc) throws IOException {
        try (doc) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.write(out);
            return out.toByteArray();
        }
    }
}
