package io.github.vihuynh72.brownie.core.prepare;

/**
 * A word-processing format Brownie does not fill directly, but opens by
 * converting it to a Word document first. Each one is converted with its own
 * import filter, named in advance: the converter is never left to guess what
 * a file is, because a guess is exactly what a file made to look like one
 * thing and be another is built to win.
 */
public enum ConvertibleFormat {

    /** Word 97 to 2003 (.doc, and .dot templates). */
    WORD_97,
    /** Word 95 and the older binary layout before Word 97's that shares the .doc name, read with the Word 95 filter. */
    WORD_95,
    /** Rich Text Format (.rtf). */
    RTF,
    /** An OpenDocument text document (.odt). */
    ODT,
    /** An OpenDocument text template (.ott). */
    ODT_TEMPLATE,
    /** An Apple Pages document (.pages), in the single-file layout {@link PagesPackages#repack} gives it. */
    PAGES
}
