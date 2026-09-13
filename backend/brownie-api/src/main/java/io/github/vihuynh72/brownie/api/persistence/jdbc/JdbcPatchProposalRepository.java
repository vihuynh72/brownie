package io.github.vihuynh72.brownie.api.persistence.jdbc;

import io.github.vihuynh72.brownie.core.revision.FieldValue;
import io.github.vihuynh72.brownie.core.revision.PatchProposal;
import io.github.vihuynh72.brownie.core.revision.PatchProposalNotFoundException;
import io.github.vihuynh72.brownie.core.revision.PatchProposalRepository;
import io.github.vihuynh72.brownie.core.revision.PatchProposalStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** JDBC persistence for patch proposals, the same {@code ?::jsonb}/RETURNING/{@link TenantContext} shape {@code JdbcQuestionRepository} already establishes. */
@Repository
class JdbcPatchProposalRepository implements PatchProposalRepository {

    private static final String COLUMNS = "id, workspace_id, document_id, base_revision_id, proposed_values, status, created_at";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    JdbcPatchProposalRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public PatchProposal create(
            long workspaceId,
            long userId,
            long documentId,
            long baseRevisionId,
            Map<String, FieldValue> proposedValues,
            Map<String, List<Long>> proposedEvidence) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        PatchProposal proposal = jdbcTemplate.queryForObject(
                """
                INSERT INTO document_patch_proposal (workspace_id, document_id, base_revision_id, proposed_values)
                VALUES (?, ?, ?, ?::jsonb)
                RETURNING\
                """ + " " + COLUMNS,
                this::mapProposal,
                workspaceId,
                documentId,
                baseRevisionId,
                DocumentContentJson.encode(objectMapper, proposedValues));
        insertEvidence(workspaceId, proposal.id(), proposedEvidence);
        return withEvidence(proposal, proposedEvidence);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PatchProposal> find(long workspaceId, long userId, long documentId, long proposalId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate.query(
                        "SELECT " + COLUMNS + " FROM document_patch_proposal WHERE workspace_id = ? AND document_id = ? AND id = ?",
                        this::mapProposal,
                        workspaceId,
                        documentId,
                        proposalId)
                .stream()
                .findFirst()
                .map(proposal -> withEvidence(proposal, loadEvidence(workspaceId, proposalId)));
    }

    @Override
    @Transactional
    public void markAccepted(long workspaceId, long userId, long documentId, long proposalId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        int updated = jdbcTemplate.update(
                "UPDATE document_patch_proposal SET status = 'ACCEPTED' WHERE workspace_id = ? AND document_id = ? AND id = ? AND status = 'PROPOSED'",
                workspaceId,
                documentId,
                proposalId);
        if (updated == 0) {
            throw new PatchProposalNotFoundException(proposalId);
        }
    }

    private void insertEvidence(long workspaceId, long proposalId, Map<String, List<Long>> proposedEvidence) {
        for (Map.Entry<String, List<Long>> entry : proposedEvidence.entrySet()) {
            for (Long sourceSpanId : entry.getValue()) {
                jdbcTemplate.update(
                        """
                        INSERT INTO document_patch_proposal_evidence (workspace_id, proposal_id, field_id, source_span_id)
                        VALUES (?, ?, ?, ?)
                        """,
                        workspaceId,
                        proposalId,
                        entry.getKey(),
                        sourceSpanId);
            }
        }
    }

    private Map<String, List<Long>> loadEvidence(long workspaceId, long proposalId) {
        Map<String, List<Long>> evidence = new LinkedHashMap<>();
        jdbcTemplate.query(
                """
                SELECT field_id, source_span_id
                FROM document_patch_proposal_evidence
                WHERE workspace_id = ? AND proposal_id = ?
                ORDER BY field_id, source_span_id
                """,
                (ResultSet rs) -> {
                    // A block body, not an expression: List.add's own boolean return would otherwise make
                    // this lambda ambiguously assignable to JdbcTemplate's ResultSetExtractor overload too
                    // (single ResultSet arg, a return value) instead of RowCallbackHandler (void, called once
                    // per row) -- confirmed as the real cause of a genuine "ResultSet not positioned properly"
                    // failure the first time this was written as an expression lambda.
                    evidence.computeIfAbsent(rs.getString("field_id"), key -> new ArrayList<>()).add(rs.getLong("source_span_id"));
                },
                workspaceId,
                proposalId);
        return evidence;
    }

    private PatchProposal mapProposal(ResultSet rs, int rowNum) throws SQLException {
        return new PatchProposal(
                rs.getLong("id"),
                rs.getLong("workspace_id"),
                rs.getLong("document_id"),
                rs.getLong("base_revision_id"),
                DocumentContentJson.decode(objectMapper, rs.getString("proposed_values")),
                Map.of(),
                PatchProposalStatus.valueOf(rs.getString("status")),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private static PatchProposal withEvidence(PatchProposal proposal, Map<String, List<Long>> evidence) {
        return new PatchProposal(
                proposal.id(),
                proposal.workspaceId(),
                proposal.documentId(),
                proposal.baseRevisionId(),
                proposal.proposedValues(),
                evidence,
                proposal.status(),
                proposal.createdAt());
    }
}
