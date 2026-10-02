package io.github.vihuynh72.brownie.api.document.docx.prepare;

import io.github.vihuynh72.brownie.api.document.docx.PoiDocxStructuralExtractor;
import io.github.vihuynh72.brownie.api.document.docx.RawDocx;
import io.github.vihuynh72.brownie.core.document.DocumentPart;
import io.github.vihuynh72.brownie.core.document.DocumentPartKind;
import io.github.vihuynh72.brownie.core.document.DocxExtractionOutcome;
import io.github.vihuynh72.brownie.core.document.DocxStructuralGraph;
import io.github.vihuynh72.brownie.core.document.StructuralNode;
import io.github.vihuynh72.brownie.core.document.StructuralNodeKind;
import io.github.vihuynh72.brownie.core.document.UnsupportedDocxFeature;
import io.github.vihuynh72.brownie.core.prepare.PreparationMode;
import io.github.vihuynh72.brownie.core.prepare.PreparationNotice;
import io.github.vihuynh72.brownie.core.prepare.PreparedCopy;
import io.github.vihuynh72.brownie.core.prepare.WorkingCopyPreparationException;
import org.apache.poi.hpsf.ClassIDPredefined;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One test per step of the working copy, each on a file that needs that
 * step, then the whole copy made from a file that needs every step. Every
 * copy is read back from its saved bytes -- its parts, its relationships,
 * and the graph the reader makes of it -- so what is checked is what would
 * be handed on.
 */
class PoiWorkingCopyPreparerTest {

    private final PoiWorkingCopyPreparer preparer = new PoiWorkingCopyPreparer();
    private final PoiDocxStructuralExtractor extractor = new PoiDocxStructuralExtractor();

    // --- 1. the package ---

    @Test
    void aTemplateOrMacroEnabledFileBecomesAnOrdinaryDocumentWithoutMacrosOrSignature() {
        for (String mainType : List.of(DirtyDocx.DOTX_MAIN, DirtyDocx.DOCM_MAIN, DirtyDocx.DOTM_MAIN)) {
            byte[] dirty = DirtyDocx.macrosSignatureAndProtection(mainType);

            PreparedCopy copy = preparer.prepare(dirty, PreparationMode.UPLOAD);

            Set<String> entries = entries(copy.docxBytes());
            assertEquals(OoxmlPackageNormalizer.DOCUMENT_MAIN, mainContentType(copy.docxBytes()), mainType);
            assertFalse(part(copy.docxBytes(), "[Content_Types].xml").contains(mainType), mainType);
            for (String gone : List.of("word/vbaProject.bin", "word/vbaData.xml", "word/customizations.xml",
                    "_xmlsignatures/origin.sigs", "_xmlsignatures/sig1.xml")) {
                assertFalse(entries.contains(gone), gone + " is still in " + entries);
            }
            assertFalse(part(copy.docxBytes(), "word/_rels/document.xml.rels").contains("vbaProject"));
            assertFalse(part(copy.docxBytes(), "word/_rels/document.xml.rels").contains("keyMapCustomizations"));
            assertFalse(part(copy.docxBytes(), "_rels/.rels").contains("digital-signature"));
            assertEquals(3, count(copy, PreparationNotice.MACROS_REMOVED), () -> copy.notices().toString());
            assertEquals(1, count(copy, PreparationNotice.SIGNATURE_REMOVED));
            assertEquals(List.of("Macro file"), lines(graphOf(copy.docxBytes()), DocumentPartKind.MAIN_DOCUMENT));
        }
    }

    @Test
    void aTemplateWithNothingToRemoveOnlyChangesItsContentType() {
        byte[] template = DirtyDocx.build(RawDocx.builder().mainContentType(DirtyDocx.DOTX_MAIN).document(DirtyDocx.paragraph(DirtyDocx.run("Plain"))));

        PreparedCopy copy = preparer.prepare(template, PreparationMode.UPLOAD);

        assertEquals(OoxmlPackageNormalizer.DOCUMENT_MAIN, mainContentType(copy.docxBytes()));
        assertTrue(part(copy.docxBytes(), "[Content_Types].xml").contains("Extension=\"xml\"")
                && part(copy.docxBytes(), "[Content_Types].xml").contains("ContentType=\"application/xml\""),
                "the other XML parts keep their own content type");
        assertTrue(copy.notices().isEmpty(), copy.notices()::toString);
    }

