package io.github.vihuynh72.brownie.api.persistence.jdbc;

import io.github.vihuynh72.brownie.core.job.CanonicalRequestHash;
import io.github.vihuynh72.brownie.core.job.IdempotencyKey;
import io.github.vihuynh72.brownie.core.revision.Document;
import io.github.vihuynh72.brownie.core.revision.DocumentCommandType;
import io.github.vihuynh72.brownie.core.revision.DocumentContent;
import io.github.vihuynh72.brownie.core.revision.DocumentContentHasher;
import io.github.vihuynh72.brownie.core.revision.DocumentIdempotencyConflictException;
import io.github.vihuynh72.brownie.core.revision.DocumentMutationResult;
import io.github.vihuynh72.brownie.core.revision.DocumentNotFoundException;
import io.github.vihuynh72.brownie.core.revision.DocumentRepository;
import io.github.vihuynh72.brownie.core.revision.DocumentRevision;
import io.github.vihuynh72.brownie.core.revision.DocumentRevisionConflictException;
import io.github.vihuynh72.brownie.core.revision.DocumentTemplateVersionUnavailableException;
import io.github.vihuynh72.brownie.core.revision.FieldValue;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * JDBC persistence for a document's mutable current pointer and immutable
 * revision rows. Content is serialized as a closed, versioned JSON shape,
 * not accepted as arbitrary JSON from persistence callers.
 */
@Repository
class JdbcDocumentRepository implements DocumentRepository {

