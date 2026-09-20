-- Removing what a person stored, in two steps a person can understand.
--
-- Moving a document to the trash hides it from every read, stops its
-- running work and refuses every further change, but keeps it restorable.
-- Deleting it for good removes every row that belongs to it in one
-- transaction -- access ends the moment that transaction commits -- and
-- hands the object keys of its files to a queue the trusted worker drains,
-- because the database and blob storage cannot share a transaction. A whole
-- workspace can be deleted the same way.
--
-- Nothing here can be done with an ordinary DELETE. No table has a DELETE
-- policy, UPDATE on document is revoked from both runtime roles, and the
-- worker has no table access at all; every step therefore crosses the same
-- explicit routine boundary the revision pointer and the job queue already
-- use: a tenant-checked routine for the API login, a session_user-guarded
-- routine for the worker login, both owned by the migration role.

-- A trashed document keeps its row and everything under it. The column is
-- the whole tombstone: reads filter on it, and the two routines that are
-- the only way to append a revision refuse a document that carries it.
ALTER TABLE document ADD COLUMN trashed_at TIMESTAMPTZ;

-- The ledger of what was asked to be removed and how far that has got. It
-- deliberately has no foreign key to workspace, document or the person who
-- asked: it must outlive all three, since a record that a workspace was
-- deleted is only useful after the workspace is gone, and the same rows are
-- what lets a restored older copy of this database be brought back in line
-- with deletions that happened after that copy was taken. It holds ids,
-- states, times and counts, never a title or a filename.
CREATE TABLE deletion_request (
    id BIGSERIAL PRIMARY KEY,
    workspace_id BIGINT NOT NULL,
    scope TEXT NOT NULL,
    target_id BIGINT NOT NULL,
    state TEXT NOT NULL,
    requested_by_user_id BIGINT NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    purge_after TIMESTAMPTZ,
    restored_at TIMESTAMPTZ,
    purged_at TIMESTAMPTZ,
    verified_at TIMESTAMPTZ,
    -- When the deleted thing had been created, written the moment before it
    -- is removed. An id alone does not say which thing it was: restoring a
    -- backup winds the id sequences back, so one id can belong to different
    -- documents in different histories of the same database. An id and a
    -- moment of creation together name one thing only.
    target_created_at TIMESTAMPTZ,
    inventory JSONB NOT NULL DEFAULT '{}'::jsonb,
    failed_purge_count INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT deletion_request_workspace_id_id_key UNIQUE (workspace_id, id),
    CONSTRAINT deletion_request_scope_known CHECK (scope IN ('DOCUMENT', 'WORKSPACE')),
    CONSTRAINT deletion_request_state_known CHECK (state IN ('TRASHED', 'RESTORED', 'PURGED', 'VERIFIED')),
    CONSTRAINT deletion_request_ids_positive CHECK (workspace_id > 0 AND target_id > 0 AND requested_by_user_id > 0),
    CONSTRAINT deletion_request_inventory_object CHECK (jsonb_typeof(inventory) = 'object'),
    CONSTRAINT deletion_request_failed_purge_count_nonnegative CHECK (failed_purge_count >= 0),
    CONSTRAINT deletion_request_state_times CHECK (
        (state = 'TRASHED' AND purge_after IS NOT NULL AND restored_at IS NULL AND purged_at IS NULL AND verified_at IS NULL)
        OR (state = 'RESTORED' AND restored_at IS NOT NULL AND purged_at IS NULL AND verified_at IS NULL)
        OR (state = 'PURGED' AND purged_at IS NOT NULL AND restored_at IS NULL AND verified_at IS NULL)
        OR (state = 'VERIFIED' AND purged_at IS NOT NULL AND verified_at IS NOT NULL AND restored_at IS NULL)
    )
);

-- One open trash entry per target. A purged or restored entry never blocks
-- a later one, and ids are never reused, so this is the only uniqueness
-- the ledger needs.
CREATE UNIQUE INDEX deletion_request_one_open_per_target_idx
    ON deletion_request (workspace_id, scope, target_id) WHERE state = 'TRASHED';
CREATE INDEX deletion_request_due_idx ON deletion_request (purge_after) WHERE state = 'TRASHED';
CREATE INDEX deletion_request_workspace_idx ON deletion_request (workspace_id, requested_at DESC, id DESC);

-- One row per stored object a deletion still has to remove. The key is
-- opaque (a workspace number and a random or job-derived suffix), which is
-- why it may stay here after the object is gone: replaying these rows
-- against a restored copy of blob storage is how a deleted file stays
-- deleted.
CREATE TABLE deletion_blob_task (
    id BIGSERIAL PRIMARY KEY,
    workspace_id BIGINT NOT NULL,
    deletion_request_id BIGINT NOT NULL,
    object_key TEXT NOT NULL,
    state TEXT NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,
    CONSTRAINT deletion_blob_task_request_fk
        FOREIGN KEY (workspace_id, deletion_request_id) REFERENCES deletion_request (workspace_id, id),
    CONSTRAINT deletion_blob_task_request_key UNIQUE (deletion_request_id, object_key),
    CONSTRAINT deletion_blob_task_state_known CHECK (state IN ('PENDING', 'DELETED')),
    CONSTRAINT deletion_blob_task_key_not_blank CHECK (char_length(btrim(object_key)) BETWEEN 1 AND 1024),
    CONSTRAINT deletion_blob_task_attempts_nonnegative CHECK (attempt_count >= 0),
    CONSTRAINT deletion_blob_task_deleted_shape CHECK ((state = 'DELETED') = (deleted_at IS NOT NULL))
);

CREATE INDEX deletion_blob_task_pending_idx ON deletion_blob_task (attempt_count, id) WHERE state = 'PENDING';
CREATE INDEX deletion_blob_task_request_idx ON deletion_blob_task (deletion_request_id, state);

ALTER TABLE deletion_request ENABLE ROW LEVEL SECURITY;
ALTER TABLE deletion_blob_task ENABLE ROW LEVEL SECURITY;

-- Members may read their workspace's ledger (the trash list and deletion
-- progress come from it). Nobody may write it except the routines below:
-- the default table privileges would otherwise hand the API role INSERT,
-- UPDATE and DELETE, and a ledger its subject can edit is not a ledger.
CREATE POLICY deletion_request_member_select ON deletion_request
    FOR SELECT TO brownie_api
    USING (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = deletion_request.workspace_id
              AND wm.user_id = current_workspace_user_id()
        )
    );

CREATE POLICY deletion_blob_task_member_select ON deletion_blob_task
    FOR SELECT TO brownie_api
    USING (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = deletion_blob_task.workspace_id
              AND wm.user_id = current_workspace_user_id()
        )
    );

REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON deletion_request FROM brownie_api, brownie_worker;
REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON deletion_blob_task FROM brownie_api, brownie_worker;

-- The two routines that are the only way to append a revision now also
-- refuse a trashed document, so no edit, review decision, lock change,
-- accepted proposal or validation result can land on one -- including a
-- request that read the document a moment before it was trashed.
CREATE OR REPLACE FUNCTION lock_document_current_revision(
    p_workspace_id BIGINT,
    p_document_id BIGINT
) RETURNS TABLE (
    document_id BIGINT,
    current_revision_id BIGINT
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
BEGIN
    IF p_workspace_id <= 0 OR p_document_id <= 0 THEN
        RETURN;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM public.workspace_member wm
        WHERE wm.workspace_id = p_workspace_id
          AND wm.user_id = public.current_workspace_user_id()
    ) THEN
        RETURN;
    END IF;

    RETURN QUERY
    SELECT d.id, d.current_revision_id
    FROM public.document d
    WHERE d.workspace_id = p_workspace_id
      AND d.id = p_document_id
      AND d.trashed_at IS NULL
    FOR UPDATE;