    /**
     * Local and shared paths go in any file; an internet address stays in an
     * upload, which is refused before it gets here if it has one, and goes
     * in a file the converter wrote. A hyperlink always stays.
     */
    @Test
    void linksToLocalFilesAreRemovedAndInternetLinksOnlyFromConverterOutput() {
        byte[] upload = cleanedByStep(DirtyDocx.links(), word -> assertEquals(3, OoxmlPackageNormalizer.normalize(word, PreparationMode.UPLOAD).links()));
        String relationships = part(upload, "word/_rels/document.xml.rels");
        assertFalse(relationships.contains("rIdSharedPicture"));
        assertFalse(relationships.contains("rIdSubDoc"));
        assertTrue(relationships.contains("rIdWebPicture"));
        assertTrue(relationships.contains("rIdHyperlink"));
        assertFalse(entries(upload).contains("word/_rels/settings.xml.rels"), "the template link was the settings' only relationship");
        assertFalse(part(upload, "word/settings.xml").contains("attachedTemplate"));
        String document = part(upload, "word/document.xml");
        assertFalse(document.contains("subDoc"));
        assertFalse(document.contains("rIdSharedPicture"));
        assertTrue(document.contains("r:embed=\"rIdEmbedded\""), "the picture keeps its own copy");

        byte[] converted = cleanedByStep(DirtyDocx.links(),
                word -> assertEquals(4, OoxmlPackageNormalizer.normalize(word, PreparationMode.CONVERTER_OUTPUT).links()));
        assertFalse(part(converted, "word/_rels/document.xml.rels").contains("rIdWebPicture"));
        assertTrue(part(converted, "word/_rels/document.xml.rels").contains("rIdHyperlink"));

        PreparedCopy copy = preparer.prepare(DirtyDocx.links(), PreparationMode.CONVERTER_OUTPUT);
        assertEquals(4, count(copy, PreparationNotice.LINKED_CONTENT_REMOVED));
        assertEquals(List.of("Linked:", "", "", "", "After", "a web page"), lines(graphOf(copy.docxBytes()), DocumentPartKind.MAIN_DOCUMENT));
    }

    @Test
    void anInternetTemplateStaysInAnUploadAndGoesFromConverterOutput() {
        PreparedCopy upload = preparer.prepare(DirtyDocx.internetTemplate(), PreparationMode.UPLOAD);
        assertTrue(part(upload.docxBytes(), "word/settings.xml").contains("attachedTemplate"));
        assertEquals(0, count(upload, PreparationNotice.LINKED_CONTENT_REMOVED));

        PreparedCopy converted = preparer.prepare(DirtyDocx.internetTemplate(), PreparationMode.CONVERTER_OUTPUT);
        assertFalse(part(converted.docxBytes(), "word/settings.xml").contains("attachedTemplate"));
        assertEquals(1, count(converted, PreparationNotice.LINKED_CONTENT_REMOVED));
    }

    // --- 2. tracked changes ---

    @Test
    void everyKindOfTrackedChangeIsAcceptedInTheBodyHeadersAndFootnotes() {
        byte[] dirty = DirtyDocx.revisions();
        DocxExtractionOutcome before = extract(dirty);
        assertTrue(assertInstanceOf(DocxExtractionOutcome.Unsupported.class, before).featureReport().refused().stream()
                .allMatch(finding -> finding.feature() == UnsupportedDocxFeature.TRACKED_CHANGES));

        PreparedCopy copy = preparer.prepare(dirty, PreparationMode.UPLOAD);

        DocxStructuralGraph graph = graphOf(copy.docxBytes());
        assertEquals(List.of("Keep added end.", "", "moved", "Bold now", "Centred now", "First half second half", "Stay", "New row",
                "Inserted mark"), lines(graph, DocumentPartKind.MAIN_DOCUMENT));
        assertEquals(List.of("Header added"), lines(graph, DocumentPartKind.HEADER));
        StructuralNode main = graph.parts().getFirst().root();
        assertEquals(Boolean.TRUE, main.children().get(3).children().getFirst().style().bold(), "the current formatting stays");
        assertEquals("center", main.children().get(4).style().alignment());
        assertEquals("right", main.children().get(5).style().alignment(), "a joined paragraph keeps the next one's formatting");
        String footnotes = part(copy.docxBytes(), "word/footnotes.xml");
        assertTrue(footnotes.contains("kept") && !footnotes.contains("dropped"));
        for (String name : List.of("word/document.xml", "word/header1.xml", "word/footnotes.xml")) {
            String xml = part(copy.docxBytes(), name);
            for (String change : List.of("<w:ins", "<w:del ", "<w:del>", "<w:del/>", "w:delText", "<w:moveFrom", "<w:moveTo", "PrChange",
                    "RangeStart", "RangeEnd")) {
                assertFalse(xml.contains(change), name + " still holds " + change);
            }
        }
        assertFalse(part(copy.docxBytes(), "word/settings.xml").contains("trackRevisions"), "the copy does not record its filling");
        assertEquals(18, count(copy, PreparationNotice.TRACKED_CHANGES_AND_COMMENTS));
    }

