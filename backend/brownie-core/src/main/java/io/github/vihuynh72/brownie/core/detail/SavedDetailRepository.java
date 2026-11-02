package io.github.vihuynh72.brownie.core.detail;

import java.util.List;

/**
 * A person's saved details, read and written only as that person: every
 * method takes the owner it acts for, and the row-level security on
 * {@code saved_detail} keeps one owner's rows invisible to another.
 */
public interface SavedDetailRepository {

    /** Every detail the owner has saved, in the registry's key order. */
    List<SavedDetail> list(long ownerUserId);

    /**
     * Writes one detail for the owner, creating or replacing the row for
     * {@code key}: a non-sensitive value lands in {@code valueText}; a
     * sensitive one arrives already sealed with its display hint and no
     * plain text is kept. {@code source*} names the form field and
     * document the value was approved on; {@code confirmedAt} refreshes to
     * the save time.
     */
    SavedDetail upsert(
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
            String sourceTitle);

    /** Removes the owner's row for {@code key}; nothing changes when there is none. */
    void delete(long ownerUserId, String key);

    /** Removes every row the owner has ("Delete all saved details"). */
    void deleteAll(long ownerUserId);
}
