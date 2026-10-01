package io.github.vihuynh72.brownie.api.document.docx.prepare;

import io.github.vihuynh72.brownie.api.document.docx.RawDocx;

import java.io.IOException;
import java.util.Base64;

/**
 * Word files carrying what the working copy takes out or keeps, written as
 * XML the way Word writes it: every kind of tracked change, comments with
 * their newer companion parts, fields that fetch or run something beside
 * form fields and page numbers, embedded objects of every kind, macro,
 * ActiveX and signature parts, template and macro-enabled content types,
 * editing restrictions, links to local and internet files, a floating text
 * box, a table inside a table, and content controls and wrappers at every
 * level. Each fixture's own javadoc says what a person sees in it.
 */
final class DirtyDocx {

    static final String RELATIONSHIPS = RawDocx.RELATIONSHIP_TYPE_BASE;
    static final String MS_RELATIONSHIPS = "http://schemas.microsoft.com/office/2006/relationships/";
    static final String IMAGE = RELATIONSHIPS + "image";
    static final String OLE_OBJECT = RELATIONSHIPS + "oleObject";
    static final String PACKAGE = RELATIONSHIPS + "package";
    static final String CONTROL = RELATIONSHIPS + "control";
    static final String SETTINGS = RELATIONSHIPS + "settings";
    static final String FOOTNOTES = RELATIONSHIPS + "footnotes";

    static final String DOTX_MAIN = "application/vnd.openxmlformats-officedocument.wordprocessingml.template.main+xml";
    static final String DOCM_MAIN = "application/vnd.ms-word.document.macroEnabled.main+xml";
    static final String DOTM_MAIN = "application/vnd.ms-word.template.macroEnabledTemplate.main+xml";

    private static final String TRACK = " w:id=\"%d\" w:author=\"Reviewer\" w:date=\"2026-01-01T00:00:00Z\"";

    /** A real 1x1 PNG, for previews and pictures. */
    static final byte[] TINY_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");

    /** Not a real compound file or package: an object that embeds this is never kept. */
    static final byte[] FAKE_BINARY = "not really a compound file".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    static final String SHEET = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    static final String SHEET_MAIN = SHEET + ".main+xml";