    /** Deleted marks in a row fold into the paragraph after them; a deleted cell goes, and a row left with none goes too. */
    @Test
    void deletedParagraphMarksJoinInOrderAndDeletedCellsTakeEmptiedRowsWithThem() throws Exception {
        String deletedMark = "<w:pPr><w:rPr><w:del w:id=\"1\" w:author=\"A\"/></w:rPr></w:pPr>";
        String body = "<w:p>" + deletedMark + DirtyDocx.run("one ") + "</w:p>"
                + "<w:p>" + deletedMark + DirtyDocx.run("two ") + "</w:p>"
                + "<w:p><w:pPr><w:jc w:val=\"center\"/></w:pPr>" + DirtyDocx.run("three") + "</w:p>"
                + "<w:tbl><w:tr><w:tc><w:tcPr><w:cellDel w:id=\"2\" w:author=\"A\"/></w:tcPr>" + DirtyDocx.paragraph(DirtyDocx.run("gone")) + "</w:tc></w:tr>"
                + "<w:tr><w:tc><w:tcPr><w:cellIns w:id=\"3\" w:author=\"A\"/></w:tcPr>" + DirtyDocx.paragraph(DirtyDocx.run("kept")) + "</w:tc></w:tr></w:tbl>";
        org.apache.xmlbeans.XmlObject xml = org.apache.xmlbeans.XmlObject.Factory.parse(RawDocx.wordRoot("document", "<w:body>" + body + "</w:body>"));

        assertEquals(4, TrackedChangeAcceptor.accept(xml));

        String saved = xml.xmlText();
        assertEquals(2, occurrences(saved, "<w:p>"), "one paragraph in the body, one in the kept cell: " + saved);
        assertTrue(saved.contains("<w:jc w:val=\"center\"/></w:pPr><w:r><w:t xml:space=\"preserve\">one </w:t></w:r>"
                + "<w:r><w:t xml:space=\"preserve\">two </w:t></w:r><w:r><w:t xml:space=\"preserve\">three</w:t>"), saved);
        assertEquals(1, occurrences(saved, "<w:tr>"), saved);
        assertTrue(saved.contains("kept") && !saved.contains("gone") && !saved.contains("cellIns"), saved);
    }

    // --- 3. comments ---

    @Test
    void commentsAreLeftOutWithEveryPartTheyKeepBesideThem() {
        byte[] dirty = DirtyDocx.comments();
        assertInstanceOf(DocxExtractionOutcome.Unsupported.class, extract(dirty));

        PreparedCopy copy = preparer.prepare(dirty, PreparationMode.UPLOAD);

        Set<String> entries = entries(copy.docxBytes());
        for (String gone : List.of("word/comments.xml", "word/commentsExtended.xml", "word/commentsIds.xml", "word/commentsExtensible.xml",
                "word/people.xml")) {
            assertFalse(entries.contains(gone), gone);
        }
        assertFalse(entries.contains("word/_rels/document.xml.rels"), "the comment parts were the document's only relationships");
        String document = part(copy.docxBytes(), "word/document.xml");
        assertFalse(document.contains("commentRange") || document.contains("commentReference"), document);
        assertFalse(document.contains("CommentReference"), "the run that only held a reference goes with it");
        assertEquals(List.of("Please sign here today."), lines(graphOf(copy.docxBytes()), DocumentPartKind.MAIN_DOCUMENT));
        assertEquals(2, count(copy, PreparationNotice.TRACKED_CHANGES_AND_COMMENTS));
    }

