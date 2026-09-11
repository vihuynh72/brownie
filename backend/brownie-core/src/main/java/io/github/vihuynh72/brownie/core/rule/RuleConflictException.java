package io.github.vihuynh72.brownie.core.rule;

import java.util.List;

/** At least one real conflict exists among the rules proposed against a template's current draft -- activation refuses with every conflict named at once, never asking anything downstream to guess which rule to ignore. */
public class RuleConflictException extends RuntimeException {

    private final List<RuleConflict> conflicts;

    public RuleConflictException(List<RuleConflict> conflicts) {
        super("Rules conflict: " + conflicts);
        this.conflicts = List.copyOf(conflicts);
    }

    public List<RuleConflict> conflicts() {
        return conflicts;
    }
}
