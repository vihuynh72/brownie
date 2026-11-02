package io.github.vihuynh72.brownie.core.detail;

import java.time.Instant;

/**
 * One approved answer kept for the next form: its value in plain text when
 * it is not sensitive, or sealed with a display hint when it is. A row has
 * exactly one of {@code valueText} / {@code valueSealed} depending on
 * {@code sensitive}.
 */
public record SavedDetail(
        long id,
        long ownerUserId,
        long workspaceId,
        String key,
        String label,
        String valueText,
        byte[] valueSealed,
        String valueHint,
        boolean sensitive,
        DetailOrigin origin,
        Long sourceDocumentId,
        String sourceLabel,
        String sourceTitle,
        Instant savedAt,
        Instant confirmedAt) {
}
