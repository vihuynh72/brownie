package io.github.vihuynh72.brownie.api.persistence.jdbc;

import io.github.vihuynh72.brownie.core.source.SourceKind;
import io.github.vihuynh72.brownie.core.source.SourceSnapshot;
import io.github.vihuynh72.brownie.core.source.SourceSnapshotRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * {@code create} is insert-or-return-existing under the table's own {@code
 * UNIQUE (artifact_id)} constraint, the same reasoning the extraction-
 * version repositories already apply: two callers attaching the same
 * artifact as a source concurrently both converge on the one real snapshot
 * that fact deserves, rather than one silently overwriting the other's.
 */
@Repository
class JdbcSourceSnapshotRepository implements SourceSnapshotRepository {

    private static final String SELECT_COLUMNS = "id, workspace_id, artifact_id, kind, fetched_at";

    private final JdbcTemplate jdbcTemplate;

    JdbcSourceSnapshotRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SourceSnapshot> find(long workspaceId, long userId, long snapshotId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate
                .query(
                        "SELECT " + SELECT_COLUMNS + " FROM source_snapshot WHERE id = ? AND workspace_id = ?",
                        this::mapRow,
                        snapshotId,
                        workspaceId)
                .stream()
                .findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SourceSnapshot> findByArtifact(long workspaceId, long userId, long artifactId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate
                .query(
                        "SELECT " + SELECT_COLUMNS + " FROM source_snapshot WHERE artifact_id = ? AND workspace_id = ?",
                        this::mapRow,
                        artifactId,
                        workspaceId)
                .stream()
                .findFirst();
    }

    @Override
    @Transactional
    public SourceSnapshot create(long workspaceId, long userId, long artifactId, SourceKind kind) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        jdbcTemplate.update(
                """
                INSERT INTO source_snapshot (workspace_id, artifact_id, kind)
                VALUES (?, ?, ?)
                ON CONFLICT (artifact_id) DO NOTHING
                """,
                workspaceId,
                artifactId,
                kind.name());
        return findByArtifact(workspaceId, userId, artifactId)
                .orElseThrow(() -> new IllegalStateException("Source snapshot for artifact " + artifactId + " vanished after saving it."));
    }

    private SourceSnapshot mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new SourceSnapshot(
                rs.getLong("id"),
                rs.getLong("workspace_id"),
                rs.getLong("artifact_id"),
                SourceKind.valueOf(rs.getString("kind")),
                rs.getObject("fetched_at", OffsetDateTime.class));
    }
}