END;
$$;

CREATE OR REPLACE FUNCTION advance_document_current_revision(
    p_workspace_id BIGINT,
    p_document_id BIGINT,
    p_expected_revision_id BIGINT,
    p_next_revision_id BIGINT
) RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
BEGIN
    IF p_workspace_id <= 0 OR p_document_id <= 0 OR p_next_revision_id <= 0 THEN
        RETURN FALSE;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM public.workspace_member wm
        WHERE wm.workspace_id = p_workspace_id
          AND wm.user_id = public.current_workspace_user_id()
    ) THEN
        RETURN FALSE;
    END IF;

    UPDATE public.document d
    SET current_revision_id = p_next_revision_id
    WHERE d.workspace_id = p_workspace_id
      AND d.id = p_document_id
      AND d.trashed_at IS NULL
      AND d.current_revision_id IS NOT DISTINCT FROM p_expected_revision_id
      AND EXISTS (
          SELECT 1
          FROM public.document_revision r
          WHERE r.workspace_id = p_workspace_id
            AND r.document_id = p_document_id
            AND r.id = p_next_revision_id
            AND r.parent_revision_id IS NOT DISTINCT FROM p_expected_revision_id
      );
    RETURN FOUND;
END;
$$;

-- Stops every unfinished job that targets one document, the same way a
-- person's own cancel request does: work nobody has claimed ends at once,
-- work a worker holds is asked to stop and ends when that worker next
-- checks. Returns how many jobs are still held by a live lease afterwards;
-- a caller about to delete rows under them waits until that reaches zero.
-- A job whose cancellation was requested earlier is locked here as well
-- and left as it is: once its lease has run out the worker's own recovery
-- finishes it off (new event rows and all), and a caller that goes on to
-- delete the job's rows must not be doing so while that is in flight.
-- Internal: no runtime role may execute it.
CREATE FUNCTION retention_cancel_document_jobs(
    p_workspace_id BIGINT,
    p_document_id BIGINT,
    p_safe_message TEXT
) RETURNS INTEGER
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_job public.job%ROWTYPE;
    v_next_state TEXT;
    v_event_type TEXT;
    v_event_id BIGINT;
    v_now TIMESTAMPTZ;
    v_live INTEGER;
BEGIN
    FOR v_job IN
        SELECT j.*
        FROM public.job j
        WHERE j.workspace_id = p_workspace_id
          AND j.resource_type = 'document'
          AND j.resource_id = p_document_id
          AND j.state IN ('QUEUED', 'WAITING_FOR_INPUT', 'LEASED', 'CANCEL_REQUESTED')
        ORDER BY j.id
        FOR UPDATE
    LOOP
        CONTINUE WHEN v_job.state = 'CANCEL_REQUESTED';
        v_now := clock_timestamp();
        IF v_job.state = 'LEASED' THEN
            v_next_state := 'CANCEL_REQUESTED';
            v_event_type := 'CANCELLATION_REQUESTED';
        ELSE
            v_next_state := 'CANCELLED';
            v_event_type := 'CANCELLED';
        END IF;

        UPDATE public.job j
        SET state = v_next_state,
            cancellation_requested_at = COALESCE(j.cancellation_requested_at, v_now),
            updated_at = v_now
        WHERE j.id = v_job.id;

        INSERT INTO public.job_event (workspace_id, job_id, sequence, event_type, state, safe_message)
        VALUES (
            v_job.workspace_id,
            v_job.id,
            (SELECT COALESCE(MAX(e.sequence), 0) + 1 FROM public.job_event e WHERE e.job_id = v_job.id),
            v_event_type,
            v_next_state,
            p_safe_message)
        RETURNING id INTO v_event_id;

        INSERT INTO public.outbox_event (delivery_key, workspace_id, job_id, job_event_id, event_type)
        VALUES (gen_random_uuid(), v_job.workspace_id, v_job.id, v_event_id, v_event_type);
    END LOOP;

    SELECT count(*) INTO v_live
    FROM public.job j
    WHERE j.workspace_id = p_workspace_id
      AND j.resource_type = 'document'
      AND j.resource_id = p_document_id
      AND j.state IN ('LEASED', 'CANCEL_REQUESTED')
      AND j.lease_expires_at > clock_timestamp();
    RETURN v_live;
END;
$$;

-- Removes every row that belongs to one document and queues every stored
-- object only that document owns. Order follows the foreign keys, all of
-- which are NO ACTION on purpose: a table added later that references a
-- document without being listed here makes this fail loudly instead of
-- leaving rows behind. Returns counts only. Internal.
--
-- What a document owns: its compiled and validated files, the published
-- and staged outputs of its jobs, the three per-run objects that no column
-- records (their keys are derived exactly as the application derives
-- them), and any source file that nothing else uses. A source snapshot is
-- shared at workspace level, so it goes only when no other document links
-- it, runs from it or cites it, and no template or example was taught from
-- the same file. "Its" sources are the ones it links, the ones its runs
-- read, and the ones its evidence cites: a source can be attached to the
-- workspace and cited by a document without ever being linked to it, and
-- deleting the document must not strand that file. An input bundle is
-- content-addressed, so it goes only when no other document's run produced
-- the identical bundle.
CREATE FUNCTION retention_purge_document(
    p_request_id BIGINT,
    p_workspace_id BIGINT,
    p_document_id BIGINT
) RETURNS JSONB
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_job_ids BIGINT[];
    v_snapshot_ids BIGINT[];
    v_artifact_ids BIGINT[];
    v_idempotency_ids BIGINT[];
    v_rows BIGINT := 0;
    v_count BIGINT;
    v_blobs BIGINT;
