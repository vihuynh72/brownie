package io.github.vihuynh72.brownie.core.rule;

/** The template does not exist, or has no currently open draft version to propose a rule against -- a rule can only ever be proposed against the template's current draft, the same version its field definitions are still being edited on. */
public class RuleTemplateVersionStateException extends RuntimeException {

    public RuleTemplateVersionStateException(String message) {
        super(message);
    }
}
