package io.github.vihuynh72.brownie.api.persistence.jdbc;

import io.github.vihuynh72.brownie.core.document.DocxFeatureReport;
import io.github.vihuynh72.brownie.core.document.DocxStructuralGraph;
import io.github.vihuynh72.brownie.core.document.ExtractionStatus;
import io.github.vihuynh72.brownie.core.document.ExtractionVersion;
import io.github.vihuynh72.brownie.core.document.ExtractionVersionRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Extraction is a pure function of an artifact's immutable bytes and the
 * extractor's own version, so every write here is insert-or-return-
 * existing: {@code INSERT ... ON CONFLICT (artifact_id, parser_version) DO
 * NOTHING} followed by re-reading whatever row actually exists. Two
 * concurrent callers racing the same (artifact, parserVersion) pair both
 * converge on a single, already-committed answer instead of one silently
 * overwriting the other's -- there is no "in-flight" state here worth
 * protecting the way {@code beginScanning} protects an active malware
 * scan, since either caller would have computed the same real answer from
 * the same immutable bytes.
 *
 * <p>The injected {@link ObjectMapper} is {@code tools.jackson}, not {@code
 * com.fasterxml.jackson} -- this Spring Boot line autoconfigures a Jackson
 * 3 {@code ObjectMapper} bean by default. The older {@code
 * com.fasterxml.jackson.databind.ObjectMapper} class is still on this
 * module's classpath (pulled in transitively by other dependencies that
 * have not moved to Jackson 3 yet), which makes it easy to import the
 * wrong one by IDE auto-complete and get a real, if confusingly worded,
 * "no bean of that type" startup failure -- confirmed by actually hitting
 * that failure and inspecting the resolved dependency tree, not assumed.
 */
@Repository
class JdbcExtractionVersionRepository implements ExtractionVersionRepository {

    private static final String SELECT_COLUMNS =
            "id, workspace_id, artifact_id, parser_version, status, feature_report, graph, failure_reason, created_at";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    JdbcExtractionVersionRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ExtractionVersion> findByArtifact(long workspaceId, long userId, long artifactId, String parserVersion) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate
                .query(
                        "SELECT " + SELECT_COLUMNS
                                + " FROM extraction_version WHERE workspace_id = ? AND artifact_id = ? AND parser_version = ?",
                        this::mapRow,
                        workspaceId,
                        artifactId,
                        parserVersion)
                .stream()
                .findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ExtractionVersion> findById(long workspaceId, long userId, long extractionVersionId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate
                .query(
                        "SELECT " + SELECT_COLUMNS + " FROM extraction_version WHERE workspace_id = ? AND id = ?",
                        this::mapRow,
                        workspaceId,
                        extractionVersionId)
                .stream()
                .findFirst();
    }

    @Override
    @Transactional
    public ExtractionVersion saveComplete(long workspaceId, long userId, long artifactId, String parserVersion, DocxStructuralGraph graph) {
        insertIgnoringConflict(
                workspaceId, userId, artifactId, parserVersion, ExtractionStatus.COMPLETE, DocxFeatureReport.empty(), graph, null);
        return requireSaved(workspaceId, userId, artifactId, parserVersion);
    }

    @Override
    @Transactional
    public ExtractionVersion saveUnsupported(
            long workspaceId, long userId, long artifactId, String parserVersion, DocxFeatureReport featureReport) {
        insertIgnoringConflict(workspaceId, userId, artifactId, parserVersion, ExtractionStatus.UNSUPPORTED, featureReport, null, null);
        return requireSaved(workspaceId, userId, artifactId, parserVersion);
    }

    @Override
    @Transactional
    public ExtractionVersion saveFailed(long workspaceId, long userId, long artifactId, String parserVersion, String failureReason) {
        insertIgnoringConflict(
                workspaceId, userId, artifactId, parserVersion, ExtractionStatus.FAILED, DocxFeatureReport.empty(), null, failureReason);
        return requireSaved(workspaceId, userId, artifactId, parserVersion);
    }

    private void insertIgnoringConflict(
            long workspaceId,
            long userId,
            long artifactId,
            String parserVersion,
            ExtractionStatus status,
            DocxFeatureReport featureReport,
            DocxStructuralGraph graph,
            String failureReason) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        jdbcTemplate.update(
                """
                INSERT INTO extraction_version
                    (workspace_id, artifact_id, parser_version, status, feature_report, graph, failure_reason)
                VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)
                ON CONFLICT (artifact_id, parser_version) DO NOTHING
                """,
                workspaceId,
                artifactId,
                parserVersion,
                status.name(),
                toJson(featureReport),
                graph == null ? null : toJson(graph),
                failureReason);
    }

    private ExtractionVersion requireSaved(long workspaceId, long userId, long artifactId, String parserVersion) {
        return findByArtifact(workspaceId, userId, artifactId, parserVersion)
                .orElseThrow(() -> new IllegalStateException(
                        "Extraction version for artifact " + artifactId + " under parser " + parserVersion + " vanished after saving it."));
    }

    private ExtractionVersion mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        String graphJson = rs.getString("graph");
        return new ExtractionVersion(
                rs.getLong("id"),
                rs.getLong("workspace_id"),
                rs.getLong("artifact_id"),
                rs.getString("parser_version"),
                ExtractionStatus.valueOf(rs.getString("status")),
                fromJson(rs.getString("feature_report"), DocxFeatureReport.class),
                graphJson == null ? null : fromJson(graphJson, DocxStructuralGraph.class),
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