BEGIN
    SELECT COALESCE(array_agg(j.id), '{}') INTO v_job_ids
    FROM public.job j
    WHERE j.workspace_id = p_workspace_id
      AND j.resource_type = 'document'
      AND j.resource_id = p_document_id;

    SELECT COALESCE(array_agg(s.id), '{}') INTO v_snapshot_ids
    FROM public.source_snapshot s
    WHERE s.workspace_id = p_workspace_id
      AND (
          EXISTS (
              SELECT 1 FROM public.document_source ds
              WHERE ds.workspace_id = p_workspace_id
                AND ds.document_id = p_document_id
                AND ds.source_snapshot_id = s.id)
          OR EXISTS (
              SELECT 1 FROM public.generation_run gr
              WHERE gr.workspace_id = p_workspace_id
                AND gr.document_id = p_document_id
                AND gr.source_snapshot_id = s.id)
          OR EXISTS (
              SELECT 1
              FROM public.document_revision_field_evidence fe
              JOIN public.source_span sp ON sp.workspace_id = fe.workspace_id AND sp.id = fe.source_span_id
              WHERE fe.workspace_id = p_workspace_id
                AND fe.document_id = p_document_id
                AND sp.source_snapshot_id = s.id)
          OR EXISTS (
              SELECT 1
              FROM public.document_patch_proposal_evidence pe
              JOIN public.document_patch_proposal pp ON pp.workspace_id = pe.workspace_id AND pp.id = pe.proposal_id
              JOIN public.source_span sp ON sp.workspace_id = pe.workspace_id AND sp.id = pe.source_span_id
              WHERE pe.workspace_id = p_workspace_id
                AND pp.document_id = p_document_id
                AND sp.source_snapshot_id = s.id))
      AND NOT EXISTS (
          SELECT 1 FROM public.document_source ds
          WHERE ds.workspace_id = p_workspace_id
            AND ds.source_snapshot_id = s.id
            AND ds.document_id <> p_document_id)
      AND NOT EXISTS (
          SELECT 1 FROM public.generation_run gr
          WHERE gr.workspace_id = p_workspace_id
            AND gr.source_snapshot_id = s.id
            AND gr.document_id <> p_document_id)
      AND NOT EXISTS (
          SELECT 1
          FROM public.document_revision_field_evidence fe
          JOIN public.source_span sp ON sp.workspace_id = fe.workspace_id AND sp.id = fe.source_span_id
          WHERE fe.workspace_id = p_workspace_id
            AND sp.source_snapshot_id = s.id
            AND fe.document_id <> p_document_id)
      AND NOT EXISTS (
          SELECT 1
          FROM public.document_patch_proposal_evidence pe
          JOIN public.document_patch_proposal pp ON pp.workspace_id = pe.workspace_id AND pp.id = pe.proposal_id
          JOIN public.source_span sp ON sp.workspace_id = pe.workspace_id AND sp.id = pe.source_span_id
          WHERE pe.workspace_id = p_workspace_id
            AND sp.source_snapshot_id = s.id
            AND pp.document_id <> p_document_id)
      AND NOT EXISTS (
          SELECT 1 FROM public.template_version tv
          WHERE tv.workspace_id = p_workspace_id AND tv.source_artifact_id = s.artifact_id)
      AND NOT EXISTS (
          SELECT 1 FROM public.template_example te
          WHERE te.workspace_id = p_workspace_id AND te.source_artifact_id = s.artifact_id);

    SELECT COALESCE(array_agg(DISTINCT owned.artifact_id), '{}') INTO v_artifact_ids
    FROM (
        SELECT c.docx_artifact_id AS artifact_id FROM public.document_compilation c
        WHERE c.workspace_id = p_workspace_id AND c.document_id = p_document_id
        UNION ALL
        SELECT c.pdf_artifact_id FROM public.document_compilation c
        WHERE c.workspace_id = p_workspace_id AND c.document_id = p_document_id
        UNION ALL
        SELECT m.docx_artifact_id FROM public.validation_manifest m
        WHERE m.workspace_id = p_workspace_id AND m.document_id = p_document_id
        UNION ALL
        SELECT m.pdf_artifact_id FROM public.validation_manifest m
        WHERE m.workspace_id = p_workspace_id AND m.document_id = p_document_id AND m.pdf_artifact_id IS NOT NULL
        UNION ALL
        SELECT o.artifact_id FROM public.job_output_artifact o
        WHERE o.workspace_id = p_workspace_id AND o.job_id = ANY (v_job_ids)
        UNION ALL
        SELECT s.artifact_id FROM public.source_snapshot s
        WHERE s.workspace_id = p_workspace_id AND s.id = ANY (v_snapshot_ids)
    ) owned
    -- Any ready file can be attached as a source or taught as a template,
    -- including one this document generated. A file something else still
    -- uses stays, whoever produced it.
    WHERE NOT EXISTS (
              SELECT 1 FROM public.source_snapshot s
              WHERE s.workspace_id = p_workspace_id
                AND s.artifact_id = owned.artifact_id
                AND NOT (s.id = ANY (v_snapshot_ids)))
      AND NOT EXISTS (
              SELECT 1 FROM public.template_version tv
              WHERE tv.workspace_id = p_workspace_id AND tv.source_artifact_id = owned.artifact_id)
      AND NOT EXISTS (
              SELECT 1 FROM public.template_example te
              WHERE te.workspace_id = p_workspace_id AND te.source_artifact_id = owned.artifact_id)
      AND NOT EXISTS (
              SELECT 1 FROM public.template_baseline_render br
              WHERE br.workspace_id = p_workspace_id
                AND owned.artifact_id IN (br.docx_artifact_id, br.pdf_artifact_id))
      AND NOT EXISTS (
              SELECT 1 FROM public.document_compilation c
              WHERE c.workspace_id = p_workspace_id
                AND c.document_id <> p_document_id
                AND owned.artifact_id IN (c.docx_artifact_id, c.pdf_artifact_id))
      AND NOT EXISTS (
              SELECT 1 FROM public.validation_manifest m
              WHERE m.workspace_id = p_workspace_id
                AND m.document_id <> p_document_id
                AND owned.artifact_id IN (m.docx_artifact_id, m.pdf_artifact_id))
      AND NOT EXISTS (
              SELECT 1 FROM public.export_receipt x
              WHERE x.workspace_id = p_workspace_id
                AND x.document_id <> p_document_id
                AND owned.artifact_id IN (x.docx_artifact_id, x.pdf_artifact_id))
      AND NOT EXISTS (
              SELECT 1 FROM public.job_output_artifact o
              WHERE o.workspace_id = p_workspace_id
                AND o.artifact_id = owned.artifact_id
                AND NOT (o.job_id = ANY (v_job_ids)));

    INSERT INTO public.deletion_blob_task (workspace_id, deletion_request_id, object_key)
    SELECT p_workspace_id, p_request_id, keys.object_key
    FROM (
        SELECT a.blob_key AS object_key FROM public.artifact a
        WHERE a.workspace_id = p_workspace_id AND a.id = ANY (v_artifact_ids)
        UNION
        -- A published output's artifact was created under the staged
        -- object's own key, so a key whose artifact is staying must stay too.
        SELECT o.object_key FROM public.job_staged_output o
        WHERE o.workspace_id = p_workspace_id AND o.job_id = ANY (v_job_ids) AND o.cleaned_at IS NULL
          AND NOT EXISTS (
              SELECT 1 FROM public.artifact kept
              WHERE kept.workspace_id = p_workspace_id
                AND kept.blob_key = o.object_key
                AND NOT (kept.id = ANY (v_artifact_ids)))
        UNION
        SELECT 'generation-runs/' || p_workspace_id || '/jobs/' || gr.job_id || '/pending-questions.json'
        FROM public.generation_run gr
        WHERE gr.workspace_id = p_workspace_id AND gr.document_id = p_document_id
        UNION
        SELECT 'generation-runs/' || p_workspace_id || '/jobs/' || gr.job_id || '/resolved-answers.json'
        FROM public.generation_run gr
        WHERE gr.workspace_id = p_workspace_id AND gr.document_id = p_document_id
        UNION
        SELECT 'generation-runs/' || p_workspace_id || '/inputs/' || gr.bundle_hash || '.json'
        FROM public.generation_run gr
        WHERE gr.workspace_id = p_workspace_id
          AND gr.document_id = p_document_id
          AND NOT EXISTS (
              SELECT 1 FROM public.generation_run other
              WHERE other.workspace_id = p_workspace_id
                AND other.bundle_hash = gr.bundle_hash
                AND other.document_id <> p_document_id)
    ) keys
    ON CONFLICT (deletion_request_id, object_key) DO NOTHING;
    GET DIAGNOSTICS v_blobs = ROW_COUNT;

    DELETE FROM public.export_receipt x WHERE x.workspace_id = p_workspace_id AND x.document_id = p_document_id;
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;
    DELETE FROM public.export_approval x WHERE x.workspace_id = p_workspace_id AND x.document_id = p_document_id;
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;
    DELETE FROM public.validation_manifest x WHERE x.workspace_id = p_workspace_id AND x.document_id = p_document_id;
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;
    DELETE FROM public.document_compilation x WHERE x.workspace_id = p_workspace_id AND x.document_id = p_document_id;
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;

    DELETE FROM public.document_patch_proposal_evidence pe
    USING public.document_patch_proposal pp
    WHERE pp.workspace_id = pe.workspace_id AND pp.id = pe.proposal_id
      AND pp.workspace_id = p_workspace_id AND pp.document_id = p_document_id;
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;
    DELETE FROM public.document_patch_proposal x WHERE x.workspace_id = p_workspace_id AND x.document_id = p_document_id;
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;

    DELETE FROM public.question x WHERE x.workspace_id = p_workspace_id AND x.document_id = p_document_id;
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;
    DELETE FROM public.generation_run x WHERE x.workspace_id = p_workspace_id AND x.document_id = p_document_id;
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;

    SELECT COALESCE(array_agg(r.idempotency_record_id), '{}') INTO v_idempotency_ids
    FROM (
        SELECT cr.idempotency_record_id FROM public.command_receipt cr
        WHERE cr.workspace_id = p_workspace_id AND cr.job_id = ANY (v_job_ids)
        UNION
        SELECT dr.idempotency_record_id FROM public.document_command_receipt dr
        WHERE dr.workspace_id = p_workspace_id AND dr.document_id = p_document_id
    ) r;

    DELETE FROM public.job_output_artifact x WHERE x.workspace_id = p_workspace_id AND x.job_id = ANY (v_job_ids);
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;
    DELETE FROM public.outbox_event x WHERE x.workspace_id = p_workspace_id AND x.job_id = ANY (v_job_ids);
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;
    DELETE FROM public.job_event x WHERE x.workspace_id = p_workspace_id AND x.job_id = ANY (v_job_ids);
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;
    DELETE FROM public.job_staged_output x WHERE x.workspace_id = p_workspace_id AND x.job_id = ANY (v_job_ids);
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;
    DELETE FROM public.command_receipt x WHERE x.workspace_id = p_workspace_id AND x.job_id = ANY (v_job_ids);
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;
    DELETE FROM public.job x WHERE x.workspace_id = p_workspace_id AND x.id = ANY (v_job_ids);
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;

    DELETE FROM public.document_revision_field_evidence x WHERE x.workspace_id = p_workspace_id AND x.document_id = p_document_id;
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;
    DELETE FROM public.document_revision_field_state x WHERE x.workspace_id = p_workspace_id AND x.document_id = p_document_id;
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;
    DELETE FROM public.document_command_receipt x WHERE x.workspace_id = p_workspace_id AND x.document_id = p_document_id;
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;
    DELETE FROM public.idempotency_record x WHERE x.workspace_id = p_workspace_id AND x.id = ANY (v_idempotency_ids);
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;
    DELETE FROM public.document_source x WHERE x.workspace_id = p_workspace_id AND x.document_id = p_document_id;
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;

    -- The document and its revisions point at each other; the pointer goes
    -- first, then every revision in one statement (a parent and its child
    -- leave together, so the self-reference never sees a missing parent).
    UPDATE public.document d SET current_revision_id = NULL
    WHERE d.workspace_id = p_workspace_id AND d.id = p_document_id;
    DELETE FROM public.document_revision x WHERE x.workspace_id = p_workspace_id AND x.document_id = p_document_id;
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;
    DELETE FROM public.document x WHERE x.workspace_id = p_workspace_id AND x.id = p_document_id;
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;

    DELETE FROM public.source_span x WHERE x.workspace_id = p_workspace_id AND x.source_snapshot_id = ANY (v_snapshot_ids);
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;
    DELETE FROM public.source_snapshot x WHERE x.workspace_id = p_workspace_id AND x.id = ANY (v_snapshot_ids);
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;
    DELETE FROM public.extraction_version x WHERE x.workspace_id = p_workspace_id AND x.artifact_id = ANY (v_artifact_ids);
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;
    DELETE FROM public.pdf_extraction_version x WHERE x.workspace_id = p_workspace_id AND x.artifact_id = ANY (v_artifact_ids);
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;
    DELETE FROM public.plain_text_extraction_version x WHERE x.workspace_id = p_workspace_id AND x.artifact_id = ANY (v_artifact_ids);
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;
    DELETE FROM public.artifact x WHERE x.workspace_id = p_workspace_id AND x.id = ANY (v_artifact_ids);
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;

    RETURN jsonb_build_object(
        'rowsRemoved', v_rows,
        'objectsQueued', v_blobs,
        'jobsRemoved', COALESCE(array_length(v_job_ids, 1), 0),
        'sourcesRemoved', COALESCE(array_length(v_snapshot_ids, 1), 0));
