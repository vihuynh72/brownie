package io.github.vihuynh72.brownie.core.prepare;

import java.util.List;

/**
 * Reads a clean working copy of a Word form for its places to fill, and
 * makes the one structural change a plan can ask for that is not a spot.
 * A real implementation parses Word files, which only the module that
 * wires this up may do.
 */
public interface WordForms {

    /**
     * The form's outline. Each of the form's own fields for a blank (a form
     * text box, a merge field, a prompt) is first written out as the text it
     * showed, so that text is part of its paragraph like any other and a
     * spot can hold it; {@link ReadForm#docxBytes()} is the file that
     * change made, which the outline, and every anchor made from it, is
     * read from. A form with no such field comes back byte for byte.
     */
    ReadForm read(byte[] docx);

    /** The file without the main document's table rows {@code rowNodeIds}; every other node keeps its id. */
    byte[] withoutRows(byte[] docx, List<String> rowNodeIds);

    /** A form's outline and the bytes it was read from. */
    record ReadForm(byte[] docxBytes, FormOutline outline) {
    }
}
