package io.github.vihuynh72.brownie.core.artifact;

import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

/**
 * Classifies uploaded content by sniffing its actual bytes -- never a
 * client-declared content type or a filename extension -- and, for a ZIP
 * package, walks its entries with hard bounds on entry count and total
 * uncompressed size so a hostile archive is rejected while it is still
 * being read, not after it has been fully expanded.
 *
 * <p>This only distinguishes an OOXML-shaped package (has the manifest
 * every DOCX must have) from an arbitrary ZIP. It does not parse OOXML
 * relationships, styles, or content -- that structural understanding is
 * deliberately out of scope here and belongs to the extraction work that
 * actually needs Apache POI.
 */
public final class ArtifactContentInspector {

    private static final byte[] PDF_SIGNATURE = "%PDF-".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] ZIP_SIGNATURE = {0x50, 0x4B, 0x03, 0x04}; // "PK\3\4"
    private static final String OOXML_MANIFEST_ENTRY = "[Content_Types].xml";
    private static final int NUL_BYTE = 0;

    private static final int SIGNATURE_PEEK_BYTES = 8;
    private static final int TEXT_SCAN_WINDOW_BYTES = 64 * 1024;
    private static final long MAX_UNCOMPRESSED_BYTES = 200L * 1024 * 1024;
    private static final int MAX_ZIP_ENTRIES = 500;
    private static final int READ_BUFFER_SIZE = 8192;

    private ArtifactContentInspector() {
    }

    public static SupportedMediaType inspect(InputStream content) throws IOException {
        return inspect(content, MAX_UNCOMPRESSED_BYTES, MAX_ZIP_ENTRIES);
    }

    /**
     * Package-private so tests can exercise the entry-count and
     * uncompressed-size bounds directly, with small thresholds, instead
     * of needing to actually build hundreds of megabytes of payload to
     * prove the real {@link #MAX_UNCOMPRESSED_BYTES}/{@link
     * #MAX_ZIP_ENTRIES} constants work. {@link #inspect(InputStream)} is
     * the only entry point real callers use.
     */
    static SupportedMediaType inspect(InputStream content, long maxUncompressedBytes, int maxEntries)
            throws IOException {
        PushbackInputStream pushback = new PushbackInputStream(content, SIGNATURE_PEEK_BYTES);
        byte[] signature = new byte[SIGNATURE_PEEK_BYTES];
        int read = readFully(pushback, signature);
        pushback.unread(signature, 0, read);

        if (startsWith(signature, read, PDF_SIGNATURE)) {
            return SupportedMediaType.PDF;
        }
        if (startsWith(signature, read, ZIP_SIGNATURE)) {
            requireOoxmlPackage(pushback, maxUncompressedBytes, maxEntries);
            return SupportedMediaType.DOCX;
        }
        requirePlausibleText(pushback);
        return SupportedMediaType.PLAIN_TEXT;
    }

    private static void requireOoxmlPackage(InputStream zipContent, long maxUncompressedBytes, int maxEntries)
            throws IOException {
        boolean sawManifest = false;
        int entryCount = 0;
        long totalUncompressed = 0;
        byte[] buffer = new byte[READ_BUFFER_SIZE];
        try (ZipInputStream zip = new ZipInputStream(zipContent)) {
            ZipEntry entry;
            while ((entry = nextEntry(zip)) != null) {
                entryCount++;
                if (entryCount > maxEntries) {
                    throw new ArtifactTooLargeException("Package contains more than " + maxEntries + " entries.");
                }
                requireSafeEntryName(entry.getName());
                if (OOXML_MANIFEST_ENTRY.equals(entry.getName())) {
                    sawManifest = true;
                }
                int read;
                try {
                    while ((read = zip.read(buffer)) != -1) {
                        totalUncompressed += read;
                        if (totalUncompressed > maxUncompressedBytes) {
                            throw new ArtifactTooLargeException(
                                    "Package expands beyond " + maxUncompressedBytes + " uncompressed bytes.");
                        }
                    }
                } catch (ZipException e) {
                    throw new UnsupportedArtifactTypeException("Package is not a valid ZIP archive.");
                }
            }
        }
        if (!sawManifest) {
            throw new UnsupportedArtifactTypeException(
                    "Package is a ZIP archive but not a recognized OOXML document (no " + OOXML_MANIFEST_ENTRY
                            + ").");
        }
    }

    private static ZipEntry nextEntry(ZipInputStream zip) throws IOException {
        try {
            return zip.getNextEntry();
        } catch (ZipException e) {
            throw new UnsupportedArtifactTypeException("Package is not a valid ZIP archive.");
        }
    }

    private static void requireSafeEntryName(String name) {
        if (name.contains("..") || name.startsWith("/") || name.startsWith("\\") || name.indexOf(NUL_BYTE) >= 0) {
            throw new UnsupportedArtifactTypeException("Package contains an unsafe entry path.");
        }
    }

    private static void requirePlausibleText(InputStream content) throws IOException {
        byte[] buffer = new byte[READ_BUFFER_SIZE];
        long scanned = 0;
        int read;
        while (scanned < TEXT_SCAN_WINDOW_BYTES && (read = content.read(buffer)) != -1) {
            for (int i = 0; i < read; i++) {
                if (buffer[i] == NUL_BYTE) {
                    throw new UnsupportedArtifactTypeException(
                            "Content does not match any supported media type or package signature.");
                }
            }
            scanned += read;
        }
    }

    private static int readFully(InputStream in, byte[] buffer) throws IOException {
        int total = 0;
        int read;
        while (total < buffer.length && (read = in.read(buffer, total, buffer.length - total)) != -1) {
            total += read;
        }
        return total;
    }

    private static boolean startsWith(byte[] data, int dataLength, byte[] prefix) {
        if (dataLength < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
