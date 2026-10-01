package io.github.vihuynh72.brownie.core.prepare;

/**
 * Turns a naming reply's raw JSON into a {@link FillSpotReply}, checking
 * its shape only. This module has no JSON library of its own, so the real
 * implementation lives with the model adapter; whether each id was offered
 * and each label is usable is {@link ModelSpotNamer}'s job, because only it
 * knows what was asked.
 */
public interface FillSpotResponseParser {

    FillSpotReply parse(String json) throws FillSpotResponseParseException;
}
