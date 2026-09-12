package io.github.vihuynh72.brownie.core.revision;

/** The mutation namespace used when scoping a document idempotency key. */
public enum DocumentCommandType {
    CREATE("document.create"),
    EDIT_CONTENT("document.edit-content");

    private final String operation;

    DocumentCommandType(String operation) {
        this.operation = operation;
    }

    public String operation() {
        return operation;
    }
}
