package io.github.vihuynh72.brownie.core.prepare;

/**
 * Where the Word file came from. A person's own upload has already been
 * refused if it links to anything on the internet, so only links to local
 * files are left to remove. A file the converter wrote from another format
 * was never checked for that, so its internet links are removed as well.
 */
public enum PreparationMode {
    UPLOAD,
    CONVERTER_OUTPUT
}
