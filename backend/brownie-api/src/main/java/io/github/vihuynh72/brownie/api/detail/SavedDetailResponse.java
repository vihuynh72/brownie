package io.github.vihuynh72.brownie.api.detail;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;

/**
 * One saved detail as the API reports it: {@code value} is null for a
 * sensitive row, which carries only its {@code hint}. {@code groupOrdinal}
 * and {@code keyOrdinal} are for sorting, never sent.
 */
public record SavedDetailResponse(
        String key,
        String label,
        String group,
        boolean sensitive,
        String value,
        String hint,
        String origin,
        DetailSource source,
        Instant savedAt,
        Instant confirmedAt,
        @JsonIgnore int groupOrdinal,
        @JsonIgnore int keyOrdinal) {
}