END;
$$;

-- Removes a whole workspace: every document through the routine above,
-- then everything a workspace holds that no document owns (templates and
-- what they were taught from, remaining jobs, remaining files and what was
-- extracted from them), then the membership, the workspace, and the
-- identity of its owner when that person owns and belongs to nothing else.
-- Every stored object the workspace ever recorded is queued. Internal.
CREATE FUNCTION retention_purge_workspace(
    p_request_id BIGINT,
    p_workspace_id BIGINT
) RETURNS JSONB
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_document_id BIGINT;
    v_documents BIGINT := 0;
    v_rows BIGINT := 0;
    v_count BIGINT;
    v_blobs BIGINT := 0;
    v_detail JSONB;
    v_owner_user_id BIGINT;
    v_identity_removed BOOLEAN := FALSE;
BEGIN
    -- Requests before documents, which is the order a member's purge or
    -- restore and the worker's sweep take them in; taking a document first
    -- here could deadlock against any of them.
    PERFORM 1
    FROM public.deletion_request r
    WHERE r.workspace_id = p_workspace_id AND r.state = 'TRASHED'
    ORDER BY r.id
    FOR UPDATE;

    SELECT w.owner_user_id INTO v_owner_user_id
    FROM public.workspace w
    WHERE w.id = p_workspace_id
    FOR UPDATE;

    FOR v_document_id IN
        SELECT d.id FROM public.document d WHERE d.workspace_id = p_workspace_id ORDER BY d.id
    LOOP
        v_detail := public.retention_purge_document(p_request_id, p_workspace_id, v_document_id);
        v_rows := v_rows + (v_detail ->> 'rowsRemoved')::BIGINT;
        v_blobs := v_blobs + (v_detail ->> 'objectsQueued')::BIGINT;
        v_documents := v_documents + 1;
    END LOOP;

    INSERT INTO public.deletion_blob_task (workspace_id, deletion_request_id, object_key)
    SELECT p_workspace_id, p_request_id, keys.object_key
    FROM (
        SELECT a.blob_key AS object_key FROM public.artifact a WHERE a.workspace_id = p_workspace_id
        UNION
        SELECT o.object_key FROM public.job_staged_output o
        WHERE o.workspace_id = p_workspace_id AND o.cleaned_at IS NULL
    ) keys
    ON CONFLICT (deletion_request_id, object_key) DO NOTHING;
    GET DIAGNOSTICS v_count = ROW_COUNT; v_blobs := v_blobs + v_count;

    -- Earlier trash entries of this workspace are closed by this deletion:
    -- their documents are gone with everything else.
    UPDATE public.deletion_request r
    SET state = 'PURGED', purged_at = clock_timestamp(), purge_after = NULL
    WHERE r.workspace_id = p_workspace_id AND r.state = 'TRASHED' AND r.id <> p_request_id;

    DELETE FROM public.rule_revision_proposal_evidence x WHERE x.workspace_id = p_workspace_id;
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;
    DELETE FROM public.rule_revision x WHERE x.workspace_id = p_workspace_id;
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;
    DELETE FROM public.template_example x WHERE x.workspace_id = p_workspace_id;
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;
    DELETE FROM public.template_baseline_render x WHERE x.workspace_id = p_workspace_id;
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;
    UPDATE public.template t SET current_active_version_id = NULL WHERE t.workspace_id = p_workspace_id;
    DELETE FROM public.template_version x WHERE x.workspace_id = p_workspace_id;
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;
    DELETE FROM public.template x WHERE x.workspace_id = p_workspace_id;
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;

    DELETE FROM public.job_output_artifact x WHERE x.workspace_id = p_workspace_id;
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;
    DELETE FROM public.outbox_event x WHERE x.workspace_id = p_workspace_id;
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;
    DELETE FROM public.job_event x WHERE x.workspace_id = p_workspace_id;
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;
    DELETE FROM public.job_staged_output x WHERE x.workspace_id = p_workspace_id;
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;
    DELETE FROM public.command_receipt x WHERE x.workspace_id = p_workspace_id;
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;
    DELETE FROM public.job x WHERE x.workspace_id = p_workspace_id;
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;
    DELETE FROM public.document_command_receipt x WHERE x.workspace_id = p_workspace_id;
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;
    DELETE FROM public.idempotency_record x WHERE x.workspace_id = p_workspace_id;
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;

    DELETE FROM public.source_span x WHERE x.workspace_id = p_workspace_id;
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;
    DELETE FROM public.source_snapshot x WHERE x.workspace_id = p_workspace_id;
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;
    DELETE FROM public.extraction_version x WHERE x.workspace_id = p_workspace_id;
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;
    DELETE FROM public.pdf_extraction_version x WHERE x.workspace_id = p_workspace_id;
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;
    DELETE FROM public.plain_text_extraction_version x WHERE x.workspace_id = p_workspace_id;
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;
    DELETE FROM public.artifact x WHERE x.workspace_id = p_workspace_id;
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;

    DELETE FROM public.workspace_member x WHERE x.workspace_id = p_workspace_id;
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;
    DELETE FROM public.workspace x WHERE x.id = p_workspace_id;
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;

    IF v_owner_user_id IS NOT NULL
            AND NOT EXISTS (SELECT 1 FROM public.workspace w WHERE w.owner_user_id = v_owner_user_id)
            AND NOT EXISTS (SELECT 1 FROM public.workspace_member wm WHERE wm.user_id = v_owner_user_id) THEN
        DELETE FROM public.user_identity u WHERE u.id = v_owner_user_id;
        v_identity_removed := FOUND;
    END IF;

    RETURN jsonb_build_object(
        'rowsRemoved', v_rows,
        'objectsQueued', v_blobs,
        'documentsRemoved', v_documents,
        'identityRemoved', v_identity_removed);