    // --- 4. fields ---

    @Test
    void fieldsThatReachOutsideAreFrozenToTheirTextAndTheRestStayLive() {
        byte[] dirty = DirtyDocx.fields();
        assertTrue(assertInstanceOf(DocxExtractionOutcome.Unsupported.class, extract(dirty)).featureReport().refused().stream()
                .allMatch(finding -> finding.feature() == UnsupportedDocxFeature.UNSUPPORTED_FIELD));

        PreparedCopy copy = preparer.prepare(dirty, PreparationMode.UPLOAD);

        assertEquals(List.of("Data: dde result", "Clause: included text", "Split: split result", "Picture: linked picture",
                "Name: \u00ABclient.name\u00BB", "Answer: typed answer", "Click here", "Text: typed", "Agree: ", "Page 3",
                "Outer: first line", "second line", "Pages: "), lines(graphOf(copy.docxBytes()), DocumentPartKind.MAIN_DOCUMENT));
        String document = part(copy.docxBytes(), "word/document.xml");
        for (String frozen : List.of("DDEAUTO", "INCLUDETEXT", "INCLUDE<", "INCLUDEPICTURE", "b.docx")) {
            assertFalse(document.contains(frozen), frozen);
        }
        for (String live : List.of("MERGEFIELD", "FILLIN", "MACROBUTTON", "FORMTEXT", "FORMCHECKBOX", " PAGE ", "NUMPAGES", "w:ffData")) {
            assertTrue(document.contains(live), live);
        }
        assertEquals(occurrences(document, "fldCharType=\"begin\""), occurrences(document, "fldCharType=\"end\""), "every field left is whole");
        assertEquals(5, count(copy, PreparationNotice.FIELDS_FROZEN));
        assertEquals(Map.of(UnsupportedDocxFeature.DYNAMIC_FIELD.name(), 7), keptAsIs(copy));
    }

    // --- 5. embedded objects ---

    @Test
    void embeddedObjectsNotOnTheAllowedListBecomeTheirPicturesAndTheirFilesGo() {
        byte[] dirty = DirtyDocx.objects();
        assertInstanceOf(DocxExtractionOutcome.Unsupported.class, extract(dirty));

        PreparedCopy copy = preparer.prepare(dirty, PreparationMode.UPLOAD);

        Set<String> entries = entries(copy.docxBytes());
        for (String gone : List.of("word/embeddings/oleObject1.bin", "word/embeddings/oleObject2.bin", "word/embeddings/oleObject3.bin",
                "word/activeX/activeX1.xml", "word/activeX/activeX1.bin")) {
            assertFalse(entries.contains(gone), gone);
        }
        assertTrue(entries.contains("word/embeddings/Microsoft_Excel_Worksheet.xlsx"), "an allowed spreadsheet stays");
        for (int i = 1; i <= 5; i++) {
            assertTrue(entries.contains("word/media/preview" + i + ".png"), "preview " + i + " stays as the picture");
        }
        String document = part(copy.docxBytes(), "word/document.xml");
        assertEquals(1, occurrences(document, "<w:object"), document);
        assertEquals(4, occurrences(document, "<w:pict>"), document);
        assertTrue(document.contains("ProgID=\"Excel.Sheet.12\""));
        for (String gone : List.of("ProgID=\"Package\"", "Equation.3", "Word.Document.12", "w:control", "rIdBare")) {
            assertFalse(document.contains(gone), gone);
        }
        String relationships = part(copy.docxBytes(), "word/_rels/document.xml.rels");
        for (String gone : List.of("rIdPackager", "rIdEquation", "rIdControl", "rIdLinked", "rIdBare")) {
            assertFalse(relationships.contains("\"" + gone + "\""), gone);
        }
        assertTrue(relationships.contains("\"rIdSheet\""));
        assertEquals(List.of("Objects:", "", "", "", "", "", ""), lines(graphOf(copy.docxBytes()), DocumentPartKind.MAIN_DOCUMENT));
        assertEquals(5, count(copy, PreparationNotice.EMBEDDED_FILES_TO_PICTURES), "four pictures and one object with none");
        assertEquals(2, count(copy, PreparationNotice.MACROS_REMOVED), "the control's two parts");
        assertEquals(1, count(copy, PreparationNotice.LINKED_CONTENT_REMOVED), "the linked document");
        assertEquals(Map.of(UnsupportedDocxFeature.EMBEDDED_OBJECT.name(), 1), keptAsIs(copy));
    }

