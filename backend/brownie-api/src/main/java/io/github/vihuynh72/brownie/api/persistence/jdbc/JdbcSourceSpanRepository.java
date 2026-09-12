package io.github.vihuynh72.brownie.api.persistence.jdbc;

import io.github.vihuynh72.brownie.core.evidence.EvidenceLocator;
import io.github.vihuynh72.brownie.core.evidence.SourceSpan;
import io.github.vihuynh72.brownie.core.evidence.SourceSpanRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.sql.PreparedStatement;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * {@link EvidenceLocator} is a sealed interface with three real shapes, so
 * persisting it needs a type discriminator -- handled by hand here with a
 * small {@code ObjectNode} tree, one {@code case} branch per variant,
 * rather than {@code @JsonTypeInfo}/{@code @JsonSubTypes} on the type
 * itself: those annotations live in {@code com.fasterxml.jackson.annotation},
 * a dependency {@code brownie-core} does not have and should not need only
 * to describe its own JSON shape -- that shape is this module's concern,
 * the same boundary that already keeps POI/PDFBox/Jackson entirely out of
 * {@code brownie-core}.
 */
@Repository
class JdbcSourceSpanRepository implements SourceSpanRepository {

    private static final String SELECT_COLUMNS =
            "id, workspace_id, source_snapshot_id, extraction_parser_version, locator, excerpt_hash, created_at";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    JdbcSourceSpanRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SourceSpan> find(long workspaceId, long userId, long spanId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate
                .query(
                        "SELECT " + SELECT_COLUMNS + " FROM source_span WHERE id = ? AND workspace_id = ?",
                        this::mapRow,
                        spanId,
                        workspaceId)
                .stream()
                .findFirst();
    }

    @Override
    @Transactional
    public SourceSpan create(
            long workspaceId, long userId, long sourceSnapshotId, String extractionParserVersion, EvidenceLocator locator,
            String excerptHash) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        String locatorJson = toJson(locator).toString();
        jdbcTemplate.update(
                connection -> {
                    PreparedStatement ps = connection.prepareStatement(
                            "INSERT INTO source_span (workspace_id, source_snapshot_id, extraction_parser_version, locator, excerpt_hash) "
                                    + "VALUES (?, ?, ?, ?::jsonb, ?)",
                            new String[] {"id"});
                    ps.setLong(1, workspaceId);
                    ps.setLong(2, sourceSnapshotId);
                    ps.setString(3, extractionParserVersion);
                    ps.setString(4, locatorJson);
                    ps.setString(5, excerptHash);
                    return ps;
                },
                keyHolder);
        long id = keyHolder.getKey().longValue();
        return find(workspaceId, userId, id)
                .orElseThrow(() -> new IllegalStateException("Just-inserted source span " + id + " vanished."));
    }

    private SourceSpan mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new SourceSpan(
                rs.getLong("id"),
                rs.getLong("workspace_id"),
                rs.getLong("source_snapshot_id"),
                rs.getString("extraction_parser_version"),
                fromJson(objectMapper.readTree(rs.getString("locator"))),
                rs.getString("excerpt_hash"),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private ObjectNode toJson(EvidenceLocator locator) {
        ObjectNode node = objectMapper.createObjectNode();
        switch (locator) {
            case EvidenceLocator.Docx docx -> {
                node.put("type", "DOCX");
                node.put("partName", docx.partName());
                node.put("nodeId", docx.nodeId());
                node.put("startCodePoint", docx.startCodePoint());
                node.put("endCodePointExclusive", docx.endCodePointExclusive());
            }
            case EvidenceLocator.Pdf pdf -> {
                node.put("type", "PDF");
                node.put("pageNumber", pdf.pageNumber());
                node.put("lineIndex", pdf.lineIndex());
                node.put("startCodePoint", pdf.startCodePoint());
                node.put("endCodePointExclusive", pdf.endCodePointExclusive());
            }
            case EvidenceLocator.PlainText plainText -> {
                node.put("type", "PLAIN_TEXT");
                node.put("startCodePoint", plainText.startCodePoint());
                node.put("endCodePointExclusive", plainText.endCodePointExclusive());
            }
        }
        return node;
    }

    private static EvidenceLocator fromJson(JsonNode node) {
        String type = node.get("type").asString();
        return switch (type) {
            case "DOCX" -> new EvidenceLocator.Docx(
                    node.get("partName").asString(),
                    node.get("nodeId").asString(),
                    node.get("startCodePoint").asInt(),
                    node.get("endCodePointExclusive").asInt());
            case "PDF" -> new EvidenceLocator.Pdf(
                    node.get("pageNumber").asInt(),
                    node.get("lineIndex").asInt(),
                    node.get("startCodePoint").asInt(),
                    node.get("endCodePointExclusive").asInt());
            case "PLAIN_TEXT" -> new EvidenceLocator.PlainText(
                    node.get("startCodePoint").asInt(), node.get("endCodePointExclusive").asInt());
            default -> throw new IllegalStateException("Unknown evidence locator type '" + type + "' in stored JSON.");
        };
    }
}
