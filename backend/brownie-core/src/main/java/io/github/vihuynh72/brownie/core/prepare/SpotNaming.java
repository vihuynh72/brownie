package io.github.vihuynh72.brownie.core.prepare;

import java.util.List;
import java.util.Objects;

/**
 * The naming step's answer: one {@link NamedSpot} per candidate, in the
 * order the candidates were given, the table row that repeats (or null),
 * and who named them. {@code source} is {@link NamingSource#MODEL} when the
 * model named at least one place; {@code rulesOnlyReason} then is null.
 * Otherwise it says why the rules named them all: {@code DISABLED},
 * {@code NO_CANDIDATES}, {@code ALLOWANCE_USED_UP} or
 * {@code MODEL_UNAVAILABLE}.
 */
public record SpotNaming(List<NamedSpot> spots, String repeatingRowKey, NamingSource source, String rulesOnlyReason,
                         List<PreparationNotice> notices) {

    public static final String DISABLED = "DISABLED";
    public static final String NO_CANDIDATES = "NO_CANDIDATES";
    public static final String ALLOWANCE_USED_UP = "ALLOWANCE_USED_UP";
    public static final String MODEL_UNAVAILABLE = "MODEL_UNAVAILABLE";

    public SpotNaming {
        spots = List.copyOf(spots);
        Objects.requireNonNull(source, "source");
        notices = List.copyOf(notices);
    }
}
