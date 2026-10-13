package io.github.vihuynh72.brownie.api.persistence.jdbc;

import io.github.vihuynh72.brownie.core.batch.Batch;
import io.github.vihuynh72.brownie.core.batch.BatchDeleteResult;
import io.github.vihuynh72.brownie.core.batch.BatchExport;
import io.github.vihuynh72.brownie.core.batch.BatchExportItem;
import io.github.vihuynh72.brownie.core.batch.BatchExportItemOutcome;
import io.github.vihuynh72.brownie.core.batch.BatchExportState;
import io.github.vihuynh72.brownie.core.batch.BatchNotFoundException;
import io.github.vihuynh72.brownie.core.batch.BatchRepository;
import io.github.vihuynh72.brownie.core.batch.BatchRow;
import io.github.vihuynh72.brownie.core.batch.BatchRowDetail;
import io.github.vihuynh72.brownie.core.batch.BatchRowProblem;
import io.github.vihuynh72.brownie.core.batch.BatchRowProblemCode;
import io.github.vihuynh72.brownie.core.batch.BatchRowState;
import io.github.vihuynh72.brownie.core.batch.BatchStepKind;
import io.github.vihuynh72.brownie.core.batch.ComposeState;
import io.github.vihuynh72.brownie.core.batch.DateOrder;
import io.github.vihuynh72.brownie.core.batch.FieldMapping;
import io.github.vihuynh72.brownie.core.batch.FieldMappingMode;
import io.github.vihuynh72.brownie.core.batch.TableFormat;
import io.github.vihuynh72.brownie.core.export.ExportFormat;
import io.github.vihuynh72.brownie.core.export.ExportReceipt;
import io.github.vihuynh72.brownie.core.job.IdempotencyKey;
import io.github.vihuynh72.brownie.core.revision.Authorship;
import io.github.vihuynh72.brownie.core.revision.DocumentContent;
import io.github.vihuynh72.brownie.core.revision.EvidenceSupport;
import io.github.vihuynh72.brownie.core.revision.FieldItemRef;
import io.github.vihuynh72.brownie.core.revision.FieldState;
import io.github.vihuynh72.brownie.core.revision.LockState;
import io.github.vihuynh72.brownie.core.revision.ReviewState;
import io.github.vihuynh72.brownie.core.revision.ValidationState;
import io.github.vihuynh72.brownie.core.validation.ValidationFinding;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The batch tables through JDBC. The lease methods keep the lock
 * invariant in the database: acquire takes the workspace's advisory lock
 * inside the same transaction that checks and writes the lease, so two
 * requests can never both win; refresh and release only ever touch a lease
 * the caller's token still names. Column-level grants mean a row's identity
 * fields cannot be updated at all, so no UPDATE here names one.
 */
@Repository
class JdbcBatchRepository implements BatchRepository {

    private static final String COLUMNS =
            "id, workspace_id, template_id, template_version_id, source_artifact_id, source_sha256, "
                    + "table_format, table_reader_version, sheet_name, has_header_row, date_order, mapping, "
                    + "title_column, title, row_count, idempotency_key, request_hash, created_by_user_id, created_at";

    private static final String ROW_COLUMNS =
            "batch_id, row_number, state, document_id, initial_revision_id, problems, compose_state, proposal_id, updated_at";

    private static final String EXPORT_COLUMNS =
            "id, workspace_id, batch_id, format, state, idempotency_key, request_hash, created_by_user_id, created_at, completed_at";

