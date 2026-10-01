package io.github.vihuynh72.brownie.core.prepare;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Everything the naming step is given about one document: its outline, the
 * places the rules found in it, and the table rows that could repeat (the
 * only ones a reply may choose). Each candidate's id is its own: a reply
 * names places by id, so two candidates sharing one could not be told apart.
 */
public record SpotNamingInput(DocumentKind kind, List<OutlineLine> outline, List<NamingCandidate> candidates,
                              List<String> offeredRowKeys) {

    public SpotNamingInput {
        Objects.requireNonNull(kind, "kind");
        outline = List.copyOf(outline);
        candidates = List.copyOf(candidates);
        offeredRowKeys = List.copyOf(offeredRowKeys);
        Set<String> ids = new HashSet<>();
        for (NamingCandidate candidate : candidates) {
            if (!ids.add(candidate.id())) {
                throw new IllegalArgumentException("The candidate id " + candidate.id() + " is used twice.");
            }
        }
    }
}
