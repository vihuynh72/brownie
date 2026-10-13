package io.github.vihuynh72.brownie.core.batch;

import io.github.vihuynh72.brownie.core.artifact.Artifact;
import io.github.vihuynh72.brownie.core.artifact.ArtifactService;
import io.github.vihuynh72.brownie.core.artifact.ReadableArtifact;
import io.github.vihuynh72.brownie.core.batch.BatchRepository.ExportDownloadRow;
import io.github.vihuynh72.brownie.core.batch.BatchRepository.NewBatch;
import io.github.vihuynh72.brownie.core.batch.BatchRepository.NewBatchExport;
import io.github.vihuynh72.brownie.core.batch.BatchRepository.NewBatchExportItem;
import io.github.vihuynh72.brownie.core.batch.BatchRepository.NewBatchRow;
import io.github.vihuynh72.brownie.core.batch.TableUnreadableException.Reason;
import io.github.vihuynh72.brownie.core.compile.RenderCapacityExceededException;
import io.github.vihuynh72.brownie.core.export.ExportArtifactIntegrityException;
import io.github.vihuynh72.brownie.core.export.ExportFormat;
import io.github.vihuynh72.brownie.core.export.ExportFormatNotOfferedException;
import io.github.vihuynh72.brownie.core.export.ExportNotApprovedException;
import io.github.vihuynh72.brownie.core.export.ExportReceipt;
import io.github.vihuynh72.brownie.core.export.ExportService;
import io.github.vihuynh72.brownie.core.export.StaleExportApprovalException;
import io.github.vihuynh72.brownie.core.job.CanonicalRequestHash;
import io.github.vihuynh72.brownie.core.job.IdempotencyKey;
import io.github.vihuynh72.brownie.core.model.ModelRequest;
import io.github.vihuynh72.brownie.core.revision.Authorship;
import io.github.vihuynh72.brownie.core.revision.DocumentContent;
import io.github.vihuynh72.brownie.core.revision.DocumentContentHasher;
import io.github.vihuynh72.brownie.core.revision.DocumentContentValidationException;
import io.github.vihuynh72.brownie.core.revision.DocumentMutationResult;
import io.github.vihuynh72.brownie.core.revision.DocumentNotFoundException;
import io.github.vihuynh72.brownie.core.revision.DocumentRevisionConflictException;
import io.github.vihuynh72.brownie.core.revision.DocumentTemplateVersionUnavailableException;
import io.github.vihuynh72.brownie.core.revision.FieldItemRef;
import io.github.vihuynh72.brownie.core.revision.FieldState;
import io.github.vihuynh72.brownie.core.revision.FieldValue;
import io.github.vihuynh72.brownie.core.revision.PatchProposal;
import io.github.vihuynh72.brownie.core.revision.ReviewState;
import io.github.vihuynh72.brownie.core.revision.RevisionService;
import io.github.vihuynh72.brownie.core.rule.RulePayload;
import io.github.vihuynh72.brownie.core.rule.RuleRepository;
import io.github.vihuynh72.brownie.core.rule.RuleRevision;
import io.github.vihuynh72.brownie.core.rule.RuleRevisionStatus;
import io.github.vihuynh72.brownie.core.template.FieldCardinality;
import io.github.vihuynh72.brownie.core.template.FieldDefinition;
import io.github.vihuynh72.brownie.core.template.FieldRequiredness;
import io.github.vihuynh72.brownie.core.template.FieldType;
import io.github.vihuynh72.brownie.core.template.Template;
import io.github.vihuynh72.brownie.core.template.TemplateKind;
import io.github.vihuynh72.brownie.core.template.TemplateNotFoundException;
import io.github.vihuynh72.brownie.core.template.TemplateRepository;
import io.github.vihuynh72.brownie.core.template.TemplateTrashedException;
import io.github.vihuynh72.brownie.core.template.TemplateVersion;
import io.github.vihuynh72.brownie.core.template.TemplateVersionMovedOnException;
import io.github.vihuynh72.brownie.core.template.TemplateVersionStatus;
import io.github.vihuynh72.brownie.core.validation.DocumentValidator;
import io.github.vihuynh72.brownie.core.validation.ValidationFinding;
import io.github.vihuynh72.brownie.core.validation.ValidationManifest;
import io.github.vihuynh72.brownie.core.validation.ValidationService;
import io.github.vihuynh72.brownie.core.validation.ValidationSeverity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The batch domain: making a batch from a table, and filling it in bounded
 * steps a browser calls until nothing is left. A step is a request, not a
 * job -- it holds the workspace's lease while it works a row at a time,
 * leaves whatever it cannot reach PENDING, and is healed by its per-row
 * idempotency keys if a request dies between a document and its row.
 */
public class BatchService {

    /** How long a step's lease lasts; refreshed after every row so a dead request's lease lapses on its own. */
    public static final Duration STEP_LEASE_TTL = Duration.ofSeconds(180);
    /** Fill bounds: no step runs more rows or more wall-clock than this in one request. */
    public static final int FILL_ROW_BUDGET = 100;
    public static final Duration FILL_TIME_BUDGET = Duration.ofSeconds(15);
    /** Accept bounds: one review revision is cheap, so the same row and time bounds as fill. */
    public static final int ACCEPT_ROW_BUDGET = 100;
    public static final Duration ACCEPT_TIME_BUDGET = Duration.ofSeconds(15);
    /** Check, export and compose bounds: each row may cost a render or a model call, so far fewer rows but longer. */
    public static final int CHECK_ROW_BUDGET = 10;
    public static final Duration CHECK_TIME_BUDGET = Duration.ofSeconds(20);
    public static final int EXPORT_ROW_BUDGET = 10;
    public static final Duration EXPORT_TIME_BUDGET = Duration.ofSeconds(20);
    public static final int COMPOSE_ROW_BUDGET = 10;
    public static final Duration COMPOSE_TIME_BUDGET = Duration.ofSeconds(20);
    /** How long a browser waits before asking again when every render slot stayed busy. */
    static final long RENDER_RETRY_AFTER_MS = 2_000;

    private static final int MAX_TITLE_CHARS = 200;
    private static final int MAX_TITLE_CELL_CHARS = 120;
    private static final int MAX_COMPOSED_TEXT_CHARS = 2_000;

    private static final Logger log = LoggerFactory.getLogger(BatchService.class);

    private final BatchRepository batches;
    private final TemplateRepository templates;
    private final ArtifactService artifacts;
    private final RevisionService revisions;
    private final XlsxTableReader xlsxReader;
    private final CsvTableParser csvParser;
    private final RuleRepository rules;
    private final ValidationService validations;
    private final ExportService exports;
    private final BatchComposer composer;
    private final int maxRows;
    private final boolean composeOffered;

    public BatchService(
            BatchRepository batches,
            TemplateRepository templates,
            ArtifactService artifacts,
            RevisionService revisions,
            XlsxTableReader xlsxReader,
            CsvTableParser csvParser,
            RuleRepository rules,
            ValidationService validations,
            ExportService exports,
            BatchComposer composer,
            int maxRows,
            boolean composeOffered) {
        this.batches = Objects.requireNonNull(batches);
        this.templates = Objects.requireNonNull(templates);
        this.artifacts = Objects.requireNonNull(artifacts);
        this.revisions = Objects.requireNonNull(revisions);
        this.xlsxReader = Objects.requireNonNull(xlsxReader);
        this.csvParser = Objects.requireNonNull(csvParser);
        this.rules = Objects.requireNonNull(rules);
        this.validations = Objects.requireNonNull(validations);
        this.exports = Objects.requireNonNull(exports);
        this.composer = composer;
        this.maxRows = maxRows;
        this.composeOffered = composeOffered;
    }

    /** How many data rows a batch may hold, so the route and capabilities agree with the reader. */
    public int maxRows() {
        return maxRows;
    }

