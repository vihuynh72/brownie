package io.github.vihuynh72.brownie.core.template;

/**
 * What a template is filled into. A {@link #DOCX} template is a Word file
 * whose places are named controls or nodes of its structure; it is filled
 * as a Word file and turned into a PDF. A {@link #PDF} template is a PDF
 * whose places are its own fillable fields or boxes on its pages; it is
 * filled as a PDF and never becomes a Word file.
 */
public enum TemplateKind {
    DOCX,
    PDF
}
