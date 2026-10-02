package io.github.vihuynh72.brownie.api.document.docx;

import io.github.vihuynh72.brownie.core.artifact.CompoundFileProbe;
import io.github.vihuynh72.brownie.core.artifact.UnsupportedArtifactTypeException;
import io.github.vihuynh72.brownie.core.artifact.UnsupportedArtifactTypeException.Reason;
import io.github.vihuynh72.brownie.core.prepare.ConvertibleFormat;
import org.apache.poi.poifs.filesystem.DirectoryEntry;
import org.apache.poi.poifs.filesystem.DocumentEntry;
import org.apache.poi.poifs.filesystem.DocumentInputStream;
import org.apache.poi.poifs.filesystem.Entry;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/**
 * Says what a Microsoft compound file holds, with POI's container reader
 * and nothing that parses the document inside. A Word 97-2003 document is
 * the one kind accepted, and only when its own header says it is not
 * encrypted; everything else gets the reason a person can act on.
 *
 * <p>Encryption is looked for in the container before the document is:
 * an encrypted Word 2007 or later file is a compound file holding the
 * encrypted package, and one under an organization's rights management
 * says so by naming a DRM transform in its data spaces. A Word binary file
 * begins with the file information block, whose identifier, version and
 * flags are at fixed offsets: a version below 0xC1 is Word 6 or 95, which
 * the converter reads with a filter of its own, and the encrypted flag
 * means the text that follows cannot be read.
 */
public final class PoiCompoundFileProbe implements CompoundFileProbe {

    private static final String DATA_SPACES = "\u0006DataSpaces";
    private static final String WORD_DOCUMENT = "WordDocument";
    private static final int FIB_BASE_BYTES = 0x20;
    private static final int WORD_IDENTIFIER = 0xA5EC;
    private static final int FIRST_WORD_97_VERSION = 0x00C1;
    private static final int FLAGS_OFFSET = 0x0A;
    private static final int ENCRYPTED_FLAG = 0x0100;
    /** Deep enough for the DRM transform's own storage; the data spaces are never nested further. */
    private static final int DATA_SPACES_DEPTH = 3;

    @Override
    public ConvertibleFormat probe(InputStream content) throws IOException {
        POIFSFileSystem container;
        try {
            container = new POIFSFileSystem(content);
        } catch (IOException | RuntimeException e) {
            // Not POI's message: it can quote the file.
            throw refused(Reason.DAMAGED, "Compound file cannot be read.");
        }
        try (container) {
            DirectoryEntry root = container.getRoot();
            if (isRightsProtected(root)) {
                throw refused(Reason.RIGHTS_PROTECTED, "Compound file is protected by rights management.");
            }
            if (root.hasEntryCaseInsensitive("EncryptionInfo") && root.hasEntryCaseInsensitive("EncryptedPackage")) {
                throw refused(Reason.PASSWORD_PROTECTED, "Compound file holds an encrypted package.");
            }
            if (root.hasEntryCaseInsensitive(WORD_DOCUMENT)) {
                return wordFormat(root);
            }
            if (root.hasEntryCaseInsensitive("Workbook") || root.hasEntryCaseInsensitive("Book")) {
                throw refused(Reason.SPREADSHEET, "Compound file is an Excel workbook.");
            }
            if (root.hasEntryCaseInsensitive("PowerPoint Document")) {
                throw refused(Reason.PRESENTATION, "Compound file is a PowerPoint presentation.");
            }
            throw refused(Reason.NOT_A_DOCUMENT, "Compound file is not a Word, Excel or PowerPoint file.");
        }
    }

    private static ConvertibleFormat wordFormat(DirectoryEntry root) {
        if (!(entryOf(root, WORD_DOCUMENT) instanceof DocumentEntry document) || document.getSize() < FIB_BASE_BYTES) {
            throw refused(Reason.DAMAGED, "Word document's header is missing.");
        }
        byte[] fib = new byte[FIB_BASE_BYTES];
        try (DocumentInputStream header = new DocumentInputStream(document)) {
            header.readFully(fib);
        } catch (IOException | RuntimeException e) {
            throw refused(Reason.DAMAGED, "Word document's header cannot be read.");
        }
        if (uint16(fib, 0) != WORD_IDENTIFIER) {
            throw refused(Reason.DAMAGED, "Word document's header does not identify it as one.");
        }
        if ((uint16(fib, FLAGS_OFFSET) & ENCRYPTED_FLAG) != 0) {
            throw refused(Reason.PASSWORD_PROTECTED, "Word document is encrypted.");
        }
        return uint16(fib, 2) < FIRST_WORD_97_VERSION ? ConvertibleFormat.WORD_95 : ConvertibleFormat.WORD_97;
    }

    /** Rights management names its transform, data space or content with "DRM"; password encryption never does. */
    private static boolean isRightsProtected(DirectoryEntry root) {
        for (Entry entry : root) {
            if (entry.getName().toUpperCase(Locale.ROOT).contains("DRM")) {
                return true;
            }
        }
        return root.hasEntryCaseInsensitive(DATA_SPACES) && namesDrm(entryOf(root, DATA_SPACES), DATA_SPACES_DEPTH);
    }

    private static boolean namesDrm(Entry entry, int depth) {
        if (entry == null) {
            return false;
        }
        if (entry.getName().toUpperCase(Locale.ROOT).contains("DRM")) {
            return true;
        }
        if (depth == 0 || !(entry instanceof DirectoryEntry directory)) {
            return false;
        }
        for (Entry child : directory) {
            if (namesDrm(child, depth - 1)) {
                return true;
            }
        }
        return false;
    }

    private static Entry entryOf(DirectoryEntry directory, String name) {
        try {
            return directory.getEntryCaseInsensitive(name);
        } catch (IOException e) {
            return null;
        }
    }

    private static int uint16(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) | (bytes[offset + 1] & 0xFF) << 8;
    }

    private static UnsupportedArtifactTypeException refused(Reason reason, String message) {
        return new UnsupportedArtifactTypeException(reason, message);
    }
}
