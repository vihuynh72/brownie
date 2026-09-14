package io.github.vihuynh72.brownie.api.persistence.jdbc;

import io.github.vihuynh72.brownie.core.example.ExampleAlignmentStatus;
import io.github.vihuynh72.brownie.core.example.TemplateExample;
import io.github.vihuynh72.brownie.core.example.TemplateExampleRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/** Plain columns only -- unlike {@code JdbcRuleRepository}, nothing here needs hand-rolled JSON encoding. */
@Repository
class JdbcTemplateExampleRepository implements TemplateExampleRepository {

    private static final String COLUMNS =
            "id, workspace_id, template_id, template_version_id, source_artifact_id, extraction_version_id, alignment_status, created_at";

    private final JdbcTemplate jdbcTemplate;

    JdbcTemplateExampleRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public TemplateExample attach(
            long workspaceId,
            long userId,
            long templateId,
            long templateVersionId,
            long sourceArtifactId,
            long extractionVersionId,
            ExampleAlignmentStatus alignmentStatus) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        long id = jdbcTemplate.queryForObject(
                """
                INSERT INTO template_example
                    (workspace_id, template_id, template_version_id, source_artifact_id, extraction_version_id, alignment_status)
                VALUES (?, ?, ?, ?, ?, ?)
                RETURNING id
                """,
                Long.class,
                workspaceId,
                templateId,
                templateVersionId,
                sourceArtifactId,
                extractionVersionId,
                alignmentStatus.name());
        return findByTemplateVersion(workspaceId, userId, templateVersionId).stream()
                .filter(example -> example.id() == id)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Template example " + id + " vanished after attaching it."));
    }

    @Override
    @Transactional(readOnly = true)
    public List<TemplateExample> findByTemplateVersion(long workspaceId, long userId, long templateVersionId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM template_example WHERE workspace_id = ? AND template_version_id = ? ORDER BY id",
                this::mapRow,
                workspaceId,
                templateVersionId);
    }

    private TemplateExample mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new TemplateExample(
                rs.getLong("id"),
                rs.getLong("workspace_id"),
                rs.getLong("template_id"),
                rs.getLong("template_version_id"),
                rs.getLong("source_artifact_id"),
                rs.getLong("extraction_version_id"),
                ExampleAlignmentStatus.valueOf(rs.getString("alignment_status")),
                rs.getObject("created_at", OffsetDateTime.class));
    }
}
