package io.github.vihuynh72.brownie.api.persistence.jdbc;

import io.github.vihuynh72.brownie.core.document.ExtractionStatus;
import io.github.vihuynh72.brownie.core.document.PdfExtractionVersion;
import io.github.vihuynh72.brownie.core.document.PdfExtractionVersionRepository;
import io.github.vihuynh72.brownie.core.document.PdfStructuralGraph;
import io.github.vihuynh72.brownie.core.document.UnsupportedPdfReason;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * The PDF analog of {@code JdbcExtractionVersionRepository}: same
 * insert-or-return-existing idempotency (extraction is a pure function of
 * an artifact's immutable bytes and the extractor's own version, so two
 * racing callers converge on one already-committed row rather than one
 * overwriting the other), and the same Jackson-3-not-2 {@link ObjectMapper}
 * this Spring Boot line actually autoconfigures (see that class's own
 * javadoc for how that was found).
 */
@Repository
class JdbcPdfExtractionVersionRepository implements PdfExtractionVersionRepository {

    private static final String SELECT_COLUMNS = "id, workspace_id, artifact_id, parser_version, status,"
            + " unsupported_reason, unsupported_detail, graph, failure_reason, created_at";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    JdbcPdfExtractionVersionRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PdfExtractionVersion> findByArtifact(long workspaceId, long userId, long artifactId, String parserVersion) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate
                .query(
                        "SELECT " + SELECT_COLUMNS
                                + " FROM pdf_extraction_version WHERE workspace_id = ? AND artifact_id = ? AND parser_version = ?",
                        this::mapRow,
                        workspaceId,
                        artifactId,
                        parserVersion)
                .stream()
                .findFirst();
    }

    @Override
    @Transactional
    public PdfExtractionVersion saveComplete(
            long workspaceId, long userId, long artifactId, String parserVersion, PdfStructuralGraph graph) {
        insertIgnoringConflict(workspaceId, userId, artifactId, parserVersion, ExtractionStatus.COMPLETE, null, null, graph, null);
        return requireSaved(workspaceId, userId, artifactId, parserVersion);
    }

    @Override
    @Transactional
    public PdfExtractionVersion saveUnsupported(
            long workspaceId, long userId, long artifactId, String parserVersion, UnsupportedPdfReason reason, String detail) {
        insertIgnoringConflict(
                workspaceId, userId, artifactId, parserVersion, ExtractionStatus.UNSUPPORTED, reason, detail, null, null);
        return requireSaved(workspaceId, userId, artifactId, parserVersion);
    }

    @Override
    @Transactional
    public PdfExtractionVersion saveFailed(long workspaceId, long userId, long artifactId, String parserVersion, String failureReason) {
        insertIgnoringConflict(
                workspaceId, userId, artifactId, parserVersion, ExtractionStatus.FAILED, null, null, null, failureReason);
        return requireSaved(workspaceId, userId, artifactId, parserVersion);
    }

    private void insertIgnoringConflict(
            long workspaceId,
            long userId,
            long artifactId,
            String parserVersion,
            ExtractionStatus status,
            UnsupportedPdfReason unsupportedReason,
            String unsupportedDetail,
            PdfStructuralGraph graph,
            String failureReason) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        jdbcTemplate.update(
                """
                INSERT INTO pdf_extraction_version
                    (workspace_id, artifact_id, parser_version, status, unsupported_reason, unsupported_detail, graph, failure_reason)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                ON CONFLICT (artifact_id, parser_version) DO NOTHING
                """,
                workspaceId,
                artifactId,
                parserVersion,
                status.name(),
                unsupportedReason == null ? null : unsupportedReason.name(),
                unsupportedDetail,
                graph == null ? null : toJson(graph),
                failureReason);
    }

    private PdfExtractionVersion requireSaved(long workspaceId, long userId, long artifactId, String parserVersion) {
        return findByArtifact(workspaceId, userId, artifactId, parserVersion)
                .orElseThrow(() -> new IllegalStateException(
                        "PDF extraction version for artifact " + artifactId + " under parser " + parserVersion
                                + " vanished after saving it."));
    }

    private PdfExtractionVersion mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        String graphJson = rs.getString("graph");
        String unsupportedReason = rs.getString("unsupported_reason");
        return new PdfExtractionVersion(
                rs.getLong("id"),
                rs.getLong("workspace_id"),
                rs.getLong("artifact_id"),
                rs.getString("parser_version"),
                ExtractionStatus.valueOf(rs.getString("status")),
                unsupportedReason == null ? null : UnsupportedPdfReason.valueOf(unsupportedReason),
                rs.getString("unsupported_detail"),
                graphJson == null ? null : fromJson(graphJson, PdfStructuralGraph.class),
                rs.getString("failure_reason"),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize " + value.getClass().getSimpleName() + " to JSON.", e);
        }
    }

    private <T> T fromJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to deserialize stored " + type.getSimpleName() + " JSON.", e);
        }
    }
}
