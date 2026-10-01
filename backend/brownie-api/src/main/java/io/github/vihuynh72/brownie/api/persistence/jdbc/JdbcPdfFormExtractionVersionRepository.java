package io.github.vihuynh72.brownie.api.persistence.jdbc;

import io.github.vihuynh72.brownie.core.document.ExtractionStatus;
import io.github.vihuynh72.brownie.core.document.PdfFormExtractionVersion;
import io.github.vihuynh72.brownie.core.document.PdfFormExtractionVersionRepository;
import io.github.vihuynh72.brownie.core.document.PdfFormGraph;
import io.github.vihuynh72.brownie.core.document.UnsupportedPdfFormReason;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Stores PDF form readings the way {@code JdbcPdfExtractionVersionRepository}
 * stores PDF source readings: insert-or-return-existing per artifact and
 * reader version, the graph as JSONB through the application's own
 * (Jackson 3) {@link ObjectMapper}. Every write is its own {@code
 * @Transactional} method on this class, never a default method on the
 * port: a default method is not proxied, so the tenant context it set would
 * not be in the same transaction as its write, and row-level security
 * would refuse it.
 */
@Repository
class JdbcPdfFormExtractionVersionRepository implements PdfFormExtractionVersionRepository {

    private static final String SELECT_COLUMNS =
            "id, workspace_id, artifact_id, parser_version, status, unsupported_reason, unsupported_detail, graph, created_at";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    JdbcPdfFormExtractionVersionRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PdfFormExtractionVersion> findById(long workspaceId, long userId, long id) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate
                .query(
                        "SELECT " + SELECT_COLUMNS + " FROM pdf_form_extraction_version WHERE workspace_id = ? AND id = ?",
                        this::mapRow,
                        workspaceId,
                        id)
                .stream()
                .findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PdfFormExtractionVersion> findByArtifact(long workspaceId, long userId, long artifactId, String parserVersion) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate
                .query(
                        "SELECT " + SELECT_COLUMNS
                                + " FROM pdf_form_extraction_version WHERE workspace_id = ? AND artifact_id = ? AND parser_version = ?",
                        this::mapRow,
                        workspaceId,
                        artifactId,
                        parserVersion)
                .stream()
                .findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Long> findCompleteIdByArtifact(long workspaceId, long userId, long artifactId, String parserVersion) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate
                .queryForList(
                        "SELECT id FROM pdf_form_extraction_version"
                                + " WHERE workspace_id = ? AND artifact_id = ? AND parser_version = ? AND status = ?",
                        Long.class,
                        workspaceId,
                        artifactId,
                        parserVersion,
                        ExtractionStatus.COMPLETE.name())
                .stream()
                .findFirst();
    }

    @Override
    @Transactional
    public PdfFormExtractionVersion saveComplete(
            long workspaceId, long userId, long artifactId, String parserVersion, PdfFormGraph graph) {
        insertIgnoringConflict(workspaceId, userId, artifactId, parserVersion, ExtractionStatus.COMPLETE, null, null, toJson(graph));
        return requireSaved(workspaceId, userId, artifactId, parserVersion);
    }

    @Override
    @Transactional
    public PdfFormExtractionVersion saveUnsupported(
            long workspaceId, long userId, long artifactId, String parserVersion, UnsupportedPdfFormReason reason, String detail) {
        insertIgnoringConflict(workspaceId, userId, artifactId, parserVersion, ExtractionStatus.UNSUPPORTED, reason, detail, null);
        return requireSaved(workspaceId, userId, artifactId, parserVersion);
    }

    private void insertIgnoringConflict(
            long workspaceId,
            long userId,
            long artifactId,
            String parserVersion,
            ExtractionStatus status,
            UnsupportedPdfFormReason unsupportedReason,
            String unsupportedDetail,
            String graphJson) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        jdbcTemplate.update(
                """
                INSERT INTO pdf_form_extraction_version
                    (workspace_id, artifact_id, parser_version, status, unsupported_reason, unsupported_detail, graph)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)
                ON CONFLICT (artifact_id, parser_version) DO NOTHING
                """,
                workspaceId,
                artifactId,
                parserVersion,
                status.name(),
                unsupportedReason == null ? null : unsupportedReason.name(),
                unsupportedDetail,
                graphJson);
    }

    private PdfFormExtractionVersion requireSaved(long workspaceId, long userId, long artifactId, String parserVersion) {
        return findByArtifact(workspaceId, userId, artifactId, parserVersion)
                .orElseThrow(() -> new IllegalStateException(
                        "PDF form reading for artifact " + artifactId + " under reader " + parserVersion + " vanished after saving it."));
    }

    private PdfFormExtractionVersion mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        String graphJson = rs.getString("graph");
        String unsupportedReason = rs.getString("unsupported_reason");
        return new PdfFormExtractionVersion(
                rs.getLong("id"),
                rs.getLong("workspace_id"),
                rs.getLong("artifact_id"),
                rs.getString("parser_version"),
                ExtractionStatus.valueOf(rs.getString("status")),
                unsupportedReason == null ? null : UnsupportedPdfFormReason.valueOf(unsupportedReason),
                rs.getString("unsupported_detail"),
                graphJson == null ? null : fromJson(graphJson),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private String toJson(PdfFormGraph graph) {
        try {
            return objectMapper.writeValueAsString(graph);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize a PDF form reading to JSON.", e);
        }
    }

    private PdfFormGraph fromJson(String json) {
        try {
            return objectMapper.readValue(json, PdfFormGraph.class);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to deserialize a stored PDF form reading.", e);
        }
    }
}
