-- One document per row of a spreadsheet: a batch pins a template, the exact
-- template version it was made on, and the uploaded file the table came from,
-- so it can be filled in bounded steps that survive a closed browser tab.
--
-- The batch row set is the whole record of what a fill did: one row per data
-- row of the table, numbered as the person sees them in their file, so a
-- resumed step picks up exactly where a request stopped and the same row can
-- never produce two documents.

-- The batch itself. The mapping of spreadsheet columns to the template's
-- fields is a bounded array of small objects, kept as JSONB the way a
-- version's field definitions are; a step's lease is the three step_*
-- columns together, all null while nothing is running.
CREATE TABLE batch (
    id BIGSERIAL PRIMARY KEY,
    workspace_id BIGINT NOT NULL REFERENCES workspace (id),
    template_id BIGINT NOT NULL,
    template_version_id BIGINT NOT NULL,
    source_artifact_id BIGINT NOT NULL,
    source_sha256 TEXT NOT NULL,
    table_format TEXT NOT NULL,
    table_reader_version TEXT NOT NULL,
    sheet_name TEXT,
    has_header_row BOOLEAN NOT NULL,
    date_order TEXT NOT NULL,
    mapping JSONB NOT NULL,
    title_column INTEGER,
    title TEXT NOT NULL,
    row_count INTEGER NOT NULL,
    idempotency_key TEXT NOT NULL,
    request_hash TEXT NOT NULL,
    step_lease_token UUID,
    step_lease_until TIMESTAMPTZ,
    step_kind TEXT,
    created_by_user_id BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT batch_workspace_id_id_key UNIQUE (workspace_id, id),
    -- Making a batch is idempotent per member: a retried request answers
    -- with the same batch instead of making another.
    CONSTRAINT batch_idempotency_key UNIQUE (workspace_id, created_by_user_id, idempotency_key),
    CONSTRAINT batch_template_fk
        FOREIGN KEY (workspace_id, template_id) REFERENCES template (workspace_id, id),
    CONSTRAINT batch_template_version_fk
        FOREIGN KEY (workspace_id, template_id, template_version_id)
        REFERENCES template_version (workspace_id, template_id, id),
    CONSTRAINT batch_source_artifact_fk
        FOREIGN KEY (workspace_id, source_artifact_id) REFERENCES artifact (workspace_id, id),
    CONSTRAINT batch_creator_membership_fk
        FOREIGN KEY (workspace_id, created_by_user_id) REFERENCES workspace_member (workspace_id, user_id),
    CONSTRAINT batch_table_format_known CHECK (table_format IN ('XLSX', 'CSV')),
    CONSTRAINT batch_table_reader_version_present CHECK (char_length(table_reader_version) BETWEEN 1 AND 200),
    CONSTRAINT batch_sheet_name_bounded CHECK (sheet_name IS NULL OR char_length(sheet_name) BETWEEN 1 AND 200),
    CONSTRAINT batch_date_order_known CHECK (date_order IN ('MDY', 'DMY')),
    CONSTRAINT batch_mapping_array CHECK (jsonb_typeof(mapping) = 'array'),
    CONSTRAINT batch_title_column_nonnegative CHECK (title_column IS NULL OR title_column >= 0),
    CONSTRAINT batch_title_present CHECK (char_length(btrim(title)) BETWEEN 1 AND 200),
    CONSTRAINT batch_row_count_bounded CHECK (row_count BETWEEN 0 AND 10000),
    CONSTRAINT batch_idempotency_key_present CHECK (char_length(idempotency_key) BETWEEN 1 AND 200),
    CONSTRAINT batch_request_hash_sha256 CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    -- A lease is held wholly or not at all: the token that owns it, the
    -- moment it lapses, and the kind of step holding it move together.
    CONSTRAINT batch_step_lease_together
        CHECK ((step_lease_token IS NULL) = (step_lease_until IS NULL)
               AND (step_lease_until IS NULL) = (step_kind IS NULL)),
    CONSTRAINT batch_step_kind_known CHECK (step_kind IS NULL OR step_kind IN ('FILL', 'ACCEPT', 'CHECK', 'EXPORT', 'COMPOSE')),
    CONSTRAINT batch_creator_positive CHECK (created_by_user_id > 0)
);

