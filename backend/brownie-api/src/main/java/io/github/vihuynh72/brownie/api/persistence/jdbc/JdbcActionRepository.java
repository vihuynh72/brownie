package io.github.vihuynh72.brownie.api.persistence.jdbc;

import io.github.vihuynh72.brownie.core.action.ActionAttempt;
import io.github.vihuynh72.brownie.core.action.ActionFailure;
import io.github.vihuynh72.brownie.core.action.ActionRepository;
import io.github.vihuynh72.brownie.core.action.ActionRequest;
import io.github.vihuynh72.brownie.core.action.ActionState;
import io.github.vihuynh72.brownie.core.action.ActionType;
import io.github.vihuynh72.brownie.core.action.ActionVerification;
import io.github.vihuynh72.brownie.core.action.AttemptKind;
import io.github.vihuynh72.brownie.core.action.AttemptOutcome;
import io.github.vihuynh72.brownie.core.action.NewAction;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * The runtime login may read its person's own actions and record a new
 * proposal; everything else here calls one of the database's action
 * routines, each its own short transaction, and reports what it answered.
 * The routines hold the lifecycle's rules and take their locks in one order,
 * so nothing here decides a transition or holds a lock across a call to the
 * provider.
 */
@Repository
class JdbcActionRepository implements ActionRepository {

    private static final String COLUMNS = "id, workspace_id, user_id, document_id, connection_id, action_type, payload_canonical,"
            + " payload_hash, sibling_key, required_revision_id, export_receipt_id, target_action_id, target_external_id, provider_key,"
            + " state, created_at, expires_at, approved_at, approval_expires_at, current_attempt_id, lease_expires_at, external_id,"
            + " external_link, verification, check_total, check_found, failure_reason, outcome_acknowledged_at, finished_at";
    private static final String ATTEMPT_COLUMNS = "id, action_id, attempt_number, kind, started_at, lease_expires_at, sent_at,"
            + " finished_at, outcome, provider_status, external_id, result_revision";

    private final JdbcTemplate jdbcTemplate;

    JdbcActionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public ActionRequest propose(long workspaceId, long userId, NewAction action) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        // The connection before the document, which the insert's foreign keys lock in their own order: the order
        // every path that changes a connection, and both purges, take them in.
        jdbcTemplate.query(
                "SELECT id FROM connector_connection WHERE workspace_id = ? AND id = ? FOR SHARE",
                rs -> null,
                workspaceId,
                action.connectionId());
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO action_request (workspace_id, user_id, document_id, connection_id, action_type, payload_canonical,
                    payload_hash, sibling_key, required_revision_id, export_receipt_id, target_action_id, target_external_id,
                    provider_key, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now() + interval '30 minutes')
                RETURNING %s
                """.formatted(COLUMNS),
                this::mapAction,
                workspaceId,
                userId,
                action.documentId(),
                action.connectionId(),
                action.type().name(),
                action.payloadCanonical(),
                action.payloadHash(),
                action.siblingKey(),
                action.requiredRevisionId(),
                action.exportReceiptId(),
                action.targetActionId(),
                action.targetExternalId(),
                action.providerKey());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ActionRequest> find(long workspaceId, long userId, long actionId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate
                .query("SELECT " + COLUMNS + " FROM action_request WHERE workspace_id = ? AND id = ?", this::mapAction, workspaceId, actionId)
                .stream()
                .findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ActionRequest> findForDocument(long workspaceId, long userId, long documentId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM action_request WHERE workspace_id = ? AND document_id = ? ORDER BY created_at DESC, id DESC",
                this::mapAction,
                workspaceId,
                documentId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ActionAttempt> attempts(long workspaceId, long userId, long actionId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate.query(
                "SELECT " + ATTEMPT_COLUMNS + " FROM action_attempt WHERE workspace_id = ? AND action_id = ? ORDER BY attempt_number",
                this::mapAttempt,
                workspaceId,
                actionId);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean siblingSentAfter(long workspaceId, long userId, long actionId, Instant since) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM action_request a
                    JOIN action_request s ON s.workspace_id = a.workspace_id AND s.user_id = a.user_id
                                         AND s.sibling_key = a.sibling_key AND s.id <> a.id
                    JOIN action_attempt t ON t.workspace_id = s.workspace_id AND t.action_id = s.id
                    WHERE a.workspace_id = ? AND a.id = ? AND a.user_id = ? AND t.sent_at > ?
                      AND (t.outcome IS NULL OR t.outcome <> 'NOT_APPLIED'))
                """, Boolean.class, workspaceId, actionId, userId, OffsetDateTime.ofInstant(since, ZoneOffset.UTC)));
    }

    @Override
    @Transactional
    public Claim claim(long workspaceId, long userId, long actionId, String presentedHash, int leaseSeconds) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        TenantContext.setCorrelationId(jdbcTemplate);
        return jdbcTemplate.queryForObject(
                "SELECT claim_outcome, claimed_attempt_id, claimed_lease_expires_at FROM action_claim(?, ?, ?, ?)",
                (rs, rowNum) -> mapClaim(rs),
                workspaceId,
                actionId,
                presentedHash,
                leaseSeconds);
    }

    @Override
    @Transactional
    public Claim claimReconcile(long workspaceId, long userId, long actionId, long connectionId, int leaseSeconds) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate.queryForObject(
                "SELECT claim_outcome, claimed_attempt_id, claimed_lease_expires_at FROM action_claim_reconcile(?, ?, ?, ?)",
                (rs, rowNum) -> mapClaim(rs),
                workspaceId,
                actionId,
                connectionId,
                leaseSeconds);
    }

    @Override
    @Transactional
    public SendPermission markSent(long workspaceId, long userId, long actionId, long attemptId, int minLeaseSeconds) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        TenantContext.setCorrelationId(jdbcTemplate);
        String answer = jdbcTemplate.queryForObject(
                "SELECT action_mark_sent(?, ?, ?, ?)", String.class, workspaceId, actionId, attemptId, minLeaseSeconds);
        return switch (answer) {
            case "SEND" -> SendPermission.SEND;
            case "NOT_FOUND" -> SendPermission.NOT_FOUND;
            case "GONE" -> SendPermission.GONE;
            case "FENCED_OUT" -> SendPermission.FENCED_OUT;
            case "LEASE_TOO_SHORT" -> SendPermission.LEASE_TOO_SHORT;
            case "ALREADY_SENT" -> SendPermission.ALREADY_SENT;
            default -> throw new IllegalStateException("action_mark_sent answered " + answer + ".");
        };
    }

    @Override
    @Transactional
    public void recordExternalId(long workspaceId, long userId, long actionId, long attemptId, String externalId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        jdbcTemplate.queryForObject(
                "SELECT action_record_external_id(?, ?, ?, ?)", String.class, workspaceId, actionId, attemptId, externalId);
    }

    @Override
    @Transactional
    public FinishResult finish(long workspaceId, long userId, long actionId, long attemptId, AttemptResult result) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        TenantContext.setCorrelationId(jdbcTemplate);
        String answer = jdbcTemplate.queryForObject(
                "SELECT action_finish(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                String.class,
                workspaceId,
                actionId,
                attemptId,
                result.nextState().name(),
                result.outcome().name(),
                result.verification() == null ? null : result.verification().name(),
                result.failure() == null ? null : result.failure().name(),
                result.externalId(),
                result.externalLink(),
                result.providerStatus(),
                result.providerReasons().isEmpty() ? null : String.join(",", result.providerReasons()),
                result.resultRevision(),
                result.conversionCount() == null ? null : result.conversionCount().total(),
                result.conversionCount() == null ? null : result.conversionCount().found());
        return switch (answer) {
            case "FINISHED" -> FinishResult.FINISHED;
            case "NOT_FOUND" -> FinishResult.NOT_FOUND;
            case "GONE" -> FinishResult.GONE;
            case "FENCED_OUT" -> FinishResult.FENCED_OUT;
            default -> throw new IllegalStateException("action_finish answered " + answer + ".");
        };
    }

    @Override
    @Transactional
    public boolean failBeforeSending(long workspaceId, long userId, long actionId, ActionFailure reason) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return "FAILED".equals(jdbcTemplate.queryForObject(
                "SELECT action_fail_before_sending(?, ?, ?)", String.class, workspaceId, actionId, reason.name()));
    }

    @Override
    @Transactional
    public boolean cancel(long workspaceId, long userId, long actionId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return "CANCELLED".equals(jdbcTemplate.queryForObject("SELECT action_cancel(?, ?)", String.class, workspaceId, actionId));
    }

    @Override
    @Transactional
    public boolean acknowledgeUnknown(long workspaceId, long userId, long actionId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return "ACKNOWLEDGED".equals(
                jdbcTemplate.queryForObject("SELECT action_acknowledge_unknown(?, ?)", String.class, workspaceId, actionId));
    }

    private static Claim mapClaim(ResultSet rs) throws SQLException {
        String code = rs.getString("claim_outcome");
        ClaimOutcome outcome = switch (code) {
            case "CLAIMED" -> ClaimOutcome.CLAIMED;
            case "NOT_FOUND" -> ClaimOutcome.NOT_FOUND;
            case "HASH_MISMATCH" -> ClaimOutcome.HASH_MISMATCH;
            case "CONNECTION_UNUSABLE" -> ClaimOutcome.CONNECTION_UNUSABLE;
            case "SIBLING_UNRESOLVED" -> ClaimOutcome.SIBLING_UNRESOLVED;
            case "NOT_APPROVABLE", "NOT_RECONCILABLE" -> ClaimOutcome.NOT_CLAIMABLE;
            case "EXPIRED", "CONNECTION_CHANGED", "DOCUMENT_GONE", "DOCUMENT_CHANGED", "EXPORT_CHANGED", "TARGET_CHANGED" -> ClaimOutcome.ENDED;
            default -> throw new IllegalStateException("An action claim answered " + code + ".");
        };
        long attemptId = rs.getLong("claimed_attempt_id");
        Long attempt = rs.wasNull() ? null : attemptId;
        OffsetDateTime lease = rs.getObject("claimed_lease_expires_at", OffsetDateTime.class);
        return new Claim(outcome, attempt, lease == null ? null : lease.toInstant());
    }

    private ActionRequest mapAction(ResultSet rs, int rowNum) throws SQLException {
        String verification = rs.getString("verification");
        String failure = rs.getString("failure_reason");
        return new ActionRequest(
                rs.getLong("id"),
                rs.getLong("workspace_id"),
                rs.getLong("user_id"),
                rs.getLong("document_id"),
                rs.getLong("connection_id"),
                ActionType.valueOf(rs.getString("action_type")),
                rs.getString("payload_canonical"),
                rs.getString("payload_hash"),
                rs.getString("sibling_key"),
                longOrNull(rs, "required_revision_id"),
                longOrNull(rs, "export_receipt_id"),
                longOrNull(rs, "target_action_id"),
                rs.getString("target_external_id"),
                rs.getString("provider_key"),
                ActionState.valueOf(rs.getString("state")),
                instant(rs, "created_at"),
                instant(rs, "expires_at"),
                instant(rs, "approved_at"),
                instant(rs, "approval_expires_at"),
                longOrNull(rs, "current_attempt_id"),
                instant(rs, "lease_expires_at"),
                rs.getString("external_id"),
                rs.getString("external_link"),
                verification == null ? null : ActionVerification.valueOf(verification),
                integerOrNull(rs, "check_total"),
                integerOrNull(rs, "check_found"),
                failure == null ? null : ActionFailure.valueOf(failure),
                instant(rs, "outcome_acknowledged_at"),
                instant(rs, "finished_at"));
    }

    private ActionAttempt mapAttempt(ResultSet rs, int rowNum) throws SQLException {
        String outcome = rs.getString("outcome");
        int status = rs.getInt("provider_status");
        Integer providerStatus = rs.wasNull() ? null : status;
        return new ActionAttempt(
                rs.getLong("id"),
                rs.getLong("action_id"),
                rs.getInt("attempt_number"),
                AttemptKind.valueOf(rs.getString("kind")),
                instant(rs, "started_at"),
                instant(rs, "lease_expires_at"),
                instant(rs, "sent_at"),
                instant(rs, "finished_at"),
                outcome == null ? null : AttemptOutcome.valueOf(outcome),
                providerStatus,
                rs.getString("external_id"),
                rs.getString("result_revision"));
    }

    private static Integer integerOrNull(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Long longOrNull(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
