package io.github.vihuynh72.brownie.core.template;

public enum UnsupportedBindingReason {
    /** No node in the pinned extraction graph matches this binding target at all. */
    NOT_FOUND,
    /** More than one node matches -- the target does not uniquely identify a location, so which one is meant is not determined. */
    AMBIGUOUS,
    /** Another field definition in the same request already uses this exact {@code fieldId}. */
    DUPLICATE_FIELD_ID,
    /** The binding is for the other kind of template: a Word binding on a PDF template, or a PDF one on a Word template. */
    WRONG_FORMAT,
    /** The PDF form field cannot take text: it is not a text field, is read-only, or is not shown on any page; or the box is on a page Brownie cannot write on. */
    NOT_FILLABLE,
    /** The box is not wholly on its page, or names a page the PDF does not have. */
    OFF_PAGE,
    /** The box is smaller than {@link TemplateBindingValidator#MIN_BOX_WIDTH} by {@link TemplateBindingValidator#MIN_BOX_HEIGHT} points, too small to write in. */
    TOO_SMALL,
    /** The place covers more than a fifth of another field's box or of one of the form's own fields, or is a form field another field is already bound to. */
    OVERLAPS
}
