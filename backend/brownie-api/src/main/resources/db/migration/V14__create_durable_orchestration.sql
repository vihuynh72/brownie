-- Durable queue metadata and command receipts. These rows deliberately hold
-- identifiers, state, hashes, counters, and storage-object metadata only;
-- application input and generated content stay in their owning stores.
CREATE TABLE job (
    id BIGSERIAL PRIMARY KEY,
    workspace_id BIGINT NOT NULL,
    requested_by_user_id BIGINT NOT NULL,
    job_type TEXT NOT NULL,
    resource_type TEXT NOT NULL,
    resource_id BIGINT NOT NULL,
    resource_version BIGINT NOT NULL,
    stage TEXT NOT NULL,
    processing_configuration_hash TEXT NOT NULL,
    state TEXT NOT NULL DEFAULT 'QUEUED',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    available_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deadline_at TIMESTAMPTZ NOT NULL DEFAULT (now() + interval '1 hour'),
    cancellation_requested_at TIMESTAMPTZ,
    lease_owner TEXT,
    lease_expires_at TIMESTAMPTZ,
    fencing_token BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT job_workspace_requested_by_fk
        FOREIGN KEY (workspace_id, requested_by_user_id) REFERENCES workspace_member (workspace_id, user_id),
    CONSTRAINT job_workspace_id_id_key UNIQUE (workspace_id, id),
    CONSTRAINT job_logical_stage_key
        UNIQUE (workspace_id, job_type, resource_type, resource_id, resource_version, stage, processing_configuration_hash),
    CONSTRAINT job_type_format CHECK (job_type ~ '^[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*$' AND char_length(job_type) <= 100),
    CONSTRAINT job_resource_type_format CHECK (resource_type ~ '^[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*$' AND char_length(resource_type) <= 100),
    CONSTRAINT job_stage_format CHECK (stage ~ '^[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*$' AND char_length(stage) <= 100),
    CONSTRAINT job_resource_id_positive CHECK (resource_id > 0),
    CONSTRAINT job_resource_version_positive CHECK (resource_version > 0),
    CONSTRAINT job_processing_hash_format CHECK (processing_configuration_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT job_state_known CHECK (state IN (
        'QUEUED', 'LEASED', 'WAITING_FOR_INPUT', 'SUCCEEDED', 'FAILED', 'DEAD', 'CANCEL_REQUESTED', 'CANCELLED'
    )),
    CONSTRAINT job_attempt_count_nonnegative CHECK (attempt_count >= 0),
    CONSTRAINT job_deadline_after_availability CHECK (deadline_at > available_at),
    CONSTRAINT job_fencing_token_nonnegative CHECK (fencing_token >= 0),
    CONSTRAINT job_lease_shape CHECK (
        (lease_owner IS NULL AND lease_expires_at IS NULL)
        OR (lease_owner IS NOT NULL AND char_length(btrim(lease_owner)) BETWEEN 1 AND 128
            AND lease_expires_at IS NOT NULL AND fencing_token > 0)
    ),
    CONSTRAINT job_lease_before_deadline CHECK (lease_expires_at IS NULL OR lease_expires_at <= deadline_at),
    CONSTRAINT job_state_lease_shape CHECK (
        (state IN ('LEASED', 'CANCEL_REQUESTED') AND lease_owner IS NOT NULL AND lease_expires_at IS NOT NULL)
        OR (state IN ('QUEUED', 'WAITING_FOR_INPUT', 'SUCCEEDED', 'FAILED', 'DEAD', 'CANCELLED')
            AND lease_owner IS NULL AND lease_expires_at IS NULL)
    )
);

CREATE INDEX job_workspace_id_idx ON job (workspace_id, id);
CREATE INDEX job_ready_to_claim_idx ON job (available_at, deadline_at, id) WHERE state = 'QUEUED';

-- One actor/workspace/operation/key maps to one canonical request digest and
-- one durable command identifier. The request body is intentionally absent.
CREATE TABLE idempotency_record (
    id BIGSERIAL PRIMARY KEY,
    workspace_id BIGINT NOT NULL,
    actor_user_id BIGINT NOT NULL,
    operation TEXT NOT NULL,
    idempotency_key TEXT NOT NULL,
    request_hash TEXT NOT NULL,
    command_id UUID NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT idempotency_record_workspace_actor_fk
        FOREIGN KEY (workspace_id, actor_user_id) REFERENCES workspace_member (workspace_id, user_id),
    CONSTRAINT idempotency_record_workspace_id_id_key UNIQUE (workspace_id, id),
    CONSTRAINT idempotency_record_receipt_identity_key
        UNIQUE (workspace_id, id, actor_user_id, operation, request_hash, command_id),
    CONSTRAINT idempotency_record_scope_key UNIQUE (workspace_id, actor_user_id, operation, idempotency_key),
    CONSTRAINT idempotency_record_operation_known CHECK (operation IN (
        'job.enqueue', 'job.request-cancellation', 'job.request-resume'
    )),
    CONSTRAINT idempotency_record_key_length CHECK (char_length(idempotency_key) BETWEEN 1 AND 200),
    CONSTRAINT idempotency_record_request_hash_format CHECK (request_hash ~ '^[0-9a-f]{64}$')
);

-- A response receipt is separate from the idempotency key so the caller can
-- receive a stable command identifier without exposing any submitted body.
CREATE TABLE command_receipt (
    command_id UUID PRIMARY KEY,
    workspace_id BIGINT NOT NULL,
    actor_user_id BIGINT NOT NULL,
    idempotency_record_id BIGINT NOT NULL,
    job_id BIGINT NOT NULL,
    operation TEXT NOT NULL,
    request_hash TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'ACCEPTED',
    accepted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT command_receipt_workspace_actor_fk
        FOREIGN KEY (workspace_id, actor_user_id) REFERENCES workspace_member (workspace_id, user_id),
    CONSTRAINT command_receipt_idempotency_fk
        FOREIGN KEY (workspace_id, idempotency_record_id, actor_user_id, operation, request_hash, command_id)
        REFERENCES idempotency_record (workspace_id, id, actor_user_id, operation, request_hash, command_id),
    CONSTRAINT command_receipt_job_fk
        FOREIGN KEY (workspace_id, job_id) REFERENCES job (workspace_id, id),
    CONSTRAINT command_receipt_idempotency_key UNIQUE (workspace_id, idempotency_record_id),
    CONSTRAINT command_receipt_operation_known CHECK (operation IN (
        'job.enqueue', 'job.request-cancellation', 'job.request-resume'
    )),
    CONSTRAINT command_receipt_request_hash_format CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT command_receipt_status_known CHECK (status = 'ACCEPTED')
);

CREATE INDEX command_receipt_workspace_actor_idx ON command_receipt (workspace_id, actor_user_id, accepted_at);

-- Ordered job events contain safe progress metadata only. A consumer can
-- recover state from job rows if it has missed retained events.
CREATE TABLE job_event (
    id BIGSERIAL PRIMARY KEY,
    workspace_id BIGINT NOT NULL,
    job_id BIGINT NOT NULL,
    sequence BIGINT NOT NULL,
    event_type TEXT NOT NULL,
    state TEXT NOT NULL,
    safe_message TEXT,
    progress_current INTEGER,
    progress_total INTEGER,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT job_event_job_fk
        FOREIGN KEY (workspace_id, job_id) REFERENCES job (workspace_id, id),
    CONSTRAINT job_event_workspace_id_id_key UNIQUE (workspace_id, id),
    CONSTRAINT job_event_workspace_job_id_id_key UNIQUE (workspace_id, job_id, id),
    CONSTRAINT job_event_workspace_job_id_id_type_key UNIQUE (workspace_id, job_id, id, event_type),
    CONSTRAINT job_event_sequence_key UNIQUE (job_id, sequence),
    CONSTRAINT job_event_sequence_positive CHECK (sequence > 0),
    CONSTRAINT job_event_type_known CHECK (event_type IN (
        'QUEUED', 'RESUMED', 'CANCELLATION_REQUESTED', 'CANCELLED', 'LEASED', 'RELEASED', 'COMPLETED', 'PROGRESS', 'STAGED_OUTPUT_RECORDED'
    )),
    CONSTRAINT job_event_state_known CHECK (state IN (
        'QUEUED', 'LEASED', 'WAITING_FOR_INPUT', 'SUCCEEDED', 'FAILED', 'DEAD', 'CANCEL_REQUESTED', 'CANCELLED'
    )),
    CONSTRAINT job_event_safe_message_length CHECK (safe_message IS NULL OR char_length(safe_message) BETWEEN 1 AND 500),
    CONSTRAINT job_event_progress_current_nonnegative CHECK (progress_current IS NULL OR progress_current >= 0),
    CONSTRAINT job_event_progress_total_nonnegative CHECK (progress_total IS NULL OR progress_total >= 0),
    CONSTRAINT job_event_progress_order CHECK (
        progress_current IS NULL OR progress_total IS NULL OR progress_current <= progress_total
    )
);

CREATE INDEX job_event_workspace_job_sequence_idx ON job_event (workspace_id, job_id, sequence);

-- A publisher delivers this record by following the job-event reference.
-- It has no arbitrary JSON payload, so a queue handoff cannot accidentally
-- duplicate application content into dispatch metadata.
CREATE TABLE outbox_event (
    delivery_key UUID PRIMARY KEY,
    workspace_id BIGINT NOT NULL,
    job_id BIGINT NOT NULL,
    job_event_id BIGINT NOT NULL,
    event_type TEXT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    CONSTRAINT outbox_event_job_fk
        FOREIGN KEY (workspace_id, job_id) REFERENCES job (workspace_id, id),
    CONSTRAINT outbox_event_job_event_fk
        FOREIGN KEY (workspace_id, job_id, job_event_id, event_type)
        REFERENCES job_event (workspace_id, job_id, id, event_type),
    CONSTRAINT outbox_event_job_event_key UNIQUE (workspace_id, job_event_id),
    CONSTRAINT outbox_event_type_known CHECK (event_type IN (
        'QUEUED', 'RESUMED', 'CANCELLATION_REQUESTED', 'CANCELLED', 'LEASED', 'RELEASED', 'COMPLETED', 'PROGRESS', 'STAGED_OUTPUT_RECORDED'
    ))
);

CREATE INDEX outbox_event_workspace_job_idx ON outbox_event (workspace_id, job_id, occurred_at);
CREATE INDEX outbox_event_unpublished_idx ON outbox_event (occurred_at) WHERE published_at IS NULL;

-- Metadata for an object produced by a leased attempt before it is attached
-- to an owning resource. The object bytes remain outside PostgreSQL.
CREATE TABLE job_staged_output (
    id BIGSERIAL PRIMARY KEY,
    workspace_id BIGINT NOT NULL,
    job_id BIGINT NOT NULL,
    worker_id TEXT NOT NULL,
    fencing_token BIGINT NOT NULL,
    output_kind TEXT NOT NULL,
    object_key TEXT NOT NULL,
    sha256 TEXT,
    byte_count BIGINT,
    state TEXT NOT NULL DEFAULT 'STAGED',
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    verified_at TIMESTAMPTZ,
    attached_at TIMESTAMPTZ,
    CONSTRAINT job_staged_output_job_fk
        FOREIGN KEY (workspace_id, job_id) REFERENCES job (workspace_id, id),
    CONSTRAINT job_staged_output_workspace_id_id_key UNIQUE (workspace_id, id),
    CONSTRAINT job_staged_output_attempt_kind_key UNIQUE (job_id, worker_id, fencing_token, output_kind),
    CONSTRAINT job_staged_output_object_key_key UNIQUE (object_key),
    CONSTRAINT job_staged_output_worker_length CHECK (char_length(worker_id) BETWEEN 1 AND 128),
    CONSTRAINT job_staged_output_fencing_positive CHECK (fencing_token > 0),
    CONSTRAINT job_staged_output_kind_format CHECK (output_kind ~ '^[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*$' AND char_length(output_kind) <= 100),
    CONSTRAINT job_staged_output_object_key_length CHECK (char_length(object_key) BETWEEN 1 AND 512),
    CONSTRAINT job_staged_output_integrity_shape CHECK (
        (sha256 IS NULL AND byte_count IS NULL)
        OR (sha256 ~ '^[0-9a-f]{64}$' AND byte_count >= 0)
    ),
    CONSTRAINT job_staged_output_state_known CHECK (state IN ('STAGED', 'VERIFIED', 'ATTACHED', 'DISCARDED'))
);

CREATE INDEX job_staged_output_workspace_job_idx ON job_staged_output (workspace_id, job_id, created_at);

ALTER TABLE job ENABLE ROW LEVEL SECURITY;
ALTER TABLE idempotency_record ENABLE ROW LEVEL SECURITY;
ALTER TABLE command_receipt ENABLE ROW LEVEL SECURITY;
ALTER TABLE job_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE outbox_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE job_staged_output ENABLE ROW LEVEL SECURITY;

-- Browser-side writes run under the acting member's transaction-local user
-- context. A member can enqueue, inspect, or request cancellation, but cannot
-- modify a completed state or claim a lease through this policy.
CREATE POLICY job_member_select ON job
    FOR SELECT TO brownie_api
    USING (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = job.workspace_id AND wm.user_id = current_workspace_user_id()
        )
    );

