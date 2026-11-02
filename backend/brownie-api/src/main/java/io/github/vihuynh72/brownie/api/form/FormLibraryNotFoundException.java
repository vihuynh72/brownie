package io.github.vihuynh72.brownie.api.form;

/** A start asked for a catalog id that is not in the library. */
public final class FormLibraryNotFoundException extends RuntimeException {

    private final String id;

    public FormLibraryNotFoundException(String id) {
        super("No form " + id + " in the library.");
        this.id = id;
    }

    public String id() {
        return id;
    }
}
