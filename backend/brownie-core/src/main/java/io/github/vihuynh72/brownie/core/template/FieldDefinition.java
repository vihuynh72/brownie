package io.github.vihuynh72.brownie.core.template;

/**
 * One typed, bound field within a {@link TemplateVersion}. {@code fieldId}
 * is stable across a template's own versions (rules and a document's field
 * revisions address a field by this ID, not by its
 * position), unique within the version it belongs to.
 *
 * <p>The last four parts are all optional, and a field that has none of
 * them reads exactly as every field did before they existed:
 * <ul>
 * <li>{@code label} is the name a person sees ("Date of birth"), taken from
 * the document or typed by the person; null means the name is worked out
 * from the ID ({@link FieldIds#labelFor}). It is only ever a name: it
 * never changes which field a value belongs to, which is always the ID.
 * <li>{@code origin} says who placed the spot; null means it came with the
 * form ({@link SpotOrigin#FORM}).
 * <li>{@code docxControl} says, for a Word document, whether the spot's
 * named control was in the file already or was added by Brownie, so taking
 * the spot away again can put the file back the way it was; null means it
 * was in the file ({@link DocxControlOrigin#ORIGINAL}), and it stays null
 * for any other kind of document.
 * <li>{@code blankText} is the form's own blank ("________", "[Company]"),
 * written back into the spot when it has no value, so an unfilled spot
 * prints the way the form did rather than as nothing at all. It is never a
 * value: nothing checks for it in the filled document.
 * </ul>
 */
public record FieldDefinition(
        String fieldId,
        FieldType type,
        FieldCardinality cardinality,
        FieldRequiredness requiredness,
        FieldBindingTarget binding,
        String label,
        SpotOrigin origin,
        DocxControlOrigin docxControl,
        String blankText) {

    /** A field with no stored label, origin, control origin or blank: one that came with the form. */
    public FieldDefinition(
            String fieldId, FieldType type, FieldCardinality cardinality, FieldRequiredness requiredness, FieldBindingTarget binding) {
        this(fieldId, type, cardinality, requiredness, binding, null, null, null, null);
    }

    /** Who placed the spot, with a field that never said read as one that came with the form. */
    public SpotOrigin effectiveOrigin() {
        return origin == null ? SpotOrigin.FORM : origin;
    }

    /** The name a person sees for this field: its stored label, or else the one worked out from its ID. */
    public String displayLabel() {
        return label != null ? label : FieldIds.labelFor(fieldId);
    }
}