    /** Word starts an object by what its file is, so an allowed program's name over any other file is not enough. */
    @Test
    void anObjectWhoseFileIsNotWhatItsProgramsNameSaysBecomesItsPicture() {
        List<Object[]> objects = List.of(
                new Object[] {"Excel.Sheet.12", DirtyDocx.OLE_OBJECT, null, DirtyDocx.compoundFile(ClassIDPredefined.OLE_V1_PACKAGE), false},
                new Object[] {"Excel.Sheet.12", DirtyDocx.OLE_OBJECT, null, DirtyDocx.compoundFile(ClassIDPredefined.EQUATION_V3), false},
                new Object[] {"Word.Document.12", DirtyDocx.PACKAGE, "application/vnd.ms-word.document.macroEnabled.12",
                        DirtyDocx.officePackage("application/vnd.ms-word.document.macroEnabled.main+xml"), false},
                new Object[] {"Excel.Sheet.12", DirtyDocx.PACKAGE, DirtyDocx.SHEET,
                        DirtyDocx.officePackage(DirtyDocx.SHEET_MAIN, "xl/vbaProject.bin"), false},
                new Object[] {"Excel.Sheet.12", DirtyDocx.PACKAGE, DirtyDocx.SHEET,
                        DirtyDocx.officePackage("application/vnd.ms-excel.sheet.macroEnabled.main+xml"), false},
                new Object[] {"Excel.Sheet.12", DirtyDocx.PACKAGE, DirtyDocx.SHEET, DirtyDocx.FAKE_BINARY, false},
                new Object[] {"Word.Document.8", DirtyDocx.OLE_OBJECT, null, DirtyDocx.compoundFile(ClassIDPredefined.WORD_V8, "Macros"), false},
                new Object[] {"PowerPoint.Show.8", DirtyDocx.OLE_OBJECT, null, DirtyDocx.compoundFile(ClassIDPredefined.POWERPOINT_V8), false},
                // An older workbook keeps its macro sheets inside its main stream, and Word 6 and 95 their macros inside the document.
                new Object[] {"Excel.Sheet.8", DirtyDocx.OLE_OBJECT, null, DirtyDocx.compoundFile(ClassIDPredefined.EXCEL_V8), false},
                new Object[] {"Excel.Chart.8", DirtyDocx.OLE_OBJECT, null, DirtyDocx.compoundFile(ClassIDPredefined.EXCEL_V8_CHART), false},
                new Object[] {"Word.Document.6", DirtyDocx.OLE_OBJECT, null, DirtyDocx.compoundFile(ClassIDPredefined.WORD_V7), false},
                // A storage named MBD and a number holds an object of the file's own, as ObjectPool does.
                new Object[] {"Word.Document.8", DirtyDocx.OLE_OBJECT, null, DirtyDocx.compoundFile(ClassIDPredefined.WORD_V8, "MBD0001A2B3"), false},
                new Object[] {"Word.Document.8", DirtyDocx.OLE_OBJECT, null, DirtyDocx.compoundFile(ClassIDPredefined.WORD_V8), true},
                new Object[] {"Excel.Sheet.12", DirtyDocx.PACKAGE, DirtyDocx.SHEET, DirtyDocx.officePackage(DirtyDocx.SHEET_MAIN), true});
        for (Object[] object : objects) {
            String name = object[0] + " over " + object[1] + " " + object[2];
            RawDocx.Builder builder = RawDocx.builder()
                    .document(DirtyDocx.paragraph(DirtyDocx.object((String) object[0], "Embed", "rIdFile", "rIdPreview")))
                    .part("word/media/preview.png", null, DirtyDocx.TINY_PNG)
                    .documentRelationship("rIdPreview", DirtyDocx.IMAGE, "media/preview.png", false)
                    .part("word/embeddings/embedded1.bin", (String) object[2], (byte[]) object[3])
                    .documentRelationship("rIdFile", (String) object[1], "embeddings/embedded1.bin", false);
            byte[] dirty = DirtyDocx.build(builder);
            boolean kept = (Boolean) object[4];

            PreparedCopy copy = preparer.prepare(dirty, PreparationMode.UPLOAD);

            assertEquals(kept, entries(copy.docxBytes()).contains("word/embeddings/embedded1.bin"), name);
            assertEquals(kept ? 0 : 1, count(copy, PreparationNotice.EMBEDDED_FILES_TO_PICTURES), name);
            assertEquals(kept, extract(dirty) instanceof DocxExtractionOutcome.Supported, name + ": the reader judges the file the same way");
        }
    }

