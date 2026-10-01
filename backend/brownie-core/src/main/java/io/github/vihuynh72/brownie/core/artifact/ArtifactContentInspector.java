package io.github.vihuynh72.brownie.core.artifact;

import io.github.vihuynh72.brownie.core.prepare.ConvertibleFormat;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
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
 * <p>In order: a PDF by its signature; a ZIP package by what it declares
 * about itself once the walk is over ({@link PackageContents}); a Microsoft
 * compound file by a {@link CompoundFileProbe}, since reading one needs a
 * library this module does not have; RTF by its opening control word; and
 * anything else as plain text if it plausibly is. Every refusal says why
 * ({@link UnsupportedArtifactTypeException.Reason}): a spreadsheet is told
 * it is a spreadsheet, a locked file that it is locked.
 *
 * <p>It does not parse Office relationships, styles, or content beyond
 * what those declarations need -- that structural understanding is
 * deliberately out of scope here and belongs to the extraction work that
 * actually needs Apache POI.
 */
public final class ArtifactContentInspector {

    private static final byte[] PDF_SIGNATURE = "%PDF-".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] ZIP_SIGNATURE = {0x50, 0x4B, 0x03, 0x04}; // "PK\3\4"
    private static final byte[] COMPOUND_FILE_SIGNATURE =
            {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};
    private static final byte[] RTF_SIGNATURE = "{\\rtf".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] UTF8_BYTE_ORDER_MARK = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
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

    /**
     * The media type under the rules for a file a person gave, with no
     * compound file reader: for a caller that only ever expects Brownie's
     * own kinds of output and would refuse anything else anyway.
     */
    public static SupportedMediaType inspect(InputStream content) throws IOException {
        return inspect(content, CompoundFileProbe.NONE, PackagePolicy.UPLOAD).mediaType();
    }

    /** Everything the inspection finds, under {@code policy}, reading a compound file with {@code probe}. */
    public static ContentInspection inspect(InputStream content, CompoundFileProbe probe, PackagePolicy policy)
            throws IOException {
        return inspect(content, probe, policy, MAX_UNCOMPRESSED_BYTES, MAX_ZIP_ENTRIES);
    }

    /**
     * Bytes already in hand: a converter's output, say, which must still be
     * a package these bounds accept before anything else reads it.
     */
    public static ContentInspection inspect(byte[] content, PackagePolicy policy) throws IOException {
        return inspect(new ByteArrayInputStream(content), CompoundFileProbe.NONE, policy);
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
        return inspect(content, CompoundFileProbe.NONE, PackagePolicy.UPLOAD, maxUncompressedBytes, maxEntries)
                .mediaType();
    }

    static ContentInspection inspect(
            InputStream content,
            CompoundFileProbe probe,
            PackagePolicy policy,
            long maxUncompressedBytes,
            int maxEntries) throws IOException {
        PushbackInputStream pushback = new PushbackInputStream(content, SIGNATURE_PEEK_BYTES);
        byte[] signature = new byte[SIGNATURE_PEEK_BYTES];
        int read = readFully(pushback, signature);
        pushback.unread(signature, 0, read);

        if (startsWith(signature, read, 0, PDF_SIGNATURE)) {
            return ContentInspection.of(SupportedMediaType.PDF);
        }
        if (startsWith(signature, read, 0, ZIP_SIGNATURE)) {
            return inspectPackage(pushback, policy, maxUncompressedBytes, maxEntries);
        }
        if (startsWith(signature, read, 0, COMPOUND_FILE_SIGNATURE)) {
            return new ContentInspection(SupportedMediaType.DOC, probe.probe(pushback));
        }
        if (startsWith(signature, read, 0, RTF_SIGNATURE)
                || (startsWith(signature, read, 0, UTF8_BYTE_ORDER_MARK)
                        && startsWith(signature, read, UTF8_BYTE_ORDER_MARK.length, RTF_SIGNATURE))) {
            return new ContentInspection(SupportedMediaType.RTF, ConvertibleFormat.RTF);
        }
        requirePlausibleText(pushback);
        return ContentInspection.of(SupportedMediaType.PLAIN_TEXT);
    }

    private static ContentInspection inspectPackage(
            InputStream zipContent, PackagePolicy policy, long maxUncompressedBytes, int maxEntries)
            throws IOException {
        PackageContents contents = new PackageContents();
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
                String name = entry.getName();
                requireSafeEntryName(name);
                // Part names are case-insensitive, so two that differ only in case are one part named twice.
                if (!partNames.add(name.toLowerCase(Locale.ROOT))) {
                    throw new UnsupportedArtifactTypeException(
                            UnsupportedArtifactTypeException.Reason.DAMAGED, "Package contains the same part more than once.");
                }
                contents.entry(name);
                expanded.beginPart();
                try {
                    if (!entry.isDirectory() && PackagePartInspector.isNamedAsXml(name)) {
                        inspectPart(name, expanded, policy, contents);
                    } else if (!entry.isDirectory() && contents.wantsHead(name)) {
                        byte[] head = new byte[contents.headBytes(name)];
                        contents.keep(name, head, readFully(expanded, head));
                    }
                    // Whatever was not read above (everything, for a part that is not XML) still counts.
                    while (expanded.read(buffer) != -1) {
                        // Reading is the point: the bounds are enforced by the stream itself.
                    }
                } catch (ZipException e) {
                    throw notAValidZip();
                }
            }
        } catch (EOFException e) {
            // The archive stops in the middle of an entry: cut short, not a storage failure.
            throw notAValidZip();
        }
        return contents.classify();
    }

    /**
     * An OpenDocument part that is not XML may be one the manifest says is
     * encrypted, so the judgement waits for the manifest; an empty one, which
     * older OpenOffice versions leave behind, is not a part to judge at all.
     */
    private static void inspectPart(String name, BoundedEntryStream part, PackagePolicy policy, PackageContents contents)
            throws IOException {
        try {
            PackagePartInspector.inspect(
                    name, part, policy, contents.mayDeclareDocumentType(name), contents.listenerFor(name));
        } catch (PackagePartInspector.MalformedPartException e) {
            if (!contents.holdsMalformed(name)) {
                throw e;
            }
            if (part.readSoFarInPart() > 0) {
                contents.malformedPartHeld();
            }
        }
    }

    private static UnsupportedArtifactTypeException notAValidZip() {
        return new UnsupportedArtifactTypeException(
                UnsupportedArtifactTypeException.Reason.DAMAGED, "Package is not a valid ZIP archive.");
    }

    private static ZipEntry nextEntry(ZipInputStream zip) throws IOException {
        try {
            return zip.getNextEntry();
        } catch (ZipException e) {
            throw notAValidZip();
        }
    }

    private static void requireSafeEntryName(String name) {
        if (name.contains("..") || name.startsWith("/") || name.startsWith("\\") || name.indexOf(NUL_BYTE) >= 0) {
            throw new UnsupportedArtifactTypeException(
                    UnsupportedArtifactTypeException.Reason.DAMAGED, "Package contains an unsafe entry path.");
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

        /** Expanded bytes read from the current part so far. */
        long readSoFarInPart() {
            return total - partStartedAtExpanded;
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

    private static boolean startsWith(byte[] data, int dataLength, int offset, byte[] prefix) {
        if (dataLength < offset + prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[offset + i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