    public boolean composeOffered() {
        return composeOffered;
    }

    /**
     * Reads an uploaded file as a table. A READY artifact of the
     * spreadsheet type goes to the workbook reader, plain text goes to the
     * CSV parser, and anything else is simply not a table.
     */
    public TableReading readArtifactTable(long workspaceId, long userId, long artifactId, TableReadRequest request)
            throws IOException {
        try (ReadableArtifact source = artifacts.openContent(workspaceId, userId, artifactId)) {
            return readTable(source.artifact(), source.content(), request);
        }
    }

    /**
     * Creates a batch: validates the pinned version and the mapping against
     * the file's own reading of itself, then writes one row per data row.
     * The same idempotency key answers with the same batch; the same key on
     * a different request is refused, as a document's key is.
     */
    public Batch create(long workspaceId, long userId, BatchCreateRequest request) throws IOException {
        String requestHash = createRequestHash(request).value();
        Optional<Batch> earlier = batches.findByIdempotencyKey(workspaceId, userId, request.idempotencyKey());
        if (earlier.isPresent()) {
            if (earlier.get().requestHash().equals(requestHash)) {
                return earlier.get();
            }
            throw new BatchIdempotencyConflictException();
        }
        Template template = templates.find(workspaceId, userId, request.templateId())
                .orElseThrow(() -> new TemplateNotFoundException(request.templateId()));
        if (template.trashedAt() != null) {
            throw new TemplateTrashedException();
        }
        if (template.currentActiveVersionId() == null || template.currentActiveVersionId() != request.templateVersionId()) {
            throw new TemplateVersionMovedOnException(
                    request.templateId(), request.templateVersionId(), template.currentActiveVersionId());
        }
        TemplateVersion version = templates.findVersion(workspaceId, userId, request.templateId(), request.templateVersionId())
                .orElseThrow(() -> new DocumentTemplateVersionUnavailableException(
                        request.templateId(), request.templateVersionId()));
        if (version.status() != TemplateVersionStatus.ACTIVATED) {
            throw new DocumentTemplateVersionUnavailableException(request.templateId(), request.templateVersionId());
        }
        try (ReadableArtifact source = artifacts.openContent(workspaceId, userId, request.artifactId())) {
            Artifact artifact = source.artifact();
            TableReading table = readTable(artifact, source.content(), readRequest(request));
            validateMapping(version, table, request);
            String title = bounded(template.displayName() + " from " + artifactFilename(artifact), MAX_TITLE_CHARS);
            boolean composeMapped = request.mapping().stream().anyMatch(m -> m.mode() == FieldMappingMode.COMPOSE);
            List<NewBatchRow> rows = table.rows().stream()
                    .map(row -> new NewBatchRow(
                            row.rowNumber(),
                            row.blank() ? BatchRowState.SKIPPED_BLANK : BatchRowState.PENDING,
                            composeMapped ? ComposeState.PENDING : ComposeState.NOT_NEEDED))
                    .toList();
            return batches.create(
                    new NewBatch(
                            workspaceId,
                            request.templateId(),
                            request.templateVersionId(),
                            request.artifactId(),
                            artifact.sha256(),
                            table.format(),
                            table.readerVersion(),
                            table.sheetName(),
                            table.hasHeaderRow(),
                            request.dateOrder() == null ? DateOrder.MDY : request.dateOrder(),
                            request.mapping(),
                            request.titleColumn(),
                            title,
                            table.rows().size(),
                            request.idempotencyKey(),
                            requestHash,
                            userId),
                    rows);
        }
    }

    /**
     * Runs the FILL step: the workspace's lease, the file re-read and proven
     * unchanged, then pending rows in order, one document each. A step that
     * cannot finish leaves rows PENDING; the next call continues. A trashed
     * template or a lost lease stops it.
     */
    public BatchStepResult fill(long workspaceId, long userId, long batchId) throws IOException {
        Batch batch = batches.find(workspaceId, userId, batchId)
                .orElseThrow(() -> new BatchNotFoundException(batchId));
        UUID token = batches.acquireStepLease(workspaceId, userId, batchId, BatchStepKind.FILL, STEP_LEASE_TTL);
        if (token == null) {
            throw new BatchBusyException();
        }
        try {
            return fillUnderLease(workspaceId, userId, batch, token);
        } finally {
            batches.releaseStepLease(workspaceId, userId, batchId, token);
        }
    }

    private BatchStepResult fillUnderLease(long workspaceId, long userId, Batch batch, UUID token) throws IOException {
        Template template = requirePinnedTemplate(workspaceId, userId, batch);
        if (template.trashedAt() != null) {
            throw new TemplateTrashedException();
        }
        Map<String, FieldDefinition> fields = fieldsById(
                templates.findVersion(workspaceId, userId, batch.templateId(), batch.templateVersionId())
                        .orElseThrow(BatchTemplateUnavailableException::new));
        TableReading table;
        String filename;
        try (ReadableArtifact source = artifacts.openContent(workspaceId, userId, batch.sourceArtifactId())) {
            if (!Objects.equals(source.artifact().sha256(), batch.sourceSha256())) {
                throw new BatchSourceChangedException();
            }
            table = readTable(source.artifact(), source.content(), storedReadRequest(batch));
            if (!table.readerVersion().equals(batch.tableReaderVersion())) {
                throw new BatchReaderChangedException();
            }
            filename = artifactFilename(source.artifact());
        }
        Map<Integer, TableRow> tableRows = new LinkedHashMap<>();
        for (TableRow row : table.rows()) {
            tableRows.put(row.rowNumber(), row);
        }
        List<BatchRow> updated = new ArrayList<>();
        int processed = 0;
        Instant deadline = Instant.now().plus(FILL_TIME_BUDGET);
        for (BatchRow row : batches.findPendingRows(workspaceId, userId, batch.id())) {
            if (processed >= FILL_ROW_BUDGET || Instant.now().isAfter(deadline)) {
                break;
            }
            TableRow tableRow = tableRows.get(row.rowNumber());
            if (tableRow == null) {
                // The fingerprint proved the bytes are the same; a row the
                // first read produced being absent means the reader no
                // longer reads them the same way.
                throw new BatchReaderChangedException();
            }
            BatchRow written = fillRow(workspaceId, userId, batch, fields, tableRow, template, filename);
            if (written == null) {
                // The row stopped being PENDING underneath the step; a batch
                // that is gone is "not there", any other move ends the step.
                if (batches.find(workspaceId, userId, batch.id()).isEmpty()) {
                    throw new BatchNotFoundException(batch.id());
                }
                break;
            }
            updated.add(written);
            processed++;
            if (!batches.refreshStepLease(workspaceId, userId, batch.id(), token, STEP_LEASE_TTL)) {
                break;
            }
        }
        int remaining = batches.countPendingRows(workspaceId, userId, batch.id());
        return new BatchStepResult(processed, remaining, null, updated);
    }

