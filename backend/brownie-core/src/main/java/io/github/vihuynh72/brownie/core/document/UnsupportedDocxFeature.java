package io.github.vihuynh72.brownie.core.document;

/**
 * Something found while reading a DOCX's structure that is worth naming.
 * Most kinds stop the read: a graph that included them would be wrong (a
 * tracked deletion read as text), or filling around them would leak or
 * fetch something (a comment, a linked picture, a field that pulls in
 * another file). The working copy made from an upload takes every one of
 * those out, so they are only ever met in a file that did not go through
 * it. The rest ({@link #keptAsIs()}) do not affect filling: the graph is
 * complete without them, the file keeps them as they are, and they are
 * reported only so a filled copy can be checked for bringing in one the
 * template did not have.
 */
public enum UnsupportedDocxFeature {
    /** A tracked insertion, deletion or move, or a tracked change to a table's rows or cells: the words differ depending on which is shown. */
    TRACKED_CHANGES(false),
    UNRESOLVED_COMMENT(false),
    FLOATING_SHAPE(true),
    NESTED_TABLE(true),
    LINKED_EXTERNAL_IMAGE(false),
    /** An embedded document of a program on the allowed list (a spreadsheet, a chart, a Word document, slides, a drawing). */
    EMBEDDED_OBJECT(true),
    /** A field that fetches or runs something, or one not known at all; the working copy freezes it to the text it shows. */
    UNSUPPORTED_FIELD(false),
    PACKAGE_SIGNATURE(false),
    /** A field Word keeps up to date by itself (a page number, a date, a cross-reference), or a form field the fill spots come from. */
    DYNAMIC_FIELD(true),
    /** An embedded file, equation, control or link whose program is not on the allowed list; the working copy turns it into its picture. */
    UNSAFE_EMBEDDED_OBJECT(false),
    /**
     * A tracked change to formatting only (bold, alignment, a section's margins, a table's borders, a list's
     * numbering): the words are the same whichever is shown, so the graph is right either way. Files read by
     * earlier graph versions were never refused for these, and templates made from them keep working; the working
     * copy of a new upload accepts them all the same.
     */
    TRACKED_FORMATTING_CHANGE(true);

    private final boolean keptAsIs;

    UnsupportedDocxFeature(boolean keptAsIs) {
        this.keptAsIs = keptAsIs;
    }

    /** True when the file may keep this as it is: the graph is complete without it and filling does not touch it. */
    public boolean keptAsIs() {
        return keptAsIs;
    }
}
