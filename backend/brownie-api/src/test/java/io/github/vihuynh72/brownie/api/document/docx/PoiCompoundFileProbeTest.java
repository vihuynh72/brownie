package io.github.vihuynh72.brownie.api.document.docx;

import io.github.vihuynh72.brownie.core.artifact.ArtifactContentInspector;
import io.github.vihuynh72.brownie.core.artifact.ContentInspection;
import io.github.vihuynh72.brownie.core.artifact.PackagePolicy;
import io.github.vihuynh72.brownie.core.artifact.SupportedMediaType;
import io.github.vihuynh72.brownie.core.artifact.UnsupportedArtifactTypeException;
import io.github.vihuynh72.brownie.core.artifact.UnsupportedArtifactTypeException.Reason;
import io.github.vihuynh72.brownie.core.prepare.ConvertibleFormat;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.poifs.crypt.EncryptionInfo;
import org.apache.poi.poifs.crypt.EncryptionMode;
import org.apache.poi.poifs.crypt.Encryptor;
import org.apache.poi.poifs.filesystem.DirectoryEntry;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Every fixture is a compound file POI itself writes: an Office document
 * encrypted by POI's own encryptor, a real Excel workbook, and containers
 * holding a Word document stream whose header is set byte by byte, which
 * is all the probe reads of it.
 */
class PoiCompoundFileProbeTest {

    private final PoiCompoundFileProbe probe = new PoiCompoundFileProbe();

    @Test
    void anOrdinaryWord97DocumentIsAcceptedAndAWord95OneIsToldApart() throws IOException {
        assertEquals(ConvertibleFormat.WORD_97, probe.probe(stream(wordDocument(0x00C1, 0))));
        assertEquals(ConvertibleFormat.WORD_97, probe.probe(stream(wordDocument(0x0101, 0))));
        assertEquals(ConvertibleFormat.WORD_95, probe.probe(stream(wordDocument(0x0068, 0))));
    }

    @Test
    void aWordDocumentWhoseHeaderSaysItIsEncryptedIsLocked() throws IOException {
        // fEncrypted, with the other flags a saved document usually has around it (fComplex, fWhichTblStm).
        assertRefused(Reason.PASSWORD_PROTECTED, wordDocument(0x00C1, 0x0100 | 0x0004 | 0x0200));
        assertRefused(Reason.PASSWORD_PROTECTED, wordDocument(0x0065, 0x0100));
    }

    @Test
    void aWordFileEncryptedWithAPasswordIsLocked() throws Exception {
        assertRefused(Reason.PASSWORD_PROTECTED, encrypted(docx()));
    }

    @Test
    void aFileUnderRightsManagementIsSaidToBeNotMerelyLocked() throws IOException {
        byte[] protectedFile = container(root -> {
            DirectoryEntry dataSpaces = root.createDirectory("\u0006DataSpaces");
            dataSpaces.createDocument("DataSpaceMap", new ByteArrayInputStream(new byte[8]));
            DirectoryEntry transform = dataSpaces.createDirectory("TransformInfo").createDirectory("DRMEncryptedTransform");
            transform.createDocument("\u0006Primary", new ByteArrayInputStream(new byte[16]));
            root.createDocument("EncryptionInfo", new ByteArrayInputStream(new byte[16]));
            root.createDocument("EncryptedPackage", new ByteArrayInputStream(new byte[64]));
        });
        byte[] olderBinary = container(root -> {
            root.createDirectory("\u0006DataSpaces");
            root.createDocument("\tDRMContent", new ByteArrayInputStream(new byte[64]));
        });

        assertRefused(Reason.RIGHTS_PROTECTED, protectedFile);
        assertRefused(Reason.RIGHTS_PROTECTED, olderBinary);
    }

    @Test
    void aWorkbookOrPresentationIsRefusedAsWhatItIs() throws IOException {
        ByteArrayOutputStream workbook = new ByteArrayOutputStream();
        try (HSSFWorkbook excel = new HSSFWorkbook()) {
            excel.createSheet("Budget").createRow(0).createCell(0).setCellValue("Rent");
            excel.write(workbook);
        }

        assertRefused(Reason.SPREADSHEET, workbook.toByteArray());
        assertRefused(Reason.SPREADSHEET, container(root -> root.createDocument("Book", new ByteArrayInputStream(new byte[64]))));
        assertRefused(Reason.PRESENTATION,
                container(root -> root.createDocument("PowerPoint Document", new ByteArrayInputStream(new byte[64]))));
    }

