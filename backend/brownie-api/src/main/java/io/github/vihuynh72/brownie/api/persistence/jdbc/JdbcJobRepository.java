package io.github.vihuynh72.brownie.api.persistence.jdbc;

import io.github.vihuynh72.brownie.core.job.CancellationCommand;
import io.github.vihuynh72.brownie.core.job.CanonicalRequestHash;
import io.github.vihuynh72.brownie.core.job.CommandReceipt;
import io.github.vihuynh72.brownie.core.job.CommandReceiptStatus;
import io.github.vihuynh72.brownie.core.job.EnqueueJobCommand;
import io.github.vihuynh72.brownie.core.job.IdempotencyConflictException;
import io.github.vihuynh72.brownie.core.job.IdempotencyKey;
import io.github.vihuynh72.brownie.core.job.IdempotencyRecord;
import io.github.vihuynh72.brownie.core.job.InvalidJobTransitionException;
import io.github.vihuynh72.brownie.core.job.JobDeadlineExceededException;
import io.github.vihuynh72.brownie.core.job.Job;
import io.github.vihuynh72.brownie.core.job.JobCommandRepository;
import io.github.vihuynh72.brownie.core.job.JobCommandType;
import io.github.vihuynh72.brownie.core.job.JobEvent;
import io.github.vihuynh72.brownie.core.job.JobEventRepository;
import io.github.vihuynh72.brownie.core.job.JobEventType;
import io.github.vihuynh72.brownie.core.job.JobLease;
import io.github.vihuynh72.brownie.core.job.JobNotFoundException;
import io.github.vihuynh72.brownie.core.job.JobOutboxRepository;
import io.github.vihuynh72.brownie.core.job.JobStage;
import io.github.vihuynh72.brownie.core.job.JobState;
import io.github.vihuynh72.brownie.core.job.JobTarget;
import io.github.vihuynh72.brownie.core.job.JobTransitionValidator;
import io.github.vihuynh72.brownie.core.job.JobType;
import io.github.vihuynh72.brownie.core.job.OutboxEvent;
import io.github.vihuynh72.brownie.core.job.ResumeJobCommand;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Browser-context adapter for durable commands and metadata-only event reads.
 * Mutations set the tenant context first and write the receipt, job state,
 * event, and outbox record as one transaction.
 */
@Repository
class JdbcJobRepository implements JobCommandRepository, JobEventRepository, JobOutboxRepository {

    private static final String JOB_COLUMNS = "id, workspace_id, requested_by_user_id, job_type, resource_type, resource_id, "
            + "resource_version, stage, processing_configuration_hash, state, attempt_count, available_at, "
            + "deadline_at, cancellation_requested_at, fencing_token, lease_owner, lease_expires_at, created_at, updated_at";

    private static final String RECEIPT_COLUMNS = "command_id, workspace_id, actor_user_id, operation, job_id, request_hash, status, accepted_at";

    private static final String IDEMPOTENCY_COLUMNS = "id, workspace_id, actor_user_id, operation, idempotency_key, request_hash, command_id, created_at";

    private final JdbcTemplate jdbcTemplate;

    JdbcJobRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public CommandReceipt enqueue(long workspaceId, long actorUserId, EnqueueJobCommand command) {
        requireTenantIds(workspaceId, actorUserId);
        TenantContext.setCurrentUser(jdbcTemplate, actorUserId);

        IdempotencyReservation reservation = reserveIdempotency(
                workspaceId, actorUserId, JobCommandType.ENQUEUE, command.idempotencyKey(), command.requestHash());
        if (!reservation.created()) {
            return receiptFor(reservation.record().commandId());
        }

        JobInsertResult jobInsert = insertOrFindJob(workspaceId, actorUserId, command);
        if (jobInsert.created()) {
            long eventId = appendEvent(
                    workspaceId, jobInsert.job().id(), 1, JobEventType.QUEUED, JobState.QUEUED, "Job queued");
            appendOutbox(workspaceId, jobInsert.job().id(), eventId, JobEventType.QUEUED);
        }
        return createReceipt(reservation.record(), jobInsert.job().id());
    }

