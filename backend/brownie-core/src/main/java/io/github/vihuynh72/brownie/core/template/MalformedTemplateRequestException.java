package io.github.vihuynh72.brownie.core.template;

/** The request's shape violates a basic invariant JSON typing alone cannot express -- a blank field ID, or a binding missing the sub-field its own kind requires. Distinct from {@link TemplateBindingValidationException}: this is malformed input, not input that is well-formed but unsupported. */
public class MalformedTemplateRequestException extends RuntimeException {

    public MalformedTemplateRequestException(String message) {
        super(message);
    }
}
