package io.github.vihuynh72.brownie.core.generation;

import io.github.vihuynh72.brownie.core.template.FieldDefinition;

import java.util.List;

/**
 * Turns a model's raw JSON reply (already known to be syntactically valid
 * JSON -- see {@link io.github.vihuynh72.brownie.core.model.ModelGateway})
 * into a typed {@link ExtractionResult} matching the exact field shape it
 * was asked for. This module has no JSON library of its own, so a real
 * implementation belongs to whichever module does; this interface exists
 * so {@link ExtractionService} stays testable with a fake one. Evidence-
 * span validation against the exact spans this run actually offered is
 * deliberately not this interface's job -- it has no way to know that set
 * -- and is checked by {@link ExtractionService} itself after parsing.
 */
public interface ExtractionResponseParser {

    ExtractionResult parse(String json, List<FieldDefinition> scalarFields, List<FieldDefinition> repeatedFields)
            throws ExtractionResponseParseException;
}