END;
$$;

-- What is left of a purged target: rows that still name it, and objects
-- still waiting to be removed. Both must be zero before a request is
-- called verified. Internal.
CREATE FUNCTION retention_remaining(
    p_request_id BIGINT
) RETURNS TABLE (
    remaining_rows BIGINT,
    pending_objects BIGINT
)
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_request public.deletion_request%ROWTYPE;
BEGIN
    SELECT r.* INTO v_request FROM public.deletion_request r WHERE r.id = p_request_id;
    IF NOT FOUND THEN
        RETURN;
    END IF;

    IF v_request.scope = 'DOCUMENT' THEN
        SELECT
            (SELECT count(*) FROM public.document x WHERE x.workspace_id = v_request.workspace_id AND x.id = v_request.target_id)
            + (SELECT count(*) FROM public.document_revision x WHERE x.workspace_id = v_request.workspace_id AND x.document_id = v_request.target_id)
            + (SELECT count(*) FROM public.document_revision_field_evidence x WHERE x.workspace_id = v_request.workspace_id AND x.document_id = v_request.target_id)
            + (SELECT count(*) FROM public.document_revision_field_state x WHERE x.workspace_id = v_request.workspace_id AND x.document_id = v_request.target_id)
            + (SELECT count(*) FROM public.document_command_receipt x WHERE x.workspace_id = v_request.workspace_id AND x.document_id = v_request.target_id)
            + (SELECT count(*) FROM public.document_compilation x WHERE x.workspace_id = v_request.workspace_id AND x.document_id = v_request.target_id)
            + (SELECT count(*) FROM public.document_patch_proposal x WHERE x.workspace_id = v_request.workspace_id AND x.document_id = v_request.target_id)
            + (SELECT count(*) FROM public.document_source x WHERE x.workspace_id = v_request.workspace_id AND x.document_id = v_request.target_id)
            + (SELECT count(*) FROM public.question x WHERE x.workspace_id = v_request.workspace_id AND x.document_id = v_request.target_id)
            + (SELECT count(*) FROM public.generation_run x WHERE x.workspace_id = v_request.workspace_id AND x.document_id = v_request.target_id)
            + (SELECT count(*) FROM public.validation_manifest x WHERE x.workspace_id = v_request.workspace_id AND x.document_id = v_request.target_id)
            + (SELECT count(*) FROM public.export_approval x WHERE x.workspace_id = v_request.workspace_id AND x.document_id = v_request.target_id)
            + (SELECT count(*) FROM public.export_receipt x WHERE x.workspace_id = v_request.workspace_id AND x.document_id = v_request.target_id)
            + (SELECT count(*) FROM public.job x WHERE x.workspace_id = v_request.workspace_id
                   AND x.resource_type = 'document' AND x.resource_id = v_request.target_id)
        INTO remaining_rows;
    ELSE
        SELECT
            (SELECT count(*) FROM public.workspace x WHERE x.id = v_request.workspace_id)
            + (SELECT count(*) FROM public.workspace_member x WHERE x.workspace_id = v_request.workspace_id)
            + (SELECT count(*) FROM public.document x WHERE x.workspace_id = v_request.workspace_id)
            + (SELECT count(*) FROM public.artifact x WHERE x.workspace_id = v_request.workspace_id)
            + (SELECT count(*) FROM public.template x WHERE x.workspace_id = v_request.workspace_id)
            + (SELECT count(*) FROM public.job x WHERE x.workspace_id = v_request.workspace_id)
            + (SELECT count(*) FROM public.source_snapshot x WHERE x.workspace_id = v_request.workspace_id)
            + (SELECT count(*) FROM public.plain_text_extraction_version x WHERE x.workspace_id = v_request.workspace_id)
        INTO remaining_rows;
    END IF;

    SELECT count(*) INTO pending_objects
    FROM public.deletion_blob_task t
    WHERE t.deletion_request_id = p_request_id AND t.state = 'PENDING';

    RETURN NEXT;
