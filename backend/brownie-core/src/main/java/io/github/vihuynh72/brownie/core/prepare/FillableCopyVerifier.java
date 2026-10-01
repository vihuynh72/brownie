package io.github.vihuynh72.brownie.core.prepare;

import io.github.vihuynh72.brownie.core.template.FieldDefinition;

import java.util.List;

/**
 * Checks, without rendering, that a working copy with its new spots is one
 * Brownie can fill: it reads as a supported document; each field's tag is
 * found exactly once, where the filler writes; the page places every
 * field; the text is what it was before the spots were made, apart from
 * the spaces put between a label and its spot; a fill with sample values
 * writes every value; and nothing active (a macro, a control, a signature)
 * is left in the package.
 */
public interface FillableCopyVerifier {

    /** {@code before} is the file the spots were made in, {@code after} the file with them, {@code fields} what they should become. */
    FillableCopyCheck check(byte[] before, byte[] after, List<FieldDefinition> fields);
}