    private static final String EXPORT_ITEM_COLUMNS =
            "batch_export_id, row_number, outcome, export_receipt_id, detail, approved_content_hash";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    JdbcBatchRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public Batch create(NewBatch batch, List<NewBatchRow> rows) {
        TenantContext.setCurrentUser(jdbcTemplate, batch.createdByUserId());
        Batch created = jdbcTemplate.queryForObject(
                """
                INSERT INTO batch (workspace_id, template_id, template_version_id, source_artifact_id, source_sha256,
                                   table_format, table_reader_version, sheet_name, has_header_row, date_order, mapping,
                                   title_column, title, row_count, idempotency_key, request_hash, created_by_user_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?)
                RETURNING
                """ + " " + COLUMNS,
                this::mapBatch,
                batch.workspaceId(),
                batch.templateId(),
                batch.templateVersionId(),
                batch.sourceArtifactId(),
                batch.sourceSha256(),
                batch.tableFormat().name(),
                batch.tableReaderVersion(),
                batch.sheetName(),
                batch.hasHeaderRow(),
                batch.dateOrder().name(),
                mappingJson(batch.mapping()),
                batch.titleColumn(),
                batch.title(),
                batch.rowCount(),
                batch.idempotencyKey().value(),
                batch.requestHash(),
                batch.createdByUserId());
        for (NewBatchRow row : rows) {
            jdbcTemplate.update(
                    """
                    INSERT INTO batch_row (workspace_id, batch_id, row_number, state, compose_state)
                    VALUES (?, ?, ?, ?, ?)
                    """,
                    batch.workspaceId(),
                    created.id(),
                    row.rowNumber(),
                    row.state().name(),
                    row.composeState().name());
        }
        AuditWriter.append(
                jdbcTemplate, batch.workspaceId(), batch.createdByUserId(), "BATCH_CREATED", "batch", created.id(),
                "{\"templateId\":" + created.templateId()
                        + ",\"templateVersionId\":" + created.templateVersionId()
                        + ",\"sourceArtifactId\":" + created.sourceArtifactId()
                        + ",\"rowCount\":" + created.rowCount() + "}");
        return created;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Batch> find(long workspaceId, long userId, long batchId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate.query(
                        "SELECT " + COLUMNS + " FROM batch WHERE workspace_id = ? AND id = ?",
                        this::mapBatch,
                        workspaceId,
                        batchId)
                .stream()
                .findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Batch> findByIdempotencyKey(long workspaceId, long userId, IdempotencyKey key) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate.query(
                        "SELECT " + COLUMNS + " FROM batch"
                                + " WHERE workspace_id = ? AND created_by_user_id = ? AND idempotency_key = ?",
                        this::mapBatch,
                        workspaceId,
                        userId,
                        key.value())
                .stream()
                .findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Batch> findAll(long workspaceId, long userId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM batch WHERE workspace_id = ? ORDER BY created_at DESC, id DESC",
                this::mapBatch,
                workspaceId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<BatchRow> findRows(long workspaceId, long userId, long batchId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate.query(
                "SELECT " + ROW_COLUMNS + " FROM batch_row WHERE workspace_id = ? AND batch_id = ? ORDER BY row_number",
                this::mapRow,
                workspaceId,
                batchId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BatchRow> findRow(long workspaceId, long userId, long batchId, int rowNumber) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate.query(
                        "SELECT " + ROW_COLUMNS + " FROM batch_row"
                                + " WHERE workspace_id = ? AND batch_id = ? AND row_number = ?",
                        this::mapRow,
                        workspaceId,
                        batchId,
                        rowNumber)
                .stream()
                .findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public List<BatchRow> findPendingRows(long workspaceId, long userId, long batchId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate.query(
                "SELECT " + ROW_COLUMNS + " FROM batch_row"
                        + " WHERE workspace_id = ? AND batch_id = ? AND state = 'PENDING' ORDER BY row_number",
                this::mapRow,
                workspaceId,
                batchId);
    }

    @Override
    @Transactional(readOnly = true)
    public int countPendingRows(long workspaceId, long userId, long batchId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM batch_row WHERE workspace_id = ? AND batch_id = ? AND state = 'PENDING'",
                Integer.class,
                workspaceId,
                batchId);
        return count == null ? 0 : count;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, Integer> createdCounts(long workspaceId, long userId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        Map<Long, Integer> counts = new LinkedHashMap<>();
        jdbcTemplate.query(
                "SELECT batch_id, count(*) FROM batch_row WHERE workspace_id = ? AND state = 'CREATED' GROUP BY batch_id",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> counts.put(rs.getLong(1), rs.getInt(2)),
                workspaceId);
        return counts;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Long> createdDocumentIds(long workspaceId, long userId, long batchId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate.queryForList(
                "SELECT document_id FROM batch_row"
                        + " WHERE workspace_id = ? AND batch_id = ? AND state = 'CREATED' ORDER BY row_number",
                Long.class,
                workspaceId,
                batchId);
    }

    /**
     * The workspace's advisory lock serializes check-and-set: whoever holds
     * a live lease anywhere in the workspace is seen inside the lock, so two
     * acquisitions can never interleave into two holders.
     */
    @Override
    @Transactional
    public UUID acquireStepLease(long workspaceId, long userId, long batchId, BatchStepKind kind, Duration ttl) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        jdbcTemplate.query(
                "SELECT pg_advisory_xact_lock(hashtextextended('brownie-batch-step:' || ?::text, 0))",
                rs -> null,
                workspaceId);
        Boolean busy = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM batch WHERE workspace_id = ? AND step_lease_until > clock_timestamp())",
                Boolean.class,
                workspaceId);
        if (Boolean.TRUE.equals(busy)) {
            return null;
        }
        UUID token = UUID.randomUUID();
        int updated = jdbcTemplate.update(
                "UPDATE batch SET step_lease_token = ?, step_lease_until = clock_timestamp() + make_interval(secs => ?),"
                        + " step_kind = ? WHERE workspace_id = ? AND id = ?",
                token,
                ttl.toSeconds(),
                kind.name(),
                workspaceId,
                batchId);
        if (updated != 1) {
            // The batch vanished between the service's read and this write;
            // a token handed out anyway would let a step claim a lease on a
            // batch that is not there.
            throw new BatchNotFoundException(batchId);
        }
        return token;
    }

    @Override
    @Transactional
    public boolean refreshStepLease(long workspaceId, long userId, long batchId, UUID token, Duration ttl) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate.update(
                        "UPDATE batch SET step_lease_until = clock_timestamp() + make_interval(secs => ?)"
                                + " WHERE workspace_id = ? AND id = ? AND step_lease_token = ?",
                        ttl.toSeconds(),
                        workspaceId,
                        batchId,
                        token)
                == 1;
    }

    @Override
    @Transactional
    public void releaseStepLease(long workspaceId, long userId, long batchId, UUID token) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        jdbcTemplate.update(
                "UPDATE batch SET step_lease_token = NULL, step_lease_until = NULL, step_kind = NULL"
                        + " WHERE workspace_id = ? AND id = ? AND step_lease_token = ?",
                workspaceId,
                batchId,
                token);
    }

    @Override
    @Transactional
    public boolean markRowCreated(
            long workspaceId, long userId, long batchId, int rowNumber,
            long documentId, long revisionId, List<BatchRowProblem> problems) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate.update(
                "UPDATE batch_row SET state = 'CREATED', document_id = ?, initial_revision_id = ?, problems = ?::jsonb,"
                        + " updated_at = clock_timestamp()"
                        + " WHERE workspace_id = ? AND batch_id = ? AND row_number = ? AND state = 'PENDING'",
                documentId,
                revisionId,
                problemsJson(problems),
                workspaceId,
                batchId,
                rowNumber)
                == 1;
    }