END;
$$;

-- Performs one open request's permanent deletion. Shared by the person's
-- own "delete forever" and by the worker's sweep of expired trash, so the
-- two can never drift apart. Returns PURGED, NOT_OPEN (nothing to do: it
-- was restored or already purged) or JOBS_STILL_STOPPING (a worker still
-- holds a live lease on this document's work; deleting rows under it could
-- let that worker write an object nothing would ever clean up, so the
-- caller tries again once the lease is released or expires). Internal.
CREATE FUNCTION retention_execute_purge(
    p_request_id BIGINT
) RETURNS TEXT
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_request public.deletion_request%ROWTYPE;
    v_live INTEGER;
    v_inventory JSONB;
BEGIN
    SELECT r.* INTO v_request
    FROM public.deletion_request r
    WHERE r.id = p_request_id
    FOR UPDATE;
    IF NOT FOUND OR v_request.state <> 'TRASHED' THEN
        RETURN 'NOT_OPEN';
    END IF;

    -- Every permanent deletion passes through here, whoever asked for it,
    -- so this is where the ledger learns exactly which thing it removed.
    UPDATE public.deletion_request r
    SET target_created_at = CASE v_request.scope
            WHEN 'DOCUMENT' THEN (SELECT d.created_at FROM public.document d
                                  WHERE d.workspace_id = v_request.workspace_id AND d.id = v_request.target_id)
            ELSE (SELECT w.created_at FROM public.workspace w WHERE w.id = v_request.target_id)
        END
    WHERE r.id = p_request_id AND r.target_created_at IS NULL;

    IF v_request.scope = 'DOCUMENT' THEN
        -- Jobs before the document: the worker's completion routine locks a
        -- job and then its document, and taking them in the other order
        -- here could deadlock against it.
        v_live := public.retention_cancel_document_jobs(
            v_request.workspace_id, v_request.target_id, 'Job cancelled because its document was deleted.');
        IF v_live > 0 THEN
            RETURN 'JOBS_STILL_STOPPING';
        END IF;
        PERFORM 1 FROM public.document d
        WHERE d.workspace_id = v_request.workspace_id AND d.id = v_request.target_id
        FOR UPDATE;
        v_inventory := public.retention_purge_document(p_request_id, v_request.workspace_id, v_request.target_id);
    ELSE
        v_live := 0;
        PERFORM public.retention_cancel_document_jobs(
            d.workspace_id, d.id, 'Job cancelled because its workspace was deleted.')
        FROM public.document d
        WHERE d.workspace_id = v_request.workspace_id;
        SELECT count(*) INTO v_live
        FROM public.job j
        WHERE j.workspace_id = v_request.workspace_id
          AND j.state IN ('LEASED', 'CANCEL_REQUESTED')
          AND j.lease_expires_at > clock_timestamp();
        IF v_live > 0 THEN
            RETURN 'JOBS_STILL_STOPPING';
        END IF;
        v_inventory := public.retention_purge_workspace(p_request_id, v_request.workspace_id);
    END IF;

    UPDATE public.deletion_request r
    SET state = 'PURGED',
        purged_at = clock_timestamp(),
        purge_after = NULL,
        inventory = v_inventory
    WHERE r.id = p_request_id;

    -- The acting member when a person asked; nobody when the worker carried
    -- out trash whose time had run out. Written last, so it exists exactly
    -- when the deletion does.
    PERFORM public.audit_append(
        v_request.workspace_id,
        public.current_workspace_user_id(),
        CASE WHEN v_request.scope = 'DOCUMENT' THEN 'DOCUMENT_DELETED' ELSE 'WORKSPACE_DELETED' END,
        CASE WHEN v_request.scope = 'DOCUMENT' THEN 'document' ELSE 'workspace' END,
        v_request.target_id,
        v_inventory || jsonb_build_object('deletionRequestId', p_request_id));
    RETURN 'PURGED';
END;
$$;

REVOKE ALL ON FUNCTION retention_cancel_document_jobs(BIGINT, BIGINT, TEXT) FROM PUBLIC, brownie_api, brownie_worker;
REVOKE ALL ON FUNCTION retention_purge_document(BIGINT, BIGINT, BIGINT) FROM PUBLIC, brownie_api, brownie_worker;
REVOKE ALL ON FUNCTION retention_purge_workspace(BIGINT, BIGINT) FROM PUBLIC, brownie_api, brownie_worker;
REVOKE ALL ON FUNCTION retention_remaining(BIGINT) FROM PUBLIC, brownie_api, brownie_worker;
REVOKE ALL ON FUNCTION retention_execute_purge(BIGINT) FROM PUBLIC, brownie_api, brownie_worker;

-- What a signed-in member may ask for. Each checks membership against the
-- acting person exactly as the revision routines do; a caller who is not a
-- member gets the same answer as a caller naming something that does not
-- exist.

-- Moves one live document to the trash, or returns the entry that already
-- holds it. NULL means no such live or trashed document for this member.
CREATE FUNCTION trash_document(
    p_workspace_id BIGINT,
    p_document_id BIGINT,
    p_retention_days INTEGER
) RETURNS BIGINT
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_actor BIGINT;
    v_trashed_at TIMESTAMPTZ;
    v_request_id BIGINT;
    v_now TIMESTAMPTZ;
BEGIN
    IF p_workspace_id <= 0 OR p_document_id <= 0 THEN
        RETURN NULL;
    END IF;
    IF p_retention_days IS NULL OR p_retention_days NOT BETWEEN 1 AND 365 THEN
        RAISE EXCEPTION 'The trash retention must be between one and 365 days.';
    END IF;

    v_actor := public.current_workspace_user_id();
    IF v_actor IS NULL OR NOT EXISTS (
        SELECT 1 FROM public.workspace_member wm
        WHERE wm.workspace_id = p_workspace_id AND wm.user_id = v_actor
    ) THEN
        RETURN NULL;
    END IF;

    SELECT d.trashed_at INTO v_trashed_at
    FROM public.document d
    WHERE d.workspace_id = p_workspace_id AND d.id = p_document_id;
    IF NOT FOUND THEN
        RETURN NULL;
    END IF;

    IF v_trashed_at IS NULL THEN
        -- Jobs before the document, the same order the worker's completion
        -- routine takes its locks in. Two people trashing the same document
        -- at once both get here; the update below lets exactly one through.
        PERFORM public.retention_cancel_document_jobs(
            p_workspace_id, p_document_id, 'Job cancelled because its document was moved to the trash.');

        v_now := clock_timestamp();
        UPDATE public.document d SET trashed_at = v_now
        WHERE d.workspace_id = p_workspace_id AND d.id = p_document_id AND d.trashed_at IS NULL;
        IF NOT FOUND THEN
            v_trashed_at := v_now;
        ELSE
            -- Starting work takes this same document lock before it enqueues
            -- a job, so from here on no new job can appear; this pass stops
            -- one that was committed between the first pass and the mark.
            PERFORM public.retention_cancel_document_jobs(
                p_workspace_id, p_document_id, 'Job cancelled because its document was moved to the trash.');
        END IF;
    END IF;

    IF v_trashed_at IS NOT NULL THEN
        SELECT r.id INTO v_request_id
        FROM public.deletion_request r
        WHERE r.workspace_id = p_workspace_id
          AND r.scope = 'DOCUMENT'
          AND r.target_id = p_document_id
          AND r.state = 'TRASHED';
        RETURN v_request_id;
    END IF;

    INSERT INTO public.deletion_request (workspace_id, scope, target_id, state, requested_by_user_id, requested_at, purge_after)
    VALUES (p_workspace_id, 'DOCUMENT', p_document_id, 'TRASHED', v_actor, v_now, v_now + make_interval(days => p_retention_days))
    RETURNING id INTO v_request_id;

    PERFORM public.audit_append(
        p_workspace_id, v_actor, 'DOCUMENT_TRASHED', 'document', p_document_id,
        jsonb_build_object('deletionRequestId', v_request_id, 'retentionDays', p_retention_days));
    RETURN v_request_id;
