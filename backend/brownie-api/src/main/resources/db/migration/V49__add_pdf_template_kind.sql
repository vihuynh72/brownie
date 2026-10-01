-- A PDF can now be a form Brownie fills, as a template of its own kind. Its
-- reading as a form (pages, words, drawn lines, fillable fields) is kept the
-- way a Word file's structure is kept: once per file and reader version,
-- never changed afterwards, and a template version is pinned to the one
-- reading its places were checked against.
--
-- The reading is its own table rather than a new use of
-- pdf_extraction_version (V9): that one holds a PDF read as a source, with
-- its own reader and cache, and reading a form does not change it.
CREATE TABLE pdf_form_extraction_version (
    id BIGSERIAL PRIMARY KEY,
    workspace_id BIGINT NOT NULL,
    artifact_id BIGINT NOT NULL,
    parser_version TEXT NOT NULL,
    status TEXT NOT NULL,
    unsupported_reason TEXT,
    unsupported_detail TEXT,
    graph JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pdf_form_extraction_version_artifact_fk
        FOREIGN KEY (workspace_id, artifact_id) REFERENCES artifact (workspace_id, id),
    CONSTRAINT pdf_form_extraction_version_artifact_parser_version_key UNIQUE (artifact_id, parser_version),
    CONSTRAINT pdf_form_extraction_version_workspace_id_id_key UNIQUE (workspace_id, id),
    -- A reading either holds the form or says why the whole file cannot be
    -- filled; never both, never neither.
    CONSTRAINT pdf_form_extraction_version_outcome CHECK (
        (status = 'COMPLETE' AND graph IS NOT NULL AND unsupported_reason IS NULL)
        OR (status = 'UNSUPPORTED' AND graph IS NULL AND unsupported_reason IS NOT NULL)),
    CONSTRAINT pdf_form_extraction_version_graph_object CHECK (graph IS NULL OR jsonb_typeof(graph) = 'object')
);

CREATE INDEX pdf_form_extraction_version_workspace_id_idx ON pdf_form_extraction_version (workspace_id);

ALTER TABLE pdf_form_extraction_version ENABLE ROW LEVEL SECURITY;

-- Written once by a member and read by members, the same shape as the other
-- readings of a file; nothing changes or removes a row but the deletion
-- routines below, which run as the table's owner.
CREATE POLICY pdf_form_extraction_version_member_select ON pdf_form_extraction_version
    FOR SELECT TO brownie_api
    USING (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = pdf_form_extraction_version.workspace_id
              AND wm.user_id = current_workspace_user_id()
        )
    );

CREATE POLICY pdf_form_extraction_version_member_insert ON pdf_form_extraction_version
    FOR INSERT TO brownie_api
    WITH CHECK (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = pdf_form_extraction_version.workspace_id
              AND wm.user_id = current_workspace_user_id()
        )
    );

REVOKE UPDATE, DELETE, TRUNCATE ON pdf_form_extraction_version FROM brownie_api, brownie_worker;

-- A template version is of one kind. A Word one is pinned to its structural
-- graph as before; a PDF one to its form reading instead, and has no Word
-- graph at all. Every version before this one is a Word one.
ALTER TABLE template_version
    ADD COLUMN kind TEXT NOT NULL DEFAULT 'DOCX',
    ADD COLUMN pdf_form_extraction_id BIGINT;
ALTER TABLE template_version ALTER COLUMN extraction_version_id DROP NOT NULL;
ALTER TABLE template_version
    ADD CONSTRAINT template_version_kind_known CHECK (kind IN ('DOCX', 'PDF')),
    ADD CONSTRAINT template_version_pdf_form_extraction_fk
        FOREIGN KEY (workspace_id, pdf_form_extraction_id) REFERENCES pdf_form_extraction_version (workspace_id, id),
    ADD CONSTRAINT template_version_reading_matches_kind CHECK (
        (kind = 'DOCX' AND extraction_version_id IS NOT NULL AND pdf_form_extraction_id IS NULL)
        OR (kind = 'PDF' AND pdf_form_extraction_id IS NOT NULL AND extraction_version_id IS NULL));

-- What the person was told when their file was made ready to fill (its
-- tracked changes accepted, places Brownie left out, a table that grows),
-- kept with the template made from it so the notes can be read again after
-- the page that first showed them is closed. A version made from another
-- copies its notes. Versions made before this have none (NULL), which is not
-- the same as an upload that had nothing to say (an empty list).
ALTER TABLE template_version
    ADD COLUMN preparation_notices JSONB,
    ADD CONSTRAINT template_version_preparation_notices_array
        CHECK (preparation_notices IS NULL OR jsonb_typeof(preparation_notices) = 'array');

