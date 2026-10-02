-- A person's upload of a Word-type form is never filled as it is: Brownie
-- makes a clean working copy of it (converted first when it is not a Word
-- document), finds the places meant for filling in, turns them into named
-- spots and stores the result as a new artifact. This table records which
-- artifact was made from which, by which version of that recipe, and what
-- was found and done, so the same request asked again answers with the
-- same copy instead of making another, and so the original stays alive for
-- as long as a template is built on the copy made from it.
--
-- One row per (source, kind, recipe version): the recipe version names the
-- reader version too, so a newer reader makes a new copy rather than
-- reinterpreting an old one. Two first requests racing each compute a copy;
-- the unique key keeps one row, and the loser's copy is later swept as
-- unreferenced like any other orphaned generated file.
CREATE TABLE artifact_derivation (
    id BIGSERIAL PRIMARY KEY,
    workspace_id BIGINT NOT NULL,
    source_artifact_id BIGINT NOT NULL,
    output_artifact_id BIGINT NOT NULL,
    kind TEXT NOT NULL,
    recipe_version TEXT NOT NULL,
    source_format TEXT NOT NULL,
    converter TEXT,
    spot_naming TEXT NOT NULL,
    rules_only_reason TEXT,
    spots JSONB NOT NULL DEFAULT '[]',
    notices JSONB NOT NULL DEFAULT '[]',
    created_by_user_id BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Deleting an artifact row takes its derivations with it: they describe
    -- nothing once either end is gone.
    CONSTRAINT artifact_derivation_source_fk
        FOREIGN KEY (workspace_id, source_artifact_id) REFERENCES artifact (workspace_id, id) ON DELETE CASCADE,
    CONSTRAINT artifact_derivation_output_fk
        FOREIGN KEY (workspace_id, output_artifact_id) REFERENCES artifact (workspace_id, id) ON DELETE CASCADE,
    CONSTRAINT artifact_derivation_output_key UNIQUE (output_artifact_id),
    CONSTRAINT artifact_derivation_recipe_key UNIQUE (source_artifact_id, kind, recipe_version),
    CONSTRAINT artifact_derivation_not_itself CHECK (source_artifact_id <> output_artifact_id),
    CONSTRAINT artifact_derivation_kind_known CHECK (kind IN ('PREPARED')),
    CONSTRAINT artifact_derivation_recipe_version_present CHECK (char_length(recipe_version) BETWEEN 1 AND 200),
    CONSTRAINT artifact_derivation_source_format_present CHECK (char_length(source_format) BETWEEN 1 AND 40),
    CONSTRAINT artifact_derivation_spot_naming_known CHECK (spot_naming IN ('MODEL', 'RULES')),
    CONSTRAINT artifact_derivation_spots_array CHECK (jsonb_typeof(spots) = 'array'),
    CONSTRAINT artifact_derivation_notices_array CHECK (jsonb_typeof(notices) = 'array'),
    CONSTRAINT artifact_derivation_user_positive CHECK (created_by_user_id > 0)
);

CREATE INDEX artifact_derivation_workspace_id_idx ON artifact_derivation (workspace_id);

ALTER TABLE artifact_derivation ENABLE ROW LEVEL SECURITY;

CREATE POLICY artifact_derivation_member_select ON artifact_derivation
    FOR SELECT TO brownie_api
    USING (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = artifact_derivation.workspace_id AND wm.user_id = current_workspace_user_id()
        )
    );

-- Only in the member's own name.
CREATE POLICY artifact_derivation_member_insert ON artifact_derivation
    FOR INSERT TO brownie_api
    WITH CHECK (
        created_by_user_id = current_workspace_user_id()
        AND EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = artifact_derivation.workspace_id AND wm.user_id = current_workspace_user_id()
        )
    );

-- A derivation is a record of what was made; it is never edited. Rows go
-- only with the workspace, or with a swept copy, both through the routines
-- below.
REVOKE UPDATE, DELETE, TRUNCATE ON artifact_derivation FROM brownie_api, brownie_worker;

-- A spot Brownie found stays marked as found until a person says it is
-- right. That is a fact about the template, not about one version of it:
-- field ids are never reused within a template, so a kept field stays kept
-- in every later version that still has it.
CREATE TABLE fill_spot_review (
    workspace_id BIGINT NOT NULL,
    template_id BIGINT NOT NULL,
    field_id TEXT NOT NULL,
    decision TEXT NOT NULL,
    reviewed_by_user_id BIGINT NOT NULL,
    reviewed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fill_spot_review_pkey PRIMARY KEY (template_id, field_id),
    CONSTRAINT fill_spot_review_template_fk
        FOREIGN KEY (workspace_id, template_id) REFERENCES template (workspace_id, id) ON DELETE CASCADE,
    CONSTRAINT fill_spot_review_field_id_safe
        CHECK (char_length(field_id) <= 64 AND field_id ~ '^[a-zA-Z][a-zA-Z0-9._-]*$'),
    CONSTRAINT fill_spot_review_decision_known CHECK (decision IN ('KEPT')),
    CONSTRAINT fill_spot_review_user_positive CHECK (reviewed_by_user_id > 0)
);

CREATE INDEX fill_spot_review_workspace_id_idx ON fill_spot_review (workspace_id);

ALTER TABLE fill_spot_review ENABLE ROW LEVEL SECURITY;

CREATE POLICY fill_spot_review_member_select ON fill_spot_review
    FOR SELECT TO brownie_api
    USING (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = fill_spot_review.workspace_id AND wm.user_id = current_workspace_user_id()
        )
    );

CREATE POLICY fill_spot_review_member_insert ON fill_spot_review
    FOR INSERT TO brownie_api
    WITH CHECK (
        reviewed_by_user_id = current_workspace_user_id()
        AND EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = fill_spot_review.workspace_id AND wm.user_id = current_workspace_user_id()
        )
    );

-- Keeping a spot twice changes nothing, so a review is written once and
-- never edited or taken back.
REVOKE UPDATE, DELETE, TRUNCATE ON fill_spot_review FROM brownie_api, brownie_worker;

-- Deleting a workspace now also removes its reviews (before the templates
-- they name) and its derivations (before the extractions and artifacts
-- they name), counted like every other row. The routine is V43's,
-- unchanged except for those two deletes.
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

-- CREATE OR REPLACE keeps the routine's owner and permissions; they are
-- restated so that this file alone says who may run it: nobody.
REVOKE ALL ON FUNCTION retention_purge_workspace(BIGINT, BIGINT) FROM PUBLIC, brownie_api, brownie_worker;

-- The sweep of files nothing refers to, V37's routine with two changes. An
-- original upload is referred to when the copy made from it is a template
-- version's source: the template is built on the copy, but the original is
-- what the person gave, and it stays for as long as the template does. One
-- step is enough, because every working copy in a chain is itself some
-- version's source. And a copy that is swept takes the derivation that
-- names it as its output, so asking for the same form again makes a new
-- copy instead of answering with one whose bytes are gone.
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
