package io.github.vihuynh72.brownie.api.persistence.jdbc;

import io.github.vihuynh72.brownie.core.detail.DetailOrigin;
import io.github.vihuynh72.brownie.core.detail.SavedDetail;
import io.github.vihuynh72.brownie.core.detail.SavedDetailRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.List;

/** Saved details through JDBC, with the owner's user id set for row-level security on every call. */
@Repository
class JdbcSavedDetailRepository implements SavedDetailRepository {

    private static final RowMapper<SavedDetail> ROW = (rs, rowNum) -> new SavedDetail(
            rs.getLong("id"),
            rs.getLong("owner_user_id"),
            rs.getLong("workspace_id"),
            rs.getString("key"),
            rs.getString("label"),
            rs.getString("value_text"),
            rs.getBytes("value_sealed"),
            rs.getString("value_hint"),
            rs.getBoolean("sensitive"),
            DetailOrigin.valueOf(rs.getString("origin")),
            rs.getObject("source_document_id") != null ? rs.getLong("source_document_id") : null,
            rs.getString("source_label"),
            rs.getString("source_title"),
            rs.getTimestamp("saved_at").toInstant(),
            rs.getTimestamp("confirmed_at").toInstant());

    private static final String COLUMNS =
            "id, owner_user_id, workspace_id, \"key\", label, value_text, value_sealed, value_hint, sensitive, origin,"
                    + " source_document_id, source_label, source_title, saved_at, confirmed_at";

    private final JdbcTemplate jdbcTemplate;

    JdbcSavedDetailRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional(readOnly = true)
    public List<SavedDetail> list(long ownerUserId) {
        TenantContext.setCurrentUser(jdbcTemplate, ownerUserId);
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM saved_detail WHERE owner_user_id = ? ORDER BY \"key\"", ROW, ownerUserId);
    }

    @Override
    @Transactional
    public SavedDetail upsert(
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
            String sourceTitle) {
        TenantContext.setCurrentUser(jdbcTemplate, ownerUserId);
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO saved_detail
                    (owner_user_id, workspace_id, "key", label, value_text, value_sealed, value_hint, sensitive, origin,
                     source_document_id, source_label, source_title)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (owner_user_id, "key") DO UPDATE SET
                    label = EXCLUDED.label,
                    value_text = EXCLUDED.value_text,
                    value_sealed = EXCLUDED.value_sealed,
                    value_hint = EXCLUDED.value_hint,
                    sensitive = EXCLUDED.sensitive,
                    origin = EXCLUDED.origin,
                    source_document_id = EXCLUDED.source_document_id,
                    source_label = EXCLUDED.source_label,
                    source_title = EXCLUDED.source_title,
                    confirmed_at = now()
                """ + "RETURNING "
                + COLUMNS,
                ROW,
                ownerUserId,
                workspaceId,
                key,
                label,
                valueText,
                valueSealed,
                valueHint,
                sensitive,
                origin.name(),
                sourceDocumentId,
                sourceLabel,
                sourceTitle);
    }

    @Override
    @Transactional
    public void delete(long ownerUserId, String key) {
        TenantContext.setCurrentUser(jdbcTemplate, ownerUserId);
        jdbcTemplate.update("DELETE FROM saved_detail WHERE owner_user_id = ? AND \"key\" = ?", ownerUserId, key);
    }

    @Override
    @Transactional
    public void deleteAll(long ownerUserId) {
        TenantContext.setCurrentUser(jdbcTemplate, ownerUserId);
        jdbcTemplate.update("DELETE FROM saved_detail WHERE owner_user_id = ?", ownerUserId);
    }
}
