package io.github.vihuynh72.brownie.api.action;

import io.github.vihuynh72.brownie.core.action.ActionOffer;
import io.github.vihuynh72.brownie.core.action.ActionType;
import io.github.vihuynh72.brownie.core.connector.ConnectorAccess;

import java.util.List;
import java.util.Set;

/**
 * The kinds of action this deployment offers, decided once at start-up: only
 * when Google is set up, only kinds a handler exists for, and only when
 * whoever runs it has switched changes in people's accounts on
 * ({@code BROWNIE_GOOGLE_ACTIONS_OFFERED}, off by default wherever it runs).
 * {@code accesses} are the connections those kinds are carried out through,
 * which are offered to connect exactly when something uses them.
 */
public record ConfiguredActionOffer(Set<ActionType> types, Set<ConnectorAccess> accesses) implements ActionOffer {

    public ConfiguredActionOffer {
        types = Set.copyOf(types);
        accesses = Set.copyOf(accesses);
    }

    @Override
    public boolean offered(ActionType type) {
        return types.contains(type);
    }

    /** Whether any offered kind of action is carried out through this kind of connection. */
    public boolean uses(ConnectorAccess access) {
        return accesses.contains(access);
    }

    /** In a fixed order, for the capabilities answer. */
    public List<String> names() {
        return types.stream().sorted().map(ActionType::name).toList();
    }
}
