package io.github.vihuynh72.brownie.core.action;

import java.util.Objects;

/**
 * A proposal ready to be recorded: its canonical payload, the key that names
 * the change it makes, and the typed facts its approval will depend on. The
 * hash is not a field: it is computed from the payload when the proposal is
 * recorded, so it cannot disagree with it.
 */
public record NewAction(
        long documentId,
        long connectionId,
        ActionType type,
        String payloadCanonical,
        String siblingKey,
        Long requiredRevisionId,
        Long exportReceiptId,
        Long targetActionId,
        String targetExternalId,
        String providerKey) {

    public NewAction {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(payloadCanonical, "payloadCanonical");
        Objects.requireNonNull(siblingKey, "siblingKey");
    }

    public String payloadHash() {
        return CanonicalJson.sha256Hex(payloadCanonical);
    }

    @Override
    public String toString() {
        return "NewAction[type=" + type + ", document=" + documentId + "]";
    }
}
