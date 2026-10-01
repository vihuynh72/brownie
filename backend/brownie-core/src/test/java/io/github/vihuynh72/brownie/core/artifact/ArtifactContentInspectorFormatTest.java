package io.github.vihuynh72.brownie.core.artifact;

import io.github.vihuynh72.brownie.core.artifact.UnsupportedArtifactTypeException.Reason;
import io.github.vihuynh72.brownie.core.prepare.ConvertibleFormat;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which word-processing format a file is, and why one is refused, decided
 * from what each kind of file declares about itself. Every package here is
 * built by hand, part by part, so each test shows exactly the declaration
 * it turns on.
 */
class ArtifactContentInspectorFormatTest {

    private static final String WORD_MAIN = "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml";

    @Test
    void aWordPackageIsTheKindItsMainPartDeclares() throws IOException {
        Map<String, SupportedMediaType> expected = Map.of(
                WORD_MAIN, SupportedMediaType.DOCX,
                "application/vnd.openxmlformats-officedocument.wordprocessingml.template.main+xml", SupportedMediaType.DOTX,
                "application/vnd.ms-word.document.macroEnabled.main+xml", SupportedMediaType.DOCM,
                "application/vnd.ms-word.template.macroEnabledTemplate.main+xml", SupportedMediaType.DOTM);
        for (Map.Entry<String, SupportedMediaType> type : expected.entrySet()) {
            ContentInspection inspection = inspect(ooxml("word/document.xml", type.getKey()));
            assertEquals(new ContentInspection(type.getValue(), null), inspection, type.getKey());
            assertEquals(type.getValue() == SupportedMediaType.DOCX ? WordRoute.NATIVE : WordRoute.NATIVE_VARIANT,
                    inspection.mediaType().wordRoute());
        }
    }

    @Test
    void theMainPartIsFoundWhereverThePackageSaysItIsAndTypedByExtensionWhenNotByName() throws IOException {
        // A strict document names its main part with a relationship type of its own, and some writers type every
        // XML part by its extension instead of naming the main one.
        byte[] strict = zip(
                part("[Content_Types].xml", "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                        + "<Default Extension=\"xml\" ContentType=\"" + WORD_MAIN + "\"/></Types>"),
                part("_rels/.rels", relationships("http://purl.oclc.org/ooxml/officeDocument/relationships/officeDocument", "/doc/main.xml")),
                part("doc/main.xml", "<w:document/>"));

        assertEquals(SupportedMediaType.DOCX, inspect(strict).mediaType());
    }