-- A PDF form is filled into a PDF and nothing else, so what is made from it
-- has no Word file. The Word columns may now be empty; where a Word file is
-- named its hash is too, and a validation run or an export always names at
-- least one file. Which formats may be approved for which kind of template
-- is decided when approving, since these tables do not know the kind.
ALTER TABLE template_baseline_render ALTER COLUMN docx_artifact_id DROP NOT NULL;

ALTER TABLE document_compilation ALTER COLUMN docx_artifact_id DROP NOT NULL;
ALTER TABLE document_compilation ALTER COLUMN docx_sha256 DROP NOT NULL;
ALTER TABLE document_compilation ADD CONSTRAINT document_compilation_docx_columns_together
    CHECK ((docx_artifact_id IS NULL) = (docx_sha256 IS NULL));

ALTER TABLE validation_manifest ALTER COLUMN docx_artifact_id DROP NOT NULL;
ALTER TABLE validation_manifest ALTER COLUMN docx_sha256 DROP NOT NULL;
ALTER TABLE validation_manifest
    ADD CONSTRAINT validation_manifest_docx_columns_together CHECK ((docx_artifact_id IS NULL) = (docx_sha256 IS NULL)),
    ADD CONSTRAINT validation_manifest_names_a_file CHECK (docx_artifact_id IS NOT NULL OR pdf_artifact_id IS NOT NULL);

ALTER TABLE export_receipt ALTER COLUMN docx_artifact_id DROP NOT NULL;
ALTER TABLE export_receipt ALTER COLUMN docx_sha256 DROP NOT NULL;
ALTER TABLE export_receipt
    ADD CONSTRAINT export_receipt_docx_columns_together CHECK ((docx_artifact_id IS NULL) = (docx_sha256 IS NULL)),
    ADD CONSTRAINT export_receipt_names_a_file CHECK (docx_artifact_id IS NOT NULL OR pdf_artifact_id IS NOT NULL);

-- A PDF is filled as it is, so preparing one makes no copy. What preparing
-- it found is kept all the same, as a derivation of the PDF to itself: its
-- spots, named once (perhaps by the model, which costs money), and what the
-- person is told. Asking again answers with the same spots and field ids
-- instead of naming the places again. Such a row is the only kind whose
-- output is its source, and a PDF has one per recipe version, so an output
-- is unique only among working copies.
ALTER TABLE artifact_derivation
    DROP CONSTRAINT artifact_derivation_kind_known,
    DROP CONSTRAINT artifact_derivation_not_itself,
    DROP CONSTRAINT artifact_derivation_output_key,
    ADD CONSTRAINT artifact_derivation_kind_known CHECK (kind IN ('PREPARED', 'PDF_FORM')),
    ADD CONSTRAINT artifact_derivation_not_itself CHECK ((source_artifact_id = output_artifact_id) = (kind = 'PDF_FORM'));
CREATE UNIQUE INDEX artifact_derivation_output_key ON artifact_derivation (output_artifact_id) WHERE kind = 'PREPARED';

-- Deleting a document for good: the routine from before, unchanged except
-- that a compilation or validation run with no Word file adds no empty entry
-- to the files it removes, a removed file's form reading goes with it, and an
-- upload stays while a template is built on the working copy made from it
-- (as the sweep keeps it), even when the document also linked the upload.
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

-- Deleting a workspace: the routine from before (V47, with its fill spot
-- reviews and file derivations), unchanged except that the workspace's form
-- readings go after its template versions, which name them, and before its
-- files, which they name.
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

-- CREATE OR REPLACE keeps the routines' owner and permissions; they are
-- restated so that this file alone says who may run them: nobody.
REVOKE ALL ON FUNCTION retention_purge_document(BIGINT, BIGINT, BIGINT) FROM PUBLIC, brownie_api, brownie_worker;
REVOKE ALL ON FUNCTION retention_purge_workspace(BIGINT, BIGINT) FROM PUBLIC, brownie_api, brownie_worker;

-- The sweep of files nothing refers to: the routine from before (V47),
-- unchanged except that a swept file's form reading goes with it, as its
-- other readings do.
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

REVOKE ALL ON FUNCTION public.worker_collect_removable_payloads(BIGINT, BIGINT, INTEGER) FROM PUBLIC, brownie_api, brownie_worker;
GRANT EXECUTE ON FUNCTION public.worker_collect_removable_payloads(BIGINT, BIGINT, INTEGER) TO brownie_worker;