CREATE INDEX batch_workspace_id_created_at_idx ON batch (workspace_id, created_at, id);

-- One row per data row of the table the batch was made from. The document
-- a fill made for it is named until that document is deleted for good --
-- batch deletion removes only the batch, never the documents, so this link
-- is exactly what a later page uses to find them.
CREATE TABLE batch_row (
    workspace_id BIGINT NOT NULL,
    batch_id BIGINT NOT NULL,
    row_number INTEGER NOT NULL,
    state TEXT NOT NULL,
    document_id BIGINT,
    initial_revision_id BIGINT,
    problems JSONB NOT NULL DEFAULT '[]',
    compose_state TEXT NOT NULL DEFAULT 'NOT_NEEDED',
    proposal_id BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT batch_row_pkey PRIMARY KEY (batch_id, row_number),
    CONSTRAINT batch_row_batch_fk
        FOREIGN KEY (workspace_id, batch_id) REFERENCES batch (workspace_id, id) ON DELETE CASCADE,
    CONSTRAINT batch_row_document_fk
        FOREIGN KEY (workspace_id, document_id) REFERENCES document (workspace_id, id),
    CONSTRAINT batch_row_initial_revision_fk
        FOREIGN KEY (workspace_id, document_id, initial_revision_id)
        REFERENCES document_revision (workspace_id, document_id, id),
    CONSTRAINT batch_row_proposal_fk
        FOREIGN KEY (workspace_id, proposal_id) REFERENCES document_patch_proposal (workspace_id, id),
    CONSTRAINT batch_row_number_positive CHECK (row_number > 0),
    CONSTRAINT batch_row_state_known
        CHECK (state IN ('PENDING', 'CREATED', 'SKIPPED_BLANK', 'FAILED', 'DOCUMENT_DELETED')),
    -- A row says CREATED exactly when it names the document and the
    -- revision the fill produced; every other state names neither.
    CONSTRAINT batch_row_created_names_document
        CHECK ((state = 'CREATED') = (document_id IS NOT NULL AND initial_revision_id IS NOT NULL)),
    CONSTRAINT batch_row_problems_array CHECK (jsonb_typeof(problems) = 'array'),
    CONSTRAINT batch_row_compose_state_known
        CHECK (compose_state IN ('NOT_NEEDED', 'PENDING', 'PROPOSED', 'FAILED', 'NO_ALLOWANCE'))
);

CREATE INDEX batch_row_workspace_id_idx ON batch_row (workspace_id);
CREATE INDEX batch_row_batch_state_idx ON batch_row (batch_id, state);

-- One export run over a batch's documents, idempotent per member. An item
-- per row records what the run did with each, so a resumed step continues
-- the same export instead of starting it over.
CREATE TABLE batch_export (
    id BIGSERIAL PRIMARY KEY,
    workspace_id BIGINT NOT NULL,
    batch_id BIGINT NOT NULL,
    format TEXT NOT NULL,
    state TEXT NOT NULL,
    idempotency_key TEXT NOT NULL,
    request_hash TEXT NOT NULL,
    created_by_user_id BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    CONSTRAINT batch_export_workspace_id_id_key UNIQUE (workspace_id, id),
    CONSTRAINT batch_export_idempotency_key UNIQUE (batch_id, created_by_user_id, idempotency_key),
    CONSTRAINT batch_export_batch_fk
        FOREIGN KEY (workspace_id, batch_id) REFERENCES batch (workspace_id, id) ON DELETE CASCADE,
    CONSTRAINT batch_export_creator_membership_fk
        FOREIGN KEY (workspace_id, created_by_user_id) REFERENCES workspace_member (workspace_id, user_id),
    CONSTRAINT batch_export_format_known CHECK (format IN ('DOCX', 'PDF', 'BOTH')),
    CONSTRAINT batch_export_state_known CHECK (state IN ('RUNNING', 'COMPLETE')),
    CONSTRAINT batch_export_complete_has_time CHECK ((state = 'COMPLETE') = (completed_at IS NOT NULL)),
    CONSTRAINT batch_export_idempotency_key_present CHECK (char_length(idempotency_key) BETWEEN 1 AND 200),
    CONSTRAINT batch_export_request_hash_sha256 CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT batch_export_creator_positive CHECK (created_by_user_id > 0)
);