    private static final String DOCUMENT_COLUMNS =
            "id, workspace_id, title, template_id, template_version_id, current_revision_id, created_at";
    private static final String REVISION_COLUMNS =
            "id, workspace_id, document_id, revision_number, parent_revision_id, content, content_hash, actor_user_id, edit_reason, created_at";
    private static final String QUALIFIED_REVISION_COLUMNS =
            "r.id, r.workspace_id, r.document_id, r.revision_number, r.parent_revision_id, r.content, r.content_hash, "
                    + "r.actor_user_id, r.edit_reason, r.created_at";
    private static final String IDEMPOTENCY_COLUMNS =
            "id, workspace_id, actor_user_id, operation, idempotency_key, request_hash, command_id, created_at";
    private static final String DOCUMENT_MUTATION_RECEIPT_COLUMNS =
            "command_id, workspace_id, actor_user_id, idempotency_record_id, document_id, revision_id, operation, request_hash, accepted_at";
    private static final int CONTENT_SCHEMA_VERSION = 1;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    JdbcDocumentRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DocumentMutationResult> findMutationResult(
            long workspaceId,
            long userId,
            DocumentCommandType commandType,
            IdempotencyKey idempotencyKey,
            CanonicalRequestHash requestHash) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        Optional<DocumentIdempotencyRecord> existing = findIdempotencyRecord(
                workspaceId, userId, commandType, idempotencyKey);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        requireMatchingHash(existing.get(), commandType, idempotencyKey, requestHash);
        return Optional.of(mutationResultFor(existing.get()));
    }

    @Override
    @Transactional
    public DocumentMutationResult createIdempotently(
            long workspaceId,
            long userId,
            IdempotencyKey idempotencyKey,
            CanonicalRequestHash requestHash,
            String title,
            long templateId,
            long templateVersionId,
            DocumentContent initialContent,
            Map<String, List<Long>> initialEvidence,
            String initialRevisionReason) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        DocumentIdempotencyReservation reservation = reserveIdempotency(
                workspaceId, userId, DocumentCommandType.CREATE, idempotencyKey, requestHash);
        if (!reservation.created()) {
            return mutationResultFor(reservation.record());
        }
        List<Long> documentIds = jdbcTemplate.queryForList(
                """
                INSERT INTO document (workspace_id, title, template_id, template_version_id)
                SELECT ?, ?, ?, ?
                WHERE EXISTS (
                    SELECT 1
                    FROM template_version
                    WHERE workspace_id = ? AND template_id = ? AND id = ? AND status = 'ACTIVATED'
                )
                RETURNING id
                """,
                Long.class,
                workspaceId,
                title,
                templateId,
                templateVersionId,
                workspaceId,
                templateId,
                templateVersionId);
        if (documentIds.isEmpty()) {
            throw new DocumentTemplateVersionUnavailableException(templateId, templateVersionId);
        }
        long documentId = documentIds.getFirst();
        long initialRevisionId = jdbcTemplate.queryForObject(
                """
                INSERT INTO document_revision
                    (workspace_id, document_id, revision_number, parent_revision_id, content, content_hash, actor_user_id, edit_reason)
                VALUES (?, ?, 1, NULL, ?::jsonb, ?, ?, ?)
                RETURNING id
                """,
                Long.class,
                workspaceId,
                documentId,
                toJson(initialContent),
                DocumentContentHasher.sha256Hex(initialContent),
                userId,
                initialRevisionReason);
        if (!advanceCurrentRevision(workspaceId, documentId, null, initialRevisionId)) {
            throw new IllegalStateException("New document " + documentId + " did not accept its initial revision pointer.");
        }
        insertEvidence(workspaceId, documentId, initialRevisionId, initialEvidence);
        createMutationReceipt(reservation.record(), documentId, initialRevisionId);
        return mutationResultFor(reservation.record());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Document> find(long workspaceId, long userId, long documentId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate.query(
                        "SELECT " + DOCUMENT_COLUMNS + " FROM document WHERE workspace_id = ? AND id = ?",
                        this::mapDocument,
                        workspaceId,
                        documentId)
                .stream()
                .findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DocumentRevision> findCurrentRevision(long workspaceId, long userId, long documentId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate.query(
                        "SELECT " + QUALIFIED_REVISION_COLUMNS + " " + """
                        FROM document d
                        JOIN document_revision r
                            ON r.workspace_id = d.workspace_id
                            AND r.document_id = d.id
                            AND r.id = d.current_revision_id
                        WHERE d.workspace_id = ? AND d.id = ?
                        """,
                        this::mapRevision,
                        workspaceId,
                        documentId)
                .stream()
                .findFirst()
                .map(this::withEvidence);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DocumentRevision> findRevision(long workspaceId, long userId, long documentId, long revisionId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate.query(
                        "SELECT " + REVISION_COLUMNS
                                + " FROM document_revision WHERE workspace_id = ? AND document_id = ? AND id = ?",
                        this::mapRevision,
                        workspaceId,
                        documentId,
                        revisionId)
                .stream()
                .findFirst()
                .map(this::withEvidence);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DocumentRevision> findHistory(long workspaceId, long userId, long documentId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        List<DocumentRevision> revisions = jdbcTemplate.query(
                "SELECT " + REVISION_COLUMNS
                        + " FROM document_revision WHERE workspace_id = ? AND document_id = ? ORDER BY revision_number",
                this::mapRevision,
                workspaceId,
                documentId);
        return revisions.stream().map(this::withEvidence).toList();
    }

    @Override
    @Transactional
    public DocumentMutationResult appendRevisionIdempotently(
            long workspaceId,
            long userId,
            IdempotencyKey idempotencyKey,
            CanonicalRequestHash requestHash,
            long documentId,
            long expectedRevisionId,
            DocumentContent content,
            Map<String, List<Long>> evidence,
            String editReason) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        DocumentIdempotencyReservation reservation = reserveIdempotency(
                workspaceId, userId, DocumentCommandType.EDIT_CONTENT, idempotencyKey, requestHash);
        if (!reservation.created()) {
            return mutationResultFor(reservation.record());
        }
        LockedDocument document = jdbcTemplate.query(
                        "SELECT document_id, current_revision_id FROM lock_document_current_revision(?, ?)",
                        (rs, rowNum) -> {
                            long currentRevisionId = rs.getLong("current_revision_id");
                            if (rs.wasNull()) {
                                throw new IllegalStateException(
                                        "Document " + documentId + " has no revision selected by its current pointer.");
                            }
                            return new LockedDocument(currentRevisionId);
                        },
                        workspaceId,
                        documentId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        if (document.currentRevisionId() != expectedRevisionId) {
            throw new DocumentRevisionConflictException(documentId, expectedRevisionId, document.currentRevisionId());
        }
        int nextRevisionNumber = jdbcTemplate.query(
                        """
                        SELECT revision_number
                        FROM document_revision
                        WHERE workspace_id = ? AND document_id = ? AND id = ?
                        """,
                        (rs, rowNum) -> rs.getInt("revision_number"),
                        workspaceId,
                        documentId,
                        expectedRevisionId)
                .stream()
                .findFirst()
                .map(number -> number + 1)
                .orElseThrow(() -> new IllegalStateException(
                        "Document " + documentId + " points at missing revision " + expectedRevisionId + "."));
        long revisionId = jdbcTemplate.queryForObject(
                """
                INSERT INTO document_revision
                    (workspace_id, document_id, revision_number, parent_revision_id, content, content_hash, actor_user_id, edit_reason)
                VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                RETURNING id
                """,
                Long.class,
                workspaceId,
                documentId,
                nextRevisionNumber,
                expectedRevisionId,
                toJson(content),
                DocumentContentHasher.sha256Hex(content),
                userId,
                editReason);
        if (!advanceCurrentRevision(workspaceId, documentId, expectedRevisionId, revisionId)) {
            long actualCurrentRevisionId = currentRevisionId(workspaceId, documentId);
            if (actualCurrentRevisionId != expectedRevisionId) {
                throw new DocumentRevisionConflictException(documentId, expectedRevisionId, actualCurrentRevisionId);
            }
            throw new IllegalStateException(
                    "Document " + documentId + " rejected revision " + revisionId + " as its direct next revision.");
        }
        insertEvidence(workspaceId, documentId, revisionId, evidence);
        createMutationReceipt(reservation.record(), documentId, revisionId);
        return mutationResultFor(reservation.record());
    }

    private DocumentIdempotencyReservation reserveIdempotency(
            long workspaceId,
            long userId,
            DocumentCommandType commandType,
            IdempotencyKey idempotencyKey,
            CanonicalRequestHash requestHash) {
        UUID candidateCommandId = UUID.randomUUID();
        List<DocumentIdempotencyRecord> inserted = jdbcTemplate.query(
                """
                INSERT INTO idempotency_record
                    (workspace_id, actor_user_id, operation, idempotency_key, request_hash, command_id)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (workspace_id, actor_user_id, operation, idempotency_key) DO NOTHING
                RETURNING id, workspace_id, actor_user_id, operation, idempotency_key, request_hash, command_id, created_at
                """,
                this::mapIdempotencyRecord,
                workspaceId,
                userId,
                commandType.operation(),
                idempotencyKey.value(),
                requestHash.value(),
                candidateCommandId);
        if (!inserted.isEmpty()) {
            return new DocumentIdempotencyReservation(inserted.getFirst(), true);
        }

        DocumentIdempotencyRecord existing = findIdempotencyRecord(workspaceId, userId, commandType, idempotencyKey)
                .orElseThrow(() -> new IllegalStateException("Idempotency record disappeared after its conflict was observed."));
        requireMatchingHash(existing, commandType, idempotencyKey, requestHash);
        return new DocumentIdempotencyReservation(existing, false);
    }

    private Optional<DocumentIdempotencyRecord> findIdempotencyRecord(
            long workspaceId,
            long userId,
            DocumentCommandType commandType,
            IdempotencyKey idempotencyKey) {
        return jdbcTemplate.query(
                        "SELECT " + IDEMPOTENCY_COLUMNS
                                + " FROM idempotency_record WHERE workspace_id = ? AND actor_user_id = ? "
                                + "AND operation = ? AND idempotency_key = ?",
                        this::mapIdempotencyRecord,
                        workspaceId,
                        userId,
                        commandType.operation(),
                        idempotencyKey.value())
                .stream()
                .findFirst();
    }

    private static void requireMatchingHash(
            DocumentIdempotencyRecord record,
            DocumentCommandType commandType,
            IdempotencyKey idempotencyKey,
            CanonicalRequestHash requestHash) {
        if (!record.requestHash().equals(requestHash)) {
            throw new DocumentIdempotencyConflictException(idempotencyKey, commandType);
        }
    }

    private void createMutationReceipt(
            DocumentIdempotencyRecord record, long documentId, long revisionId) {
        jdbcTemplate.update(
                """
                INSERT INTO document_command_receipt
                    (command_id, workspace_id, actor_user_id, idempotency_record_id, document_id, revision_id, operation, request_hash)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                record.commandId(),
                record.workspaceId(),
                record.actorUserId(),
                record.id(),
                documentId,
                revisionId,
                record.commandType().operation(),
                record.requestHash().value());
    }

    private DocumentMutationResult mutationResultFor(DocumentIdempotencyRecord record) {
        DocumentMutationReceipt receipt = jdbcTemplate.query(
                        "SELECT " + DOCUMENT_MUTATION_RECEIPT_COLUMNS
                                + " FROM document_command_receipt WHERE workspace_id = ? AND idempotency_record_id = ?",
                        this::mapMutationReceipt,
                        record.workspaceId(),
                        record.id())
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Document mutation receipt disappeared after its idempotency record was persisted."));
        if (!receipt.commandId().equals(record.commandId())
                || receipt.actorUserId() != record.actorUserId()
                || receipt.commandType() != record.commandType()
                || !receipt.requestHash().equals(record.requestHash())) {
            throw new IllegalStateException("Document mutation receipt does not match its idempotency record.");
        }
        Document document = find(record.workspaceId(), record.actorUserId(), receipt.documentId())
                .orElseThrow(() -> new IllegalStateException("Document " + receipt.documentId() + " vanished after mutation."));
        DocumentRevision revision = findRevision(
                        record.workspaceId(), record.actorUserId(), receipt.documentId(), receipt.revisionId())
                .orElseThrow(() -> new IllegalStateException(
                        "Revision " + receipt.revisionId() + " vanished after document mutation."));
        return new DocumentMutationResult(
                receipt.commandId(),
                receipt.commandType(),
                document,
                revision,
                receipt.requestHash(),
                receipt.acceptedAt());
    }

    private boolean advanceCurrentRevision(
            long workspaceId, long documentId, Long expectedRevisionId, long nextRevisionId) {
        Boolean advanced = jdbcTemplate.queryForObject(
                "SELECT advance_document_current_revision(?, ?, CAST(? AS bigint), ?)",
                Boolean.class,
                workspaceId,
                documentId,
                expectedRevisionId,
                nextRevisionId);
        return Boolean.TRUE.equals(advanced);
    }

    private long currentRevisionId(long workspaceId, long documentId) {
        Long currentRevisionId = jdbcTemplate.queryForObject(
                "SELECT current_revision_id FROM document WHERE workspace_id = ? AND id = ?",
                Long.class,
                workspaceId,
                documentId);
        if (currentRevisionId == null) {
            throw new IllegalStateException("Document " + documentId + " has no revision selected by its current pointer.");
        }
        return currentRevisionId;
    }

    private Document mapDocument(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        long currentRevisionId = rs.getLong("current_revision_id");
        if (rs.wasNull()) {
            throw new IllegalStateException("Stored document " + rs.getLong("id") + " has no current revision pointer.");
        }
        return new Document(
                rs.getLong("id"),
                rs.getLong("workspace_id"),
                rs.getString("title"),
                rs.getLong("template_id"),
                rs.getLong("template_version_id"),
                currentRevisionId,
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private DocumentRevision mapRevision(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        long parentRevisionId = rs.getLong("parent_revision_id");
        boolean hasNoParent = rs.wasNull();
        DocumentContent content = fromJson(rs.getString("content"));
        String contentHash = rs.getString("content_hash");
        String calculatedHash = DocumentContentHasher.sha256Hex(content);
        if (!calculatedHash.equals(contentHash)) {
            throw new IllegalStateException(
                    "Stored revision " + rs.getLong("id") + " content does not match its recorded content hash.");
        }
        return new DocumentRevision(
                rs.getLong("id"),
                rs.getLong("workspace_id"),
                rs.getLong("document_id"),
                rs.getInt("revision_number"),
                hasNoParent ? null : parentRevisionId,
                content,
                contentHash,
                rs.getLong("actor_user_id"),
                rs.getString("edit_reason"),
                rs.getObject("created_at", OffsetDateTime.class),
                Map.of());
    }

    private DocumentRevision withEvidence(DocumentRevision revision) {
        Map<String, List<Long>> evidence = loadEvidence(revision.workspaceId(), revision.documentId(), revision.id());
        return new DocumentRevision(
                revision.id(),
                revision.workspaceId(),
                revision.documentId(),
                revision.revisionNumber(),
                revision.parentRevisionId(),
                revision.content(),
                revision.contentHash(),
                revision.actorUserId(),
                revision.editReason(),
                revision.createdAt(),
                evidence);
    }

    private Map<String, List<Long>> loadEvidence(long workspaceId, long documentId, long revisionId) {
        Map<String, List<Long>> evidence = new LinkedHashMap<>();
        jdbcTemplate.query(
                """
                SELECT field_id, source_span_id
                FROM document_revision_field_evidence
                WHERE workspace_id = ? AND document_id = ? AND revision_id = ?
                ORDER BY field_id, source_span_id
                """,
                (java.sql.ResultSet rs) -> {
                    evidence.computeIfAbsent(rs.getString("field_id"), key -> new ArrayList<>())
                            .add(rs.getLong("source_span_id"));
                },
                workspaceId,
                documentId,
                revisionId);
        return evidence;
    }

    private void insertEvidence(
            long workspaceId, long documentId, long revisionId, Map<String, List<Long>> evidence) {
        for (Map.Entry<String, List<Long>> entry : evidence.entrySet()) {
            for (Long sourceSpanId : entry.getValue()) {
                jdbcTemplate.update(
                        """
                        INSERT INTO document_revision_field_evidence
                            (workspace_id, document_id, revision_id, field_id, source_span_id)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                        workspaceId,
                        documentId,
                        revisionId,
                        entry.getKey(),
                        sourceSpanId);
            }
        }
    }

    private DocumentIdempotencyRecord mapIdempotencyRecord(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new DocumentIdempotencyRecord(
                rs.getLong("id"),
                rs.getLong("workspace_id"),
                rs.getLong("actor_user_id"),
                commandType(rs.getString("operation")),
                new IdempotencyKey(rs.getString("idempotency_key")),
                new CanonicalRequestHash(rs.getString("request_hash")),
                rs.getObject("command_id", UUID.class),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private DocumentMutationReceipt mapMutationReceipt(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new DocumentMutationReceipt(
                rs.getObject("command_id", UUID.class),
                rs.getLong("workspace_id"),
                rs.getLong("actor_user_id"),
                rs.getLong("idempotency_record_id"),
                rs.getLong("document_id"),
                rs.getLong("revision_id"),
                commandType(rs.getString("operation")),
                new CanonicalRequestHash(rs.getString("request_hash")),
                rs.getObject("accepted_at", OffsetDateTime.class));
    }

    private String toJson(DocumentContent content) {
        List<Map<String, Object>> fields = new ArrayList<>();
        content.fields().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            Map<String, Object> field = new LinkedHashMap<>();
            field.put("fieldId", entry.getKey());
            switch (entry.getValue()) {
                case FieldValue.TextValue(String value) -> {
                    field.put("type", "TEXT");
                    field.put("cardinality", "SCALAR");
                    field.put("value", value);
                }
                case FieldValue.DateValue(LocalDate value) -> {
                    field.put("type", "DATE");
                    field.put("cardinality", "SCALAR");
                    field.put("value", value.toString());
                }
                case FieldValue.RepeatedTextValue(List<String> values) -> {
                    field.put("type", "TEXT");
                    field.put("cardinality", "REPEATED");
                    field.put("values", values);
                }
                case FieldValue.RepeatedDateValue(List<LocalDate> values) -> {
                    field.put("type", "DATE");
                    field.put("cardinality", "REPEATED");
                    field.put("values", values.stream().map(LocalDate::toString).toList());
                }
            }
            fields.add(field);
        });
        try {
            return objectMapper.writeValueAsString(Map.of("schemaVersion", CONTENT_SCHEMA_VERSION, "fields", fields));
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize typed document content.", e);
        }
    }

    @SuppressWarnings("unchecked")
    private DocumentContent fromJson(String json) {
        try {
            Object decoded = objectMapper.readValue(json, Object.class);
            Map<String, Object> root = objectMap(decoded, "document content");
            requireKeys(root, Set.of("schemaVersion", "fields"), "document content");
            if (!(root.get("schemaVersion") instanceof Number schemaVersion)
                    || schemaVersion.intValue() != CONTENT_SCHEMA_VERSION) {
                throw malformedContent("Document content has an unsupported schema version.");
            }
            if (!(root.get("fields") instanceof List<?> rawFields)) {
                throw malformedContent("Document content fields must be an array.");
            }
            Map<String, FieldValue> fields = new LinkedHashMap<>();
            for (Object rawField : rawFields) {
                Map<String, Object> field = objectMap(rawField, "document content field");
                String fieldId = requiredString(field, "fieldId", "document content field");
                FieldValue value = valueFromMap(field);
                if (fields.putIfAbsent(fieldId, value) != null) {
                    throw malformedContent("Document content repeats field ID " + fieldId + ".");
                }
            }
            return new DocumentContent(fields);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to deserialize typed document content.", e);
        }
    }

    private FieldValue valueFromMap(Map<String, Object> field) {
        String type = requiredString(field, "type", "document content field");
        String cardinality = requiredString(field, "cardinality", "document content field");
        return switch (type + ":" + cardinality) {
            case "TEXT:SCALAR" -> {
                requireKeys(field, Set.of("fieldId", "type", "cardinality", "value"), "TEXT scalar field");
                yield new FieldValue.TextValue(requiredString(field, "value", "TEXT scalar field"));
            }
            case "DATE:SCALAR" -> {
                requireKeys(field, Set.of("fieldId", "type", "cardinality", "value"), "DATE scalar field");
                yield new FieldValue.DateValue(parseDate(requiredString(field, "value", "DATE scalar field")));
            }
            case "TEXT:REPEATED" -> {
                requireKeys(field, Set.of("fieldId", "type", "cardinality", "values"), "TEXT repeated field");
                yield new FieldValue.RepeatedTextValue(requiredStringList(field, "values", "TEXT repeated field"));
            }
            case "DATE:REPEATED" -> {
                requireKeys(field, Set.of("fieldId", "type", "cardinality", "values"), "DATE repeated field");
                yield new FieldValue.RepeatedDateValue(requiredStringList(field, "values", "DATE repeated field")
                        .stream()
                        .map(this::parseDate)
                        .toList());
            }
            default -> throw malformedContent("Unsupported document field shape " + type + ":" + cardinality + ".");
        };
    }

    private static Map<String, Object> objectMap(Object value, String description) {
        if (!(value instanceof Map<?, ?> rawMap)) {
            throw malformedContent("Expected " + description + " to be an object.");
        }
        Map<String, Object> mapped = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw malformedContent("Expected " + description + " keys to be strings.");
            }
            mapped.put(key, entry.getValue());
        }
        return mapped;
    }

    private static void requireKeys(Map<String, Object> value, Set<String> expected, String description) {
        if (!value.keySet().equals(expected)) {
            throw malformedContent("Unexpected properties in " + description + ".");
        }
    }

    private static String requiredString(Map<String, Object> value, String key, String description) {
        if (!(value.get(key) instanceof String string)) {
            throw malformedContent("Expected " + description + " property " + key + " to be a string.");
        }
        return string;
    }

    private static List<String> requiredStringList(Map<String, Object> value, String key, String description) {
        if (!(value.get(key) instanceof List<?> rawValues)) {
            throw malformedContent("Expected " + description + " property " + key + " to be an array.");
        }
        List<String> values = new ArrayList<>();
        for (Object rawValue : rawValues) {
            if (!(rawValue instanceof String string)) {
                throw malformedContent("Expected " + description + " values to be strings.");
            }
            values.add(string);
        }
        return values;
    }

    private LocalDate parseDate(String date) {
        try {
            return LocalDate.parse(date);
        } catch (DateTimeParseException e) {
            throw malformedContent("Expected DATE value to use ISO local-date form.");
        }
    }

    private static IllegalStateException malformedContent(String detail) {
        return new IllegalStateException("Stored typed document content is invalid: " + detail);
    }

    private static DocumentCommandType commandType(String operation) {
        return switch (operation) {
            case "document.create" -> DocumentCommandType.CREATE;
            case "document.edit-content" -> DocumentCommandType.EDIT_CONTENT;
            default -> throw new IllegalStateException("Unknown persisted document operation: " + operation);
        };
    }

    private record LockedDocument(long currentRevisionId) {
    }

    private record DocumentIdempotencyRecord(
            long id,
            long workspaceId,
            long actorUserId,
            DocumentCommandType commandType,
            IdempotencyKey idempotencyKey,
            CanonicalRequestHash requestHash,
            UUID commandId,
            OffsetDateTime createdAt) {
    }

    private record DocumentIdempotencyReservation(DocumentIdempotencyRecord record, boolean created) {
    }

    private record DocumentMutationReceipt(
            UUID commandId,
            long workspaceId,
            long actorUserId,
            long idempotencyRecordId,
            long documentId,
            long revisionId,
            DocumentCommandType commandType,
            CanonicalRequestHash requestHash,
            OffsetDateTime acceptedAt) {
    }
}
