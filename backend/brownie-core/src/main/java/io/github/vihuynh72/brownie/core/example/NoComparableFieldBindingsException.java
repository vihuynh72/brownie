package io.github.vihuynh72.brownie.core.example;

/**
 * The draft has no {@code ContentControlTag}-bound field to compare an
 * example against -- either it has no field definitions at all yet, or
 * every one of its fields is bound by {@code StructuralNode}, a per-document
 * sibling-index path that is never expected to match literally across two
 * separately extracted documents and therefore proves nothing about whether
 * an example belongs to the same template family. Examples are meant to be
 * compared only once a template's own field mappings are established; a
 * draft with nothing comparable yet has not reached that point.
 */
public class NoComparableFieldBindingsException extends RuntimeException {

    public NoComparableFieldBindingsException(long templateId) {
        super("Template " + templateId
                + " has no content-control-tag-bound field yet; map at least one such field before attaching an example.");
    }
}