CREATE INDEX batch_export_workspace_id_idx ON batch_export (workspace_id);

-- The composite foreign key from batch_export_item needs it, the same
-- cross-tenant-reference protection every other tenant table already has.
ALTER TABLE export_receipt ADD CONSTRAINT export_receipt_workspace_id_id_key UNIQUE (workspace_id, id);

CREATE TABLE batch_export_item (
    workspace_id BIGINT NOT NULL,
    batch_export_id BIGINT NOT NULL,
    row_number INTEGER NOT NULL,
    outcome TEXT NOT NULL,
    export_receipt_id BIGINT,
    detail TEXT,
    -- The document's content hash the moment the export was made: approval
    -- binds the run to exactly that content, and an item whose document has
    -- changed since is told apart from an unfinished one rather than
    -- exported. Written at insert, never afterwards.
    approved_content_hash TEXT,
    CONSTRAINT batch_export_item_pkey PRIMARY KEY (batch_export_id, row_number),
    CONSTRAINT batch_export_item_export_fk
        FOREIGN KEY (workspace_id, batch_export_id) REFERENCES batch_export (workspace_id, id) ON DELETE CASCADE,
    CONSTRAINT batch_export_item_receipt_fk
        FOREIGN KEY (workspace_id, export_receipt_id) REFERENCES export_receipt (workspace_id, id),
    CONSTRAINT batch_export_item_row_number_positive CHECK (row_number > 0),
    CONSTRAINT batch_export_item_outcome_known
        CHECK (outcome IN ('PENDING', 'EXPORTED', 'BLOCKED', 'SUGGESTION_WAITING', 'NOT_CREATED',
                           'DOCUMENT_GONE', 'CHANGED', 'FAILED')),
    CONSTRAINT batch_export_item_exported_has_receipt
        CHECK ((outcome = 'EXPORTED') = (export_receipt_id IS NOT NULL)),
    -- A code plus a field id, never anything a cell or a person wrote.
    CONSTRAINT batch_export_item_detail_bounded CHECK (detail IS NULL OR char_length(detail) <= 500),
    CONSTRAINT batch_export_item_approved_content_hash_sha256
        CHECK (approved_content_hash IS NULL OR approved_content_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX batch_export_item_workspace_id_idx ON batch_export_item (workspace_id);

ALTER TABLE batch ENABLE ROW LEVEL SECURITY;
ALTER TABLE batch_row ENABLE ROW LEVEL SECURITY;
ALTER TABLE batch_export ENABLE ROW LEVEL SECURITY;
ALTER TABLE batch_export_item ENABLE ROW LEVEL SECURITY;

CREATE POLICY batch_member_select ON batch
    FOR SELECT TO brownie_api
    USING (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = batch.workspace_id AND wm.user_id = current_workspace_user_id()
        )
    );

-- Only in the member's own name.
CREATE POLICY batch_member_insert ON batch
    FOR INSERT TO brownie_api
    WITH CHECK (
        created_by_user_id = current_workspace_user_id()
        AND EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = batch.workspace_id AND wm.user_id = current_workspace_user_id()
        )
    );

CREATE POLICY batch_member_update ON batch
    FOR UPDATE TO brownie_api
    USING (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = batch.workspace_id AND wm.user_id = current_workspace_user_id()
        )
    )
    WITH CHECK (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = batch.workspace_id AND wm.user_id = current_workspace_user_id()
        )
    );

CREATE POLICY batch_row_member_select ON batch_row
    FOR SELECT TO brownie_api
    USING (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = batch_row.workspace_id AND wm.user_id = current_workspace_user_id()
        )
    );

CREATE POLICY batch_row_member_insert ON batch_row
    FOR INSERT TO brownie_api
    WITH CHECK (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = batch_row.workspace_id AND wm.user_id = current_workspace_user_id()
        )
    );

CREATE POLICY batch_row_member_update ON batch_row
    FOR UPDATE TO brownie_api
    USING (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = batch_row.workspace_id AND wm.user_id = current_workspace_user_id()
        )
    )
    WITH CHECK (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = batch_row.workspace_id AND wm.user_id = current_workspace_user_id()
        )
    );