CREATE POLICY job_member_insert ON job
    FOR INSERT TO brownie_api
    WITH CHECK (
        requested_by_user_id = current_workspace_user_id()
        AND state = 'QUEUED'
        AND attempt_count = 0
        AND cancellation_requested_at IS NULL
        AND lease_owner IS NULL
        AND lease_expires_at IS NULL
        AND fencing_token = 0
        AND EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = job.workspace_id AND wm.user_id = current_workspace_user_id()
        )
    );

CREATE POLICY job_member_request_cancellation ON job
    FOR UPDATE TO brownie_api
    USING (
        state IN ('QUEUED', 'LEASED', 'WAITING_FOR_INPUT')
        AND EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = job.workspace_id AND wm.user_id = current_workspace_user_id()
        )
    )
    WITH CHECK (
        cancellation_requested_at IS NOT NULL
        AND (
            (state = 'CANCEL_REQUESTED' AND lease_owner IS NOT NULL AND lease_expires_at IS NOT NULL)
            OR (state = 'CANCELLED' AND lease_owner IS NULL AND lease_expires_at IS NULL)
        )
        AND EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = job.workspace_id AND wm.user_id = current_workspace_user_id()
        )
    );

CREATE POLICY job_member_resume ON job
    FOR UPDATE TO brownie_api
    USING (
        state = 'WAITING_FOR_INPUT'
        AND EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = job.workspace_id AND wm.user_id = current_workspace_user_id()
        )
    )
    WITH CHECK (
        cancellation_requested_at IS NULL
        AND lease_owner IS NULL
        AND lease_expires_at IS NULL
        AND (
            (state = 'QUEUED' AND deadline_at > clock_timestamp())
            OR (state = 'DEAD' AND deadline_at <= clock_timestamp())
        )
        AND EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = job.workspace_id AND wm.user_id = current_workspace_user_id()
        )
    );

