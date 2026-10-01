package io.github.vihuynh72.brownie.core.prepare;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Puts a Pages file into the one layout the converter's Pages reader
 * understands: a single archive whose top level holds the document, with
 * the document's own parts under {@code Index/}.
 *
 * <p>A Pages document is really a folder, and what arrives depends on how
 * it left the Mac. Saved as a single file, it is already that archive.
 * Compressed from the Finder, the whole folder sits one level down, under
 * {@code <name>.pages/}, next to a {@code __MACOSX/} folder of Finder
 * bookkeeping. And in the folder form, the document's parts are themselves
 * zipped, as {@code Index.zip}. This removes the one folder level, leaves
 * the Finder bookkeeping out, and unpacks {@code Index.zip} into
 * {@code Index/}; anything already in the right layout comes back as it
 * was.
 *
 * <p>The archive is read as warily as an upload is: bounded in entries and
 * in expanded bytes (the inner archive counting against the same bounds),
 * refused when it expands implausibly far for its size, when it names one
 * part twice, or when a name tries to climb out of the archive. A file that
 * fails any of that is {@link DocumentConversionException.Reason#DAMAGED}.
 */
public final class PagesPackages {

    private static final byte[] ZIP_SIGNATURE = {0x50, 0x4B, 0x03, 0x04}; // "PK\3\4"
    private static final String PAGES_FOLDER_SUFFIX = ".pages";
    private static final String FINDER_BOOKKEEPING = "__MACOSX/";
    private static final String ZIPPED_INDEX = "Index.zip";
    private static final String INDEX_FOLDER = "Index/";

    private static final long MAX_UNCOMPRESSED_BYTES = 200L * 1024 * 1024;
    private static final int MAX_ENTRIES = 500;
    private static final long MAX_COMPRESSION_RATIO = 200;
    private static final long COMPRESSION_RATIO_FLOOR_BYTES = 8L * 1024 * 1024;
    private static final int READ_BUFFER_SIZE = 8192;

    private PagesPackages() {
    }

    public static byte[] repack(byte[] pages) {
        return repack(pages, MAX_UNCOMPRESSED_BYTES, MAX_ENTRIES);
    }

    /**
     * Package-private so tests can prove the bounds with small thresholds
     * instead of building hundreds of megabytes; {@link #repack(byte[])} is
     * the only entry point real callers use.
     */
    static byte[] repack(byte[] pages, long maxUncompressedBytes, int maxEntries) {
        if (pages == null || pages.length < ZIP_SIGNATURE.length || !startsWithZipSignature(pages)) {
            throw damaged("This Pages file is not an archive.");
        }
        try {
            List<String> names = listNames(pages, maxUncompressedBytes, maxEntries);
            String folder = singlePagesFolder(names);
            boolean zippedIndex = names.contains(folder + ZIPPED_INDEX);
            boolean finderBookkeeping = names.stream().anyMatch(PagesPackages::isFinderBookkeeping);
            if (folder.isEmpty() && !zippedIndex && !finderBookkeeping) {
                return pages;
            }
            return rewrite(pages, folder, maxUncompressedBytes, maxEntries);
        } catch (ZipException e) {
            throw damaged("This Pages file is not a valid archive.", e);
        } catch (IOException e) {
            // Everything is read from memory, so this is a malformed archive rather than a failing disk.
            throw damaged("This Pages file could not be read as an archive.", e);
        }
    }

    /** Every entry's name, read under the bounds, so nothing is decided about an archive that was not read to the end. */
    private static List<String> listNames(byte[] pages, long maxUncompressedBytes, int maxEntries) throws IOException {
        Budget budget = new Budget(pages.length, maxUncompressedBytes, maxEntries);
        List<String> names = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        byte[] buffer = new byte[READ_BUFFER_SIZE];
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(pages))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                budget.countEntry();
                String name = requireSafeName(entry.getName());
                requireFirstMention(seen, name);
                names.add(name);
                InputStream bounded = budget.bounded(zip);
                while (bounded.read(buffer) != -1) {
                    // Reading is the point: the bounds are enforced by the stream itself.
                }
            }
        }
        return names;
    }

    /**
     * The {@code <name>.pages/} folder every entry (Finder bookkeeping
     * aside) sits in, or the empty string when they do not all share one.
     */
    private static String singlePagesFolder(List<String> names) {
        String folder = null;
        for (String name : names) {
            if (isFinderBookkeeping(name)) {
                continue;
            }
            int slash = name.indexOf('/');
            if (slash < 0) {
                return "";
            }
            String first = name.substring(0, slash + 1);
            if (folder == null) {
                folder = first;
            } else if (!folder.equals(first)) {
                return "";
            }
        }
        if (folder == null) {
            return "";
        }
        String bare = folder.substring(0, folder.length() - 1);
        return bare.toLowerCase(Locale.ROOT).endsWith(PAGES_FOLDER_SUFFIX) && bare.length() > PAGES_FOLDER_SUFFIX.length()
                ? folder
                : "";
    }

    private static byte[] rewrite(byte[] pages, String folder, long maxUncompressedBytes, int maxEntries)
            throws IOException {
        Budget budget = new Budget(pages.length, maxUncompressedBytes, maxEntries);
        Set<String> written = new HashSet<>();
        ByteArrayOutputStream out = new ByteArrayOutputStream(pages.length);
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(pages));
                ZipOutputStream repacked = new ZipOutputStream(out)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                budget.countEntry();
                String name = requireSafeName(entry.getName());
                InputStream content = budget.bounded(zip);
                if (isFinderBookkeeping(name) || entry.isDirectory()) {
                    drain(content);
                    continue;
                }
                String path = name.substring(folder.length());
                if (ZIPPED_INDEX.equals(path)) {
                    unpackIndex(content, repacked, written, budget);
                } else {
                    copy(path, content, repacked, written);
                }
            }
        }
        return out.toByteArray();
    }

    /** The inner archive is read straight from the outer one, never held whole, under the same bounds. */
    private static void unpackIndex(InputStream zippedIndex, ZipOutputStream repacked, Set<String> written, Budget budget)
            throws IOException {
        // The inner reader is not closed: closing it would close the outer archive it reads from.
        ZipInputStream index = new ZipInputStream(new NonClosing(zippedIndex));
        ZipEntry entry;
        while ((entry = index.getNextEntry()) != null) {
            budget.countEntry();
            String name = requireSafeName(entry.getName());
            InputStream content = budget.bounded(index);
            if (entry.isDirectory()) {
                drain(content);
                continue;
            }
            copy(name.startsWith(INDEX_FOLDER) ? name : INDEX_FOLDER + name, content, repacked, written);
        }
        drain(zippedIndex);
    }

    private static void copy(String path, InputStream content, ZipOutputStream repacked, Set<String> written)
            throws IOException {
        requireFirstMention(written, path);
        repacked.putNextEntry(new ZipEntry(path));
        content.transferTo(repacked);
        repacked.closeEntry();
    }

    private static void drain(InputStream content) throws IOException {
        byte[] buffer = new byte[READ_BUFFER_SIZE];
        while (content.read(buffer) != -1) {
            // Counted against the bounds, and otherwise left out.
        }
    }

    private static boolean isFinderBookkeeping(String name) {
        return name.startsWith(FINDER_BOOKKEEPING);
    }

    private static String requireSafeName(String name) {
        if (name.isEmpty() || name.contains("..") || name.startsWith("/") || name.contains("\\") || name.indexOf(0) >= 0) {
            throw damaged("This Pages file contains an unsafe entry path.");
        }
        return name;
    }

    /** Names are compared without case, as the archive's readers on a Mac would: two that differ only in case are one part twice. */
    private static void requireFirstMention(Set<String> seen, String name) {
        if (!seen.add(name.toLowerCase(Locale.ROOT))) {
            throw damaged("This Pages file contains the same part more than once.");
        }
    }

    private static boolean startsWithZipSignature(byte[] bytes) {
        for (int i = 0; i < ZIP_SIGNATURE.length; i++) {
            if (bytes[i] != ZIP_SIGNATURE[i]) {
                return false;
            }
        }
        return true;
    }

    private static DocumentConversionException damaged(String message) {
        return new DocumentConversionException(DocumentConversionException.Reason.DAMAGED, message);
    }

    private static DocumentConversionException damaged(String message, Throwable cause) {
        return new DocumentConversionException(DocumentConversionException.Reason.DAMAGED, message, cause);
    }

    /**
     * The limits for one pass over the archive: entries counted, and every
     * expanded byte counted as it is read, whoever reads it. The ratio is
     * taken against the whole file, which is already in memory and so has a
     * known size.
     */
    private static final class Budget {

        private final long compressedBytes;
        private final long maxBytes;
        private final int maxEntries;
        private long expanded;
        private int entries;

        private Budget(long compressedBytes, long maxBytes, int maxEntries) {
            this.compressedBytes = Math.max(1, compressedBytes);
            this.maxBytes = maxBytes;
            this.maxEntries = maxEntries;
        }

        void countEntry() {
            entries++;
            if (entries > maxEntries) {
                throw damaged("This Pages file contains more than " + maxEntries + " parts.");
            }
        }

        InputStream bounded(InputStream entryContent) {
            return new NonClosing(entryContent) {
                @Override
                public int read(byte[] target, int offset, int length) throws IOException {
                    int read = super.read(target, offset, length);
                    if (read > 0) {
                        count(read);
                    }
                    return read;
                }

                @Override
                public int read() throws IOException {
                    int value = super.read();
                    if (value >= 0) {
                        count(1);
                    }
                    return value;
                }
            };
        }

        private void count(int read) {
            expanded += read;
            if (expanded > maxBytes) {
                throw damaged("This Pages file expands beyond " + maxBytes + " bytes.");
            }
            if (expanded > COMPRESSION_RATIO_FLOOR_BYTES && expanded / compressedBytes > MAX_COMPRESSION_RATIO) {
                throw damaged("This Pages file expands implausibly far for its size.");
            }
        }
    }

    /** Reads through to one entry of an archive without letting a reader close the archive itself. */
    private static class NonClosing extends FilterInputStream {

        NonClosing(InputStream in) {
            super(in);
        }

        @Override
        public void close() {
            // The archive this reads from outlives any one entry.
        }
    }
}
