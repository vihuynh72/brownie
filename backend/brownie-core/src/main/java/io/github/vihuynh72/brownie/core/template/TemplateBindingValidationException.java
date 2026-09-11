package io.github.vihuynh72.brownie.core.template;

import java.util.List;

/**
 * At least one submitted field definition does not resolve to real,
 * unambiguous content in the pinned extraction graph, or reuses another
 * field's own ID -- the request is well-formed but not semantically valid,
 * so nothing was persisted. {@code problems} names every failing field at
 * once rather than only the first, so a caller can fix them all in one
 * pass.
 */
public class TemplateBindingValidationException extends RuntimeException {

    private final List<UnsupportedBinding> problems;

    public TemplateBindingValidationException(List<UnsupportedBinding> problems) {
        super("Field bindings not supported: " + problems);
        this.problems = List.copyOf(problems);
    }

    public List<UnsupportedBinding> problems() {
        return problems;
    }
}