    /**
     * One row into one document. Cells map to field values by the batch's
     * mapping; problems the cells carried become problems on the row, never
     * the cell's text. The per-row key and hash make a retry after a crash
     * between the document and the row update resolve to the same document.
     * Null means the row was no longer PENDING by the time the step marked
     * it; the caller ends the step over it.
     */
    private BatchRow fillRow(
            long workspaceId, long userId, Batch batch, Map<String, FieldDefinition> fields,
            TableRow row, Template template, String filename) {
        Map<String, FieldValue> content = new LinkedHashMap<>();
        List<BatchRowProblem> problems = new ArrayList<>();
        for (FieldMapping mapping : batch.mapping()) {
            if (mapping.mode() == FieldMappingMode.COMPOSE) {
                continue;
            }
            FieldDefinition field = fields.get(mapping.fieldId());
            if (field == null) {
                continue;
            }
            TableCell cell = mapping.column() < row.cells().size()
                    ? row.cells().get(mapping.column())
                    : TableCell.blank();
            if (cell.problem() != null) {
                problems.add(new BatchRowProblem(
                        cell.problem() == CellProblem.FORMULA_WITHOUT_RESULT
                                ? BatchRowProblemCode.FORMULA_WITHOUT_RESULT
                                : BatchRowProblemCode.ERROR_VALUE,
                        mapping.fieldId(),
                        mapping.column()));
                continue;
            }
            if (field.type() == FieldType.DATE) {
                if (cell.date() != null) {
                    content.put(mapping.fieldId(), new FieldValue.DateValue(cell.date()));
                } else {
                    problems.add(new BatchRowProblem(BatchRowProblemCode.NOT_A_DATE, mapping.fieldId(), mapping.column()));
                }
            } else {
                String value = cell.display().trim();
                if (!value.isEmpty()) {
                    content.put(mapping.fieldId(), new FieldValue.TextValue(value));
                }
            }
        }
        String title = documentTitle(batch, template, row);
        DocumentContent documentContent = new DocumentContent(content);
        try {
            DocumentMutationResult result = revisions.createImportedDocument(
                    workspaceId,
                    userId,
                    new IdempotencyKey("batch-" + batch.id() + "-row-" + row.rowNumber() + "-create"),
                    rowRequestHash(batch, row.rowNumber(), documentContent, title),
                    title,
                    batch.templateId(),
                    batch.templateVersionId(),
                    documentContent,
                    "Filled from row " + row.rowNumber() + " of " + filename);
            if (!batches.markRowCreated(
                    workspaceId, userId, batch.id(), row.rowNumber(),
                    result.document().id(), result.revision().id(), problems)) {
                return null;
            }
        } catch (DocumentContentValidationException refused) {
            List<BatchRowProblem> refusal = new ArrayList<>(problems);
            refused.problems().stream()
                    .map(problem -> problem.fieldId())
                    .distinct()
                    .forEach(fieldId -> refusal.add(new BatchRowProblem(BatchRowProblemCode.CONTENT_REFUSED, fieldId, null)));
            if (!batches.markRowFailed(workspaceId, userId, batch.id(), row.rowNumber(), refusal)) {
                return null;
            }
        } catch (DocumentTemplateVersionUnavailableException unavailable) {
            throw new BatchTemplateUnavailableException();
        }
        return rowAfter(workspaceId, userId, batch.id(), row.rowNumber());
    }

    private BatchRow rowAfter(long workspaceId, long userId, long batchId, int rowNumber) {
        return batches.findRow(workspaceId, userId, batchId, rowNumber)
                .orElseThrow(() -> new IllegalStateException("A batch row just written could not be read back."));
    }

    private String documentTitle(Batch batch, Template template, TableRow row) {
        Integer titleColumn = batch.titleColumn();
        if (titleColumn != null && titleColumn < row.cells().size()) {
            String text = row.cells().get(titleColumn).display().trim();
            if (!text.isEmpty()) {
                return template.displayName() + ", " + bounded(text, MAX_TITLE_CELL_CHARS);
            }
        }
        return template.displayName() + ", row " + row.rowNumber();
    }

    private TableReading readTable(Artifact artifact, InputStream content, TableReadRequest request) throws IOException {
        return switch (artifact.detectedMediaType()) {
            case XLSX -> xlsxReader.read(content, request, limits());
            case PLAIN_TEXT -> csvParser.parse(content, request, limits());
            case null, default -> throw new TableUnreadableException(
                    Reason.NOT_A_TABLE, "This file is not a spreadsheet or a CSV a table can be read from.");
        };
    }

    private TableLimits limits() {
        return TableLimits.ofRows(maxRows);
    }

    private TableReadRequest readRequest(BatchCreateRequest request) {
        return new TableReadRequest(request.sheet(), request.hasHeaderRow(), request.dateOrder());
    }

    private TableReadRequest storedReadRequest(Batch batch) {
        return new TableReadRequest(batch.sheetName(), batch.hasHeaderRow(), batch.dateOrder());
    }

    /**
     * Everything a mapping must be: fields that exist, each named once,
     * columns that exist, scalar fields only, compose only where it can
     * act, and every required field answered for. Order matters: a template
     * whose required list a table can never fill says so before the
     * narrower complaints are even asked.
     */
    private void validateMapping(TemplateVersion version, TableReading table, BatchCreateRequest request) {
        Map<String, FieldDefinition> fields = fieldsById(version);
        List<String> needsList = fields.values().stream()
                .filter(def -> def.cardinality() == FieldCardinality.REPEATED
                        && def.requiredness() == FieldRequiredness.REQUIRED)
                .map(FieldDefinition::fieldId)
                .sorted()
                .toList();
        if (!needsList.isEmpty()) {
            throw new BatchTemplateNeedsListException(needsList);
        }
        Set<String> seen = new LinkedHashSet<>();
        for (FieldMapping mapping : request.mapping()) {
            FieldDefinition field = fields.get(mapping.fieldId());
            if (field == null) {
                throw new BatchUnknownFieldException(mapping.fieldId());
            }
            if (!seen.add(mapping.fieldId())) {
                throw new BatchDuplicateMappingException(mapping.fieldId());
            }
            if (field.cardinality() == FieldCardinality.REPEATED) {
                throw new BatchFieldNotFillableException(mapping.fieldId());
            }
            if (mapping.column() >= table.columns().size()) {
                throw new BatchUnknownColumnException(mapping.column());
            }
            if (mapping.mode() == FieldMappingMode.COMPOSE) {
                if (!composeOffered) {
                    throw new BatchComposeNotOfferedException("This deployment does not offer compose in a batch.");
                }
                if (field.type() != FieldType.TEXT) {
                    throw new BatchComposeNotOfferedException("Only a text field can be filled by compose.");
                }
            }
        }
        if (request.titleColumn() != null && request.titleColumn() >= table.columns().size()) {
            throw new BatchUnknownColumnException(request.titleColumn());
        }
        List<String> unmapped = fields.values().stream()
                .filter(def -> def.cardinality() == FieldCardinality.SCALAR
                        && def.requiredness() == FieldRequiredness.REQUIRED
                        && !seen.contains(def.fieldId()))
                .map(FieldDefinition::fieldId)
                .sorted()
                .toList();
        if (!unmapped.isEmpty()) {
            throw new BatchRequiredFieldsUnmappedException(unmapped);
        }
    }

    private static Map<String, FieldDefinition> fieldsById(TemplateVersion version) {
        Map<String, FieldDefinition> fields = new LinkedHashMap<>();
        for (FieldDefinition field : version.fieldDefinitions()) {
            fields.put(field.fieldId(), field);
        }
        return fields;
    }

    /** The hash that tells a retried create apart from a different request under the same key. */
    private static CanonicalRequestHash createRequestHash(BatchCreateRequest request) {
        StringBuilder mapping = new StringBuilder();
        request.mapping().forEach(m -> mapping
                .append(m.fieldId()).append(':').append(m.column()).append(':').append(m.mode()).append('\n'));
        return CanonicalRequestHash.sha256OfCanonicalText(
                "batch-create\0" + request.templateId() + "\0" + request.templateVersionId() + "\0"
                        + request.artifactId() + "\0" + Objects.toString(request.sheet(), "") + "\0"
                        + request.hasHeaderRow() + "\0" + Objects.toString(request.dateOrder(), "") + "\0"
                        + mapping + "\0" + Objects.toString(request.titleColumn(), ""));
    }

    /** The key and hash one row's document creation is remembered by -- deterministic across retries. */
    private static CanonicalRequestHash rowRequestHash(Batch batch, int rowNumber, DocumentContent content, String title) {
        return CanonicalRequestHash.sha256OfCanonicalText(
                "FILL\0" + batch.id() + "\0" + rowNumber + "\0" + batch.templateVersionId() + "\0"
                        + DocumentContentHasher.sha256Hex(content) + "\0" + title);
    }