    @Override
    @Transactional
    public CommandReceipt requestCancellation(long workspaceId, long actorUserId, CancellationCommand command) {
        requireTenantIds(workspaceId, actorUserId);
        TenantContext.setCurrentUser(jdbcTemplate, actorUserId);

        IdempotencyReservation reservation = reserveIdempotency(
                workspaceId,
                actorUserId,
                JobCommandType.REQUEST_CANCELLATION,
                command.idempotencyKey(),
                command.requestHash());
        if (!reservation.created()) {
            return receiptFor(reservation.record().commandId());
        }

        Job job = lockJob(workspaceId, command.jobId());
        if (job.state() == JobState.CANCEL_REQUESTED) {
            return createReceipt(reservation.record(), job.id());
        }

        JobState nextState = switch (job.state()) {
            case QUEUED, WAITING_FOR_INPUT -> JobState.CANCELLED;
            case LEASED -> JobState.CANCEL_REQUESTED;
            default -> throw new InvalidJobTransitionException(job.state(), JobState.CANCELLED);
        };
        JobTransitionValidator.requireAllowed(job.state(), nextState);

        int changed = jdbcTemplate.update(
                """
                UPDATE job
                SET state = ?, cancellation_requested_at = COALESCE(cancellation_requested_at, now()), updated_at = now()
                WHERE workspace_id = ? AND id = ? AND state = ?
                """,
                nextState.name(),
                workspaceId,
                job.id(),
                job.state().name());
        if (changed != 1) {
            throw new IllegalStateException("Job " + job.id() + " changed while cancellation was being recorded.");
        }

        JobEventType eventType = nextState == JobState.CANCELLED
                ? JobEventType.CANCELLED
                : JobEventType.CANCELLATION_REQUESTED;
        long eventId = appendEvent(
                workspaceId,
                job.id(),
                nextSequence(job.id()),
                eventType,
                nextState,
                nextState == JobState.CANCELLED ? "Job cancelled" : "Cancellation requested");
        appendOutbox(workspaceId, job.id(), eventId, eventType);
        return createReceipt(reservation.record(), job.id());
    }