CREATE POLICY idempotency_record_actor_select ON idempotency_record
    FOR SELECT TO brownie_api
    USING (
        actor_user_id = current_workspace_user_id()
        AND EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = idempotency_record.workspace_id AND wm.user_id = current_workspace_user_id()
        )
    );

CREATE POLICY idempotency_record_actor_insert ON idempotency_record
    FOR INSERT TO brownie_api
    WITH CHECK (
        actor_user_id = current_workspace_user_id()
        AND EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = idempotency_record.workspace_id AND wm.user_id = current_workspace_user_id()
        )
    );

CREATE POLICY command_receipt_actor_select ON command_receipt
    FOR SELECT TO brownie_api
    USING (
        actor_user_id = current_workspace_user_id()
        AND EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = command_receipt.workspace_id AND wm.user_id = current_workspace_user_id()
        )
    );

CREATE POLICY command_receipt_actor_insert ON command_receipt
    FOR INSERT TO brownie_api
    WITH CHECK (
        actor_user_id = current_workspace_user_id()
        AND EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = command_receipt.workspace_id AND wm.user_id = current_workspace_user_id()
        )
    );

CREATE POLICY job_event_member_select ON job_event
    FOR SELECT TO brownie_api
    USING (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = job_event.workspace_id AND wm.user_id = current_workspace_user_id()
        )
    );

