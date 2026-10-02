package io.github.vihuynh72.brownie.core.prepare;

import java.util.List;

/**
 * Turns places in a Word file into fill spots, and back. The model never
 * writes the file: every change to it goes through this one deterministic
 * editor, whether the place was found by Brownie when the file was uploaded
 * or pointed at by the person later. A spot is a content control directly
 * inside a paragraph of the body or of a top-level table cell, tagged with
 * the field's id and titled with its label, because that is the only kind of
 * control the filler writes into.
 *
 * <p>Edits are applied in order to one copy of the file; the result is a
 * new file, the input is never changed. An edit that cannot be made where it
 * asks throws {@link FillSpotPlacementException} and nothing is returned.
 */
public interface FillSpotEditor {

    EditedDocx apply(byte[] docx, List<SpotEdit> edits);
}