    // --- 6. wrappers and content controls ---

    @Test
    void wrappersAndBlockControlsGoWhileInlineControlsBuildingBlocksAndKeptFeaturesStay() {
        byte[] dirty = DirtyDocx.controlsAndKeptFeatures();
        List<String> before = lines(graphOf(dirty), DocumentPartKind.MAIN_DOCUMENT);
        assertFalse(before.contains("Row in control"), "a row wrapped in a control has no node before cleaning: " + before);

        PreparedCopy copy = preparer.prepare(dirty, PreparationMode.UPLOAD);

        DocxStructuralGraph graph = graphOf(copy.docxBytes());
        assertEquals(List.of("Street: Main Street hidden", "Wrapped paragraph", "[Company name]", "Clause one", "Clause two", "",
                "Row in control | ", "Plain cell | Cell in control", "Outer | ", "Client: [client]", ""),
                lines(graph, DocumentPartKind.MAIN_DOCUMENT));
        StructuralNode company = graph.parts().getFirst().root().children().get(2);
        assertEquals("center", company.style().alignment());
        StructuralNode control = company.children().getFirst();
        assertEquals(StructuralNodeKind.CONTENT_CONTROL, control.kind(), "a placeholder-only block control becomes a fill spot in its line");
        assertEquals("company", control.contentControlTag());
        assertEquals("[Company name]", control.children().getFirst().text());
        String document = part(copy.docxBytes(), "word/document.xml");
        assertFalse(document.contains("smartTag") || document.contains("customXml"), document);
        assertEquals(3, occurrences(document, "<w:sdt>"), "company, the table of contents and client.name: " + document);
        assertTrue(document.contains("w:showingPlcHdr") && document.contains("w:sdtEndPr"), "the control keeps its own properties");
        assertTrue(document.contains("docPartObj"));
        assertEquals(Map.of(UnsupportedDocxFeature.FLOATING_SHAPE.name(), 2, UnsupportedDocxFeature.NESTED_TABLE.name(), 1), keptAsIs(copy),
                "a text box is reported for its drawing and for its older fallback shape");
        assertTrue(copy.notices().stream().allMatch(notice -> notice.code().equals(PreparationNotice.KEPT_AS_IS)), copy.notices()::toString);
    }

    // --- 7. editing restrictions ---

    @Test
    void editingRestrictionsAreLifted() {
        PreparedCopy copy = preparer.prepare(DirtyDocx.macrosSignatureAndProtection(DirtyDocx.DOCM_MAIN), PreparationMode.UPLOAD);

        String settings = part(copy.docxBytes(), "word/settings.xml");
        assertFalse(settings.contains("documentProtection") || settings.contains("writeProtection"), settings);
        assertEquals(2, count(copy, PreparationNotice.EDITING_RESTRICTION_REMOVED));
    }

    // --- the check before the copy is handed on ---

    @Test
    void aCopyStillHoldingAMacroProjectControlOrSignatureIsRefused() {
        for (byte[] unclean : List.of(DirtyDocx.macrosSignatureAndProtection(DirtyDocx.DOCM_MAIN), DirtyDocx.objects())) {
            WorkingCopyPreparationException refused = assertThrows(WorkingCopyPreparationException.class,
                    () -> PoiWorkingCopyPreparer.requireNoMacroOrSignatureParts(unclean));
            assertEquals(WorkingCopyPreparationException.Reason.NOT_CLEAN, refused.reason());
        }
        PoiWorkingCopyPreparer.requireNoMacroOrSignatureParts(
                preparer.prepare(DirtyDocx.macrosSignatureAndProtection(DirtyDocx.DOCM_MAIN), PreparationMode.UPLOAD).docxBytes());
    }