    @Override
    @Transactional(noRollbackFor = JobDeadlineExceededException.class)
    public CommandReceipt requestResume(long workspaceId, long actorUserId, ResumeJobCommand command) {
        requireTenantIds(workspaceId, actorUserId);
        TenantContext.setCurrentUser(jdbcTemplate, actorUserId);

        Optional<IdempotencyRecord> existing = findIdempotencyRecord(
                workspaceId, actorUserId, JobCommandType.REQUEST_RESUME, command.idempotencyKey());
        if (existing.isPresent()) {
            IdempotencyRecord record = existing.get();
            if (!record.requestHash().equals(command.requestHash())) {
                throw new IdempotencyConflictException(command.idempotencyKey(), JobCommandType.REQUEST_RESUME);
            }
            return receiptFor(record.commandId());
        }

        Job job = lockJob(workspaceId, command.jobId());
        Optional<IdempotencyRecord> recordCreatedWhileWaiting = findIdempotencyRecord(
                workspaceId, actorUserId, JobCommandType.REQUEST_RESUME, command.idempotencyKey());
        if (recordCreatedWhileWaiting.isPresent()) {
            IdempotencyRecord record = recordCreatedWhileWaiting.get();
            if (!record.requestHash().equals(command.requestHash())) {
                throw new IdempotencyConflictException(command.idempotencyKey(), JobCommandType.REQUEST_RESUME);
            }
            return receiptFor(record.commandId());
        }
        if (job.state() == JobState.WAITING_FOR_INPUT && !job.deadlineAt().isAfter(databaseNow())) {
            expireWaitingJob(workspaceId, job.id());
            throw new JobDeadlineExceededException(job.id());
        }

        JobTransitionValidator.requireAllowed(job.state(), JobState.QUEUED);
        if (job.state() != JobState.WAITING_FOR_INPUT) {
            throw new InvalidJobTransitionException(job.state(), JobState.QUEUED);
        }

        List<Job> resumed = jdbcTemplate.query(
                """
                UPDATE job
                SET state = 'QUEUED', updated_at = clock_timestamp()
                WHERE workspace_id = ?
                  AND id = ?
                  AND state = 'WAITING_FOR_INPUT'
                  AND deadline_at > clock_timestamp()
                RETURNING *
                """,
                this::mapJob,
                workspaceId,
                job.id());
        if (resumed.isEmpty()) {
            expireWaitingJob(workspaceId, job.id());
            throw new JobDeadlineExceededException(job.id());
        }

        IdempotencyReservation reservation = reserveIdempotency(
                workspaceId,
                actorUserId,
                JobCommandType.REQUEST_RESUME,
                command.idempotencyKey(),
                command.requestHash());
        if (!reservation.created()) {
            return receiptFor(reservation.record().commandId());
        }

        long eventId = appendEvent(
                workspaceId,
                job.id(),
                nextSequence(job.id()),
                JobEventType.RESUMED,
                JobState.QUEUED,
                "Job resumed");
        appendOutbox(workspaceId, job.id(), eventId, JobEventType.RESUMED);
        return createReceipt(reservation.record(), job.id());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Job> find(long workspaceId, long actorUserId, long jobId) {
        requireTenantIds(workspaceId, actorUserId);
        if (jobId <= 0) {
            throw new IllegalArgumentException("jobId must be positive.");
        }
        TenantContext.setCurrentUser(jdbcTemplate, actorUserId);
        return jdbcTemplate
                .query(
                        "SELECT " + JOB_COLUMNS + " FROM job WHERE workspace_id = ? AND id = ?",
                        this::mapJob,
                        workspaceId,
                        jobId)
                .stream()
                .findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CommandReceipt> findReceipt(long workspaceId, long actorUserId, UUID commandId) {
        requireTenantIds(workspaceId, actorUserId);
        if (commandId == null) {
            throw new IllegalArgumentException("commandId must not be null.");
        }
        TenantContext.setCurrentUser(jdbcTemplate, actorUserId);
        return jdbcTemplate
                .query(
                        "SELECT " + RECEIPT_COLUMNS + " FROM command_receipt WHERE workspace_id = ? AND command_id = ?",
                        this::mapReceipt,
                        workspaceId,
                        commandId)
                .stream()
                .findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public List<JobEvent> findAfter(long workspaceId, long actorUserId, long afterEventId, int limit) {
        requireTenantIds(workspaceId, actorUserId);
        if (afterEventId < 0) {
            throw new IllegalArgumentException("afterEventId must not be negative.");
        }
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100.");
        }
        TenantContext.setCurrentUser(jdbcTemplate, actorUserId);
        return jdbcTemplate.query(
                """
                SELECT e.id, e.workspace_id, e.job_id, e.sequence, e.event_type, e.state, e.safe_message,
                       e.progress_current, e.progress_total, e.created_at,
                       j.resource_type, j.resource_id, j.resource_version, j.stage
                FROM job_event e
                JOIN job j ON j.workspace_id = e.workspace_id AND j.id = e.job_id
                WHERE e.workspace_id = ? AND e.id > ?
                ORDER BY e.id
                LIMIT ?
                """,
                this::mapEvent,
                workspaceId,
                afterEventId,
                limit);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean exists(long workspaceId, long actorUserId, long eventId) {
        requireTenantIds(workspaceId, actorUserId);
        if (eventId <= 0) {
            throw new IllegalArgumentException("eventId must be positive.");
        }
        TenantContext.setCurrentUser(jdbcTemplate, actorUserId);
        Boolean exists = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM job_event WHERE workspace_id = ? AND id = ?)",
                Boolean.class,
                workspaceId,
                eventId);
        return Boolean.TRUE.equals(exists);
    }

    @Override
    @Transactional(readOnly = true)
    public List<OutboxEvent> findByJob(long workspaceId, long actorUserId, long jobId) {
        requireTenantIds(workspaceId, actorUserId);
        if (jobId <= 0) {
            throw new IllegalArgumentException("jobId must be positive.");
        }
        TenantContext.setCurrentUser(jdbcTemplate, actorUserId);
        return jdbcTemplate.query(
                """
                SELECT delivery_key, workspace_id, job_id, job_event_id, event_type, occurred_at, published_at
                FROM outbox_event
                WHERE workspace_id = ? AND job_id = ?
                ORDER BY occurred_at, delivery_key
                """,
                this::mapOutboxEvent,
                workspaceId,
                jobId);
    }

    private IdempotencyReservation reserveIdempotency(
            long workspaceId,
            long actorUserId,
            JobCommandType commandType,
            IdempotencyKey key,
            CanonicalRequestHash requestHash) {
        UUID candidateCommandId = UUID.randomUUID();
        List<IdempotencyRecord> inserted = jdbcTemplate.query(
                """
                INSERT INTO idempotency_record
                    (workspace_id, actor_user_id, operation, idempotency_key, request_hash, command_id)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (workspace_id, actor_user_id, operation, idempotency_key) DO NOTHING
                RETURNING id, workspace_id, actor_user_id, operation, idempotency_key, request_hash, command_id, created_at
                """,
                this::mapIdempotencyRecord,
                workspaceId,
                actorUserId,
                commandType.operation(),
                key.value(),
                requestHash.value(),
                candidateCommandId);
        if (!inserted.isEmpty()) {
            return new IdempotencyReservation(inserted.get(0), true);
        }

        IdempotencyRecord existing = findIdempotencyRecord(workspaceId, actorUserId, commandType, key)
                .orElseThrow(() -> new IllegalStateException("Idempotency record disappeared after its conflict was observed."));
        if (!existing.requestHash().equals(requestHash)) {
            throw new IdempotencyConflictException(key, commandType);
        }
        return new IdempotencyReservation(existing, false);
    }

    private Optional<IdempotencyRecord> findIdempotencyRecord(
            long workspaceId, long actorUserId, JobCommandType commandType, IdempotencyKey key) {
        return jdbcTemplate
                .query(
                        "SELECT " + IDEMPOTENCY_COLUMNS
                                + " FROM idempotency_record WHERE workspace_id = ? AND actor_user_id = ? "
                                + "AND operation = ? AND idempotency_key = ?",
                        this::mapIdempotencyRecord,
                        workspaceId,
                        actorUserId,
                        commandType.operation(),
                        key.value())
                .stream()
                .findFirst();
    }

    private void expireWaitingJob(long workspaceId, long jobId) {
        List<Job> expired = jdbcTemplate.query(
                """
                UPDATE job
                SET state = 'DEAD', updated_at = clock_timestamp()
                WHERE workspace_id = ?
                  AND id = ?
                  AND state = 'WAITING_FOR_INPUT'
                  AND deadline_at <= clock_timestamp()
                RETURNING *
                """,
                this::mapJob,
                workspaceId,
                jobId);
        if (expired.isEmpty()) {
            throw new IllegalStateException("Job " + jobId + " changed while its expired resume was being recorded.");
        }
        long eventId = appendEvent(
                workspaceId,
                jobId,
                nextSequence(jobId),
                JobEventType.RELEASED,
                JobState.DEAD,
                "Job deadline reached before it was resumed");
        appendOutbox(workspaceId, jobId, eventId, JobEventType.RELEASED);
    }

    private JobInsertResult insertOrFindJob(long workspaceId, long actorUserId, EnqueueJobCommand command) {
        List<Long> insertedIds = jdbcTemplate.query(
                """
                INSERT INTO job
                    (workspace_id, requested_by_user_id, job_type, resource_type, resource_id, resource_version,
                     stage, processing_configuration_hash, available_at, deadline_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (workspace_id, job_type, resource_type, resource_id, resource_version, stage, processing_configuration_hash)
                    DO NOTHING
                RETURNING id
                """,
                (rs, rowNum) -> rs.getLong("id"),
                workspaceId,
                actorUserId,
                command.jobType().value(),
                command.target().resourceType(),
                command.target().resourceId(),
                command.target().resourceVersion(),
                command.stage().value(),
                command.processingConfigurationHash().value(),
                command.availableAt(),
                command.deadlineAt());
        Job job = findLogicalJob(workspaceId, command)
                .orElseThrow(() -> new IllegalStateException("Job vanished after it was inserted or found."));
        return new JobInsertResult(job, !insertedIds.isEmpty());
    }

    private Optional<Job> findLogicalJob(long workspaceId, EnqueueJobCommand command) {
        return jdbcTemplate
                .query(
                        """
                        SELECT %s FROM job
                        WHERE workspace_id = ? AND job_type = ? AND resource_type = ? AND resource_id = ?
                          AND resource_version = ? AND stage = ? AND processing_configuration_hash = ?
                        """.formatted(JOB_COLUMNS),
                        this::mapJob,
                        workspaceId,
                        command.jobType().value(),
                        command.target().resourceType(),
                        command.target().resourceId(),
                        command.target().resourceVersion(),
                        command.stage().value(),
                        command.processingConfigurationHash().value())
                .stream()
                .findFirst();
    }

    private Job lockJob(long workspaceId, long jobId) {
        Optional<Job> locked = jdbcTemplate
                .query(
                        "SELECT " + JOB_COLUMNS + " FROM job WHERE workspace_id = ? AND id = ? FOR UPDATE",
                        this::mapJob,
                        workspaceId,
                        jobId)
                .stream()
                .findFirst();
        if (locked.isPresent()) {
            return locked.get();
        }

        // PostgreSQL applies UPDATE policies to SELECT FOR UPDATE. Terminal
        // jobs are still member-visible but deliberately fail that policy, so
        // read one without a lock to report an invalid transition rather than
        // misclassifying the member-visible job as absent.
        return jdbcTemplate
                .query(
                        "SELECT " + JOB_COLUMNS + " FROM job WHERE workspace_id = ? AND id = ?",
                        this::mapJob,
                        workspaceId,
                        jobId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new JobNotFoundException(jobId));
    }

    private CommandReceipt createReceipt(IdempotencyRecord record, long jobId) {
        jdbcTemplate.update(
                """
                INSERT INTO command_receipt
                    (command_id, workspace_id, actor_user_id, idempotency_record_id, job_id, operation, request_hash)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                record.commandId(),
                record.workspaceId(),
                record.actorUserId(),
                record.id(),
                jobId,
                record.commandType().operation(),
                record.requestHash().value());
        return receiptFor(record.commandId());
    }

    private CommandReceipt receiptFor(UUID commandId) {
        return jdbcTemplate
                .query(
                        "SELECT " + RECEIPT_COLUMNS + " FROM command_receipt WHERE command_id = ?",
                        this::mapReceipt,
                        commandId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Command receipt disappeared after it was persisted."));
    }

    private long appendEvent(
            long workspaceId,
            long jobId,
            long sequence,
            JobEventType eventType,
            JobState state,
            String safeMessage) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO job_event (workspace_id, job_id, sequence, event_type, state, safe_message)
                VALUES (?, ?, ?, ?, ?, ?)
                RETURNING id
                """,
                Long.class,
                workspaceId,
                jobId,
                sequence,
                eventType.name(),
                state.name(),
                safeMessage);
    }

    private long nextSequence(long jobId) {
        Long next = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(sequence), 0) + 1 FROM job_event WHERE job_id = ?", Long.class, jobId);
        if (next == null) {
            throw new IllegalStateException("Could not determine the next job event sequence.");
        }
        return next;
    }

