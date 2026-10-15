package io.github.vihuynh72.brownie.core.mcp;

/**
 * Which file a download link hands out. Every export produces a DOCX; a
 * PDF exists only when the export produced one, and a link for a file
 * that does not exist is refused at issue time, not at download.
 */
public enum McpDownloadFile {

    DOCX,
    PDF
}