CREATE POLICY job_event_member_insert ON job_event
    FOR INSERT TO brownie_api
    WITH CHECK (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = job_event.workspace_id AND wm.user_id = current_workspace_user_id()
        )
    );

CREATE POLICY outbox_event_member_select ON outbox_event
    FOR SELECT TO brownie_api
    USING (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = outbox_event.workspace_id AND wm.user_id = current_workspace_user_id()
        )
    );

CREATE POLICY outbox_event_member_insert ON outbox_event
    FOR INSERT TO brownie_api
    WITH CHECK (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = outbox_event.workspace_id AND wm.user_id = current_workspace_user_id()
        )
    );

CREATE POLICY job_staged_output_member_select ON job_staged_output
    FOR SELECT TO brownie_api
    USING (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = job_staged_output.workspace_id AND wm.user_id = current_workspace_user_id()
        )
    );

-- The worker role has no policies on document, source, receipt, or
-- idempotency tables. It can see and change queue metadata and stage-output
-- metadata, and may append only metadata-only outbox references for events it
-- has inserted within the same short transaction.
CREATE POLICY job_worker_select ON job
    FOR SELECT TO brownie_worker
    USING (true);

CREATE POLICY job_worker_update ON job
    FOR UPDATE TO brownie_worker
    USING (true)
    WITH CHECK (true);

