package io.github.vihuynh72.brownie.core.action;

/**
 * Which kinds of action this deployment offers. Writing to people's accounts
 * is a deliberate choice for each deployment, so nothing is offered until
 * whoever runs it switches it on and the provider is set up.
 */
public interface ActionOffer {

    boolean offered(ActionType type);
}