CREATE POLICY batch_export_member_select ON batch_export
    FOR SELECT TO brownie_api
    USING (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = batch_export.workspace_id AND wm.user_id = current_workspace_user_id()
        )
    );

CREATE POLICY batch_export_member_insert ON batch_export
    FOR INSERT TO brownie_api
    WITH CHECK (
        created_by_user_id = current_workspace_user_id()
        AND EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = batch_export.workspace_id AND wm.user_id = current_workspace_user_id()
        )
    );

CREATE POLICY batch_export_member_update ON batch_export
    FOR UPDATE TO brownie_api
    USING (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = batch_export.workspace_id AND wm.user_id = current_workspace_user_id()
        )
    )
    WITH CHECK (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = batch_export.workspace_id AND wm.user_id = current_workspace_user_id()
        )
    );

CREATE POLICY batch_export_item_member_select ON batch_export_item
    FOR SELECT TO brownie_api
    USING (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = batch_export_item.workspace_id AND wm.user_id = current_workspace_user_id()
        )
    );

CREATE POLICY batch_export_item_member_insert ON batch_export_item
    FOR INSERT TO brownie_api
    WITH CHECK (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = batch_export_item.workspace_id AND wm.user_id = current_workspace_user_id()
        )
    );

CREATE POLICY batch_export_item_member_update ON batch_export_item
    FOR UPDATE TO brownie_api
    USING (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = batch_export_item.workspace_id AND wm.user_id = current_workspace_user_id()
        )
    )
    WITH CHECK (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = batch_export_item.workspace_id AND wm.user_id = current_workspace_user_id()
        )
    );

-- A batch's fixed record -- which template, which version, which file, the
-- mapping -- is never edited. What may change is narrow and named column
-- by column: a step's lease on the batch; what a step did with each row;
-- an export's state; an item's outcome. Nothing deletes any of it but
-- delete_batch and the retention routines, which run as the table's owner.
REVOKE UPDATE, DELETE, TRUNCATE ON batch FROM brownie_api, brownie_worker;
GRANT UPDATE (step_lease_token, step_lease_until, step_kind) ON batch TO brownie_api;

REVOKE UPDATE, DELETE, TRUNCATE ON batch_row FROM brownie_api, brownie_worker;
GRANT UPDATE (state, document_id, initial_revision_id, problems, compose_state, proposal_id, updated_at)
    ON batch_row TO brownie_api;

REVOKE UPDATE, DELETE, TRUNCATE ON batch_export FROM brownie_api, brownie_worker;
GRANT UPDATE (state, completed_at) ON batch_export TO brownie_api;

REVOKE UPDATE, DELETE, TRUNCATE ON batch_export_item FROM brownie_api, brownie_worker;
GRANT UPDATE (outcome, export_receipt_id, detail) ON batch_export_item TO brownie_api;

-- Removing a batch removes only the batch: its rows, its exports and their
-- items, then the batch itself. The documents a fill made stay; the
-- spreadsheet it read is then unreferenced and the ordinary unreferenced
-- sweep collects it. Membership is checked the same way trash_document
-- checks it, so the route can answer "not there" for a batch in someone
-- else's workspace the same as one that does not exist.
CREATE FUNCTION delete_batch(
    p_workspace_id BIGINT,
    p_batch_id BIGINT
) RETURNS TEXT
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_actor BIGINT;
    v_row_count INTEGER;
    v_lease_until TIMESTAMPTZ;