    @Test
    void aContainerOfNothingKnownIsNotADocumentAndOneThatCannotBeReadIsDamaged() throws IOException {
        byte[] signatureOnly = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1, 1, 2, 3};
        byte[] halfAWordFile = wordDocument(0x00C1, 0);

        assertRefused(Reason.NOT_A_DOCUMENT, container(root -> root.createDocument("Contents", new ByteArrayInputStream(new byte[64]))));
        assertRefused(Reason.DAMAGED, signatureOnly);
        assertRefused(Reason.DAMAGED, Arrays.copyOf(halfAWordFile, 600));
        assertRefused(Reason.DAMAGED, container(root -> root.createDocument("WordDocument", new ByteArrayInputStream(new byte[12]))));
        assertRefused(Reason.DAMAGED, container(root -> root.createDocument("WordDocument", new ByteArrayInputStream(new byte[64]))));
    }

    /** What an upload meets: the inspector hands the whole compound file to this probe. */
    @Test
    void throughTheInspectorAWordFileIsADocAndAnEncryptedOneIsRefused() throws Exception {
        assertEquals(
                new ContentInspection(SupportedMediaType.DOC, ConvertibleFormat.WORD_95),
                ArtifactContentInspector.inspect(stream(wordDocument(0x0068, 0)), probe, PackagePolicy.UPLOAD));
        UnsupportedArtifactTypeException refused = assertThrows(
                UnsupportedArtifactTypeException.class,
                () -> ArtifactContentInspector.inspect(stream(encrypted(docx())), probe, PackagePolicy.UPLOAD));
        assertEquals(Reason.PASSWORD_PROTECTED, refused.reason());
    }

    private void assertRefused(Reason reason, byte[] content) {
        UnsupportedArtifactTypeException refused =
                assertThrows(UnsupportedArtifactTypeException.class, () -> probe.probe(stream(content)));
        assertEquals(reason, refused.reason(), refused.getMessage());
    }

    private static ByteArrayInputStream stream(byte[] bytes) {
        return new ByteArrayInputStream(bytes);
    }

    /**
     * A container whose {@code WordDocument} stream begins with a file
     * information block: the Word identifier, {@code version}, and {@code
     * flags} at offset 0x0A, followed by enough of a document to be one.
     */
    private static byte[] wordDocument(int version, int flags) throws IOException {
        byte[] stream = new byte[4096];
        stream[0] = (byte) 0xEC;
        stream[1] = (byte) 0xA5;
        stream[2] = (byte) version;
        stream[3] = (byte) (version >>> 8);
        stream[0x0A] = (byte) flags;
        stream[0x0B] = (byte) (flags >>> 8);
        return container(root -> {
            root.createDocument("WordDocument", new ByteArrayInputStream(stream));
            root.createDocument("1Table", new ByteArrayInputStream(new byte[512]));
        });
    }

    private static byte[] docx() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (XWPFDocument document = new XWPFDocument()) {
            document.createParagraph().createRun().setText("Name: ________");
            document.write(out);
        }
        return out.toByteArray();
    }

    /** {@code docx} encrypted the way Word 2010 and later encrypt with a password (agile encryption). */
    private static byte[] encrypted(byte[] docx) throws Exception {
        try (POIFSFileSystem container = new POIFSFileSystem()) {
            Encryptor encryptor = new EncryptionInfo(EncryptionMode.agile).getEncryptor();
            encryptor.confirmPassword("correct horse");
            try (OPCPackage document = OPCPackage.open(new ByteArrayInputStream(docx));
                    OutputStream encrypting = encryptor.getDataStream(container)) {
                document.save(encrypting);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            container.writeFilesystem(out);
            return out.toByteArray();
        }
    }

    private interface Contents {
        void write(DirectoryEntry root) throws IOException;
    }

    private static byte[] container(Contents contents) throws IOException {
        try (POIFSFileSystem container = new POIFSFileSystem()) {
            contents.write(container.getRoot());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            container.writeFilesystem(out);
            return out.toByteArray();
        }
    }
}
