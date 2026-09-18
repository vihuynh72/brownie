package io.github.vihuynh72.brownie.core.generation;

import java.util.List;

/**
 * At least one required field's accepted fact is still unresolved (its
 * question, if any, has not been answered yet). No paid composition
 * call runs while the
 * workflow is still waiting on required input -- this is refused before
 * the model gateway is ever touched, the same "check before spending"
 * discipline {@link io.github.vihuynh72.brownie.core.generation.usage.UsageBudget}
 * already enforces for budget and cancellation.
 */
public class RequiredFactsUnresolvedException extends Exception {

    private final List<String> unresolvedRequiredFieldIds;

    public RequiredFactsUnresolvedException(List<String> unresolvedRequiredFieldIds) {
        super("Required field(s) are still unresolved and have no answered question: " + unresolvedRequiredFieldIds);
        this.unresolvedRequiredFieldIds = List.copyOf(unresolvedRequiredFieldIds);
    }

    public List<String> unresolvedRequiredFieldIds() {
        return unresolvedRequiredFieldIds;
    }
}
