package io.github.vihuynh72.brownie.api.persistence.jdbc;

import io.github.vihuynh72.brownie.core.artifact.Artifact;
import io.github.vihuynh72.brownie.core.artifact.ArtifactRepository;
import io.github.vihuynh72.brownie.core.artifact.ArtifactStatus;
import io.github.vihuynh72.brownie.core.artifact.SupportedMediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
class JdbcArtifactRepository implements ArtifactRepository {

    private static final RowMapper<Artifact> ARTIFACT_ROW_MAPPER = (rs, rowNum) -> new Artifact(
            rs.getLong("id"),
            rs.getLong("workspace_id"),
            rs.getString("blob_key"),
            ArtifactStatus.valueOf(rs.getString("status")),
            (Long) rs.getObject("byte_count"),
            rs.getString("sha256"),
            mapMediaType(rs.getString("detected_media_type")),
            rs.getString("display_filename"),
            rs.getString("rejection_reason"),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("finalized_at", OffsetDateTime.class));

    private static final String SELECT_COLUMNS = "id, workspace_id, blob_key, status, byte_count, sha256,"
            + " detected_media_type, display_filename, rejection_reason, created_at, finalized_at";

    private final JdbcTemplate jdbcTemplate;

    JdbcArtifactRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public Artifact initiateUpload(long workspaceId, long userId, String displayFilename) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        // Opaque and server-generated: no user input (a filename, a
        // client-declared type) is part of this key or reaches blob
        // storage at all.
        String blobKey = "workspace-" + workspaceId + "/" + UUID.randomUUID();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(
                connection -> {
                    PreparedStatement ps = connection.prepareStatement(
                            "INSERT INTO artifact (workspace_id, blob_key, display_filename) VALUES (?, ?, ?)",
                            new String[] {"id"});
                    ps.setLong(1, workspaceId);
                    ps.setString(2, blobKey);
                    ps.setString(3, displayFilename);
                    return ps;
                },
                keyHolder);
        long id = keyHolder.getKey().longValue();
        return find(workspaceId, userId, id)
                .orElseThrow(() -> new IllegalStateException("Just-inserted artifact " + id + " vanished."));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Artifact> find(long workspaceId, long userId, long artifactId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate
                .query(
                        "SELECT " + SELECT_COLUMNS + " FROM artifact WHERE id = ? AND workspace_id = ?",
                        ARTIFACT_ROW_MAPPER,
                        artifactId,
                        workspaceId)
                .stream()
                .findFirst();
    }

    @Override
    @Transactional
    public Artifact recordUploadedContent(
            long workspaceId,
            long userId,
            long artifactId,
            long byteCount,
            String sha256,
            SupportedMediaType detectedMediaType) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        jdbcTemplate.update(
                """
                UPDATE artifact SET byte_count = ?, sha256 = ?, detected_media_type = ?
                WHERE id = ? AND workspace_id = ? AND status = 'UPLOADING' AND byte_count IS NULL
                """,
                byteCount,
                sha256,
                detectedMediaType.name(),
                artifactId,
                workspaceId);
        return find(workspaceId, userId, artifactId)
                .orElseThrow(() -> new IllegalStateException("Artifact " + artifactId + " vanished while recording its content."));
    }

    @Override
    @Transactional
    public Artifact finalizeUpload(long workspaceId, long userId, long artifactId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        jdbcTemplate.update(
                """
                UPDATE artifact SET status = 'QUARANTINED', finalized_at = now()
                WHERE id = ? AND workspace_id = ? AND status = 'UPLOADING' AND byte_count IS NOT NULL
                """,
                artifactId,
                workspaceId);
        return find(workspaceId, userId, artifactId)
                .orElseThrow(() -> new IllegalStateException("Artifact " + artifactId + " vanished while finalizing it."));
    }

    @Override
    @Transactional
    public Artifact reject(long workspaceId, long userId, long artifactId, String reason) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        jdbcTemplate.update(
                """
                UPDATE artifact SET status = 'REJECTED', rejection_reason = ?
                WHERE id = ? AND workspace_id = ? AND status = 'UPLOADING'
                """,
                reason,
                artifactId,
                workspaceId);
        return find(workspaceId, userId, artifactId)
                .orElseThrow(() -> new IllegalStateException("Artifact " + artifactId + " vanished while rejecting it."));
    }

    private static SupportedMediaType mapMediaType(String value) {
        return value == null ? null : SupportedMediaType.valueOf(value);
    }
}
