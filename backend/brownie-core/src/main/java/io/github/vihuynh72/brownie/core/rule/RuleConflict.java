package io.github.vihuynh72.brownie.core.rule;

import java.util.List;

/** One detected conflict and every rule revision ID it involves (one for a protected-region/field conflict, two or more for rules that directly disagree) -- the "specific explanation" template activation must give when it refuses to proceed. */
public record RuleConflict(RuleConflictReason reason, List<Long> ruleIds, String detail) {
}
