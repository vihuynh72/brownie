package io.github.vihuynh72.brownie.core.prepare;

import java.util.Objects;

/**
 * The Word document a conversion produced, and what produced it. The bytes
 * came out of a program that had just read a stranger's file, so they are
 * no more trusted than an upload: whoever receives them inspects and cleans
 * them before anything else reads them. {@code converterVersion} names the
 * program, the exact image it ran in and the import filter it was told to
 * use, so the question "what made this copy" always has one answer.
 */
public record ConvertedDocument(byte[] docxBytes, String converterVersion) {

    public ConvertedDocument {
        docxBytes = Objects.requireNonNull(docxBytes, "docxBytes").clone();
        if (converterVersion == null || converterVersion.isBlank()) {
            throw new IllegalArgumentException("converterVersion must not be blank.");
        }
    }

    @Override
    public byte[] docxBytes() {
        return docxBytes.clone();
    }
}
