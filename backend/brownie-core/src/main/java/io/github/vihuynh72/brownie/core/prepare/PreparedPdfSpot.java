package io.github.vihuynh72.brownie.core.prepare;

import io.github.vihuynh72.brownie.core.template.FieldDefinition;

import java.util.Objects;

/**
 * One place to fill found in a PDF: the field it becomes (bound to one of
 * the form's own text fields, or to a box on a page), who named it, and
 * whether the form or the naming step suggests it is required. The hint
 * never makes the field required: every place found starts optional, and
 * only a person makes one required.
 */
public record PreparedPdfSpot(FieldDefinition definition, NamingSource namedBy, boolean requiredHint) {

    public PreparedPdfSpot {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(namedBy, "namedBy");
    }
}