    private static String bounded(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static String artifactFilename(Artifact artifact) {
        return artifact.displayFilename() == null || artifact.displayFilename().isBlank()
                ? "the spreadsheet"
                : artifact.displayFilename();
    }

    public List<BatchSummary> list(long workspaceId, long userId) {
        Map<Long, Integer> created = batches.createdCounts(workspaceId, userId);
        return batches.findAll(workspaceId, userId).stream()
                .map(batch -> new BatchSummary(
                        batch,
                        created.getOrDefault(batch.id(), 0),
                        batches.createdDocumentIds(workspaceId, userId, batch.id())))
                .toList();
    }

    /**
     * A batch with every row enriched for the detail screen -- read
     * set-based (see {@link BatchRepository#findRowDetails}) plus the
     * pre-check each row's current content would face, computed the same
     * way {@code ValidationService} computes it but without a render.
     */
    public BatchDetail find(long workspaceId, long userId, long batchId) {
        Batch batch = requireBatch(workspaceId, userId, batchId);
        List<BatchRowDetail> details = batches.findRowDetails(workspaceId, userId, batchId);
        Map<String, FieldDefinition> fields = fieldsById(pinnedVersion(workspaceId, userId, batch));
        List<RuleRevision> acceptedRules = acceptedRules(workspaceId, userId, batch);
        List<EnrichedRow> rows = new ArrayList<>(details.size());
        for (BatchRowDetail detail : details) {
            rows.add(new EnrichedRow(detail, preCheckBlocking(detail, fields, acceptedRules)));
        }
        return new BatchDetail(batch, rows);
    }

    /**
     * Runs the ACCEPT step: every row is evaluated each call (a verdict
     * is cheap once the set-based detail is loaded) but at most {@link
     * #ACCEPT_ROW_BUDGET} rows get their review revision per call. A row
     * that is not in the exact shape an unreviewed accept requires is
     * answered SKIPPED with the code that says why, and does not count
     * against the budget.
     */
    public BatchAcceptResult accept(long workspaceId, long userId, long batchId) {
        Batch batch = requireBatch(workspaceId, userId, batchId);
        UUID token = acquire(workspaceId, userId, batch, BatchStepKind.ACCEPT);
        try {
            return acceptUnderLease(workspaceId, userId, batch, token);
        } finally {
            batches.releaseStepLease(workspaceId, userId, batchId, token);
        }
    }

    private BatchAcceptResult acceptUnderLease(long workspaceId, long userId, Batch batch, UUID token) {
        Map<String, FieldDefinition> fields = fieldsById(pinnedVersion(workspaceId, userId, batch));
        List<RuleRevision> acceptedRules = acceptedRules(workspaceId, userId, batch);
        List<BatchRowDetail> details = batches.findRowDetails(workspaceId, userId, batch.id());

        List<AcceptRowResult> results = new ArrayList<>();
        int accepted = 0;
        int remaining = 0;
        Instant deadline = Instant.now().plus(ACCEPT_TIME_BUDGET);
        for (BatchRowDetail detail : details) {
            AcceptSkipCode skip = acceptSkip(detail, fields, acceptedRules);
            if (skip != null) {
                results.add(new AcceptRowResult(detail.row(), false, skip));
                continue;
            }
            if (accepted >= ACCEPT_ROW_BUDGET || Instant.now().isAfter(deadline)) {
                remaining++;
                continue;
            }
            long revisionId = detail.currentRevisionId();
            revisions.recordReviewDecisions(
                    workspaceId,
                    userId,
                    acceptKey(batch.id(), detail.row().rowNumber(), revisionId),
                    stepHash("ACCEPT", batch, detail.row().rowNumber(), revisionId),
                    detail.row().documentId(),
                    revisionId,
                    allAccepted(detail),
                    "Accepted with the rest of the batch.");
            results.add(new AcceptRowResult(
                    rowAfter(workspaceId, userId, batch.id(), detail.row().rowNumber()), true, null));
            accepted++;
            if (!batches.refreshStepLease(workspaceId, userId, batch.id(), token, STEP_LEASE_TTL)) {
                remaining += countAcceptableAfter(details, detail.row().rowNumber(), fields, acceptedRules);
                break;
            }
        }
        return new BatchAcceptResult(accepted, remaining, null, results);
    }

    /** Rows still accept-eligible once the step stopped: the loop's own evaluation, continued. */
    private int countAcceptableAfter(
            List<BatchRowDetail> details, int afterRow, Map<String, FieldDefinition> fields, List<RuleRevision> acceptedRules) {
        int count = 0;
        for (BatchRowDetail detail : details) {
            if (detail.row().rowNumber() > afterRow && acceptSkip(detail, fields, acceptedRules) == null) {
                count++;
            }
        }
        return count;
    }

    /**
     * Why a row is not accepted, or null when it is. Order matters: a row
     * that is already fully reviewed answers ALREADY_ACCEPTED before a
     * suggestion or a problem it may also carry is even looked at.
     */
    private AcceptSkipCode acceptSkip(
            BatchRowDetail detail, Map<String, FieldDefinition> fields, List<RuleRevision> acceptedRules) {
        if (detail.row().state() != BatchRowState.CREATED) {
            return AcceptSkipCode.NOT_CREATED;
        }
        if (!detail.documentPresent() || detail.currentRevisionId() == null) {
            return AcceptSkipCode.DOCUMENT_GONE;
        }
        if (isAccepted(detail)) {
            return AcceptSkipCode.ALREADY_ACCEPTED;
        }
        if (detail.currentContent() == null || detail.currentContent().fields().isEmpty()) {
            // A document with no content can never carry a reviewable
            // item, so accepting it could never finish: answer the nearest
            // honest verdict rather than leave it eligible forever.
            return AcceptSkipCode.BLOCKING;
        }
        if (detail.proposalWaiting()) {
            return AcceptSkipCode.SUGGESTION_WAITING;
        }
        if (hasAiAuthorship(detail)) {
            return AcceptSkipCode.AI_COMPOSED;
        }
        if (preCheckBlocking(detail, fields, acceptedRules) > 0) {
            return AcceptSkipCode.BLOCKING;
        }
        if (detail.manifestFindings().stream().anyMatch(f -> f.severity() == ValidationSeverity.BLOCKING)) {
            return AcceptSkipCode.BLOCKING;
        }
        if (!detail.row().problems().isEmpty()
                && Objects.equals(detail.currentContentHash(), detail.initialContentHash())) {
            return AcceptSkipCode.HAS_PROBLEMS;
        }
        return null;
    }

    /** Every item reviewed ACCEPTED on a non-empty document -- the GET row's accepted flag and the accept idempotence check share it. */
    public static boolean isAccepted(BatchRowDetail detail) {
        DocumentContent content = detail.currentContent();
        if (content == null || content.fields().isEmpty() || detail.fieldStates().isEmpty()) {
            return false;
        }
        return detail.fieldStates().values().stream().allMatch(state -> state.review() == ReviewState.ACCEPTED);
    }

    /** Any item a model had a hand in must be looked at by a person, so a batch accept never touches it. */
    private static boolean hasAiAuthorship(BatchRowDetail detail) {
        return detail.fieldStates().values().stream()
                .anyMatch(state -> state.authorship() == Authorship.AI_COMPOSED || state.authorship() == Authorship.MIXED);
    }

    private Map<FieldItemRef, ReviewState> allAccepted(BatchRowDetail detail) {
        Map<FieldItemRef, ReviewState> decisions = new LinkedHashMap<>();
        detail.fieldStates().keySet().forEach(ref -> decisions.put(ref, ReviewState.ACCEPTED));
        return decisions;
    }

    /**
     * Runs the CHECK step: every CREATED row whose document is there and
     * whose current revision no manifest covers gets one validation run,
     * in row order, inside the check budget. Render capacity running out
     * ends the step early with {@code retryAfterMs}; the row it was on
     * stays unchecked and is not a failure.
     */
    public BatchStepResult check(long workspaceId, long userId, long batchId) {
        Batch batch = requireBatch(workspaceId, userId, batchId);
        UUID token = acquire(workspaceId, userId, batch, BatchStepKind.CHECK);
        try {
            return checkUnderLease(workspaceId, userId, batch, token);
        } finally {
            batches.releaseStepLease(workspaceId, userId, batchId, token);
        }
    }

    private BatchStepResult checkUnderLease(long workspaceId, long userId, Batch batch, UUID token) {
        pinnedVersion(workspaceId, userId, batch);
        List<BatchRowDetail> details = batches.findRowDetails(workspaceId, userId, batch.id());
        List<BatchRow> updated = new ArrayList<>();
        int processed = 0;
        Long retryAfterMs = null;
        Instant deadline = Instant.now().plus(CHECK_TIME_BUDGET);
        for (BatchRowDetail detail : details) {
            if (processed >= CHECK_ROW_BUDGET || Instant.now().isAfter(deadline)) {
                break;
            }
            if (!needsCheck(detail)) {
                continue;
            }
            long revisionId = detail.currentRevisionId();
            try {
                validations.validate(
                        workspaceId,
                        userId,
                        checkKey(batch.id(), detail.row().rowNumber(), revisionId),
                        stepHash("CHECK", batch, detail.row().rowNumber(), revisionId),
                        detail.row().documentId(),
                        revisionId);
            } catch (RenderCapacityExceededException capacity) {
                retryAfterMs = RENDER_RETRY_AFTER_MS;
                break;
            } catch (DocumentNotFoundException gone) {
                // The document left between the set-read and this row; the next read sees it gone too.
                continue;
            }
            updated.add(detail.row());
            processed++;
            if (!batches.refreshStepLease(workspaceId, userId, batch.id(), token, STEP_LEASE_TTL)) {
                break;
            }
        }
        int remaining = batches.countUncheckedRows(workspaceId, userId, batch.id());
        return new BatchStepResult(processed, remaining, retryAfterMs, updated);
    }

    private static boolean needsCheck(BatchRowDetail detail) {
        return detail.row().state() == BatchRowState.CREATED
                && detail.documentPresent()
                && detail.currentRevisionId() != null
                && detail.manifestId() == null;
    }

    /**
     * Runs the COMPOSE step: every CREATED row whose compose still waits
     * gets one bounded model call from its notes, and a usable reply
     * becomes a proposal a person reviews -- never a direct write. A row
     * with no notes at all ends NOT_NEEDED; one whose reply cannot be
     * trusted ends FAILED with the code; the allowance running out marks
     * the row NO_ALLOWANCE and stops the step, since the rows behind it
     * would answer the same.
     */
    public BatchStepResult compose(long workspaceId, long userId, long batchId) throws IOException {
        Batch batch = requireBatch(workspaceId, userId, batchId);
        if (!composeOffered) {
            throw new BatchComposeUnavailableException("This deployment does not offer compose in a batch.");
        }
        UUID token = acquire(workspaceId, userId, batch, BatchStepKind.COMPOSE);
        try {
            return composeUnderLease(workspaceId, userId, batch, token);
        } finally {
            batches.releaseStepLease(workspaceId, userId, batchId, token);
        }
    }

    private BatchStepResult composeUnderLease(long workspaceId, long userId, Batch batch, UUID token)
            throws IOException {
        Template template = requirePinnedTemplate(workspaceId, userId, batch);
        Map<String, FieldDefinition> fields = fieldsById(pinnedVersion(workspaceId, userId, batch));
        List<RuleRevision> acceptedRules = acceptedRules(workspaceId, userId, batch);
        TableReading table = rereadTable(workspaceId, userId, batch);
        Map<Integer, TableRow> tableRows = rowsByNumber(table);
        List<BatchRowDetail> details = batches.findRowDetails(workspaceId, userId, batch.id());

        List<BatchRow> updated = new ArrayList<>();
        int processed = 0;
        Instant deadline = Instant.now().plus(COMPOSE_TIME_BUDGET);
        for (BatchRowDetail detail : details) {
            if (processed >= COMPOSE_ROW_BUDGET || Instant.now().isAfter(deadline)) {
                break;
            }
            if (detail.row().state() != BatchRowState.CREATED
                    || detail.row().composeState() != ComposeState.PENDING
                    || !detail.documentPresent()) {
                continue;
            }
            TableRow tableRow = tableRows.get(detail.row().rowNumber());
            if (tableRow == null) {
                throw new BatchReaderChangedException();
            }
            ComposeStep step = composeRow(workspaceId, userId, batch, template, fields, acceptedRules, detail, tableRow);
            if (step == ComposeStep.STOP) {
                break;
            }
            updated.add(rowAfter(workspaceId, userId, batch.id(), detail.row().rowNumber()));
            processed++;
            if (!batches.refreshStepLease(workspaceId, userId, batch.id(), token, STEP_LEASE_TTL)) {
                break;
            }
        }
        int remaining = batches.countComposePendingRows(workspaceId, userId, batch.id());
        return new BatchStepResult(processed, remaining, null, updated);
    }

    private enum ComposeStep { DONE, STOP }

    /**
     * One row's compose: notes into one bounded request, the strict reply
     * into cleaned texts, the texts into a proposal. Every resolution --
     * NOT_NEEDED, PROPOSED, FAILED -- is written on the row before the
     * step moves on; only NO_ALLOWANCE stops the whole step.
     */
    private ComposeStep composeRow(
            long workspaceId, long userId, Batch batch, Template template,
            Map<String, FieldDefinition> fields, List<RuleRevision> acceptedRules,
            BatchRowDetail detail, TableRow tableRow) {
        List<BatchComposePromptBuilder.Place> places = new ArrayList<>();
        for (FieldMapping mapping : batch.mapping()) {
            if (mapping.mode() != FieldMappingMode.COMPOSE) {
                continue;
            }
            FieldDefinition field = fields.get(mapping.fieldId());
            if (field == null) {
                continue;
            }
            String notes = cellText(tableRow, mapping.column());
            places.add(new BatchComposePromptBuilder.Place(mapping.fieldId(), fieldLabel(field), notes));
        }
        if (places.isEmpty() || places.stream().allMatch(place -> place.notes().isBlank())) {
            markComposeResolved(workspaceId, userId, batch, detail, ComposeState.NOT_NEEDED, null, List.of());
            return ComposeStep.DONE;
        }

        List<BatchComposePromptBuilder.ContextValue> context = new ArrayList<>();
        for (FieldMapping mapping : batch.mapping()) {
            if (mapping.mode() == FieldMappingMode.VALUE) {
                String value = cellText(tableRow, mapping.column()).trim();
                FieldDefinition field = fields.get(mapping.fieldId());
                if (!value.isEmpty() && field != null) {
                    context.add(new BatchComposePromptBuilder.ContextValue(fieldLabel(field), value));
                }
            }
        }

        Set<String> expectedIds = new LinkedHashSet<>();
        places.forEach(place -> expectedIds.add(place.fieldId()));
        ModelRequest request;
        try {
            request = BatchComposePromptBuilder.build(template.displayName(), context, places);
        } catch (IllegalArgumentException unsafe) {
            // A field id that cannot be inlined safely resolves the row, not the step.
            markComposeResolved(
                    workspaceId, userId, batch, detail, ComposeState.FAILED, null,
                    List.of(new BatchRowProblem(BatchRowProblemCode.RESPONSE_UNREADABLE, null, null)));
            return ComposeStep.DONE;
        }
        BatchComposer.Outcome outcome = composer.compose(workspaceId, userId, request, expectedIds);

        switch (outcome.status()) {
            case NO_ALLOWANCE -> {
                markComposeResolved(workspaceId, userId, batch, detail, ComposeState.NO_ALLOWANCE, null, List.of());
                return ComposeStep.STOP;
            }
            case FAILED -> {
                markComposeResolved(
                        workspaceId, userId, batch, detail, ComposeState.FAILED, null,
                        List.of(new BatchRowProblem(outcome.failureCode(), null, null)));
                return ComposeStep.DONE;
            }
            case COMPOSED -> {
                Map<String, FieldValue> proposed = new LinkedHashMap<>();
                for (Map.Entry<String, String> entry : outcome.texts().entrySet()) {
                    String cleaned = cleanComposedText(entry.getValue());
                    int limit = maxComposedLength(acceptedRules, entry.getKey());
                    if (cleaned.isEmpty() || cleaned.length() > Math.min(limit, MAX_COMPOSED_TEXT_CHARS)) {
                        continue;
                    }
                    proposed.put(entry.getKey(), new FieldValue.TextValue(cleaned));
                }
                if (proposed.isEmpty()) {
                    markComposeResolved(
                            workspaceId, userId, batch, detail, ComposeState.FAILED, null,
                            List.of(new BatchRowProblem(BatchRowProblemCode.NOTHING_COMPOSED, null, null)));
                    return ComposeStep.DONE;
                }
                try {
                    PatchProposal proposal = revisions.proposePatch(
                            workspaceId, userId, detail.row().documentId(), detail.currentRevisionId(),
                            proposed, Map.of());
                    markComposeResolved(
                            workspaceId, userId, batch, detail, ComposeState.PROPOSED, proposal.id(), List.of());
                } catch (DocumentNotFoundException gone) {
                    markComposeResolved(
                            workspaceId, userId, batch, detail, ComposeState.FAILED, null,
                            List.of(new BatchRowProblem(BatchRowProblemCode.DOCUMENT_GONE, null, null)));
                }
                return ComposeStep.DONE;
            }
        }
        return ComposeStep.DONE;
    }

    /**
     * A compose resolution on the row, or a step-ending move: false from
     * the repository means another step resolved it first, so the row is
     * re-read and the step stops rather than writing over someone else's
     * resolution.
     */
    private void markComposeResolved(
            long workspaceId, long userId, Batch batch, BatchRowDetail detail,
            ComposeState state, Long proposalId, List<BatchRowProblem> problems) {
        if (!batches.markRowComposeResolved(
                workspaceId, userId, batch.id(), detail.row().rowNumber(), state, proposalId, problems)) {
            throw new IllegalStateException(
                    "Batch " + batch.id() + " row " + detail.row().rowNumber() + " left compose-PENDING under the step.");
        }
    }

    private static String fieldLabel(FieldDefinition field) {
        return field.label() == null || field.label().isBlank() ? field.fieldId() : field.label();
    }

    private static String cellText(TableRow row, int column) {
        return column < row.cells().size() ? row.cells().get(column).display() : "";
    }

    /** One line: every run of whitespace becomes a single space, so a composed text cannot smuggle structure. */
    private static String cleanComposedText(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    /** The field's own accepted MaxTextLength rule where one exists; otherwise the compose-wide bound. */
    private static int maxComposedLength(List<RuleRevision> acceptedRules, String fieldId) {
        for (RuleRevision rule : acceptedRules) {
            if (rule.payload() instanceof RulePayload.MaxTextLength(String ruleFieldId, int maxCharacters)
                    && ruleFieldId.equals(fieldId)) {
                return maxCharacters;
            }
        }
        return MAX_COMPOSED_TEXT_CHARS;
    }

    /**
     * Makes an export run: one item per row, PENDING for every row with a
     * live document (with the content hash approval binds), a final
     * outcome for the rest. A PDF template exports PDF and nothing else.
     * The same idempotency key answers with the same run; a different
     * format under it is a conflict, exactly as a batch's key is.
     */
    public BatchExportDetail createExport(long workspaceId, long userId, long batchId, BatchExportCreateRequest request) {
        Batch batch = requireBatch(workspaceId, userId, batchId);
        String requestHash = exportRequestHash(batch.id(), request.format());
        Optional<BatchExport> earlier =
                batches.findExportByIdempotencyKey(workspaceId, userId, batchId, request.idempotencyKey());
        if (earlier.isPresent()) {
            if (earlier.get().requestHash().equals(requestHash)) {
                return new BatchExportDetail(
                        earlier.get(), batches.findExportItems(workspaceId, userId, earlier.get().id()));
            }
            throw new BatchIdempotencyConflictException();
        }
        TemplateVersion version = pinnedVersion(workspaceId, userId, batch);
        if (version.kind() == TemplateKind.PDF && request.format() != ExportFormat.PDF) {
            throw new ExportFormatNotOfferedException(request.format());
        }
        List<BatchRowDetail> details = batches.findRowDetails(workspaceId, userId, batchId);
        List<NewBatchExportItem> items = new ArrayList<>(details.size());
        for (BatchRowDetail detail : details) {
            if (detail.row().state() == BatchRowState.CREATED && detail.documentPresent() && detail.currentContentHash() != null) {
                items.add(new NewBatchExportItem(
                        detail.row().rowNumber(), BatchExportItemOutcome.PENDING, detail.currentContentHash()));
            } else if (detail.row().state() == BatchRowState.CREATED || detail.row().state() == BatchRowState.DOCUMENT_DELETED) {
                items.add(new NewBatchExportItem(detail.row().rowNumber(), BatchExportItemOutcome.DOCUMENT_GONE, null));
            } else {
                items.add(new NewBatchExportItem(detail.row().rowNumber(), BatchExportItemOutcome.NOT_CREATED, null));
            }
        }
        BatchExport export = batches.createExport(
                workspaceId,
                new NewBatchExport(
                        batchId, request.format(), BatchExportState.RUNNING,
                        request.idempotencyKey(), requestHash, userId),
                items);
        return new BatchExportDetail(export, batches.findExportItems(workspaceId, userId, export.id()));
    }

    /**
     * Runs the EXPORT step: pending items in row order, each resolved to a
     * receipt or a named outcome, inside the export budget. A document
     * that changed since the run was made is CHANGED, never shipped as if
     * it were the approved bytes; render capacity running out leaves the
     * item PENDING and hands the caller {@code retryAfterMs}. When no
     * PENDING item is left the run is COMPLETE and its BATCH_EXPORTED
     * audit lands in the same transaction.
     */
    public BatchExportStepResult advanceExport(long workspaceId, long userId, long batchId, long exportId) {
        Batch batch = requireBatch(workspaceId, userId, batchId);
        BatchExport export = requireExport(workspaceId, userId, batchId, exportId);
        UUID token = acquire(workspaceId, userId, batch, BatchStepKind.EXPORT);
        try {
            return advanceExportUnderLease(workspaceId, userId, batch, export, token);
        } finally {
            batches.releaseStepLease(workspaceId, userId, batchId, token);
        }
    }

    private BatchExportStepResult advanceExportUnderLease(
            long workspaceId, long userId, Batch batch, BatchExport export, UUID token) {
        if (export.state() == BatchExportState.COMPLETE) {
            return new BatchExportStepResult(0, 0, null, BatchExportState.COMPLETE, List.of());
        }
        pinnedVersion(workspaceId, userId, batch);
        Map<Integer, BatchRowDetail> details = new LinkedHashMap<>();
        for (BatchRowDetail detail : batches.findRowDetails(workspaceId, userId, batch.id())) {
            details.put(detail.row().rowNumber(), detail);
        }

        List<BatchExportItem> updated = new ArrayList<>();
        int processed = 0;
        Long retryAfterMs = null;
        Instant deadline = Instant.now().plus(EXPORT_TIME_BUDGET);
        for (BatchExportItem item : batches.findPendingExportItems(workspaceId, userId, export.id())) {
            if (processed >= EXPORT_ROW_BUDGET || Instant.now().isAfter(deadline)) {
                break;
            }
            ItemOutcome outcome;
            try {
                outcome = advanceItem(workspaceId, userId, batch, export, item, details.get(item.rowNumber()));
            } catch (RenderCapacityExceededException capacity) {
                retryAfterMs = RENDER_RETRY_AFTER_MS;
                break;
            }
            if (!batches.updateExportItem(
                    workspaceId, userId, export.id(), item.rowNumber(),
                    outcome.outcome(), outcome.receiptId(), outcome.detail())) {
                break;
            }
            updated.add(new BatchExportItem(
                    item.batchExportId(), item.rowNumber(), outcome.outcome(), outcome.receiptId(),
                    outcome.detail(), item.approvedContentHash()));
            processed++;
            if (!batches.refreshStepLease(workspaceId, userId, batch.id(), token, STEP_LEASE_TTL)) {
                break;
            }
        }
        int remaining = batches.countPendingExportItems(workspaceId, userId, export.id());
        BatchExportState state = export.state();
        if (remaining == 0) {
            batches.completeExport(workspaceId, userId, batch.id(), export.id());
            state = BatchExportState.COMPLETE;
        }
        return new BatchExportStepResult(processed, remaining, retryAfterMs, state, updated);
    }

    private record ItemOutcome(BatchExportItemOutcome outcome, Long receiptId, String detail) {

        static ItemOutcome of(BatchExportItemOutcome outcome) {
            return new ItemOutcome(outcome, null, null);
        }

        static ItemOutcome of(BatchExportItemOutcome outcome, String detail) {
            return new ItemOutcome(outcome, null, detail);
        }

        static ItemOutcome exported(long receiptId) {
            return new ItemOutcome(BatchExportItemOutcome.EXPORTED, receiptId, null);
        }
    }

    /**
     * One item to its outcome. The checks run in approval order: the
     * document still there and still the approved bytes first, then the
     * things a person still owes (a suggestion, a check), then export
     * itself. An unexpected failure is the item's FAILED, never the
     * step's -- the step moves to the next row and reports it.
     */
    private ItemOutcome advanceItem(
            long workspaceId, long userId, Batch batch, BatchExport export,
            BatchExportItem item, BatchRowDetail detail) {
        try {
            if (detail == null || detail.row().state() != BatchRowState.CREATED || !detail.documentPresent()) {
                return ItemOutcome.of(BatchExportItemOutcome.DOCUMENT_GONE);
            }
            if (!Objects.equals(detail.currentContentHash(), item.approvedContentHash())) {
                return ItemOutcome.of(BatchExportItemOutcome.CHANGED, "CHANGED_SINCE_APPROVAL");
            }
            if (detail.proposalWaiting()) {
                return ItemOutcome.of(BatchExportItemOutcome.SUGGESTION_WAITING);
            }

            long revisionId = detail.currentRevisionId();
            Long manifestId = detail.manifestId();
            List<ValidationFinding> findings = detail.manifestFindings();
            if (manifestId == null) {
                ValidationManifest manifest = validations.validate(
                        workspaceId,
                        userId,
                        checkKey(batch.id(), item.rowNumber(), revisionId),
                        stepHash("CHECK", batch, item.rowNumber(), revisionId),
                        detail.row().documentId(),
                        revisionId);
                manifestId = manifest.id();
                findings = manifest.findings();
                revisionId = manifest.revisionId();
            }
            Optional<ValidationFinding> blocking = findings.stream()
                    .filter(f -> f.severity() == ValidationSeverity.BLOCKING)
                    .findFirst();
            if (blocking.isPresent()) {
                ValidationFinding finding = blocking.get();
                return ItemOutcome.of(
                        BatchExportItemOutcome.BLOCKED,
                        finding.fieldId() == null
                                ? finding.code().name()
                                : finding.code().name() + ":" + finding.fieldId());
            }

            ExportReceipt receipt = reusableReceipt(workspaceId, userId, detail, export.format(), manifestId, revisionId);
            if (receipt == null) {
                exports.approve(workspaceId, userId, detail.row().documentId(), manifestId, export.format());
                receipt = exports.export(workspaceId, userId, detail.row().documentId());
            }
            return ItemOutcome.exported(receipt.id());
        } catch (DocumentNotFoundException gone) {
            return ItemOutcome.of(BatchExportItemOutcome.DOCUMENT_GONE);
        } catch (RenderCapacityExceededException capacity) {
            throw capacity;
        } catch (StaleExportApprovalException | DocumentRevisionConflictException stale) {
            // The document moved on between this step's set-read and its
            // approval or export: the same story CHANGED_SINCE_APPROVAL
            // tells when the hash check itself catches it first.
            return ItemOutcome.of(BatchExportItemOutcome.CHANGED, "CHANGED_SINCE_APPROVAL");
        } catch (ExportArtifactIntegrityException integrity) {
            return ItemOutcome.of(BatchExportItemOutcome.FAILED, "ARTIFACT_INTEGRITY");
        } catch (ExportNotApprovedException notApproved) {
            return ItemOutcome.of(BatchExportItemOutcome.FAILED, "EXPORT_NOT_APPROVED");
        } catch (RuntimeException unexpected) {
            log.warn(
                    "Export of batch {} row {} failed unexpectedly; the item is FAILED.",
                    batch.id(), item.rowNumber(), unexpected);
            return ItemOutcome.of(BatchExportItemOutcome.FAILED, "EXPORT_FAILED");
        }
    }

    /**
     * A receipt the document already holds for this exact revision,
     * manifest and format: the second leg of a resumed advance lands here
     * instead of approving again, so one document never gets two receipts
     * for the same export.
     */
    private ExportReceipt reusableReceipt(
            long workspaceId, long userId, BatchRowDetail detail, ExportFormat format,
            long manifestId, long revisionId) {
        Optional<ExportReceipt> latest =
                exports.findLatestReceipt(workspaceId, userId, detail.row().documentId());
        return latest
                .filter(receipt -> receipt.revisionId() == revisionId
                        && receipt.validationManifestId() == manifestId
                        && receipt.format() == format)
                .orElse(null);
    }

    public BatchExportDetail findExport(long workspaceId, long userId, long batchId, long exportId) {
        requireBatch(workspaceId, userId, batchId);
        BatchExport export = requireExport(workspaceId, userId, batchId, exportId);
        return new BatchExportDetail(export, batches.findExportItems(workspaceId, userId, exportId));
    }

    /**
     * The plan a download writes: a safe, unique entry per exported file
     * and one CSV row per item. Refused while the run is RUNNING or has
     * nothing exported -- there is no archive to stream either way.
     */
    public BatchExportZip prepareExportZip(long workspaceId, long userId, long batchId, long exportId) {
        Batch batch = requireBatch(workspaceId, userId, batchId);
        BatchExport export = requireExport(workspaceId, userId, batchId, exportId);
        if (export.state() != BatchExportState.COMPLETE) {
            throw new BatchExportNotReadyException(export);
        }
        List<ExportDownloadRow> rows = batches.findExportDownloadRows(workspaceId, userId, exportId);
        List<BatchExportZip.Entry> entries = new ArrayList<>();
        List<List<String>> csvRows = new ArrayList<>();
        Set<String> used = new LinkedHashSet<>();
        for (ExportDownloadRow row : rows) {
            String wordFile = null;
            String pdfFile = null;
            String wordSha = null;
            String pdfSha = null;
            if (row.outcome() == BatchExportItemOutcome.EXPORTED && row.receipt() != null) {
                ExportReceipt receipt = row.receipt();
                // A receipt on a Word template always names the Word file
                // the run rendered from; it still ships only in the format
                // the person asked this export for.
                if (receipt.docxArtifactId() != null && export.format() != ExportFormat.PDF) {
                    wordFile = uniqueEntryName(used, row.rowNumber(), row.documentTitle(), "docx");
                    entries.add(new BatchExportZip.Entry(wordFile, receipt.docxArtifactId(), receipt.docxSha256()));
                    wordSha = receipt.docxSha256();
                }
                if (receipt.pdfArtifactId() != null && export.format() != ExportFormat.DOCX) {
                    pdfFile = uniqueEntryName(used, row.rowNumber(), row.documentTitle(), "pdf");
                    entries.add(new BatchExportZip.Entry(pdfFile, receipt.pdfArtifactId(), receipt.pdfSha256()));
                    pdfSha = receipt.pdfSha256();
                }
            }
            csvRows.add(List.of(
                    String.valueOf(row.rowNumber()),
                    row.documentTitle() == null ? "" : row.documentTitle(),
                    row.outcome().name(),
                    wordFile == null ? "" : wordFile,
                    pdfFile == null ? "" : pdfFile,
                    wordSha == null ? "" : wordSha,
                    pdfSha == null ? "" : pdfSha,
                    row.detail() == null ? "" : row.detail()));
        }
        if (entries.isEmpty()) {
            throw new BatchExportNotReadyException(export);
        }
        return new BatchExportZip(batch.title(), entries, csvRows);
    }

    /** Streams the planned archive: every file read whole, proven against its receipt's hash, then written. */
    public void writeExportZip(long workspaceId, long userId, BatchExportZip zip, OutputStream out) throws IOException {
        BatchZipWriter.write(zip, artifactId -> readArtifactBytes(workspaceId, userId, artifactId), out);
    }

    private byte[] readArtifactBytes(long workspaceId, long userId, long artifactId) throws IOException {
        try (ReadableArtifact artifact = artifacts.openContent(workspaceId, userId, artifactId)) {
            return artifact.content().readAllBytes();
        }
    }

    /**
     * Deletes the batch and its rows; the documents it made are not the
     * batch's and stay. A batch a step still holds is busy, not gone.
     */
    public void delete(long workspaceId, long userId, long batchId) {
        switch (batches.delete(workspaceId, userId, batchId)) {
            case DELETED -> { }
            case BUSY -> throw new BatchBusyException();
            case NOT_FOUND -> throw new BatchNotFoundException(batchId);
        }
    }

    /** A batch with every one of its rows enriched, for the detail route. */
    public record BatchDetail(Batch batch, List<EnrichedRow> rows) {

        public BatchDetail {
            rows = List.copyOf(rows);
        }
    }

    /** A row's set-loaded detail plus the count of findings its current content would collect before a render ever ran. */
    public record EnrichedRow(BatchRowDetail detail, int preCheckBlocking) {
    }

    private Batch requireBatch(long workspaceId, long userId, long batchId) {
        return batches.find(workspaceId, userId, batchId)
                .orElseThrow(() -> new BatchNotFoundException(batchId));
    }

    private BatchExport requireExport(long workspaceId, long userId, long batchId, long exportId) {
        return batches.findExport(workspaceId, userId, batchId, exportId)
                .orElseThrow(() -> new BatchExportNotFoundException(batchId, exportId));
    }

    private UUID acquire(long workspaceId, long userId, Batch batch, BatchStepKind kind) {
        UUID token = batches.acquireStepLease(workspaceId, userId, batch.id(), kind, STEP_LEASE_TTL);
        if (token == null) {
            throw new BatchBusyException();
        }
        return token;
    }

    private TemplateVersion pinnedVersion(long workspaceId, long userId, Batch batch) {
        return templates.findVersion(workspaceId, userId, batch.templateId(), batch.templateVersionId())
                .orElseThrow(BatchTemplateUnavailableException::new);
    }

    private List<RuleRevision> acceptedRules(long workspaceId, long userId, Batch batch) {
        return rules.findByTemplateVersion(workspaceId, userId, batch.templateVersionId())
                .stream()
                .filter(rule -> rule.status() == RuleRevisionStatus.ACCEPTED)
                .toList();
    }

    /**
     * The template the batch pins, still there. Trash is a fill-only gate:
     * a trashed template makes no new documents, but documents a batch
     * already made are checked, accepted and exported like any other.
     */
    private Template requirePinnedTemplate(long workspaceId, long userId, Batch batch) {
        return templates.find(workspaceId, userId, batch.templateId())
                .orElseThrow(BatchTemplateUnavailableException::new);
    }

    /** The stored file re-read and proven unchanged -- the same fingerprint fill proves before it trusts a row's cells. */
    private TableReading rereadTable(long workspaceId, long userId, Batch batch) throws IOException {
        try (ReadableArtifact source = artifacts.openContent(workspaceId, userId, batch.sourceArtifactId())) {
            if (!Objects.equals(source.artifact().sha256(), batch.sourceSha256())) {
                throw new BatchSourceChangedException();
            }
            TableReading table = readTable(source.artifact(), source.content(), storedReadRequest(batch));
            if (!table.readerVersion().equals(batch.tableReaderVersion())) {
                throw new BatchReaderChangedException();
            }
            return table;
        }
    }

    private static Map<Integer, TableRow> rowsByNumber(TableReading table) {
        Map<Integer, TableRow> rows = new LinkedHashMap<>();
        for (TableRow row : table.rows()) {
            rows.put(row.rowNumber(), row);
        }
        return rows;
    }

    /** Blocking findings the document's own content would collect -- requiredness and the accepted content rules, with no render. */
    private int preCheckBlocking(
            BatchRowDetail detail, Map<String, FieldDefinition> fields, List<RuleRevision> acceptedRules) {
        if (detail.currentContent() == null) {
            return 0;
        }
        List<ValidationFinding> findings = new ArrayList<>();
        findings.addAll(DocumentValidator.checkRequiredness(detail.currentContent(), List.copyOf(fields.values()), acceptedRules));
        findings.addAll(DocumentValidator.checkContentRules(detail.currentContent(), acceptedRules));
        return (int) findings.stream().filter(f -> f.severity() == ValidationSeverity.BLOCKING).count();
    }

    /** The name inside the archive for one file: its row, then the document's title made safe for any filesystem. */
    static String uniqueEntryName(Set<String> used, int rowNumber, String title, String extension) {
        String base = String.format(java.util.Locale.ROOT, "%03d", rowNumber)
                + " " + BatchZipWriter.sanitizeComponent(title);
        String name = base + "." + extension;
        int suffix = 2;
        while (!used.add(name)) {
            name = base + " (" + suffix + ")." + extension;
            suffix++;
        }
        return name;
    }

    private static IdempotencyKey acceptKey(long batchId, int rowNumber, long revisionId) {
        return new IdempotencyKey("batch-" + batchId + "-row-" + rowNumber + "-accept-r" + revisionId);
    }

    private static IdempotencyKey checkKey(long batchId, int rowNumber, long revisionId) {
        return new IdempotencyKey("batch-" + batchId + "-row-" + rowNumber + "-check-r" + revisionId);
    }

    /** The hash a retried step row is remembered by: the step, the batch, the row, the pinned version and the exact revision. */
    private static CanonicalRequestHash stepHash(String step, Batch batch, int rowNumber, long revisionId) {
        return CanonicalRequestHash.sha256OfCanonicalText(
                step + "\0" + batch.id() + "\0" + rowNumber + "\0" + batch.templateVersionId() + "\0" + revisionId);
    }

    private static String exportRequestHash(long batchId, ExportFormat format) {
        return CanonicalRequestHash.sha256OfCanonicalText(
                "batch-export\0" + batchId + "\0" + format).value();
    }
}
