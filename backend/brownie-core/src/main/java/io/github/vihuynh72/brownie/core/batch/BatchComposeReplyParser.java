package io.github.vihuynh72.brownie.core.batch;

import java.util.Set;

/**
 * Decodes the compose step's strict reply. Kept as a port so the core
 * stays free of a JSON library; the Jackson implementation lives beside
 * the other reply parsers in the ai module.
 */
public interface BatchComposeReplyParser {

    /**
     * @param json the reply's body
     * @param expectedIds the field ids the request asked for -- the reply
     * must name exactly these
     */
    BatchComposeReply parse(String json, Set<String> expectedIds) throws BatchComposeReplyParseException;
}
