package io.github.vihuynh72.brownie.core.artifact;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

/**
 * Classifies uploaded content by sniffing its actual bytes -- never a
 * client-declared content type or a filename extension -- and, for a ZIP
 * package, walks its entries with hard bounds on entry count and total
 * uncompressed size so a hostile archive is rejected while it is still
 * being read, not after it has been fully expanded. The same walk refuses
 * a package that names one part twice (two readers can then disagree about
 * which one is the document), one that expands implausibly far for its
 * size, and any XML part that {@link PackagePartInspector} refuses.
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
    /**
     * Text-heavy Word parts compress ten or twenty to one, a large empty
     * table perhaps fifty. A package that has expanded two hundred to one
     * over more than a few megabytes is not a document.
     */
    private static final long MAX_COMPRESSION_RATIO = 200;
    private static final long COMPRESSION_RATIO_FLOOR_BYTES = 8L * 1024 * 1024;
    /**
     * The archive reader fetches compressed bytes ahead in steps of this
     * size, so where one part's compressed bytes end and the next one's
     * begin is only known to within one step. The step is counted as the
     * part's own, which errs towards calling a small part ordinary.
     */
    private static final long COMPRESSED_READ_AHEAD_BYTES = 512;
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
        Set<String> partNames = new HashSet<>();
        CountingInputStream compressed = new CountingInputStream(zipContent);
        byte[] buffer = new byte[READ_BUFFER_SIZE];
        try (ZipInputStream zip = new ZipInputStream(compressed)) {
            BoundedEntryStream expanded = new BoundedEntryStream(zip, compressed, maxUncompressedBytes);
            ZipEntry entry;
            while ((entry = nextEntry(zip)) != null) {
                entryCount++;
                if (entryCount > maxEntries) {
                    throw new ArtifactTooLargeException("Package contains more than " + maxEntries + " entries.");
                }
                requireSafeEntryName(entry.getName());
                // Part names are case-insensitive, so two that differ only in case are one part named twice.
                if (!partNames.add(entry.getName().toLowerCase(Locale.ROOT))) {
                    throw new UnsupportedArtifactTypeException("Package contains the same part more than once.");
                }
                if (OOXML_MANIFEST_ENTRY.equals(entry.getName())) {
                    sawManifest = true;
                }
                expanded.beginPart();
                try {
                    if (!entry.isDirectory() && PackagePartInspector.isNamedAsXml(entry.getName())) {
                        PackagePartInspector.inspect(entry.getName(), expanded);
                    }
                    // Whatever was not read above (everything, for a part that is not XML) still counts.
                    while (expanded.read(buffer) != -1) {
                        // Reading is the point: the bounds are enforced by the stream itself.
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

    /** Counts what is read from the archive as it arrives, which is the compressed side of the ratio. */
    private static final class CountingInputStream extends FilterInputStream {

        private long total;

        private CountingInputStream(InputStream in) {
            super(in);
        }

        long total() {
            return total;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                total++;
            }
            return value;
        }

        @Override
        public int read(byte[] target, int offset, int length) throws IOException {
            int read = super.read(target, offset, length);
            if (read > 0) {
                total += read;
            }
            return read;
        }
    }

    /**
     * The archive's expanded bytes, across every entry, with the limits
     * enforced on the way through, so that they hold no matter who is
     * reading: this class draining an entry, or the XML reader inspecting
     * one. Reaching the end of one entry is the end of this stream until
     * the archive moves to the next.
     *
     * <p>Expanding implausibly far is asked two ways, and both while the
     * bytes are still arriving. Of the package as a whole; and of the
     * bytes that came out of parts which, taken alone, expanded that far,
     * added up across the package. The second is there because the first
     * can be kept looking reasonable by putting something incompressible
     * in front, and because a question asked of one part at a time can be
     * avoided by cutting the same content into many parts.
     */
    private static final class BoundedEntryStream extends InputStream {

        private final ZipInputStream zip;
        private final CountingInputStream compressed;
        private final long maxBytes;
        private long total;
        private long partStartedAtExpanded;
        private long partStartedAtCompressed;
        private long implausibleBytesInEarlierParts;

        private BoundedEntryStream(ZipInputStream zip, CountingInputStream compressed, long maxBytes) {
            this.zip = zip;
            this.compressed = compressed;
            this.maxBytes = maxBytes;
        }

        void beginPart() {
            if (partExpandsImplausiblyFar()) {
                implausibleBytesInEarlierParts += total - partStartedAtExpanded;
            }
            partStartedAtExpanded = total;
            partStartedAtCompressed = compressed.total();
        }

        private boolean partExpandsImplausiblyFar() {
            long partCompressed = compressed.total() - partStartedAtCompressed + COMPRESSED_READ_AHEAD_BYTES;
            return (total - partStartedAtExpanded) / partCompressed > MAX_COMPRESSION_RATIO;
        }

        private void requirePlausibleExpansion() {
            boolean whole = total > COMPRESSION_RATIO_FLOOR_BYTES
                    && total / Math.max(1, compressed.total()) > MAX_COMPRESSION_RATIO;
            long implausibleBytes = implausibleBytesInEarlierParts
                    + (partExpandsImplausiblyFar() ? total - partStartedAtExpanded : 0);
            if (whole || implausibleBytes > COMPRESSION_RATIO_FLOOR_BYTES) {
                throw new ArtifactTooLargeException("Package expands implausibly far for its size.");
            }
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int read = read(one, 0, 1);
            return read < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] target, int offset, int length) throws IOException {
            int read = zip.read(target, offset, length);
            if (read > 0) {
                total += read;
                if (total > maxBytes) {
                    throw new ArtifactTooLargeException("Package expands beyond " + maxBytes + " uncompressed bytes.");
                }
                requirePlausibleExpansion();
            }
            return read;
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