END;
$$;

-- Takes a document back out of the trash. Work that was cancelled when it
-- went in stays cancelled; the person starts it again if they want it.
CREATE FUNCTION restore_trashed_document(
    p_workspace_id BIGINT,
    p_request_id BIGINT
) RETURNS TEXT
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_actor BIGINT;
    v_request public.deletion_request%ROWTYPE;
BEGIN
    v_actor := public.current_workspace_user_id();
    IF v_actor IS NULL OR NOT EXISTS (
        SELECT 1 FROM public.workspace_member wm
        WHERE wm.workspace_id = p_workspace_id AND wm.user_id = v_actor
    ) THEN
        RETURN 'NOT_FOUND';
    END IF;

    SELECT r.* INTO v_request
    FROM public.deletion_request r
    WHERE r.workspace_id = p_workspace_id AND r.id = p_request_id AND r.scope = 'DOCUMENT'
    FOR UPDATE;
    IF NOT FOUND THEN
        RETURN 'NOT_FOUND';
    END IF;
    IF v_request.state = 'RESTORED' THEN
        RETURN 'RESTORED';
    END IF;
    IF v_request.state <> 'TRASHED' THEN
        RETURN 'NOT_OPEN';
    END IF;

    UPDATE public.document d SET trashed_at = NULL
    WHERE d.workspace_id = p_workspace_id AND d.id = v_request.target_id;

    UPDATE public.deletion_request r
    SET state = 'RESTORED', restored_at = clock_timestamp(), purge_after = NULL
    WHERE r.id = p_request_id;

    PERFORM public.audit_append(
        p_workspace_id, v_actor, 'DOCUMENT_RESTORED', 'document', v_request.target_id,
        jsonb_build_object('deletionRequestId', p_request_id));
    RETURN 'RESTORED';
END;
$$;

-- Deletes a trashed document for good, now.
CREATE FUNCTION purge_trashed_document(
    p_workspace_id BIGINT,
    p_request_id BIGINT
) RETURNS TEXT
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_actor BIGINT;
    v_state TEXT;