    @Override
    @Transactional
    public boolean markRowFailed(long workspaceId, long userId, long batchId, int rowNumber, List<BatchRowProblem> problems) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate.update(
                "UPDATE batch_row SET state = 'FAILED', problems = ?::jsonb, updated_at = clock_timestamp()"
                        + " WHERE workspace_id = ? AND batch_id = ? AND row_number = ? AND state = 'PENDING'",
                problemsJson(problems),
                workspaceId,
                batchId,
                rowNumber)
                == 1;
    }

    /**
     * One statement per kind of fact over the whole batch, then assembled
     * in memory -- the count never grows with the row count. The
     * revision-id IN list is bounded by two ids a document (its current
     * and its initial), so even a full batch stays a handful of wide,
     * indexed reads.
     */
    @Override
    @Transactional(readOnly = true)
    public List<BatchRowDetail> findRowDetails(long workspaceId, long userId, long batchId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        List<RowDocFacts> facts = jdbcTemplate.query(
                """
                SELECT r.batch_id, r.row_number, r.state, r.document_id, r.initial_revision_id, r.problems,
                       r.compose_state, r.proposal_id, r.updated_at,
                       d.title AS doc_title, d.current_revision_id AS doc_revision_id,
                       (d.trashed_at IS NULL) AS doc_present
                FROM batch_row r
                LEFT JOIN document d ON d.workspace_id = r.workspace_id AND d.id = r.document_id
                WHERE r.workspace_id = ? AND r.batch_id = ?
                ORDER BY r.row_number
                """,
                this::mapRowDocFacts,
                workspaceId,
                batchId);

        Set<Long> documentIds = new HashSet<>();
        Set<Long> revisionIds = new HashSet<>();
        for (RowDocFacts fact : facts) {
            if (fact.documentId() != null) {
                documentIds.add(fact.documentId());
            }
            if (fact.currentRevisionId() != null) {
                revisionIds.add(fact.currentRevisionId());
            }
            if (fact.row().initialRevisionId() != null) {
                revisionIds.add(fact.row().initialRevisionId());
            }
        }

        Map<Long, RevisionFacts> revisions = loadRevisions(workspaceId, revisionIds);
        Map<Long, Map<FieldItemRef, FieldState>> fieldStates = loadFieldStates(workspaceId, revisionIds);
        Map<Long, ManifestFacts> manifests = loadManifests(workspaceId, documentIds, facts);
        Set<Long> proposalWaiting = loadProposedDocuments(workspaceId, documentIds);
        Map<Long, Integer> openQuestions = loadOpenQuestions(workspaceId, documentIds);
        Map<Integer, LastExportFacts> lastExports = loadLastExport(workspaceId, batchId);

        List<BatchRowDetail> details = new ArrayList<>(facts.size());
        for (RowDocFacts fact : facts) {
            RevisionFacts current = fact.currentRevisionId() == null ? null : revisions.get(fact.currentRevisionId());
            RevisionFacts initial = fact.row().initialRevisionId() == null ? null : revisions.get(fact.row().initialRevisionId());
            ManifestFacts manifest = fact.documentId() == null ? null : manifests.get(fact.documentId());
            LastExportFacts lastExport = lastExports.get(fact.row().rowNumber());
            details.add(new BatchRowDetail(
                    fact.row(),
                    fact.docTitle(),
                    fact.docPresent(),
                    fact.currentRevisionId(),
                    current == null ? null : current.content(),
                    current == null ? null : current.contentHash(),
                    initial == null ? null : initial.contentHash(),
                    fact.currentRevisionId() == null
                            ? Map.of()
                            : fieldStates.getOrDefault(fact.currentRevisionId(), Map.of()),
                    manifest == null ? null : manifest.id(),
                    manifest == null ? List.of() : manifest.findings(),
                    fact.documentId() != null && proposalWaiting.contains(fact.documentId()),
                    fact.documentId() == null ? 0 : openQuestions.getOrDefault(fact.documentId(), 0),
                    lastExport == null ? null : lastExport.exportId(),
                    lastExport == null ? null : lastExport.outcome()));
        }
        return details;
    }

    private Map<Long, RevisionFacts> loadRevisions(long workspaceId, Set<Long> revisionIds) {
        if (revisionIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, RevisionFacts> revisions = new HashMap<>();
        String placeholders = placeholders(revisionIds.size());
        jdbcTemplate.query(
                "SELECT id, content, content_hash FROM document_revision WHERE workspace_id = ? AND id IN (" + placeholders + ")",
                (ResultSet rs) -> {
                    revisions.put(
                            rs.getLong("id"),
                            new RevisionFacts(
                                    new DocumentContent(DocumentContentJson.decode(objectMapper, rs.getString("content"))),
                                    rs.getString("content_hash")));
                },
                withFirst(workspaceId, revisionIds));
        return revisions;
    }

    private Map<Long, Map<FieldItemRef, FieldState>> loadFieldStates(long workspaceId, Set<Long> revisionIds) {
        if (revisionIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Map<FieldItemRef, FieldState>> states = new HashMap<>();
        jdbcTemplate.query(
                "SELECT revision_id, field_id, item_index, authorship, evidence_support, validation, review, lock_state"
                        + " FROM document_revision_field_state"
                        + " WHERE workspace_id = ? AND revision_id IN (" + placeholders(revisionIds.size()) + ")"
                        + " ORDER BY revision_id, field_id, item_index",
                (ResultSet rs) -> {
                    Integer itemIndex = rs.getObject("item_index") == null ? null : rs.getInt("item_index");
                    states.computeIfAbsent(rs.getLong("revision_id"), key -> new LinkedHashMap<>())
                            .put(
                                    new FieldItemRef(rs.getString("field_id"), itemIndex),
                                    new FieldState(
                                            Authorship.valueOf(rs.getString("authorship")),
                                            EvidenceSupport.valueOf(rs.getString("evidence_support")),
                                            ValidationState.valueOf(rs.getString("validation")),
                                            ReviewState.valueOf(rs.getString("review")),
                                            LockState.valueOf(rs.getString("lock_state"))));
                },
                withFirst(workspaceId, revisionIds));
        return states;
    }

    /** The newest manifest each document has on its current revision -- a manifest on any other revision does not count. */
    private Map<Long, ManifestFacts> loadManifests(long workspaceId, Set<Long> documentIds, List<RowDocFacts> facts) {
        Map<Long, Long> currentByDocument = new HashMap<>();
        for (RowDocFacts fact : facts) {
            if (fact.documentId() != null && fact.docPresent() && fact.currentRevisionId() != null) {
                currentByDocument.put(fact.documentId(), fact.currentRevisionId());
            }
        }
        if (currentByDocument.isEmpty()) {
            return Map.of();
        }
        Map<Long, ManifestFacts> manifests = new HashMap<>();
        jdbcTemplate.query(
                "SELECT id, document_id, revision_id, findings FROM validation_manifest"
                        + " WHERE workspace_id = ? AND document_id IN (" + placeholders(documentIds.size()) + ")"
                        + " ORDER BY id",
                (ResultSet rs) -> {
                    long documentId = rs.getLong("document_id");
                    Long currentRevisionId = currentByDocument.get(documentId);
                    if (currentRevisionId != null && rs.getLong("revision_id") == currentRevisionId) {
                        manifests.put(documentId, new ManifestFacts(
                                rs.getLong("id"),
                                ValidationFindingJson.decode(objectMapper, rs.getString("findings"))));
                    }
                },
                withFirst(workspaceId, documentIds));
        return manifests;
    }

    private Set<Long> loadProposedDocuments(long workspaceId, Set<Long> documentIds) {
        if (documentIds.isEmpty()) {
            return Set.of();
        }
        Set<Long> documents = new HashSet<>();
        jdbcTemplate.query(
                "SELECT DISTINCT document_id FROM document_patch_proposal"
                        + " WHERE workspace_id = ? AND status = 'PROPOSED'"
                        + " AND document_id IN (" + placeholders(documentIds.size()) + ")",
                (ResultSet rs) -> {
                    documents.add(rs.getLong("document_id"));
                },
                withFirst(workspaceId, documentIds));
        return documents;
    }

    /** OPEN questions on each document's newest generation run; a document with no run answers zero. */
    private Map<Long, Integer> loadOpenQuestions(long workspaceId, Set<Long> documentIds) {
        if (documentIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Integer> counts = new HashMap<>();
        jdbcTemplate.query(
                "WITH latest AS ("
                        + " SELECT DISTINCT ON (document_id) document_id, id AS run_id"
                        + " FROM generation_run"
                        + " WHERE workspace_id = ? AND document_id IN (" + placeholders(documentIds.size()) + ")"
                        + " ORDER BY document_id, created_at DESC, id DESC)"
                        + " SELECT l.document_id, count(q.id) FROM latest l"
                        + " LEFT JOIN question q ON q.workspace_id = ? AND q.generation_run_id = l.run_id AND q.status = 'OPEN'"
                        + " GROUP BY l.document_id",
                (ResultSet rs) -> {
                    counts.put(rs.getLong("document_id"), rs.getInt(2));
                },
                concat(workspaceId, workspaceId, documentIds));
        return counts;
    }

    /** The row's outcome in the batch's newest export run, when one exists. */
    private Map<Integer, LastExportFacts> loadLastExport(long workspaceId, long batchId) {
        Map<Integer, LastExportFacts> outcomes = new HashMap<>();
        jdbcTemplate.query(
                "SELECT i.row_number, i.batch_export_id, i.outcome FROM batch_export_item i"
                        + " WHERE i.workspace_id = ? AND i.batch_export_id = ("
                        + "   SELECT e.id FROM batch_export e"
                        + "   WHERE e.workspace_id = ? AND e.batch_id = ?"
                        + "   ORDER BY e.created_at DESC, e.id DESC LIMIT 1)",
                (ResultSet rs) -> {
                    outcomes.put(
                            rs.getInt("row_number"),
                            new LastExportFacts(
                                    rs.getLong("batch_export_id"),
                                    BatchExportItemOutcome.valueOf(rs.getString("outcome"))));
                },
                workspaceId,
                workspaceId,
                batchId);
        return outcomes;
    }

    @Override
    @Transactional
    public boolean markRowComposeResolved(
            long workspaceId, long userId, long batchId, int rowNumber,
            ComposeState composeState, Long proposalId, List<BatchRowProblem> problems) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate.update(
                "UPDATE batch_row SET compose_state = ?, proposal_id = ?, problems = ?::jsonb,"
                        + " updated_at = clock_timestamp()"
                        + " WHERE workspace_id = ? AND batch_id = ? AND row_number = ? AND compose_state = 'PENDING'",
                composeState.name(),
                proposalId,
                problemsJson(problems),
                workspaceId,
                batchId,
                rowNumber)
                == 1;
    }

    @Override
    @Transactional(readOnly = true)
    public int countComposePendingRows(long workspaceId, long userId, long batchId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM batch_row"
                        + " WHERE workspace_id = ? AND batch_id = ? AND state = 'CREATED' AND compose_state = 'PENDING'",
                Integer.class,
                workspaceId,
                batchId);
        return count == null ? 0 : count;
    }

    @Override
    @Transactional(readOnly = true)
    public int countUncheckedRows(long workspaceId, long userId, long batchId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT count(*) FROM batch_row r
                JOIN document d ON d.workspace_id = r.workspace_id AND d.id = r.document_id AND d.trashed_at IS NULL
                WHERE r.workspace_id = ? AND r.batch_id = ? AND r.state = 'CREATED'
                  AND NOT EXISTS (
                      SELECT 1 FROM validation_manifest m
                      WHERE m.workspace_id = r.workspace_id AND m.document_id = d.id
                        AND m.revision_id = d.current_revision_id)
                """,
                Integer.class,
                workspaceId,
                batchId);
        return count == null ? 0 : count;
    }

    @Override
    @Transactional
    public BatchExport createExport(long workspaceId, NewBatchExport export, List<NewBatchExportItem> items) {
        TenantContext.setCurrentUser(jdbcTemplate, export.createdByUserId());
        BatchExport created = jdbcTemplate.queryForObject(
                """
                INSERT INTO batch_export (workspace_id, batch_id, format, state, idempotency_key, request_hash, created_by_user_id)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                RETURNING
                """ + " " + EXPORT_COLUMNS,
                this::mapExport,
                workspaceId,
                export.batchId(),
                export.format().name(),
                export.state().name(),
                export.idempotencyKey().value(),
                export.requestHash(),
                export.createdByUserId());
        for (NewBatchExportItem item : items) {
            jdbcTemplate.update(
                    """
                    INSERT INTO batch_export_item (workspace_id, batch_export_id, row_number, outcome, approved_content_hash)
                    VALUES (?, ?, ?, ?, ?)
                    """,
                    workspaceId,
                    created.id(),
                    item.rowNumber(),
                    item.outcome().name(),
                    item.approvedContentHash());
        }
        return created;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BatchExport> findExport(long workspaceId, long userId, long batchId, long exportId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate.query(
                        "SELECT " + EXPORT_COLUMNS + " FROM batch_export WHERE workspace_id = ? AND batch_id = ? AND id = ?",
                        this::mapExport,
                        workspaceId,
                        batchId,
                        exportId)
                .stream()
                .findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BatchExport> findExportByIdempotencyKey(
            long workspaceId, long userId, long batchId, IdempotencyKey key) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate.query(
                        "SELECT " + EXPORT_COLUMNS + " FROM batch_export"
                                + " WHERE workspace_id = ? AND batch_id = ? AND created_by_user_id = ? AND idempotency_key = ?",
                        this::mapExport,
                        workspaceId,
                        batchId,
                        userId,
                        key.value())
                .stream()
                .findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public List<BatchExportItem> findExportItems(long workspaceId, long userId, long exportId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate.query(
                "SELECT " + EXPORT_ITEM_COLUMNS + " FROM batch_export_item"
                        + " WHERE workspace_id = ? AND batch_export_id = ? ORDER BY row_number",
                this::mapExportItem,
                workspaceId,
                exportId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<BatchExportItem> findPendingExportItems(long workspaceId, long userId, long exportId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate.query(
                "SELECT " + EXPORT_ITEM_COLUMNS + " FROM batch_export_item"
                        + " WHERE workspace_id = ? AND batch_export_id = ? AND outcome = 'PENDING' ORDER BY row_number",
                this::mapExportItem,
                workspaceId,
                exportId);
    }

    @Override
    @Transactional(readOnly = true)
    public int countPendingExportItems(long workspaceId, long userId, long exportId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM batch_export_item"
                        + " WHERE workspace_id = ? AND batch_export_id = ? AND outcome = 'PENDING'",
                Integer.class,
                workspaceId,
                exportId);
        return count == null ? 0 : count;
    }

    @Override
    @Transactional
    public boolean updateExportItem(
            long workspaceId, long userId, long exportId, int rowNumber,
            BatchExportItemOutcome outcome, Long exportReceiptId, String detail) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate.update(
                "UPDATE batch_export_item SET outcome = ?, export_receipt_id = ?, detail = ?"
                        + " WHERE workspace_id = ? AND batch_export_id = ? AND row_number = ? AND outcome = 'PENDING'",
                outcome.name(),
                exportReceiptId,
                detail,
                workspaceId,
                exportId,
                rowNumber)
                == 1;
    }

    /**
     * Finishes the run and audits it in the same transaction, so a crash
     * can never leave a COMPLETE export with no BATCH_EXPORTED row. The
     * counts are read from the items themselves -- the audit tells what
     * the table says happened, not what the step thinks it did.
     */
    @Override
    @Transactional
    public void completeExport(long workspaceId, long userId, long batchId, long exportId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        Map<String, Integer> counts = new LinkedHashMap<>();
        jdbcTemplate.query(
                "SELECT outcome, count(*) FROM batch_export_item"
                        + " WHERE workspace_id = ? AND batch_export_id = ? GROUP BY outcome ORDER BY outcome",
                (ResultSet rs) -> {
                    counts.put(rs.getString(1), rs.getInt(2));
                },
                workspaceId,
                exportId);
        jdbcTemplate.update(
                "UPDATE batch_export SET state = 'COMPLETE', completed_at = clock_timestamp()"
                        + " WHERE workspace_id = ? AND id = ? AND state = 'RUNNING'",
                workspaceId,
                exportId);
        StringBuilder detail = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Integer> count : counts.entrySet()) {
            if (!first) {
                detail.append(',');
            }
            detail.append('"').append(count.getKey().toLowerCase(java.util.Locale.ROOT)).append("\":").append(count.getValue());
            first = false;
        }
        if (!first) {
            detail.append(',');
        }
        detail.append("\"exportId\":").append(exportId);
        detail.append('}');
        AuditWriter.append(
                jdbcTemplate, workspaceId, userId, "BATCH_EXPORTED", "batch", batchId, detail.toString());
    }

    @Override
    @Transactional(readOnly = true)
    public List<ExportDownloadRow> findExportDownloadRows(long workspaceId, long userId, long exportId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate.query(
                """
                SELECT i.row_number, i.outcome, i.detail,
                       d.title AS doc_title,
                       e.id AS receipt_id, e.workspace_id AS receipt_workspace_id, e.document_id, e.revision_id,
                       e.template_version_id, e.export_approval_id, e.validation_manifest_id,
                       e.docx_artifact_id, e.docx_sha256, e.pdf_artifact_id, e.pdf_sha256,
                       e.format AS receipt_format, e.actor_user_id, e.exported_at
                FROM batch_export_item i
                JOIN batch_export be ON be.workspace_id = i.workspace_id AND be.id = i.batch_export_id
                LEFT JOIN batch_row r ON r.workspace_id = i.workspace_id AND r.batch_id = be.batch_id
                    AND r.row_number = i.row_number
                LEFT JOIN document d ON d.workspace_id = r.workspace_id AND d.id = r.document_id
                LEFT JOIN export_receipt e ON e.workspace_id = i.workspace_id AND e.id = i.export_receipt_id
                WHERE i.workspace_id = ? AND i.batch_export_id = ?
                ORDER BY i.row_number
                """,
                this::mapDownloadRow,
                workspaceId,
                exportId);
    }

    /**
     * The security-definer routine, which checks membership itself before it
     * deletes -- the API's own privileges are deliberately not enough to
     * delete from these tables, so this is the only way a batch goes away.
     */
    @Override
    @Transactional
    public BatchDeleteResult delete(long workspaceId, long userId, long batchId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        TenantContext.setCorrelationId(jdbcTemplate);
        String result = jdbcTemplate.queryForObject(
                "SELECT delete_batch(?, ?)", String.class, workspaceId, batchId);
        return result == null ? BatchDeleteResult.NOT_FOUND : BatchDeleteResult.valueOf(result);
    }

    private Batch mapBatch(ResultSet rs, int rowNum) throws SQLException {
        return new Batch(
                rs.getLong("id"),
                rs.getLong("workspace_id"),
                rs.getLong("template_id"),
                rs.getLong("template_version_id"),
                rs.getLong("source_artifact_id"),
                rs.getString("source_sha256"),
                TableFormat.valueOf(rs.getString("table_format")),
                rs.getString("table_reader_version"),
                rs.getString("sheet_name"),
                rs.getBoolean("has_header_row"),
                DateOrder.valueOf(rs.getString("date_order")),
                mappingFromJson(rs.getString("mapping")),
                (Integer) rs.getObject("title_column"),
                rs.getString("title"),
                rs.getInt("row_count"),
                rs.getString("idempotency_key"),
                rs.getString("request_hash"),
                rs.getLong("created_by_user_id"),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private BatchRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        Long documentId = rs.getLong("document_id");
        if (rs.wasNull()) {
            documentId = null;
        }
        Long revisionId = rs.getLong("initial_revision_id");
        if (rs.wasNull()) {
            revisionId = null;
        }
        Long proposalId = rs.getLong("proposal_id");
        if (rs.wasNull()) {
            proposalId = null;
        }
        return new BatchRow(
                rs.getLong("batch_id"),
                rs.getInt("row_number"),
                BatchRowState.valueOf(rs.getString("state")),
                documentId,
                revisionId,
                problemsFromJson(rs.getString("problems")),
                ComposeState.valueOf(rs.getString("compose_state")),
                proposalId,
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    private String mappingJson(List<FieldMapping> mapping) {
        try {
            return objectMapper.writeValueAsString(mapping.stream().map(MappingJson::from).toList());
        } catch (Exception e) {
            throw new IllegalStateException("Field mappings could not be written as JSON.", e);
        }
    }

    private List<FieldMapping> mappingFromJson(String json) {
        try {
            return List.of(objectMapper.readValue(json, MappingJson[].class)).stream().map(MappingJson::toDomain).toList();
        } catch (Exception e) {
            throw new IllegalStateException("Stored field mappings could not be read.", e);
        }
    }

    private String problemsJson(List<BatchRowProblem> problems) {
        try {
            return objectMapper.writeValueAsString(problems.stream().map(ProblemJson::from).toList());
        } catch (Exception e) {
            throw new IllegalStateException("Row problems could not be written as JSON.", e);
        }
    }

    private List<BatchRowProblem> problemsFromJson(String json) {
        if (json == null) {
            return List.of();
        }
        try {
            return List.of(objectMapper.readValue(json, ProblemJson[].class)).stream().map(ProblemJson::toDomain).toList();
        } catch (Exception e) {
            throw new IllegalStateException("Stored row problems could not be read.", e);
        }
    }

    /** The stored JSON shape of one mapping. */
    private record MappingJson(String fieldId, Integer column, String mode) {

        static MappingJson from(FieldMapping mapping) {
            return new MappingJson(mapping.fieldId(), mapping.column(), mapping.mode().name());
        }

        FieldMapping toDomain() {
            return new FieldMapping(fieldId, column, FieldMappingMode.valueOf(mode));
        }
    }

    /** The stored JSON shape of one row problem: a code, a field id, a column -- never cell text. */
    private record ProblemJson(String code, String fieldId, Integer column) {

        static ProblemJson from(BatchRowProblem problem) {
            return new ProblemJson(problem.code().name(), problem.fieldId(), problem.column());
        }

        BatchRowProblem toDomain() {
            return new BatchRowProblem(BatchRowProblemCode.valueOf(code), fieldId, column);
        }
    }

    private RowDocFacts mapRowDocFacts(ResultSet rs, int rowNum) throws SQLException {
        return new RowDocFacts(
                mapRow(rs, rowNum),
                rs.getObject("document_id", Long.class),
                rs.getObject("doc_revision_id", Long.class),
                rs.getString("doc_title"),
                rs.getBoolean("doc_present"));
    }

    private BatchExport mapExport(ResultSet rs, int rowNum) throws SQLException {
        return new BatchExport(
                rs.getLong("id"),
                rs.getLong("workspace_id"),
                rs.getLong("batch_id"),
                ExportFormat.valueOf(rs.getString("format")),
                BatchExportState.valueOf(rs.getString("state")),
                rs.getString("idempotency_key"),
                rs.getString("request_hash"),
                rs.getLong("created_by_user_id"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("completed_at", OffsetDateTime.class));
    }

    private BatchExportItem mapExportItem(ResultSet rs, int rowNum) throws SQLException {
        return new BatchExportItem(
                rs.getLong("batch_export_id"),
                rs.getInt("row_number"),
                BatchExportItemOutcome.valueOf(rs.getString("outcome")),
                rs.getObject("export_receipt_id", Long.class),
                rs.getString("detail"),
                rs.getString("approved_content_hash"));
    }

    private ExportDownloadRow mapDownloadRow(ResultSet rs, int rowNum) throws SQLException {
        Long receiptId = rs.getObject("receipt_id", Long.class);
        ExportReceipt receipt = receiptId == null
                ? null
                : new ExportReceipt(
                        receiptId,
                        rs.getLong("receipt_workspace_id"),
                        rs.getLong("document_id"),
                        rs.getLong("revision_id"),
                        rs.getLong("template_version_id"),
                        rs.getLong("export_approval_id"),
                        rs.getLong("validation_manifest_id"),
                        rs.getObject("docx_artifact_id", Long.class),
                        rs.getString("docx_sha256"),
                        rs.getObject("pdf_artifact_id", Long.class),
                        rs.getString("pdf_sha256"),
                        ExportFormat.valueOf(rs.getString("receipt_format")),
                        rs.getLong("actor_user_id"),
                        rs.getObject("exported_at", OffsetDateTime.class));
        return new ExportDownloadRow(
                rs.getInt("row_number"),
                BatchExportItemOutcome.valueOf(rs.getString("outcome")),
                rs.getString("detail"),
                rs.getString("doc_title"),
                receipt);
    }

    private static String placeholders(int count) {
        return String.join(", ", java.util.Collections.nCopies(count, "?"));
    }

    private static Object[] withFirst(long first, Set<Long> ids) {
        Object[] args = new Object[ids.size() + 1];
        args[0] = first;
        int i = 1;
        for (Long id : ids) {
            args[i++] = id;
        }
        return args;
    }

    private static Object[] concat(long first, long second, Set<Long> ids) {
        Object[] args = new Object[ids.size() + 2];
        args[0] = first;
        args[1] = second;
        int i = 2;
        for (Long id : ids) {
            args[i++] = id;
        }
        return args;
    }

    /** A batch row joined to its document's live facts -- the base the per-kind queries hang off. */
    private record RowDocFacts(
            BatchRow row,
            Long documentId,
            Long currentRevisionId,
            String docTitle,
            boolean docPresent) {
    }

    private record RevisionFacts(DocumentContent content, String contentHash) {
    }

    private record ManifestFacts(long id, List<ValidationFinding> findings) {
    }

    private record LastExportFacts(long exportId, BatchExportItemOutcome outcome) {
    }
}