CREATE POLICY job_event_worker_select ON job_event
    FOR SELECT TO brownie_worker
    USING (true);

CREATE POLICY job_event_worker_insert ON job_event
    FOR INSERT TO brownie_worker
    WITH CHECK (true);

CREATE POLICY outbox_event_worker_insert ON outbox_event
    FOR INSERT TO brownie_worker
    WITH CHECK (true);

CREATE POLICY job_staged_output_worker_select ON job_staged_output
    FOR SELECT TO brownie_worker
    USING (true);

CREATE POLICY job_staged_output_worker_insert ON job_staged_output
    FOR INSERT TO brownie_worker
    WITH CHECK (true);

CREATE POLICY job_staged_output_worker_update ON job_staged_output
    FOR UPDATE TO brownie_worker
    USING (true)
    WITH CHECK (true);

-- The runtime roles inherit broad table privileges from local bootstrap, but
-- neither needs authority to replace a job's target, requester, or logical
-- deduplication key. Keep browser writes to cancellation metadata and worker
-- writes to its lease/lifecycle fields. Row policies remain responsible for
-- tenant and role visibility; the worker adapter compares its lease fence in
-- every lifecycle update.
REVOKE UPDATE ON job FROM brownie_api, brownie_worker;

GRANT UPDATE (state, cancellation_requested_at, updated_at)
    ON job TO brownie_api;

GRANT UPDATE (
    state,
    attempt_count,
    available_at,
    lease_owner,
    lease_expires_at,
    fencing_token,
    updated_at
) ON job TO brownie_worker;

REVOKE ALL PRIVILEGES ON outbox_event FROM brownie_worker;

GRANT INSERT (delivery_key, workspace_id, job_id, job_event_id, event_type)
    ON outbox_event TO brownie_worker;