BEGIN
    IF p_workspace_id <= 0 OR p_batch_id <= 0 THEN
        RETURN 'NOT_FOUND';
    END IF;

    v_actor := public.current_workspace_user_id();
    IF v_actor IS NULL OR NOT EXISTS (
        SELECT 1 FROM public.workspace_member wm
        WHERE wm.workspace_id = p_workspace_id AND wm.user_id = v_actor
    ) THEN
        RETURN 'NOT_FOUND';
    END IF;

    -- A step's lease lives on this same row; taking the row before checking
    -- it keeps a delete from slipping in while a lease is being written.
    SELECT b.row_count, b.step_lease_until INTO v_row_count, v_lease_until
    FROM public.batch b
    WHERE b.workspace_id = p_workspace_id AND b.id = p_batch_id
    FOR UPDATE;
    IF NOT FOUND THEN
        RETURN 'NOT_FOUND';
    END IF;

    -- A live lease means a step is still writing this batch's rows; the
    -- batch stays until the step lets go or its lease lapses.
    IF v_lease_until IS NOT NULL AND v_lease_until > clock_timestamp() THEN
        RETURN 'BUSY';
    END IF;

    DELETE FROM public.batch_export_item i
    USING public.batch_export e
    WHERE e.workspace_id = i.workspace_id AND e.id = i.batch_export_id
      AND i.workspace_id = p_workspace_id AND e.batch_id = p_batch_id;
    DELETE FROM public.batch_export e
    WHERE e.workspace_id = p_workspace_id AND e.batch_id = p_batch_id;
    DELETE FROM public.batch_row r
    WHERE r.workspace_id = p_workspace_id AND r.batch_id = p_batch_id;
    DELETE FROM public.batch b
    WHERE b.workspace_id = p_workspace_id AND b.id = p_batch_id;

    PERFORM public.audit_append(
        p_workspace_id,
        v_actor,
        'BATCH_DELETED',
        'batch',
        p_batch_id,
        jsonb_build_object('rowCount', v_row_count));
    RETURN 'DELETED';
END;
$$;

REVOKE ALL ON FUNCTION delete_batch(BIGINT, BIGINT) FROM PUBLIC, brownie_api, brownie_worker;
GRANT EXECUTE ON FUNCTION delete_batch(BIGINT, BIGINT) TO brownie_api;

-- Three new audited actions: a batch made, a batch's export finished, a
-- batch removed. Their details carry ids and counts only, never a label a
-- person typed.
ALTER TABLE audit_event DROP CONSTRAINT audit_event_action_known;
ALTER TABLE audit_event ADD CONSTRAINT audit_event_action_known CHECK (action IN (
    'DOCUMENT_TRASHED', 'DOCUMENT_RESTORED', 'DOCUMENT_DELETED', 'WORKSPACE_DELETED',
    'DOCUMENT_EXPORTED', 'JOB_RETRIED', 'SUPPORT_GRANT_CREATED', 'SUPPORT_GRANT_REVOKED',
    'CONNECTOR_CONNECTED', 'CONNECTOR_DISCONNECTED', 'SOURCE_IMPORTED',
    'EXTERNAL_ACTION_APPROVED', 'EXTERNAL_ACTION_SENT', 'EXTERNAL_ACTION_FINISHED',
    'TEMPLATE_VERSION_DERIVED', 'DOCUMENT_TEMPLATE_VERSION_CHANGED',
    'BATCH_CREATED', 'BATCH_EXPORTED', 'BATCH_DELETED'
));