    private void appendOutbox(long workspaceId, long jobId, long jobEventId, JobEventType eventType) {
        jdbcTemplate.update(
                """
                INSERT INTO outbox_event (delivery_key, workspace_id, job_id, job_event_id, event_type)
                VALUES (?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                workspaceId,
                jobId,
                jobEventId,
                eventType.name());
    }

    private OffsetDateTime databaseNow() {
        return jdbcTemplate.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class);
    }

    private Job mapJob(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        String leaseOwner = rs.getString("lease_owner");
        JobLease lease = leaseOwner == null
                ? null
                : new JobLease(leaseOwner, rs.getObject("lease_expires_at", OffsetDateTime.class));
        return new Job(
                rs.getLong("id"),
                rs.getLong("workspace_id"),
                rs.getLong("requested_by_user_id"),
                new JobType(rs.getString("job_type")),
                new JobTarget(
                        rs.getString("resource_type"), rs.getLong("resource_id"), rs.getLong("resource_version")),
                new JobStage(rs.getString("stage")),
                new CanonicalRequestHash(rs.getString("processing_configuration_hash")),
                JobState.valueOf(rs.getString("state")),
                rs.getInt("attempt_count"),
                rs.getObject("available_at", OffsetDateTime.class),
                rs.getObject("deadline_at", OffsetDateTime.class),
                rs.getObject("cancellation_requested_at", OffsetDateTime.class),
                rs.getLong("fencing_token"),
                lease,
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    private CommandReceipt mapReceipt(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new CommandReceipt(
                rs.getObject("command_id", UUID.class),
                rs.getLong("workspace_id"),
                rs.getLong("actor_user_id"),
                commandType(rs.getString("operation")),
                rs.getLong("job_id"),
                new CanonicalRequestHash(rs.getString("request_hash")),
                CommandReceiptStatus.valueOf(rs.getString("status")),
                rs.getObject("accepted_at", OffsetDateTime.class));
    }

    private IdempotencyRecord mapIdempotencyRecord(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new IdempotencyRecord(
                rs.getLong("id"),
                rs.getLong("workspace_id"),
                rs.getLong("actor_user_id"),
                commandType(rs.getString("operation")),
                new IdempotencyKey(rs.getString("idempotency_key")),
                new CanonicalRequestHash(rs.getString("request_hash")),
                rs.getObject("command_id", UUID.class),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private JobEvent mapEvent(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new JobEvent(
                rs.getLong("id"),
                rs.getLong("workspace_id"),
                rs.getLong("job_id"),
                new JobTarget(
                        rs.getString("resource_type"),
                        rs.getLong("resource_id"),
                        rs.getLong("resource_version")),
                new JobStage(rs.getString("stage")),
                rs.getLong("sequence"),
                JobEventType.valueOf(rs.getString("event_type")),
                JobState.valueOf(rs.getString("state")),
                rs.getString("safe_message"),
                rs.getObject("progress_current", Integer.class),
                rs.getObject("progress_total", Integer.class),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private OutboxEvent mapOutboxEvent(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new OutboxEvent(
                rs.getObject("delivery_key", UUID.class),
                rs.getLong("workspace_id"),
                rs.getLong("job_id"),
                rs.getLong("job_event_id"),
                JobEventType.valueOf(rs.getString("event_type")),
                rs.getObject("occurred_at", OffsetDateTime.class),
                rs.getObject("published_at", OffsetDateTime.class));
    }

    private JobCommandType commandType(String operation) {
        return switch (operation) {
            case "job.enqueue" -> JobCommandType.ENQUEUE;
            case "job.request-cancellation" -> JobCommandType.REQUEST_CANCELLATION;
            case "job.request-resume" -> JobCommandType.REQUEST_RESUME;
            default -> throw new IllegalStateException("Unknown persisted job command operation: " + operation);
        };
    }

    private void requireTenantIds(long workspaceId, long actorUserId) {
        if (workspaceId <= 0) {
            throw new IllegalArgumentException("workspaceId must be positive.");
        }
        if (actorUserId <= 0) {
            throw new IllegalArgumentException("actorUserId must be positive.");
        }
    }

    private record IdempotencyReservation(IdempotencyRecord record, boolean created) {
    }

    private record JobInsertResult(Job job, boolean created) {
    }
}
