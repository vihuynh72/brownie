package io.github.vihuynh72.brownie.core.batch;

import java.util.List;

/**
 * Everything a download writes, planned while the request still has its
 * database handle: one entry per exported file with the receipt's hash to
 * prove it by, and one CSV row per item of the run. {@code batchTitle} is
 * the archive's suggested filename before sanitizing.
 */
public record BatchExportZip(
        String batchTitle,
        List<Entry> entries,
        List<List<String>> csvRows) {

    public BatchExportZip {
        entries = List.copyOf(entries);
        csvRows = csvRows.stream().map(List::copyOf).toList();
    }

    /** One file in the archive: its already-unique entry name, the artifact to read, and the SHA-256 its receipt promises. */
    public record Entry(String name, long artifactId, String expectedSha256) {
    }
}