-- Deleting a document for good: the routine from before (V49), unchanged
-- except that before its export receipts and the document itself go, a
-- batch row that named it is marked DOCUMENT_DELETED and names it no
-- longer, and an export item that named one of its receipts reads
-- DOCUMENT_GONE. The batch keeps the row: the fill happened; only what it
-- made is gone.
CREATE OR REPLACE FUNCTION retention_purge_document(
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
          WHERE te.workspace_id = p_workspace_id AND te.source_artifact_id = s.artifact_id)
      AND NOT EXISTS (
          SELECT 1 FROM public.artifact_derivation d
          JOIN public.template_version tv
            ON tv.workspace_id = d.workspace_id AND tv.source_artifact_id = d.output_artifact_id
          WHERE d.workspace_id = p_workspace_id AND d.source_artifact_id = s.artifact_id);

    SELECT COALESCE(array_agg(DISTINCT owned.artifact_id), '{}') INTO v_artifact_ids
    FROM (
        SELECT c.docx_artifact_id AS artifact_id FROM public.document_compilation c
        WHERE c.workspace_id = p_workspace_id AND c.document_id = p_document_id AND c.docx_artifact_id IS NOT NULL
        UNION ALL
        SELECT c.pdf_artifact_id FROM public.document_compilation c
        WHERE c.workspace_id = p_workspace_id AND c.document_id = p_document_id
        UNION ALL
        SELECT m.docx_artifact_id FROM public.validation_manifest m
        WHERE m.workspace_id = p_workspace_id AND m.document_id = p_document_id AND m.docx_artifact_id IS NOT NULL
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
              SELECT 1 FROM public.artifact_derivation d
              JOIN public.template_version tv
                ON tv.workspace_id = d.workspace_id AND tv.source_artifact_id = d.output_artifact_id
              WHERE d.workspace_id = p_workspace_id AND d.source_artifact_id = owned.artifact_id)
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

    -- What the person proposed or had Brownie do in their own account for
    -- this document holds what was sent (a file name, an event's text, the
    -- text added to a Doc), so it goes with the document: attempts, then the
    -- actions, then the export receipts they name. What was made in the
    -- person's account stays there. The actions are locked first, as every
    -- routine that writes an attempt locks its action first.
    PERFORM 1 FROM public.action_request a
    WHERE a.workspace_id = p_workspace_id AND a.document_id = p_document_id
    ORDER BY a.id
    FOR UPDATE;

    -- The batch rows that named this document, and the export items that
    -- named its receipts, name them no longer: the document is gone, the
    -- batch's record of what its fill made stays. Done before the receipts
    -- and the document below are removed, since both point at them.
    UPDATE public.batch_row r
    SET state = 'DOCUMENT_DELETED', document_id = NULL, initial_revision_id = NULL,
        proposal_id = NULL, updated_at = clock_timestamp()
    WHERE r.workspace_id = p_workspace_id AND r.document_id = p_document_id;
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;
    UPDATE public.batch_export_item i
    SET outcome = 'DOCUMENT_GONE', export_receipt_id = NULL
    FROM public.export_receipt x
    WHERE x.workspace_id = i.workspace_id AND x.id = i.export_receipt_id
      AND i.workspace_id = p_workspace_id AND x.document_id = p_document_id;
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;

    DELETE FROM public.action_attempt x
    USING public.action_request a
    WHERE a.workspace_id = x.workspace_id AND a.id = x.action_id
      AND a.workspace_id = p_workspace_id AND a.document_id = p_document_id;
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;
    DELETE FROM public.action_request x WHERE x.workspace_id = p_workspace_id AND x.document_id = p_document_id;
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;

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
    DELETE FROM public.pdf_form_extraction_version x WHERE x.workspace_id = p_workspace_id AND x.artifact_id = ANY (v_artifact_ids);
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