    /**
     * An Office Open XML document as an object embeds it: a package declaring
     * {@code mainContentType} for its main part, with {@code otherParts} (names
     * such as {@code xl/vbaProject.bin}) beside it.
     */
    static byte[] officePackage(String mainContentType, String... otherParts) {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(bytes)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("[Content_Types].xml"));
            zip.write(("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                    + "<Override PartName=\"/main.xml\" ContentType=\"" + mainContentType + "\"/></Types>")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.putNextEntry(new java.util.zip.ZipEntry("main.xml"));
            zip.write("<main/>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            for (String part : otherParts) {
                zip.putNextEntry(new java.util.zip.ZipEntry(part));
                zip.write(new byte[] {1});
            }
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    /** An older compound file whose root names {@code program}, holding one stream and the storages named. */
    static byte[] compoundFile(org.apache.poi.hpsf.ClassIDPredefined program, String... storages) {
        try (org.apache.poi.poifs.filesystem.POIFSFileSystem container = new org.apache.poi.poifs.filesystem.POIFSFileSystem()) {
            container.getRoot().setStorageClsid(program.getClassID());
            container.getRoot().createDocument("Contents", new java.io.ByteArrayInputStream(new byte[] {1, 2, 3}));
            for (String storage : storages) {
                container.getRoot().createDirectory(storage);
            }
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            container.writeFilesystem(bytes);
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private DirtyDocx() {
    }

    static String run(String text) {
        return "<w:r><w:t xml:space=\"preserve\">" + text + "</w:t></w:r>";
    }

    static String paragraph(String... runs) {
        return "<w:p>" + String.join("", runs) + "</w:p>";
    }

    static String settings(String inner) {
        return RawDocx.wordRoot("settings", inner);
    }

    /** A complex field: its code, then its result runs. */
    static String field(String instruction, String result) {
        return "<w:r><w:fldChar w:fldCharType=\"begin\"/></w:r>"
                + "<w:r><w:instrText xml:space=\"preserve\"> " + instruction + " </w:instrText></w:r>"
                + "<w:r><w:fldChar w:fldCharType=\"separate\"/></w:r>"
                + run(result)
                + "<w:r><w:fldChar w:fldCharType=\"end\"/></w:r>";
    }

    /** An embedded or linked object shown by its preview picture, the way Word writes one. */
    static String object(String progId, String type, String objectRelationshipId, String previewRelationshipId) {
        return "<w:r><w:object w:dxaOrig=\"1440\" w:dyaOrig=\"720\">"
                + "<v:shape id=\"_x0000_i_" + objectRelationshipId + "\" type=\"#_x0000_t75\" style=\"width:72pt;height:36pt\" o:ole=\"\">"
                + "<v:imagedata r:id=\"" + previewRelationshipId + "\" o:title=\"\"/></v:shape>"
                + "<o:OLEObject Type=\"" + type + "\" ProgID=\"" + progId + "\" ShapeID=\"_x0000_i1025\" DrawAspect=\"Content\""
                + " ObjectID=\"_1\" r:id=\"" + objectRelationshipId + "\"/>"
                + "</w:object></w:r>";
    }

    /**
     * Every kind of tracked change. Shown with all of them accepted, the
     * body reads: "Keep added end." / an empty line, where the text moved away from /
     * "moved" / "Bold now" / "Centred now" /
     * "First half second half" / a table with the rows "Stay" and "New row" /
     * "Inserted mark"; the header reads "Header added"; the footnote reads
     * "Note kept".
     */
    static byte[] revisions() {
        return build(revisionParts(RawDocx.builder().document(revisionBody()))
                .part("word/settings.xml", RawDocx.SETTINGS_CONTENT_TYPE, settings("<w:trackRevisions/>"))
                .documentRelationship("rIdSettings", SETTINGS, "settings.xml", false));
    }

    /** Ends with the section properties, so it goes last in a body. */
    static String revisionBody() {
        return "<w:p>" + run("Keep ")
                + "<w:ins" + TRACK.formatted(1) + ">" + run("added ") + "</w:ins>"
                + "<w:del" + TRACK.formatted(2) + "><w:r><w:delText xml:space=\"preserve\">removed </w:delText></w:r></w:del>"
                + run("end.") + "</w:p>"
                + "<w:p><w:moveFromRangeStart w:id=\"3\" w:name=\"move1\" w:author=\"Reviewer\"/>"
                + "<w:moveFrom" + TRACK.formatted(4) + "><w:r><w:t>moved</w:t></w:r></w:moveFrom>"
                + "<w:moveFromRangeEnd w:id=\"3\"/></w:p>"
                + "<w:p><w:moveToRangeStart w:id=\"5\" w:name=\"move1\" w:author=\"Reviewer\"/>"
                + "<w:moveTo" + TRACK.formatted(6) + "><w:r><w:t>moved</w:t></w:r></w:moveTo>"
                + "<w:moveToRangeEnd w:id=\"5\"/></w:p>"
                + "<w:p><w:r><w:rPr><w:b/><w:rPrChange" + TRACK.formatted(7) + "><w:rPr/></w:rPrChange></w:rPr>"
                + "<w:t>Bold now</w:t></w:r></w:p>"
                + "<w:p><w:pPr><w:jc w:val=\"center\"/><w:pPrChange" + TRACK.formatted(8) + "><w:pPr><w:jc w:val=\"left\"/></w:pPr>"
                + "</w:pPrChange></w:pPr>" + run("Centred now") + "</w:p>"
                + "<w:p><w:pPr><w:rPr><w:del" + TRACK.formatted(9) + "/></w:rPr></w:pPr>" + run("First half ") + "</w:p>"
                + "<w:p><w:pPr><w:jc w:val=\"right\"/></w:pPr>" + run("second half") + "</w:p>"
                + "<w:tbl><w:tblPr><w:tblW w:w=\"0\" w:type=\"auto\"/><w:tblPrChange" + TRACK.formatted(10)
                + "><w:tblPr/></w:tblPrChange></w:tblPr><w:tblGrid><w:gridCol w:w=\"4000\"/></w:tblGrid>"
                + "<w:tr><w:tc><w:tcPr><w:tcW w:w=\"4000\" w:type=\"dxa\"/><w:tcPrChange" + TRACK.formatted(11)
                + "><w:tcPr/></w:tcPrChange></w:tcPr>" + paragraph(run("Stay")) + "</w:tc></w:tr>"
                + "<w:tr><w:trPr><w:del" + TRACK.formatted(12) + "/></w:trPr><w:tc>" + paragraph(run("Gone row")) + "</w:tc></w:tr>"
                + "<w:tr><w:trPr><w:ins" + TRACK.formatted(13) + "/><w:trPrChange" + TRACK.formatted(14)
                + "><w:trPr/></w:trPrChange></w:trPr><w:tc>" + paragraph(run("New row")) + "</w:tc></w:tr>"
                + "</w:tbl>"
                + "<w:p><w:pPr><w:rPr><w:ins" + TRACK.formatted(15) + "/></w:rPr></w:pPr>" + run("Inserted mark")
                + "<w:r><w:footnoteReference w:id=\"1\"/></w:r></w:p>"
                + "<w:sectPr><w:headerReference w:type=\"default\" r:id=\"rIdHeader\"/><w:pgSz w:w=\"12240\" w:h=\"15840\"/>"
                + "<w:sectPrChange" + TRACK.formatted(16) + "><w:sectPr/></w:sectPrChange></w:sectPr>";
    }

    static RawDocx.Builder revisionParts(RawDocx.Builder builder) {
        String header = "<w:p>" + run("Header ") + "<w:ins" + TRACK.formatted(17) + ">" + run("added") + "</w:ins></w:p>";
        String footnotes = "<w:footnote w:type=\"separator\" w:id=\"-1\"><w:p><w:r><w:separator/></w:r></w:p></w:footnote>"
                + "<w:footnote w:type=\"continuationSeparator\" w:id=\"0\"><w:p><w:r><w:continuationSeparator/></w:r></w:p></w:footnote>"
                + "<w:footnote w:id=\"1\"><w:p>" + run("Note ")
                + "<w:del" + TRACK.formatted(18) + "><w:r><w:delText xml:space=\"preserve\">dropped </w:delText></w:r></w:del>"
                + run("kept") + "</w:p></w:footnote>";
        return builder
                .part("word/header1.xml", RawDocx.HEADER_CONTENT_TYPE, RawDocx.wordRoot("hdr", header))
                .documentRelationship("rIdHeader", RELATIONSHIPS + "header", "header1.xml", false)
                .part("word/footnotes.xml", "application/vnd.openxmlformats-officedocument.wordprocessingml.footnotes+xml",
                        RawDocx.wordRoot("footnotes", footnotes))
                .documentRelationship("rIdFootnotes", FOOTNOTES, "footnotes.xml", false);
    }

    /**
     * Two comments, with the parts Word 2013 and later keep beside them. The
     * body reads "Please sign here today."
     */
    static byte[] comments() {
        return build(commentParts(RawDocx.builder().document(commentBody())));
    }

    static String commentBody() {
        return "<w:p>" + run("Please ")
                + "<w:commentRangeStart w:id=\"0\"/>" + run("sign here")
                + "<w:commentRangeEnd w:id=\"0\"/><w:r><w:rPr><w:rStyle w:val=\"CommentReference\"/></w:rPr><w:commentReference w:id=\"0\"/></w:r>"
                + run(" today.")
                + "<w:r><w:commentReference w:id=\"1\"/></w:r></w:p>";
    }

    static RawDocx.Builder commentParts(RawDocx.Builder builder) {
        String comments = "<w:comment w:id=\"0\" w:author=\"Ana\" w:initials=\"A\"><w:p><w:r><w:annotationRef/></w:r>"
                + run("Is this the right place?") + "</w:p></w:comment>"
                + "<w:comment w:id=\"1\" w:author=\"Ben\" w:initials=\"B\"><w:p>" + run("Private note") + "</w:p></w:comment>";
        String extended = "<w15:commentsEx xmlns:w15=\"http://schemas.microsoft.com/office/word/2012/wordml\">"
                + "<w15:commentEx w15:paraId=\"1A2B3C4D\" w15:done=\"0\"/></w15:commentsEx>";
        String ids = "<w16cid:commentsIds xmlns:w16cid=\"http://schemas.microsoft.com/office/word/2016/wordml/cid\">"
                + "<w16cid:commentId w16cid:paraId=\"1A2B3C4D\" w16cid:durableId=\"2A3B4C5D\"/></w16cid:commentsIds>";
        String extensible = "<w16cex:commentsExtensible xmlns:w16cex=\"http://schemas.microsoft.com/office/word/2018/wordml/cex\">"
                + "<w16cex:commentExtensible w16cex:durableId=\"2A3B4C5D\" w16cex:dateUtc=\"2026-01-01T00:00:00Z\"/></w16cex:commentsExtensible>";
        String people = "<w15:people xmlns:w15=\"http://schemas.microsoft.com/office/word/2012/wordml\">"
                + "<w15:person w15:author=\"Ana\"><w15:presenceInfo w15:providerId=\"None\" w15:userId=\"Ana\"/></w15:person></w15:people>";
        return builder
                .part("word/comments.xml", "application/vnd.openxmlformats-officedocument.wordprocessingml.comments+xml",
                        RawDocx.wordRoot("comments", comments))
                .documentRelationship("rIdComments", RELATIONSHIPS + "comments", "comments.xml", false)
                .part("word/commentsExtended.xml", "application/vnd.openxmlformats-officedocument.wordprocessingml.commentsExtended+xml",
                        xml(extended))
                .documentRelationship("rIdCommentsEx", "http://schemas.microsoft.com/office/2011/relationships/commentsExtended",
                        "commentsExtended.xml", false)
                .part("word/commentsIds.xml", "application/vnd.openxmlformats-officedocument.wordprocessingml.commentsIds+xml", xml(ids))
                .documentRelationship("rIdCommentsIds", "http://schemas.microsoft.com/office/2016/09/relationships/commentsIds",
                        "commentsIds.xml", false)
                .part("word/commentsExtensible.xml", "application/vnd.openxmlformats-officedocument.wordprocessingml.commentsExtensible+xml",
                        xml(extensible))
                .documentRelationship("rIdCommentsExt", "http://schemas.microsoft.com/office/2018/08/relationships/commentsExtensible",
                        "commentsExtensible.xml", false)
                .part("word/people.xml", "application/vnd.openxmlformats-officedocument.wordprocessingml.people+xml", xml(people))
                .documentRelationship("rIdPeople", "http://schemas.microsoft.com/office/2011/relationships/people", "people.xml", false);
    }

    /**
     * Fields of every treatment. The body reads, as Word shows it: "Data: dde
     * result" / "Clause: included text" / "Split: split result" / "Picture:
     * linked picture" / "Name: \u00ABclient.name\u00BB" / "Answer: typed answer" / "Click
     * here" / "Text: typed" / "Agree: " / "Page 3" / "Outer: first line" and
     * "second line", one field's result over two paragraphs / "Pages: 7".
     */
    static byte[] fields() {
        return build(RawDocx.builder().document(fieldBody()));
    }

    static String fieldBody() {
        return paragraph(run("Data: "), field("DDEAUTO c:\\\\windows\\\\system32\\\\cmd.exe \"/k calc.exe\"", "dde result"))
                + paragraph(run("Clause: "), field("INCLUDETEXT \"C:\\\\\\\\share\\\\\\\\clause.docx\"", "included text"))
                + paragraph(run("Split: "),
                        "<w:r><w:fldChar w:fldCharType=\"begin\"/></w:r><w:r><w:instrText xml:space=\"preserve\"> INCLUDE</w:instrText></w:r>"
                        + "<w:r><w:instrText xml:space=\"preserve\">TEXT \"a.docx\" </w:instrText></w:r>"
                        + "<w:r><w:fldChar w:fldCharType=\"separate\"/></w:r>" + run("split result")
                        + "<w:r><w:fldChar w:fldCharType=\"end\"/></w:r>")
                + paragraph(run("Picture: "), "<w:fldSimple w:instr=\" INCLUDEPICTURE &quot;https://example.com/p.png&quot; \\d \">"
                        + run("linked picture") + "</w:fldSimple>")
                + paragraph(run("Name: "), field("MERGEFIELD client.name \\* MERGEFORMAT", "\u00ABclient.name\u00BB"))
                + paragraph(run("Answer: "), field("FILLIN \"What is your answer?\"", "typed answer"))
                + paragraph(field("MACROBUTTON NoMacro Click here", "Click here"))
                + paragraph(run("Text: "), "<w:r><w:fldChar w:fldCharType=\"begin\"><w:ffData><w:name w:val=\"Text1\"/>"
                        + "<w:enabled/><w:calcOnExit w:val=\"0\"/><w:textInput/></w:ffData></w:fldChar></w:r>"
                        + "<w:r><w:instrText xml:space=\"preserve\"> FORMTEXT </w:instrText></w:r>"
                        + "<w:r><w:fldChar w:fldCharType=\"separate\"/></w:r>" + run("typed") + "<w:r><w:fldChar w:fldCharType=\"end\"/></w:r>")
                + paragraph(run("Agree: "), "<w:r><w:fldChar w:fldCharType=\"begin\"><w:ffData><w:name w:val=\"Check1\"/>"
                        + "<w:enabled/><w:calcOnExit w:val=\"0\"/><w:checkBox><w:sizeAuto/><w:default w:val=\"0\"/></w:checkBox></w:ffData>"
                        + "</w:fldChar></w:r><w:r><w:instrText xml:space=\"preserve\"> FORMCHECKBOX </w:instrText></w:r>"
                        + "<w:r><w:fldChar w:fldCharType=\"end\"/></w:r>")
                + paragraph(run("Page "), field("PAGE", "3"))
                + "<w:p>" + run("Outer: ") + "<w:r><w:fldChar w:fldCharType=\"begin\"/></w:r>"
                + "<w:r><w:instrText xml:space=\"preserve\"> INCLUDETEXT \"b.docx\" </w:instrText></w:r>"
                + "<w:r><w:fldChar w:fldCharType=\"separate\"/></w:r>" + run("first line") + "</w:p>"
                + "<w:p>" + run("second line") + "<w:r><w:fldChar w:fldCharType=\"end\"/></w:r></w:p>"
                + paragraph(run("Pages: "), "<w:fldSimple w:instr=\" NUMPAGES \">" + run("7") + "</w:fldSimple>");
    }

    /**
     * Embedded objects of every kind, each shown by its preview picture: a
     * packaged file, an old equation, a spreadsheet (allowed), an ActiveX
     * text box, a Word document linked from a local path, and a packaged
     * file with no preview at all. The body reads "Objects:" and nothing
     * else; the pictures carry no text.
     */
    static byte[] objects() {
        return build(objectParts(RawDocx.builder().document(objectBody())));
    }

    static String objectBody() {
        return paragraph(run("Objects:"))
                + paragraph(object("Package", "Embed", "rIdPackager", "rIdPreview1"))
                + paragraph(object("Equation.3", "Embed", "rIdEquation", "rIdPreview2"))
                + paragraph(object("Excel.Sheet.12", "Embed", "rIdSheet", "rIdPreview3"))
                + paragraph("<w:r><w:object w:dxaOrig=\"1440\" w:dyaOrig=\"720\">"
                        + "<v:shape id=\"_x0000_i1030\" type=\"#_x0000_t75\" style=\"width:72pt;height:18pt\" o:ole=\"\">"
                        + "<v:imagedata r:id=\"rIdPreview4\" o:title=\"\"/></v:shape>"
                        + "<w:control r:id=\"rIdControl\" w:name=\"TextBox1\" w:shapeid=\"_x0000_i1030\"/></w:object></w:r>")
                + paragraph(object("Word.Document.12", "Link", "rIdLinked", "rIdPreview5"))
                + paragraph("<w:r><w:object><o:OLEObject Type=\"Embed\" ProgID=\"Package\" ShapeID=\"_x0000_i1040\" ObjectID=\"_9\""
                        + " r:id=\"rIdBare\"/></w:object></w:r>");
    }

    static RawDocx.Builder objectParts(RawDocx.Builder builder) {
        for (int i = 1; i <= 5; i++) {
            builder.part("word/media/preview" + i + ".png", null, TINY_PNG)
                    .documentRelationship("rIdPreview" + i, IMAGE, "media/preview" + i + ".png", false);
        }
        return builder
                .part("word/embeddings/oleObject1.bin", null, FAKE_BINARY)
                .documentRelationship("rIdPackager", OLE_OBJECT, "embeddings/oleObject1.bin", false)
                .part("word/embeddings/oleObject2.bin", null, FAKE_BINARY)
                .documentRelationship("rIdEquation", OLE_OBJECT, "embeddings/oleObject2.bin", false)
                .part("word/embeddings/Microsoft_Excel_Worksheet.xlsx", SHEET, officePackage(SHEET_MAIN))
                .documentRelationship("rIdSheet", PACKAGE, "embeddings/Microsoft_Excel_Worksheet.xlsx", false)
                .part("word/activeX/activeX1.xml", "application/vnd.ms-office.activeX+xml",
                        xml("<ax:ocx xmlns:ax=\"http://schemas.microsoft.com/office/2006/activeX\" ax:classid=\"{8BD21D10-EC42-11CE-9E0D-00AA006002F3}\""
                                + " ax:persistence=\"persistStorage\" r:id=\"rId1\" xmlns:r=\"" + RawDocx.R + "\"/>"))
                .relationship("word/activeX/activeX1.xml", "rId1", MS_RELATIONSHIPS + "activeXControlBinary", "activeX1.bin", false)
                .part("word/activeX/activeX1.bin", "application/vnd.ms-office.activeX", FAKE_BINARY)
                .documentRelationship("rIdControl", CONTROL, "activeX/activeX1.xml", false)
                .documentRelationship("rIdLinked", OLE_OBJECT, "file:///C:/docs/linked.docx", true)
                .part("word/embeddings/oleObject3.bin", null, FAKE_BINARY)
                .documentRelationship("rIdBare", OLE_OBJECT, "embeddings/oleObject3.bin", false);
    }

    /**
     * A macro-enabled file (or, with {@code mainContentType}, a template of
     * either kind) with a macro project, its data, keyboard customizations,
     * a digital signature and form protection. The body reads "Macro file".
     */
    static byte[] macrosSignatureAndProtection(String mainContentType) {
        return build(macroAndSignatureParts(RawDocx.builder().mainContentType(mainContentType).document(paragraph(run("Macro file"))))
                .part("word/settings.xml", RawDocx.SETTINGS_CONTENT_TYPE, settings(PROTECTION))
                .documentRelationship("rIdSettings", SETTINGS, "settings.xml", false));
    }

    static final String PROTECTION = "<w:writeProtection w:recommended=\"1\"/><w:documentProtection w:edit=\"forms\" w:enforcement=\"1\"/>";

    static RawDocx.Builder macroAndSignatureParts(RawDocx.Builder builder) {
        return builder
                .part("word/vbaProject.bin", "application/vnd.ms-office.vbaProject", FAKE_BINARY)
                .documentRelationship("rIdVba", MS_RELATIONSHIPS + "vbaProject", "vbaProject.bin", false)
                .part("word/vbaData.xml", "application/vnd.ms-word.vbaData+xml",
                        xml("<wne:vbaSuppData xmlns:wne=\"http://schemas.microsoft.com/office/word/2006/wordml\"/>"))
                .relationship("word/vbaProject.bin", "rId1", MS_RELATIONSHIPS + "wordVbaData", "vbaData.xml", false)
                .part("word/customizations.xml", "application/vnd.ms-word.keyMapCustomizations+xml",
                        xml("<wne:tcg xmlns:wne=\"http://schemas.microsoft.com/office/word/2006/wordml\"/>"))
                .documentRelationship("rIdKeys", MS_RELATIONSHIPS + "keyMapCustomizations", "customizations.xml", false)
                .part("_xmlsignatures/origin.sigs", "application/vnd.openxmlformats-package.digital-signature-origin", new byte[0])
                .packageRelationship("rIdSig", "http://schemas.openxmlformats.org/package/2006/relationships/digital-signature/origin",
                        "_xmlsignatures/origin.sigs")
                .part("_xmlsignatures/sig1.xml", "application/vnd.openxmlformats-package.digital-signature-xmlsignature+xml",
                        xml("<Signature xmlns=\"http://www.w3.org/2000/09/xmldsig#\"/>"))
                .relationship("_xmlsignatures/origin.sigs", "rId1",
                        "http://schemas.openxmlformats.org/package/2006/relationships/digital-signature/signature", "sig1.xml", false);
    }

    /**
     * Links to files outside: a template, a picture and a document on local
     * or shared paths, and a template and a picture on the internet. The
     * body reads "Linked:" and a sub-document the file only points at.
     */
    static byte[] links() {
        String picture = "<w:r><w:drawing><wp:inline><wp:extent cx=\"9525\" cy=\"9525\"/><wp:docPr id=\"1\" name=\"Picture 1\"/>"
                + "<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><pic:pic>"
                + "<pic:nvPicPr><pic:cNvPr id=\"1\" name=\"p.png\"/><pic:cNvPicPr/></pic:nvPicPr>"
                + "<pic:blipFill><a:blip r:embed=\"rIdEmbedded\" r:link=\"%s\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>"
                + "<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"9525\" cy=\"9525\"/></a:xfrm><a:prstGeom prst=\"rect\"/></pic:spPr>"
                + "</pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r>";
        String body = paragraph(run("Linked:"))
                + paragraph(picture.formatted("rIdSharedPicture"))
                + paragraph(picture.formatted("rIdWebPicture"))
                + paragraph("<w:subDoc r:id=\"rIdSubDoc\"/>")
                + paragraph(run("After"))
                + paragraph("<w:hyperlink r:id=\"rIdHyperlink\">" + run("a web page") + "</w:hyperlink>");
        return build(RawDocx.builder()
                .document(body)
                .part("word/media/embedded.png", null, TINY_PNG)
                .documentRelationship("rIdEmbedded", IMAGE, "media/embedded.png", false)
                .documentRelationship("rIdSharedPicture", IMAGE, "file://fileserver/share/logo.png", true)
                .documentRelationship("rIdWebPicture", IMAGE, "https://example.com/logo.png", true)
                .documentRelationship("rIdSubDoc", RELATIONSHIPS + "subDocument", "file:///C:/docs/chapter.docx", true)
                .documentRelationship("rIdHyperlink", RELATIONSHIPS + "hyperlink", "https://example.com/", true)
                .part("word/settings.xml", RawDocx.SETTINGS_CONTENT_TYPE, settings("<w:attachedTemplate r:id=\"rIdTemplate\"/>"))
                .documentRelationship("rIdSettings", SETTINGS, "settings.xml", false)
                .relationship("word/settings.xml", "rIdTemplate", RELATIONSHIPS + "attachedTemplate",
                        "file:///C:/Users/someone/Templates/Normal.dotm", true));
    }

    /** {@link #links()} with its template on the internet instead of a local path. */
    static byte[] internetTemplate() {
        return build(RawDocx.builder()
                .document(paragraph(run("Web template")))
                .part("word/settings.xml", RawDocx.SETTINGS_CONTENT_TYPE, settings("<w:attachedTemplate r:id=\"rIdTemplate\"/>"))
                .documentRelationship("rIdSettings", SETTINGS, "settings.xml", false)
                .relationship("word/settings.xml", "rIdTemplate", RELATIONSHIPS + "attachedTemplate", "https://example.com/t.dotx", true));
    }

    /** A floating text box, written the way Word 2010 and later write one: a drawing, with an older shape as its fallback. */
    static String floatingTextBox(String text) {
        return "<w:r><mc:AlternateContent><mc:Choice Requires=\"wps\"><w:drawing>"
                + "<wp:anchor distT=\"0\" distB=\"0\" distL=\"114300\" distR=\"114300\" simplePos=\"0\" relativeHeight=\"1\" behindDoc=\"0\""
                + " locked=\"0\" layoutInCell=\"1\" allowOverlap=\"1\"><wp:simplePos x=\"0\" y=\"0\"/>"
                + "<wp:positionH relativeFrom=\"column\"><wp:posOffset>0</wp:posOffset></wp:positionH>"
                + "<wp:positionV relativeFrom=\"paragraph\"><wp:posOffset>0</wp:posOffset></wp:positionV>"
                + "<wp:extent cx=\"914400\" cy=\"457200\"/><wp:wrapSquare wrapText=\"bothSides\"/><wp:docPr id=\"2\" name=\"Text Box 2\"/>"
                + "<a:graphic><a:graphicData uri=\"http://schemas.microsoft.com/office/word/2010/wordprocessingShape\"><wps:wsp>"
                + "<wps:cNvSpPr txBox=\"1\"/><wps:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"914400\" cy=\"457200\"/></a:xfrm>"
                + "<a:prstGeom prst=\"rect\"/></wps:spPr><wps:txbx><w:txbxContent>" + paragraph(run(text)) + "</w:txbxContent></wps:txbx>"
                + "<wps:bodyPr/></wps:wsp></a:graphicData></a:graphic></wp:anchor></w:drawing></mc:Choice>"
                + "<mc:Fallback><w:pict><v:shape id=\"Text Box 2\" o:spid=\"_x0000_s1026\" type=\"#_x0000_t202\""
                + " style=\"position:absolute;margin-left:0;margin-top:0;width:72pt;height:36pt;z-index:1\">"
                + "<v:textbox><w:txbxContent>" + paragraph(run(text)) + "</w:txbxContent></v:textbox></v:shape></w:pict>"
                + "</mc:Fallback></mc:AlternateContent></w:r>";
    }

    /**
     * Wrappers and content controls at every level, beside what the file
     * keeps as it is: a floating text box and a table inside a table. The
     * body reads "Street: Main Street hidden" / "Wrapped paragraph" /
     * "[Company name]" in a control tagged {@code company} / "Clause one" /
     * "Clause two" / "Contents", in a table of contents control / a table
     * whose rows read "Row in control" and "Plain cell", "Cell in control",
     * and whose last cell holds another table / "Client: [client]" in a
     * control tagged {@code client.name} / the text box.
     */
    static byte[] controlsAndKeptFeatures() {
        return build(RawDocx.builder().document(controlBody()));
    }

    static String controlBody() {
        return paragraph(run("Street: "),
                "<w:smartTag w:uri=\"urn:schemas-microsoft-com:office:smarttags\" w:element=\"Street\"><w:smartTagPr>"
                        + "<w:attr w:name=\"x\" w:val=\"y\"/></w:smartTagPr>" + run("Main Street") + "</w:smartTag>",
                run(" "), "<w:customXml w:element=\"secret\"><w:customXmlPr/>" + run("hidden") + "</w:customXml>")
                + "<w:customXml w:element=\"block\">" + paragraph(run("Wrapped paragraph")) + "</w:customXml>"
                + "<w:sdt><w:sdtPr><w:alias w:val=\"Company\"/><w:tag w:val=\"company\"/><w:showingPlcHdr/><w:text/></w:sdtPr>"
                + "<w:sdtEndPr/><w:sdtContent><w:p><w:pPr><w:jc w:val=\"center\"/></w:pPr>" + run("[Company name]") + "</w:p>"
                + "</w:sdtContent></w:sdt>"
                + "<w:sdt><w:sdtPr><w:tag w:val=\"clauses\"/></w:sdtPr><w:sdtContent>"
                + paragraph(run("Clause one")) + paragraph(run("Clause two")) + "</w:sdtContent></w:sdt>"
                + "<w:sdt><w:sdtPr><w:docPartObj><w:docPartGallery w:val=\"Table of Contents\"/><w:docPartUnique/></w:docPartObj>"
                + "</w:sdtPr><w:sdtContent>" + paragraph(run("Contents")) + "</w:sdtContent></w:sdt>"
                + "<w:tbl><w:tblPr/><w:tblGrid><w:gridCol w:w=\"3000\"/><w:gridCol w:w=\"3000\"/></w:tblGrid>"
                + "<w:sdt><w:sdtPr><w:tag w:val=\"rows\"/></w:sdtPr><w:sdtContent>"
                + "<w:tr><w:tc>" + paragraph(run("Row in control")) + "</w:tc><w:tc>" + paragraph() + "</w:tc></w:tr>"
                + "</w:sdtContent></w:sdt>"
                + "<w:tr><w:tc>" + paragraph(run("Plain cell")) + "</w:tc>"
                + "<w:sdt><w:sdtPr><w:tag w:val=\"cell\"/></w:sdtPr><w:sdtContent><w:tc>" + paragraph(run("Cell in control"))
                + "</w:tc></w:sdtContent></w:sdt></w:tr>"
                + "<w:tr><w:tc>" + paragraph(run("Outer")) + "</w:tc><w:tc><w:tbl><w:tblPr/><w:tblGrid><w:gridCol w:w=\"1000\"/></w:tblGrid>"
                + "<w:tr><w:tc>" + paragraph(run("Inner")) + "</w:tc></w:tr></w:tbl>" + paragraph() + "</w:tc></w:tr>"
                + "</w:tbl>"
                + paragraph(run("Client: "), "<w:sdt><w:sdtPr><w:tag w:val=\"client.name\"/></w:sdtPr><w:sdtContent>" + run("[client]")
                        + "</w:sdtContent></w:sdt>")
                + paragraph(floatingTextBox("In a box"));
    }

    /**
     * All of the above in one file: a macro-enabled template with every
     * tracked change, comments, fields, objects, wrappers, kept features,
     * macros, a signature, protection and a local template link. The text
     * each fixture's javadoc gives is what a person sees here too.
     */
    static byte[] everything() {
        String body = paragraph(run("Everything")) + fieldBody() + objectBody() + controlBody() + commentBody() + revisionBody();
        RawDocx.Builder builder = RawDocx.builder().mainContentType(DOTM_MAIN).document(body);
        revisionParts(builder);
        commentParts(builder);
        objectParts(builder);
        macroAndSignatureParts(builder);
        return build(builder
                .part("word/settings.xml", RawDocx.SETTINGS_CONTENT_TYPE,
                        settings("<w:trackRevisions/>" + PROTECTION + "<w:attachedTemplate r:id=\"rIdTemplate\"/>"))
                .documentRelationship("rIdSettings", SETTINGS, "settings.xml", false)
                .relationship("word/settings.xml", "rIdTemplate", RELATIONSHIPS + "attachedTemplate",
                        "file:///C:/Users/someone/Templates/Normal.dotm", true));
    }

    private static String xml(String root) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" + root;
    }

    static byte[] build(RawDocx.Builder builder) {
        try {
            return builder.build();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