    /** An upload linking a picture from the internet should have been refused before it got here; the copy is never handed on. */
    @Test
    void aCopyTheReaderStillRefusesIsNotHandedOn() {
        WorkingCopyPreparationException refused = assertThrows(WorkingCopyPreparationException.class,
                () -> preparer.prepare(DirtyDocx.links(), PreparationMode.UPLOAD));
        assertEquals(WorkingCopyPreparationException.Reason.NOT_CLEAN, refused.reason());
        assertTrue(refused.getMessage().contains("LINKED_EXTERNAL_IMAGE"), refused.getMessage());
    }

    @Test
    void bytesThatAreNotAWordPackageAreDamaged() {
        byte[] notAZip = "plain text, not a package".getBytes(StandardCharsets.US_ASCII);
        byte[] noMainDocument = DirtyDocx.build(RawDocx.builder()
                .document(DirtyDocx.paragraph())
                .packageRelationship("rIdOther", RawDocx.RELATIONSHIP_TYPE_BASE + "custom-properties", "docProps/custom.xml"));
        for (byte[] bytes : List.of(notAZip, withoutMainRelationship(noMainDocument))) {
            WorkingCopyPreparationException refused = assertThrows(WorkingCopyPreparationException.class,
                    () -> preparer.prepare(bytes, PreparationMode.UPLOAD));
            assertEquals(WorkingCopyPreparationException.Reason.DAMAGED, refused.reason());
        }
    }

    // --- all of it ---

    /**
     * One file needing every step comes out as a copy the reader accepts,
     * reading exactly as the file did when shown with its changes accepted,
     * its comments hidden and its fields showing their results.
     */
    @Test
    void aFileNeedingEveryStepBecomesASupportedCopyThatReadsAsItWasShown() {
        byte[] dirty = DirtyDocx.everything();
        assertInstanceOf(DocxExtractionOutcome.Unsupported.class, extract(dirty));

        PreparedCopy copy = preparer.prepare(dirty, PreparationMode.UPLOAD);

        List<String> shown = new ArrayList<>(List.of("Everything"));
        shown.addAll(List.of("Data: dde result", "Clause: included text", "Split: split result", "Picture: linked picture",
                "Name: \u00ABclient.name\u00BB", "Answer: typed answer", "Click here", "Text: typed", "Agree: ", "Page 3",
                "Outer: first line", "second line", "Pages: "));
        shown.addAll(List.of("Objects:", "", "", "", "", "", ""));
        shown.addAll(List.of("Street: Main Street hidden", "Wrapped paragraph", "[Company name]", "Clause one", "Clause two", "",
                "Row in control | ", "Plain cell | Cell in control", "Outer | ", "Client: [client]", ""));
        shown.add("Please sign here today.");
        shown.addAll(List.of("Keep added end.", "", "moved", "Bold now", "Centred now", "First half second half", "Stay", "New row",
                "Inserted mark"));
        DocxStructuralGraph graph = graphOf(copy.docxBytes());
        assertEquals(shown, lines(graph, DocumentPartKind.MAIN_DOCUMENT));
        assertEquals(List.of("Header added"), lines(graph, DocumentPartKind.HEADER));

        Map<String, Integer> counts = new LinkedHashMap<>();
        copy.notices().stream().filter(notice -> !notice.code().equals(PreparationNotice.KEPT_AS_IS))
                .forEach(notice -> counts.put(notice.code(), notice.count()));
        Map<String, Integer> expected = new LinkedHashMap<>();
        expected.put(PreparationNotice.MACROS_REMOVED, 5);
        expected.put(PreparationNotice.SIGNATURE_REMOVED, 1);
        expected.put(PreparationNotice.LINKED_CONTENT_REMOVED, 2);
        expected.put(PreparationNotice.TRACKED_CHANGES_AND_COMMENTS, 20);
        expected.put(PreparationNotice.FIELDS_FROZEN, 5);
        expected.put(PreparationNotice.EMBEDDED_FILES_TO_PICTURES, 5);
        expected.put(PreparationNotice.EDITING_RESTRICTION_REMOVED, 2);
        assertEquals(expected, counts, "every step, in the order the steps ran");
        assertEquals(Set.of("DYNAMIC_FIELD", "EMBEDDED_OBJECT", "FLOATING_SHAPE", "NESTED_TABLE"), keptAsIs(copy).keySet());
        assertEquals(OoxmlPackageNormalizer.DOCUMENT_MAIN, mainContentType(copy.docxBytes()));
        PoiWorkingCopyPreparer.requireNoMacroOrSignatureParts(copy.docxBytes());

        PreparedCopy again = preparer.prepare(copy.docxBytes(), PreparationMode.UPLOAD);
        assertTrue(again.notices().stream().allMatch(notice -> notice.code().equals(PreparationNotice.KEPT_AS_IS)),
                "a clean copy needs nothing more: " + again.notices());
        assertEquals(shown, lines(graphOf(again.docxBytes()), DocumentPartKind.MAIN_DOCUMENT));
    }

