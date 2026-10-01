package io.github.vihuynh72.brownie.core.prepare;

import java.util.List;
import java.util.Objects;

/**
 * Names places with Brownie's own rules and nothing else: every candidate
 * that does not look like a place to sign is kept, under the label and
 * type the rules gave it, and the repeating row is the first offered row
 * that a kept candidate sits in. Nothing leaves the process. This is what
 * runs when the model is switched off, and what {@link ModelSpotNamer}
 * falls back to for any part of a document the model did not name.
 */
public final class RulesOnlySpotNamer implements SpotNamer {

    private final String reason;

    /** {@code reason} is what the answer reports as {@link SpotNaming#rulesOnlyReason()}, such as {@link SpotNaming#DISABLED}. */
    public RulesOnlySpotNamer(String reason) {
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    @Override
    public SpotNaming name(long workspaceId, long userId, SpotNamingInput input) {
        List<NamedSpot> spots = input.candidates().stream().map(RulesOnlySpotNamer::byRules).toList();
        return new SpotNaming(spots, repeatingRowByRules(input), NamingSource.RULES, reason, List.of());
    }

    /** The rules' own decision about one candidate: kept unless it looks like a place to sign. */
    static NamedSpot byRules(NamingCandidate candidate) {
        return new NamedSpot(candidate.id(), !candidate.signatureLike(), candidate.rulesLabel(), candidate.rulesType(),
                candidate.rulesType().name(), false, false);
    }

    /** The first offered row that a kept candidate sits in, or null when none does. */
    static String repeatingRowByRules(SpotNamingInput input) {
        for (String rowKey : input.offeredRowKeys()) {
            boolean carried = input.candidates().stream()
                    .anyMatch(candidate -> !candidate.signatureLike() && rowKey.equals(candidate.rowKey()));
            if (carried) {
                return rowKey;
            }
        }
        return null;
    }
}