    @Test
    void aWorkbookOrAPresentationIsRefusedAsWhatItIsInsteadOfPassingForAWordFile() {
        assertRefused(Reason.SPREADSHEET, () -> ooxml("xl/workbook.xml", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"));
        assertRefused(Reason.SPREADSHEET, () -> ooxml("xl/workbook.xml", "application/vnd.ms-excel.sheet.macroEnabled.main+xml"));
        assertRefused(Reason.PRESENTATION, () -> ooxml("ppt/presentation.xml", "application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"));
        assertRefused(Reason.PRESENTATION, () -> ooxml("ppt/presentation.xml", "application/vnd.ms-powerpoint.slideshow.macroEnabled.main+xml"));
    }

    @Test
    void aPackageWithNoMainDocumentOrAnUnknownOneIsNotADocument() {
        assertRefused(Reason.NOT_A_DOCUMENT, () -> zip(
                part("[Content_Types].xml", contentTypes("/word/document.xml", WORD_MAIN)),
                part("word/document.xml", "<w:document/>")));
        assertRefused(Reason.NOT_A_DOCUMENT, () -> ooxml("Documents/1/FixedDocument.fdoc", "application/vnd.ms-package.xps-fixeddocument+xml"));
        assertRefused(Reason.NOT_A_DOCUMENT, () -> zip(
                part("[Content_Types].xml", "<Types/>"),
                part("_rels/.rels", relationships("http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument", "word/document.xml"))));
    }

    @Test
    void aLinkThatFetchesFromANetworkIsRemoteContentInAnUploadButLeftToTheCleanerInConverterOutput() throws IOException {
        byte[] linked = zip(
                part("[Content_Types].xml", contentTypes("/word/document.xml", WORD_MAIN)),
                part("_rels/.rels", mainRelationship("word/document.xml")),
                part("word/document.xml", "<w:document/>"),
                part("word/_rels/document.xml.rels", relationships(
                        "http://schemas.openxmlformats.org/officeDocument/2006/relationships/image", "https://pictures.example/logo.png", true)));

        UnsupportedArtifactTypeException refused = assertThrows(UnsupportedArtifactTypeException.class, () -> inspect(linked));
        assertEquals(Reason.REMOTE_CONTENT, refused.reason());
        assertEquals(SupportedMediaType.DOCX, ArtifactContentInspector.inspect(linked, PackagePolicy.CONVERTER_OUTPUT).mediaType());
    }

    @Test
    void converterOutputIsHeldToEveryOtherBound() throws IOException {
        String deep = "<a>".repeat(PackagePartInspector.MAX_ELEMENT_DEPTH + 1) + "</a>".repeat(PackagePartInspector.MAX_ELEMENT_DEPTH + 1);
        List<byte[]> hostile = List.of(
                wordPackageWith(part("word/styles.xml", "<?xml version=\"1.0\"?><!DOCTYPE s [<!ENTITY e \"x\">]><s>&e;</s>")),
                wordPackageWith(part("word/styles.xml", deep)),
                wordPackageWith(part("word/styles.xml", "<unclosed>")));
        for (byte[] bytes : hostile) {
            UnsupportedArtifactTypeException refused = assertThrows(
                    UnsupportedArtifactTypeException.class, () -> ArtifactContentInspector.inspect(bytes, PackagePolicy.CONVERTER_OUTPUT));
            assertEquals(Reason.DAMAGED, refused.reason());
        }
        byte[] manyParts = wordPackageWith(part("a.bin", "1"), part("b.bin", "2"), part("c.bin", "3"));
        assertThrows(ArtifactTooLargeException.class, () -> ArtifactContentInspector.inspect(
                new ByteArrayInputStream(manyParts), CompoundFileProbe.NONE, PackagePolicy.CONVERTER_OUTPUT, 1_000_000, 4));
    }

    @Test
    void aPackageBuiltWrongIsDamaged() throws IOException {
        byte[] whole = wordPackageWith(part("word/styles.xml", "<s/>"));
        byte[] truncated = Arrays.copyOf(whole, whole.length / 2);

        assertRefused(Reason.DAMAGED, () -> truncated);
        assertRefused(Reason.DAMAGED, () -> wordPackageWith(part("word/styles.xml", "<s>")));
        assertRefused(Reason.DAMAGED, () -> wordPackageWith(part("../escape.xml", "<s/>")));
        assertRefused(Reason.DAMAGED, () -> wordPackageWith(part("word/styles.xml", "<?xml version=\"1.0\"?><!DOCTYPE s SYSTEM \"s.dtd\"><s/>")));
    }

    // ---- OpenDocument ----------------------------------------------------------------------------

    @Test
    void anOpenDocumentTextIsTheFormatItsMimetypeSaysAndATemplateIsToldApart() throws IOException {
        assertEquals(
                new ContentInspection(SupportedMediaType.ODT, ConvertibleFormat.ODT),
                inspect(odf("application/vnd.oasis.opendocument.text", MANIFEST)));
        assertEquals(
                new ContentInspection(SupportedMediaType.ODT, ConvertibleFormat.ODT_TEMPLATE),
                inspect(odf("application/vnd.oasis.opendocument.text-template", MANIFEST)));
    }

    @Test
    void anOpenDocumentSpreadsheetPresentationOrDrawingIsRefusedAsWhatItIs() {
        assertRefused(Reason.SPREADSHEET, () -> odf("application/vnd.oasis.opendocument.spreadsheet", MANIFEST));
        assertRefused(Reason.PRESENTATION, () -> odf("application/vnd.oasis.opendocument.presentation-template", MANIFEST));
        assertRefused(Reason.NOT_A_DOCUMENT, () -> odf("application/vnd.oasis.opendocument.graphics", MANIFEST));
        assertRefused(Reason.NOT_A_DOCUMENT, () -> odf("application/epub+zip", MANIFEST));
    }

    @Test
    void anEncryptedOpenDocumentIsLockedNotDamagedEvenThoughItsPartsAreNotXml() throws IOException {
        // The manifest comes last, as OpenOffice and LibreOffice write it, after the parts it says are encrypted.
        String encryptedManifest = "<manifest:manifest xmlns:manifest=\"urn:oasis:names:tc:opendocument:xmlns:manifest:1.0\">"
                + "<manifest:file-entry manifest:full-path=\"/\" manifest:media-type=\"application/vnd.oasis.opendocument.text\"/>"
                + "<manifest:file-entry manifest:full-path=\"content.xml\" manifest:media-type=\"text/xml\">"
                + "<manifest:encryption-data manifest:checksum-type=\"SHA1/1K\" manifest:checksum=\"AAAA\">"
                + "<manifest:algorithm manifest:algorithm-name=\"Blowfish CFB\" manifest:initialisation-vector=\"AAAA\"/>"
                + "</manifest:encryption-data></manifest:file-entry></manifest:manifest>";
        byte[] encrypted = zip(
                stored("mimetype", "application/vnd.oasis.opendocument.text"),
                part("content.xml", "\u0001\u00ff\u0013 not XML at all"),
                part("styles.xml", "\u0002\u00fe more ciphertext"),
                part("META-INF/manifest.xml", encryptedManifest));

        assertRefused(Reason.PASSWORD_PROTECTED, () -> encrypted);
    }

    @Test
    void anOpenDocumentWhosePartIsNotXmlAndNotEncryptedIsDamaged() {
        assertRefused(Reason.DAMAGED, () -> zip(
                stored("mimetype", "application/vnd.oasis.opendocument.text"),
                part("content.xml", "<office:document-content><unclosed>"),
                part("META-INF/manifest.xml", MANIFEST)));
        // The manifest is never encrypted, so it is judged at once.
        assertRefused(Reason.DAMAGED, () -> odf("application/vnd.oasis.opendocument.text", "<manifest:manifest>"));
    }

    @Test
    void theOpenOffice2ManifestsDocumentTypeIsAllowedThereAndNowhereElse() throws IOException {
        String doctype = "<!DOCTYPE manifest:manifest PUBLIC \"-//OpenOffice.org//DTD Manifest 1.0//EN\" \"Manifest.dtd\">";
        String openOffice2 = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + doctype
                + "<manifest:manifest xmlns:manifest=\"http://openoffice.org/2001/manifest\">"
                + "<manifest:file-entry manifest:media-type=\"application/vnd.oasis.opendocument.text\" manifest:full-path=\"/\"/>"
                + "</manifest:manifest>";

        assertEquals(SupportedMediaType.ODT, inspect(odf("application/vnd.oasis.opendocument.text", openOffice2)).mediaType());

        // Not with a subset of its own, which is where entities are declared.
        String withSubset = "<?xml version=\"1.0\"?><!DOCTYPE manifest:manifest [<!ENTITY x \"y\">]><manifest:manifest>&x;</manifest:manifest>";
        assertRefused(Reason.DAMAGED, () -> odf("application/vnd.oasis.opendocument.text", withSubset));
        // Not in any other part of the same package.
        assertRefused(Reason.DAMAGED, () -> zip(
                stored("mimetype", "application/vnd.oasis.opendocument.text"),
                part("content.xml", "<?xml version=\"1.0\"?>" + doctype + "<office:document-content/>"),
                part("META-INF/manifest.xml", MANIFEST)));
        // Not in a manifest of that name inside a package of another kind.
        assertRefused(Reason.DAMAGED, () -> wordPackageWith(part("META-INF/manifest.xml", openOffice2)));
    }

    @Test
    void anEmptyPartOlderOpenOfficeVersionsLeaveBehindIsNotDamage() throws IOException {
        byte[] openOffice3 = zip(
                stored("mimetype", "application/vnd.oasis.opendocument.text"),
                part("Configurations2/accelerator/current.xml", ""),
                part("content.xml", "<office:document-content/>"),
                part("META-INF/manifest.xml", MANIFEST));

        assertEquals(SupportedMediaType.ODT, inspect(openOffice3).mediaType());
    }

    // ---- Pages -------------------------------------------------------------------------------------

    @Test
    void aPagesDocumentIsKnownByWhoWroteItsDocumentPartTablesAndAll() throws IOException {
        // Pages keeps a table in the same parts Numbers does, so the parts' names cannot tell the two apart.
        byte[] pages = zip(
                part("Index/Document.iwa", iwa(10000)),
                part("Index/CalculationEngine-2471.iwa", "\u0000"),
                part("Index/Tables/DataList-12.iwa", "\u0000"),
                part("Metadata/Properties.plist", "<?xml version=\"1.0\"?><!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"x\"><plist/>"));

        assertEquals(new ContentInspection(SupportedMediaType.PAGES, ConvertibleFormat.PAGES), inspect(pages));
    }

    @Test
    void aNumbersSpreadsheetOrKeynotePresentationIsRefusedAsWhatItIs() {
        assertRefused(Reason.SPREADSHEET, () -> zip(
                part("Index/Document.iwa", iwa(1)),
                part("Index/CalculationEngine.iwa", "\u0000"),
                part("Index/Tables/DataList.iwa", "\u0000")));
        assertRefused(Reason.PRESENTATION, () -> zip(
                part("Index/Document.iwa", iwa(1)),
                part("Index/Slide-8801.iwa", "\u0000"),
                part("Index/MasterSlide-12.iwa", "\u0000")));
        assertRefused(Reason.SPREADSHEET, () -> zip(part("index.xml", "<ls:document xmlns:ls=\"http://developer.apple.com/namespaces/ls\"/>")));
        assertRefused(Reason.PRESENTATION, () -> zip(part("index.apxl", "<key:presentation/>")));
    }

    @Test
    void aPagesDocumentLockedWithAPasswordIsSaidToBe() {
        assertRefused(Reason.PASSWORD_PROTECTED, () -> zip(
                part(".iwpv2", "\u0000verifier"),
                part(".iwph", "a hint"),
                part("Index/Document.iwa", "\u0007ciphertext")));
        assertRefused(Reason.PASSWORD_PROTECTED, () -> zip(
                part("Form.pages/.iwpv2", "\u0000verifier"),
                part("Form.pages/Index/Document.iwa", "\u0007ciphertext")));
    }

    @Test
    void anOlderPagesDocumentIsKnownByTheRootOfItsIndex() throws IOException {
        byte[] pages09 = zip(
                part("index.xml", "<?xml version=\"1.0\"?><sl:document xmlns:sl=\"http://developer.apple.com/namespaces/sl\"><sl:body/></sl:document>"),
                part("QuickLook/Thumbnail.jpg", "\u00ff\u00d8"));

        assertEquals(SupportedMediaType.PAGES, inspect(pages09).mediaType());
    }

    @Test
    void aZippedPagesPackageFolderIsReadInsideItsFolder() throws IOException {
        // What a Mac's own "Compress" makes of a document saved as a package, resource forks included.
        byte[] zipped = zip(
                part("Form.pages/", ""),
                part("Form.pages/Index/Document.iwa", iwa(10000)),
                part("Form.pages/Metadata/DocumentIdentifier", "0F2C"),
                part("Form.pages/preview.jpg", "\u00ff\u00d8"),
                part("__MACOSX/Form.pages/._preview.jpg", "\u0000\u0005\u0016\u0007"));
        byte[] olderPackage = zip(
                part("Form.pages/Index.zip", "PK"),
                part("Form.pages/Metadata/Properties.plist", "<plist/>"));

        assertEquals(new ContentInspection(SupportedMediaType.PAGES, ConvertibleFormat.PAGES), inspect(zipped));
        assertEquals(SupportedMediaType.PAGES, inspect(olderPackage).mediaType());
        assertRefused(Reason.SPREADSHEET, () -> zip(part("Budget.numbers/Index.zip", "PK")));
    }

    @Test
    void anIworkDocumentPartThatCannotBeReadIsDamagedAndAnArchiveOfNothingKnownIsNotADocument() {
        assertRefused(Reason.DAMAGED, () -> zip(part("Index/Document.iwa", "\u0001not a chunk")));
        assertRefused(Reason.NOT_A_DOCUMENT, () -> zip(part("photos/one.jpg", "\u00ff\u00d8"), part("notes.txt", "hello")));
        assertRefused(Reason.NOT_A_DOCUMENT, () -> zip(part("Form.pages/notes.txt", "a folder named like a document, holding none")));
    }

    // ---- RTF, compound files, text ----------------------------------------------------------------

    @Test
    void rtfIsItsOwnFormatWithOrWithoutAByteOrderMarkAndNoLongerPlainText() throws IOException {
        byte[] rtf = "{\\rtf1\\ansi\\deff0 {\\fonttbl {\\f0 Times;}} Name: ____\\par}".getBytes(StandardCharsets.US_ASCII);
        byte[] withMark = concat(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, rtf);

        assertEquals(new ContentInspection(SupportedMediaType.RTF, ConvertibleFormat.RTF), inspect(rtf));
        assertEquals(new ContentInspection(SupportedMediaType.RTF, ConvertibleFormat.RTF), inspect(withMark));
        assertEquals(SupportedMediaType.PLAIN_TEXT, inspect("Notes about {\\rtf1 files".getBytes(StandardCharsets.US_ASCII)).mediaType());
    }

    @Test
    void aCompoundFileIsHandedWholeToTheProbeWhoseAnswerDecides() throws IOException {
        byte[] compoundFile = concat(
                new byte[] {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1},
                "rest of the container".getBytes(StandardCharsets.US_ASCII));
        CompoundFileProbe wordNinetyFive = content -> {
            assertArrayEquals(compoundFile, content.readAllBytes());
            return ConvertibleFormat.WORD_95;
        };
        CompoundFileProbe spreadsheet = content -> {
            throw new UnsupportedArtifactTypeException(Reason.SPREADSHEET, "Workbook.");
        };

        assertEquals(
                new ContentInspection(SupportedMediaType.DOC, ConvertibleFormat.WORD_95),
                ArtifactContentInspector.inspect(new ByteArrayInputStream(compoundFile), wordNinetyFive, PackagePolicy.UPLOAD));
        assertEquals(Reason.SPREADSHEET, assertThrows(UnsupportedArtifactTypeException.class, () -> ArtifactContentInspector.inspect(
                new ByteArrayInputStream(compoundFile), spreadsheet, PackagePolicy.UPLOAD)).reason());
        // With no reader for it, a compound file is refused as it always was.
        assertEquals(Reason.NOT_A_DOCUMENT, assertThrows(UnsupportedArtifactTypeException.class, () -> ArtifactContentInspector.inspect(
                new ByteArrayInputStream(compoundFile), CompoundFileProbe.NONE, PackagePolicy.UPLOAD)).reason());
    }

    @Test
    void binaryThatIsNothingKnownIsNotADocument() {
        assertRefused(Reason.NOT_A_DOCUMENT, () -> new byte[] {0x7F, 0x45, 0x4C, 0x46, 0x00, 0x01});
    }

    @Test
    void anInspectionNamesAConvertedFormatExactlyForATypeThatIsConverted() {
        assertThrows(IllegalArgumentException.class, () -> new ContentInspection(SupportedMediaType.RTF, null));
        assertThrows(IllegalArgumentException.class, () -> new ContentInspection(SupportedMediaType.DOCX, ConvertibleFormat.WORD_97));
        for (SupportedMediaType type : SupportedMediaType.values()) {
            assertTrue(type.mimeType().contains("/"), type.name());
        }
    }

    // ---- building packages by hand ------------------------------------------------------------------

    private static final String MANIFEST = "<manifest:manifest xmlns:manifest=\"urn:oasis:names:tc:opendocument:xmlns:manifest:1.0\">"
            + "<manifest:file-entry manifest:full-path=\"/\" manifest:media-type=\"application/vnd.oasis.opendocument.text\"/>"
            + "<manifest:file-entry manifest:full-path=\"content.xml\" manifest:media-type=\"text/xml\"/></manifest:manifest>";

    private interface Bytes {
        byte[] get() throws IOException;
    }

    private static void assertRefused(Reason reason, Bytes content) {
        UnsupportedArtifactTypeException refused = assertThrows(UnsupportedArtifactTypeException.class, () -> inspect(content.get()));
        assertEquals(reason, refused.reason(), refused.getMessage());
    }

    private static ContentInspection inspect(byte[] content) throws IOException {
        return ArtifactContentInspector.inspect(new ByteArrayInputStream(content), CompoundFileProbe.NONE, PackagePolicy.UPLOAD);
    }

    private static byte[] ooxml(String mainPart, String mainContentType) throws IOException {
        return zip(
                part("[Content_Types].xml", contentTypes("/" + mainPart, mainContentType)),
                part("_rels/.rels", mainRelationship(mainPart)),
                part(mainPart, "<main/>"));
    }

    @SafeVarargs
    private static byte[] wordPackageWith(Map.Entry<String, byte[]>... more) throws IOException {
        List<Map.Entry<String, byte[]>> parts = new ArrayList<>(List.of(
                part("[Content_Types].xml", contentTypes("/word/document.xml", WORD_MAIN)),
                part("_rels/.rels", mainRelationship("word/document.xml")),
                part("word/document.xml", "<w:document/>")));
        parts.addAll(List.of(more));
        return zip(parts);
    }

    private static byte[] odf(String mimetype, String manifest) throws IOException {
        return zip(
                stored("mimetype", mimetype),
                part("content.xml", "<office:document-content/>"),
                part("styles.xml", "<office:document-styles/>"),
                part("META-INF/manifest.xml", manifest));
    }

    private static String contentTypes(String partName, String contentType) {
        return "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"" + partName + "\" ContentType=\"" + contentType + "\"/></Types>";
    }

    private static String mainRelationship(String target) {
        return relationships("http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument", target);
    }

    private static String relationships(String type, String target) {
        return relationships(type, target, false);
    }

    private static String relationships(String type, String target, boolean external) {
        return "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"" + type + "\" Target=\"" + target + "\""
                + (external ? " TargetMode=\"External\"" : "") + "/></Relationships>";
    }

    /**
     * An iWork document part whose first object has {@code type}: one
     * chunk of one Snappy literal holding an {@code ArchiveInfo} with a
     * single {@code MessageInfo}.
     */
    static byte[] iwa(long type) {
        ByteArrayOutputStream messageInfo = new ByteArrayOutputStream();
        messageInfo.write(0x08);
        varint(messageInfo, type);
        messageInfo.write(0x18);
        messageInfo.write(0x00);
        ByteArrayOutputStream archiveInfo = new ByteArrayOutputStream();
        archiveInfo.write(0x08);
        archiveInfo.write(0x01);
        archiveInfo.write(0x12);
        varint(archiveInfo, messageInfo.size());
        archiveInfo.writeBytes(messageInfo.toByteArray());
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();
        varint(decoded, archiveInfo.size());
        decoded.writeBytes(archiveInfo.toByteArray());
        byte[] payload = decoded.toByteArray();

        ByteArrayOutputStream snappy = new ByteArrayOutputStream();
        varint(snappy, payload.length);
        snappy.write((payload.length - 1) << 2);
        snappy.writeBytes(payload);
        byte[] block = snappy.toByteArray();

        ByteArrayOutputStream chunk = new ByteArrayOutputStream();
        chunk.write(0x00);
        chunk.write(block.length & 0xFF);
        chunk.write((block.length >>> 8) & 0xFF);
        chunk.write((block.length >>> 16) & 0xFF);
        chunk.writeBytes(block);
        chunk.writeBytes("the rest of the document".getBytes(StandardCharsets.US_ASCII));
        return chunk.toByteArray();
    }

    private static void varint(ByteArrayOutputStream out, long value) {
        long remaining = value;
        while (remaining >= 0x80) {
            out.write((int) (remaining & 0x7F) | 0x80);
            remaining >>>= 7;
        }
        out.write((int) remaining);
    }

    private static Map.Entry<String, byte[]> part(String name, String content) {
        return Map.entry(name, content.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static Map.Entry<String, byte[]> part(String name, byte[] content) {
        return Map.entry(name, content);
    }

    /** The {@code mimetype} entry is stored uncompressed, as the format asks, though nothing here depends on it. */
    private static Map.Entry<String, byte[]> stored(String name, String content) {
        return Map.entry("stored:" + name, content.getBytes(StandardCharsets.US_ASCII));
    }

    @SafeVarargs
    private static byte[] zip(Map.Entry<String, byte[]>... parts) throws IOException {
        return zip(List.of(parts));
    }

    private static byte[] zip(List<Map.Entry<String, byte[]>> parts) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            for (Map.Entry<String, byte[]> part : parts) {
                String name = part.getKey();
                ZipEntry entry;
                if (name.startsWith("stored:")) {
                    entry = new ZipEntry(name.substring("stored:".length()));
                    entry.setMethod(ZipEntry.STORED);
                    entry.setSize(part.getValue().length);
                    CRC32 crc = new CRC32();
                    crc.update(part.getValue());
                    entry.setCrc(crc.getValue());
                } else {
                    entry = new ZipEntry(name);
                }
                zip.putNextEntry(entry);
                zip.write(part.getValue());
                zip.closeEntry();
            }
        }
        return buffer.toByteArray();
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] joined = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, joined, first.length, second.length);
        return joined;
    }
}
