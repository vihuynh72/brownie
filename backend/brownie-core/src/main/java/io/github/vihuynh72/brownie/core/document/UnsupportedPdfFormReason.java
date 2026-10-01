package io.github.vihuynh72.brownie.core.document;

/** Why a PDF cannot be used as a form Brownie fills in. Each is a property of the whole file. */
public enum UnsupportedPdfFormReason {
    /** The file is locked, including with only an owner password that restricts what may be done with it. Saving a filled copy would drop that protection, so the file is left alone. */
    ENCRYPTED,
    /** The file carries a signature. Filling it in would break the signature. */
    SIGNED,
    /** The form is an XFA form, static or dynamic, a kind Brownie cannot fill. */
    XFA,
    /** Something in the file starts another program when it is opened or clicked. */
    LAUNCH_ACTION,
    /** The file carries other files inside it. */
    EMBEDDED_FILES,
    /** The file runs a script when it is opened or a page is shown (a field's own formatting script is not this). */
    DOCUMENT_JAVASCRIPT,
    /** The bytes cannot be read as a PDF. */
    DAMAGED,
    /** More pages, fields or content than any form holds, or content that expands far beyond what a file of its size can hold. */
    TOO_LARGE
}
