package io.github.vihuynh72.brownie.api.persistence.jdbc;

import io.github.vihuynh72.brownie.core.template.BaselineRenderResult;
import io.github.vihuynh72.brownie.core.template.TemplateBaselineRenderRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/** Plain columns only -- {@code failedFieldIds} is never persisted, since only a passing baseline is ever recorded at all (see {@link BaselineRenderResult#passed()}). */
@Repository
class JdbcTemplateBaselineRenderRepository implements TemplateBaselineRenderRepository {

    private final JdbcTemplate jdbcTemplate;

    JdbcTemplateBaselineRenderRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public void recordBaselineRender(long workspaceId, long userId, long templateVersionId, BaselineRenderResult result) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        jdbcTemplate.update(
                "INSERT INTO template_baseline_render (workspace_id, template_version_id, docx_artifact_id, pdf_artifact_id, renderer_version) "
                        + "VALUES (?, ?, ?, ?, ?)",
                workspaceId,
                templateVersionId,
                result.docxArtifactId(),
                result.pdfArtifactId(),
                result.rendererVersion());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BaselineRenderResult> findBaselineRender(long workspaceId, long userId, long templateVersionId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate
                .query(
                        "SELECT docx_artifact_id, pdf_artifact_id, renderer_version FROM template_baseline_render "
                                + "WHERE workspace_id = ? AND template_version_id = ?",
                        (rs, rowNum) -> new BaselineRenderResult(
                                rs.getLong("docx_artifact_id"), rs.getLong("pdf_artifact_id"), rs.getString("renderer_version"), List.of()),
                        workspaceId,
                        templateVersionId)
                .stream()
                .findFirst();
    }
}