BEGIN
    v_actor := public.current_workspace_user_id();
    IF v_actor IS NULL OR NOT EXISTS (
        SELECT 1 FROM public.workspace_member wm
        WHERE wm.workspace_id = p_workspace_id AND wm.user_id = v_actor
    ) THEN
        RETURN 'NOT_FOUND';
    END IF;

    -- Locked, so a call that waited behind another purge of the same entry
    -- (a second click, or the worker's sweep) sees what that one did.
    SELECT r.state INTO v_state
    FROM public.deletion_request r
    WHERE r.workspace_id = p_workspace_id AND r.id = p_request_id AND r.scope = 'DOCUMENT'
    FOR UPDATE;
    IF NOT FOUND THEN
        RETURN 'NOT_FOUND';
    END IF;
    IF v_state IN ('PURGED', 'VERIFIED') THEN
        RETURN 'PURGED';
    END IF;

    RETURN public.retention_execute_purge(p_request_id);
END;
$$;

-- Deletes the caller's own workspace for good. Only its owner may ask, and
-- there is no trash step and no "scheduled" state: either the workspace is
-- gone when this returns, or a worker still holds one of its jobs, nothing
-- but that job's cancellation request was changed, and the caller asks
-- again in a moment. A deletion left to finish by itself would let the
-- person sign back in to a workspace that then vanishes under them. The
-- request id comes back so the caller can name the ledger entry even
-- though nobody can read it once the membership is gone.
CREATE FUNCTION delete_workspace(
    p_workspace_id BIGINT
) RETURNS TABLE (
    outcome TEXT,
    request_id BIGINT
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_actor BIGINT;
    v_now TIMESTAMPTZ;
BEGIN
    v_actor := public.current_workspace_user_id();
    IF v_actor IS NULL OR NOT EXISTS (
        SELECT 1
        FROM public.workspace w
        JOIN public.workspace_member wm ON wm.workspace_id = w.id AND wm.user_id = v_actor
        WHERE w.id = p_workspace_id AND w.owner_user_id = v_actor AND wm.role = 'OWNER'
    ) THEN
        outcome := 'NOT_FOUND';
        request_id := NULL;
        RETURN NEXT;
        RETURN;
    END IF;

    PERFORM public.retention_cancel_document_jobs(
        d.workspace_id, d.id, 'Job cancelled because its workspace was deleted.')
    FROM public.document d
    WHERE d.workspace_id = p_workspace_id;
    IF EXISTS (
        SELECT 1 FROM public.job j
        WHERE j.workspace_id = p_workspace_id
          AND j.state IN ('LEASED', 'CANCEL_REQUESTED')
          AND j.lease_expires_at > clock_timestamp()
    ) THEN
        outcome := 'JOBS_STILL_STOPPING';
        request_id := NULL;
        RETURN NEXT;
        RETURN;
    END IF;

    v_now := clock_timestamp();
    INSERT INTO public.deletion_request (workspace_id, scope, target_id, state, requested_by_user_id, requested_at, purge_after)
    VALUES (p_workspace_id, 'WORKSPACE', p_workspace_id, 'TRASHED', v_actor, v_now, v_now)
    RETURNING id INTO request_id;

    outcome := public.retention_execute_purge(request_id);
    IF outcome <> 'PURGED' THEN
        RAISE EXCEPTION 'A workspace deletion that was cleared to proceed answered %.', outcome;
    END IF;
    RETURN NEXT;
END;
$$;

REVOKE ALL ON FUNCTION trash_document(BIGINT, BIGINT, INTEGER) FROM PUBLIC, brownie_api, brownie_worker;
REVOKE ALL ON FUNCTION restore_trashed_document(BIGINT, BIGINT) FROM PUBLIC, brownie_api, brownie_worker;
REVOKE ALL ON FUNCTION purge_trashed_document(BIGINT, BIGINT) FROM PUBLIC, brownie_api, brownie_worker;
REVOKE ALL ON FUNCTION delete_workspace(BIGINT) FROM PUBLIC, brownie_api, brownie_worker;
GRANT EXECUTE ON FUNCTION trash_document(BIGINT, BIGINT, INTEGER) TO brownie_api;
GRANT EXECUTE ON FUNCTION restore_trashed_document(BIGINT, BIGINT) TO brownie_api;
GRANT EXECUTE ON FUNCTION purge_trashed_document(BIGINT, BIGINT) TO brownie_api;
GRANT EXECUTE ON FUNCTION delete_workspace(BIGINT) TO brownie_api;

-- What the trusted worker may do: carry out trash that has expired, remove
-- queued objects, and close a request once nothing is left. It never reads
-- a tenant table directly; it gets ids and opaque object keys.

-- Carries out up to p_limit expired trash entries, oldest first, and
-- returns one row per entry with what happened to it.
CREATE FUNCTION worker_purge_expired_trash(
    p_limit INTEGER
) RETURNS TABLE (
    request_id BIGINT,
    outcome TEXT
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_id BIGINT;
BEGIN
    IF session_user <> 'brownie_worker' THEN
        RAISE EXCEPTION 'Worker retention routines require the brownie_worker login.'
            USING ERRCODE = '42501';
    END IF;
    IF p_limit IS NULL OR p_limit NOT BETWEEN 1 AND 256 THEN
        RAISE EXCEPTION 'The purge batch size must be between one and 256.';
    END IF;

    FOR v_id IN
        SELECT r.id
        FROM public.deletion_request r
        WHERE r.state = 'TRASHED'
          AND r.purge_after <= clock_timestamp()
        ORDER BY r.failed_purge_count, r.purge_after, r.id
        LIMIT p_limit
        FOR UPDATE SKIP LOCKED
    LOOP
        request_id := v_id;
        -- One request that cannot be carried out (a table added since that
        -- this migration's purge does not know, say) must not undo the
        -- others in the same sweep; it is reported and tried again later.
        BEGIN
            outcome := public.retention_execute_purge(v_id);
        EXCEPTION WHEN OTHERS THEN
            outcome := 'FAILED';
            UPDATE public.deletion_request r
            SET failed_purge_count = r.failed_purge_count + 1
            WHERE r.id = v_id;
        END;
        RETURN NEXT;
    END LOOP;
END;
$$;

-- Hands out up to p_limit objects still waiting to be removed and counts
-- the attempt, fewest attempts first: an object the store keeps refusing
-- to remove falls behind the ones that can be removed instead of holding
-- the head of the queue. Skips rows another worker holds.
CREATE FUNCTION worker_collect_pending_blob_deletions(
    p_limit INTEGER
) RETURNS SETOF public.deletion_blob_task
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
BEGIN
    IF session_user <> 'brownie_worker' THEN
        RAISE EXCEPTION 'Worker retention routines require the brownie_worker login.'
            USING ERRCODE = '42501';
    END IF;
    IF p_limit IS NULL OR p_limit NOT BETWEEN 1 AND 512 THEN
        RAISE EXCEPTION 'The deletion batch size must be between one and 512.';
    END IF;

    RETURN QUERY
    UPDATE public.deletion_blob_task t
    SET attempt_count = t.attempt_count + 1
    WHERE t.id IN (
        SELECT c.id
        FROM public.deletion_blob_task c
        WHERE c.state = 'PENDING'
        ORDER BY c.attempt_count, c.id
        LIMIT p_limit
        FOR UPDATE SKIP LOCKED)
    RETURNING t.*;
END;
$$;

-- Closes a purged request when nothing of its target is left: no row that
-- names it and no object still queued. Also the way a request that queued
-- no objects at all gets closed.
CREATE FUNCTION worker_verify_deletion(
    p_request_id BIGINT
) RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_state TEXT;
    v_remaining_rows BIGINT;
    v_pending_objects BIGINT;
BEGIN
    IF session_user <> 'brownie_worker' THEN
        RAISE EXCEPTION 'Worker retention routines require the brownie_worker login.'
            USING ERRCODE = '42501';
    END IF;

    SELECT r.state INTO v_state
    FROM public.deletion_request r
    WHERE r.id = p_request_id
    FOR UPDATE;
    IF NOT FOUND OR v_state <> 'PURGED' THEN
        RETURN COALESCE(v_state = 'VERIFIED', FALSE);
    END IF;

    SELECT rem.remaining_rows, rem.pending_objects INTO v_remaining_rows, v_pending_objects
    FROM public.retention_remaining(p_request_id) rem;
    IF v_remaining_rows > 0 OR v_pending_objects > 0 THEN
        RETURN FALSE;
    END IF;

    UPDATE public.deletion_request r
    SET state = 'VERIFIED', verified_at = clock_timestamp()
    WHERE r.id = p_request_id;
    RETURN TRUE;
END;
$$;

-- Records that one object is gone. When that was the last one of its
-- request and a recount finds no row left, the request becomes VERIFIED.
CREATE FUNCTION worker_mark_blob_deleted(
    p_task_id BIGINT,
    p_object_key TEXT
) RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_request_id BIGINT;
BEGIN
    IF session_user <> 'brownie_worker' THEN
        RAISE EXCEPTION 'Worker retention routines require the brownie_worker login.'
            USING ERRCODE = '42501';
    END IF;

    UPDATE public.deletion_blob_task t
    SET state = 'DELETED', deleted_at = clock_timestamp()
    WHERE t.id = p_task_id
      AND t.object_key = p_object_key
      AND t.state = 'PENDING'
    RETURNING t.deletion_request_id INTO v_request_id;
    IF NOT FOUND THEN
        RETURN FALSE;
    END IF;

    PERFORM public.worker_verify_deletion(v_request_id);
    RETURN TRUE;
END;
$$;

-- Purged requests that queued nothing, or whose last object was removed
-- while verification could not run, still need closing.
CREATE FUNCTION worker_collect_unverified_deletions(
    p_limit INTEGER
) RETURNS SETOF BIGINT
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
BEGIN
    IF session_user <> 'brownie_worker' THEN
        RAISE EXCEPTION 'Worker retention routines require the brownie_worker login.'
            USING ERRCODE = '42501';
    END IF;
    IF p_limit IS NULL OR p_limit NOT BETWEEN 1 AND 256 THEN
        RAISE EXCEPTION 'The verification batch size must be between one and 256.';
    END IF;

    RETURN QUERY
    SELECT r.id
    FROM public.deletion_request r
    WHERE r.state = 'PURGED'
      AND NOT EXISTS (
          SELECT 1 FROM public.deletion_blob_task t
          WHERE t.deletion_request_id = r.id AND t.state = 'PENDING')
    ORDER BY r.id
    LIMIT p_limit;
END;
$$;

REVOKE ALL ON FUNCTION worker_purge_expired_trash(INTEGER) FROM PUBLIC, brownie_api, brownie_worker;
REVOKE ALL ON FUNCTION worker_collect_pending_blob_deletions(INTEGER) FROM PUBLIC, brownie_api, brownie_worker;
REVOKE ALL ON FUNCTION worker_mark_blob_deleted(BIGINT, TEXT) FROM PUBLIC, brownie_api, brownie_worker;
REVOKE ALL ON FUNCTION worker_verify_deletion(BIGINT) FROM PUBLIC, brownie_api, brownie_worker;
REVOKE ALL ON FUNCTION worker_collect_unverified_deletions(INTEGER) FROM PUBLIC, brownie_api, brownie_worker;
GRANT EXECUTE ON FUNCTION worker_purge_expired_trash(INTEGER) TO brownie_worker;
GRANT EXECUTE ON FUNCTION worker_collect_pending_blob_deletions(INTEGER) TO brownie_worker;
GRANT EXECUTE ON FUNCTION worker_mark_blob_deleted(BIGINT, TEXT) TO brownie_worker;
GRANT EXECUTE ON FUNCTION worker_verify_deletion(BIGINT) TO brownie_worker;
GRANT EXECUTE ON FUNCTION worker_collect_unverified_deletions(INTEGER) TO brownie_worker;
