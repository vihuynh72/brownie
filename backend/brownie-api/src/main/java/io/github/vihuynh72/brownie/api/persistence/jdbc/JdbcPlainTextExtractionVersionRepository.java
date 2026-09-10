package io.github.vihuynh72.brownie.api.persistence.jdbc;

import io.github.vihuynh72.brownie.core.document.ExtractionStatus;
import io.github.vihuynh72.brownie.core.document.PlainTextExtractionVersion;
import io.github.vihuynh72.brownie.core.document.PlainTextExtractionVersionRepository;
import io.github.vihuynh72.brownie.core.document.PlainTextStructuralGraph;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * The plain-text analog of {@code JdbcExtractionVersionRepository}/{@code
 * JdbcPdfExtractionVersionRepository}: same insert-or-return-existing
 * idempotency. Simpler than either -- {@code original_text}/{@code
 * normalized_text} are plain {@code TEXT} columns, not {@code jsonb}, so no
 * JSON (de)serialization or Jackson-typed {@code ObjectMapper} is needed
 * here at all.
 */
@Repository
class JdbcPlainTextExtractionVersionRepository implements PlainTextExtractionVersionRepository {

    private static final String SELECT_COLUMNS =
            "id, workspace_id, artifact_id, parser_version, status, original_text, normalized_text, failure_reason, created_at";

    private final JdbcTemplate jdbcTemplate;

    JdbcPlainTextExtractionVersionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PlainTextExtractionVersion> findByArtifact(long workspaceId, long userId, long artifactId, String parserVersion) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate
                .query(
                        "SELECT " + SELECT_COLUMNS
                                + " FROM plain_text_extraction_version WHERE workspace_id = ? AND artifact_id = ? AND parser_version = ?",
                        this::mapRow,
                        workspaceId,
                        artifactId,
                        parserVersion)
                .stream()
                .findFirst();
    }

    @Override
    @Transactional
    public PlainTextExtractionVersion saveComplete(
            long workspaceId, long userId, long artifactId, String parserVersion, PlainTextStructuralGraph graph) {
        insertIgnoringConflict(
                workspaceId, userId, artifactId, parserVersion, ExtractionStatus.COMPLETE, graph.originalText(), graph.normalizedText(), null);
        return requireSaved(workspaceId, userId, artifactId, parserVersion);
    }

    @Override
    @Transactional
    public PlainTextExtractionVersion saveFailed(
            long workspaceId, long userId, long artifactId, String parserVersion, String failureReason) {
        insertIgnoringConflict(workspaceId, userId, artifactId, parserVersion, ExtractionStatus.FAILED, null, null, failureReason);
        return requireSaved(workspaceId, userId, artifactId, parserVersion);
    }

    private void insertIgnoringConflict(
            long workspaceId,
            long userId,
            long artifactId,
            String parserVersion,
            ExtractionStatus status,
            String originalText,
            String normalizedText,
            String failureReason) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        jdbcTemplate.update(
                """
                INSERT INTO plain_text_extraction_version
                    (workspace_id, artifact_id, parser_version, status, original_text, normalized_text, failure_reason)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (artifact_id, parser_version) DO NOTHING
                """,
                workspaceId,
                artifactId,
                parserVersion,
                status.name(),
                originalText,
                normalizedText,
                failureReason);
    }

    private PlainTextExtractionVersion requireSaved(long workspaceId, long userId, long artifactId, String parserVersion) {
        return findByArtifact(workspaceId, userId, artifactId, parserVersion)
                .orElseThrow(() -> new IllegalStateException(
                        "Plain-text extraction version for artifact " + artifactId + " under parser " + parserVersion
                                + " vanished after saving it."));
    }

    private PlainTextExtractionVersion mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        String originalText = rs.getString("original_text");
        String normalizedText = rs.getString("normalized_text");
        String parserVersion = rs.getString("parser_version");
        PlainTextStructuralGraph graph =
                originalText == null ? null : new PlainTextStructuralGraph(parserVersion, originalText, normalizedText);
        return new PlainTextExtractionVersion(
                rs.getLong("id"),
                rs.getLong("workspace_id"),
                rs.getLong("artifact_id"),
                parserVersion,
                ExtractionStatus.valueOf(rs.getString("status")),
                graph,
                rs.getString("failure_reason"),
                rs.getObject("created_at", OffsetDateTime.class));
    }
}
