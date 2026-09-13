package io.github.vihuynh72.brownie.core.compile;

import io.github.vihuynh72.brownie.core.revision.DocumentContent;
import io.github.vihuynh72.brownie.core.template.FieldDefinition;

import java.util.List;

/**
 * Binds a document revision's typed content into one template's original
 * DOCX bytes, using only the field definitions' own approved bindings --
 * never a field the template does not declare, and never model-composed
 * text. Depends on no framework or parsing library of its own; a real
 * Apache POI implementation is supplied by whichever module wires this up,
 * matching {@code DocumentExtractionService}'s own dependency-inversion
 * shape.
 */
public interface TemplateFiller {

    FilledDocument fill(byte[] templateDocxBytes, List<FieldDefinition> fieldDefinitions, DocumentContent content);
}
