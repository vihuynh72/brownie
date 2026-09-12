package io.github.vihuynh72.brownie.core.template;

/** One field definition, from a rejected set, and exactly why its binding is not supported. */
public record UnsupportedBinding(String fieldId, UnsupportedBindingReason reason) {
}