    private static byte[] withoutMainRelationship(byte[] docx) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(docx));
                java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(out)) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                byte[] content = in.readAllBytes();
                if (entry.getName().equals("_rels/.rels")) {
                    content = new String(content, StandardCharsets.UTF_8).replaceAll("<Relationship Id=\"rId1\"[^>]*/>", "")
                            .getBytes(StandardCharsets.UTF_8);
                }
                zip.putNextEntry(new ZipEntry(entry.getName()));
                zip.write(content);
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    // --- helpers ---

    private interface Step {
        void apply(WordPackage word);
    }

    /** Runs one step alone and saves the package. */
    private static byte[] cleanedByStep(byte[] dirty, Step step) {
        try (WordPackage word = WordPackage.open(dirty)) {
            step.apply(word);
            return word.save();
        }
    }

    private DocxStructuralGraph graphOf(byte[] docx) {
        return assertInstanceOf(DocxExtractionOutcome.Supported.class, extract(docx)).graph();
    }

    private DocxExtractionOutcome extract(byte[] docx) {
        try {
            return extractor.extract(new ByteArrayInputStream(docx));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static int count(PreparedCopy copy, String code) {
        return copy.notices().stream().filter(notice -> notice.code().equals(code)).mapToInt(PreparationNotice::count).sum();
    }

    private static Map<String, Integer> keptAsIs(PreparedCopy copy) {
        Map<String, Integer> kept = new LinkedHashMap<>();
        copy.notices().stream()
                .filter(notice -> notice.code().equals(PreparationNotice.KEPT_AS_IS))
                .forEach(notice -> kept.put(notice.detail(), notice.count()));
        return kept;
    }

    /**
     * The text a person reads in one part, a line per paragraph; a table
     * row is one line, its cells' text joined by " | ".
     */
    static List<String> lines(DocxStructuralGraph graph, DocumentPartKind kind) {
        List<String> lines = new ArrayList<>();
        for (DocumentPart part : graph.parts()) {
            if (part.kind() != kind) {
                continue;
            }
            for (StructuralNode block : part.root().children()) {
                if (block.kind() == StructuralNodeKind.TABLE) {
                    for (StructuralNode row : block.children()) {
                        List<String> cells = new ArrayList<>();
                        for (StructuralNode cell : row.children()) {
                            cells.add(String.join("/", cell.children().stream().map(PoiWorkingCopyPreparerTest::textOf).toList()));
                        }
                        lines.add(String.join(" | ", cells));
                    }
                } else {
                    lines.add(textOf(block));
                }
            }
        }
        return lines;
    }

    static String textOf(StructuralNode node) {
        StringBuilder text = new StringBuilder(node.text() == null ? "" : node.text());
        for (StructuralNode child : node.children()) {
            text.append(textOf(child));
        }
        return text.toString();
    }

    static Set<String> entries(byte[] zip) {
        Set<String> names = new TreeSet<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                names.add(entry.getName());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return names;
    }

    static String part(byte[] zip, String name) {
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                if (entry.getName().equals(name)) {
                    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        throw new AssertionError(name + " is not in the package " + entries(zip));
    }

    /** The content type the package gives its main document, from its own override. */
    static String mainContentType(byte[] zip) {
        java.util.regex.Matcher override = java.util.regex.Pattern
                .compile("<Override[^>]*PartName=\"/word/document.xml\"[^>]*>")
                .matcher(part(zip, "[Content_Types].xml"));
        assertTrue(override.find(), "no override for the main document");
        java.util.regex.Matcher type = java.util.regex.Pattern.compile("ContentType=\"([^\"]+)\"").matcher(override.group());
        assertTrue(type.find());
        return type.group(1);
    }

    static int occurrences(String text, String of) {
        int count = 0;
        for (int at = text.indexOf(of); at >= 0; at = text.indexOf(of, at + of.length())) {
            count++;
        }
        return count;
    }
}