-- Deleting a workspace: the routine from before (V49), unchanged except
-- that the workspace's batch export items, exports, rows and batches go
-- first, before its documents -- a batch row can still name one -- and
-- before its templates and files, which a batch names.
CREATE OR REPLACE FUNCTION retention_purge_workspace(
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

    -- The connections next, before anything that names them: every other
    -- path that changes a connection (disconnecting, recording a choice,
    -- recording a copy) takes the connection before its choices, and taking
    -- them the other way round here could deadlock against any of them. An
    -- import or a new choice still being recorded is waited for, so what it
    -- wrote is removed below with the rest rather than failing the deletion.
    PERFORM 1
    FROM public.connector_connection c
    WHERE c.workspace_id = p_workspace_id
    ORDER BY c.id
    FOR UPDATE;

    DELETE FROM public.batch_export_item x WHERE x.workspace_id = p_workspace_id;
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;
    DELETE FROM public.batch_export x WHERE x.workspace_id = p_workspace_id;
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;
    DELETE FROM public.batch_row x WHERE x.workspace_id = p_workspace_id;
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;
    DELETE FROM public.batch x WHERE x.workspace_id = p_workspace_id;
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;

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
    DELETE FROM public.fill_spot_review x WHERE x.workspace_id = p_workspace_id;
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
    DELETE FROM public.connector_resource_grant x WHERE x.workspace_id = p_workspace_id;
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;
    DELETE FROM public.connector_connection x WHERE x.workspace_id = p_workspace_id;
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;
    DELETE FROM public.artifact_derivation x WHERE x.workspace_id = p_workspace_id;
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;
    DELETE FROM public.extraction_version x WHERE x.workspace_id = p_workspace_id;
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;
    DELETE FROM public.pdf_extraction_version x WHERE x.workspace_id = p_workspace_id;
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;
    DELETE FROM public.pdf_form_extraction_version x WHERE x.workspace_id = p_workspace_id;
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

-- Counting what a deletion still has to remove: the routine from before
-- (V45), unchanged except that a workspace's count covers the four batch
-- tables too, and a document's count covers the batch rows still naming it.
CREATE OR REPLACE FUNCTION retention_remaining(
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
            + (SELECT count(*) FROM public.action_request x WHERE x.workspace_id = v_request.workspace_id AND x.document_id = v_request.target_id)
            + (SELECT count(*) FROM public.batch_row x WHERE x.workspace_id = v_request.workspace_id AND x.document_id = v_request.target_id)
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
            + (SELECT count(*) FROM public.batch_export_item x WHERE x.workspace_id = v_request.workspace_id)
            + (SELECT count(*) FROM public.batch_export x WHERE x.workspace_id = v_request.workspace_id)
            + (SELECT count(*) FROM public.batch_row x WHERE x.workspace_id = v_request.workspace_id)
            + (SELECT count(*) FROM public.batch x WHERE x.workspace_id = v_request.workspace_id)
            + (SELECT count(*) FROM public.job x WHERE x.workspace_id = v_request.workspace_id)
            + (SELECT count(*) FROM public.source_snapshot x WHERE x.workspace_id = v_request.workspace_id)
            + (SELECT count(*) FROM public.plain_text_extraction_version x WHERE x.workspace_id = v_request.workspace_id)
            + (SELECT count(*) FROM public.connector_resource_grant x WHERE x.workspace_id = v_request.workspace_id)
            + (SELECT count(*) FROM public.connector_connection x WHERE x.workspace_id = v_request.workspace_id)
            + (SELECT count(*) FROM public.action_request x WHERE x.workspace_id = v_request.workspace_id)
            + (SELECT count(*) FROM public.action_attempt x WHERE x.workspace_id = v_request.workspace_id)
        INTO remaining_rows;
    END IF;

    SELECT count(*) INTO pending_objects
    FROM public.deletion_blob_task t
    WHERE t.deletion_request_id = p_request_id AND t.state = 'PENDING';

    RETURN NEXT;
END;
$$;

-- The sweep of files nothing refers to: the routine from before (V49),
-- unchanged except that a file a batch reads its rows from is referenced
-- for as long as the batch exists, so deleting a batch is what lets its
-- spreadsheet be collected.
CREATE OR REPLACE FUNCTION public.worker_collect_removable_payloads(
    p_rejected_after_millis BIGINT,
    p_unreferenced_after_millis BIGINT,
    p_limit INTEGER
) RETURNS TABLE (
    artifact_id BIGINT,
    workspace_id BIGINT,
    blob_key TEXT
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_now TIMESTAMPTZ;
    v_orphan_ids BIGINT[];
BEGIN
    IF session_user <> 'brownie_worker' THEN
        RAISE EXCEPTION 'Worker retention routines require the brownie_worker login.'
            USING ERRCODE = '42501';
    END IF;
    IF p_rejected_after_millis IS NULL OR p_rejected_after_millis < 0 THEN
        RAISE EXCEPTION 'The age after which a rejected file is removed must not be negative.';
    END IF;
    IF p_unreferenced_after_millis IS NULL OR p_unreferenced_after_millis < 3600000 THEN
        RAISE EXCEPTION 'A file cannot be called unreferenced before one hour has passed.';
    END IF;
    IF p_limit IS NULL OR p_limit NOT BETWEEN 1 AND 512 THEN
        RAISE EXCEPTION 'The sweep batch size must be between one and 512.';
    END IF;

    v_now := clock_timestamp();

    SELECT COALESCE(array_agg(c.id), '{}') INTO v_orphan_ids
    FROM (
        SELECT a.id
        FROM public.artifact a
        WHERE a.status = 'READY'
          AND COALESCE(a.finalized_at, a.created_at) <= v_now - make_interval(secs => p_unreferenced_after_millis / 1000.0)
          AND NOT EXISTS (SELECT 1 FROM public.source_snapshot x WHERE x.workspace_id = a.workspace_id AND x.artifact_id = a.id)
          AND NOT EXISTS (SELECT 1 FROM public.template_version x WHERE x.workspace_id = a.workspace_id AND x.source_artifact_id = a.id)
          AND NOT EXISTS (SELECT 1 FROM public.template_example x WHERE x.workspace_id = a.workspace_id AND x.source_artifact_id = a.id)
          AND NOT EXISTS (SELECT 1 FROM public.template_baseline_render x
                          WHERE x.workspace_id = a.workspace_id AND a.id IN (x.docx_artifact_id, x.pdf_artifact_id))
          AND NOT EXISTS (SELECT 1 FROM public.document_compilation x
                          WHERE x.workspace_id = a.workspace_id AND a.id IN (x.docx_artifact_id, x.pdf_artifact_id))
          AND NOT EXISTS (SELECT 1 FROM public.validation_manifest x
                          WHERE x.workspace_id = a.workspace_id AND a.id IN (x.docx_artifact_id, x.pdf_artifact_id))
          AND NOT EXISTS (SELECT 1 FROM public.export_receipt x
                          WHERE x.workspace_id = a.workspace_id AND a.id IN (x.docx_artifact_id, x.pdf_artifact_id))
          AND NOT EXISTS (SELECT 1 FROM public.job_output_artifact x WHERE x.workspace_id = a.workspace_id AND x.artifact_id = a.id)
          AND NOT EXISTS (SELECT 1 FROM public.batch x WHERE x.workspace_id = a.workspace_id AND x.source_artifact_id = a.id)
          AND NOT EXISTS (SELECT 1 FROM public.artifact_derivation d
                          JOIN public.template_version x
                            ON x.workspace_id = d.workspace_id AND x.source_artifact_id = d.output_artifact_id
                          WHERE d.workspace_id = a.workspace_id AND d.source_artifact_id = a.id)
        ORDER BY a.id
        LIMIT p_limit
        FOR UPDATE OF a SKIP LOCKED
    ) c;

    IF array_length(v_orphan_ids, 1) > 0 THEN
        DELETE FROM public.artifact_derivation x WHERE x.output_artifact_id = ANY (v_orphan_ids);
        DELETE FROM public.extraction_version x WHERE x.artifact_id = ANY (v_orphan_ids);
        DELETE FROM public.pdf_extraction_version x WHERE x.artifact_id = ANY (v_orphan_ids);
        DELETE FROM public.pdf_form_extraction_version x WHERE x.artifact_id = ANY (v_orphan_ids);
        DELETE FROM public.plain_text_extraction_version x WHERE x.artifact_id = ANY (v_orphan_ids);
        UPDATE public.artifact a
        SET status = 'REJECTED', rejection_reason = 'UNREFERENCED_EXPIRED'
        WHERE a.id = ANY (v_orphan_ids);
    END IF;

    RETURN QUERY
    SELECT a.id, a.workspace_id, a.blob_key
    FROM public.artifact a
    WHERE a.status = 'REJECTED'
      AND a.payload_removed_at IS NULL
      AND (a.id = ANY (v_orphan_ids)
           OR COALESCE(a.finalized_at, a.created_at) <= v_now - make_interval(secs => p_rejected_after_millis / 1000.0))
    ORDER BY a.id
    LIMIT p_limit
    FOR UPDATE OF a SKIP LOCKED;
END;
$$;

-- CREATE OR REPLACE keeps the routines' owner and permissions; they are
-- restated so that this file alone says who may run them.
REVOKE ALL ON FUNCTION retention_purge_document(BIGINT, BIGINT, BIGINT) FROM PUBLIC, brownie_api, brownie_worker;
REVOKE ALL ON FUNCTION retention_purge_workspace(BIGINT, BIGINT) FROM PUBLIC, brownie_api, brownie_worker;
REVOKE ALL ON FUNCTION retention_remaining(BIGINT) FROM PUBLIC, brownie_api, brownie_worker;
REVOKE ALL ON FUNCTION public.worker_collect_removable_payloads(BIGINT, BIGINT, INTEGER) FROM PUBLIC, brownie_api, brownie_worker;
GRANT EXECUTE ON FUNCTION public.worker_collect_removable_payloads(BIGINT, BIGINT, INTEGER) TO brownie_worker;
