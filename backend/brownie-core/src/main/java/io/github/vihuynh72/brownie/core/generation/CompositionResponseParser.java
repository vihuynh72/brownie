package io.github.vihuynh72.brownie.core.generation;

import java.util.List;
import java.util.Map;

/**
 * Turns a composition reply's raw JSON into one {@link FieldCandidate} per
 * requested composable field. This module has no JSON library of its own
 * (see {@link CompositionPromptBuilder}), so a real implementation belongs
 * to whichever module does; this interface exists so {@link
 * CompositionService} stays testable with a fake one, the same split
 * {@link ExtractionResponseParser} already establishes for extraction.
 */
public interface CompositionResponseParser {

    Map<String, FieldCandidate> parse(String json, List<String> composableFieldIds) throws CompositionResponseParseException;
}
