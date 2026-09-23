-- Where a source came from when Brownie copied it from a person's connected
-- account instead of receiving it as an upload.
--
-- A copied source is still an ordinary artifact with an ordinary snapshot:
-- the bytes went through the same checks an upload does, and extraction,
-- evidence, generation and deletion treat it exactly as they treat an
-- upload. What these columns add is what an upload cannot say about itself:
-- which connection and which of the person's choices it was read through,
-- the provider's own identifier for it, the provider's version at the moment
-- it was read (so that a later, changed version is a new snapshot and never
-- a silent replacement of this one), when the provider says it last changed,
-- what it was called there, where it can be opened there, and how Brownie
-- changed its form on the way in (a calendar event written out as text).
-- fetched_at, already here, is when.
--
-- A snapshot is never updated, and these columns inherit that: once written
-- they say for good what was read, even after the person disconnects or the
-- original is changed or deleted.
--
-- Only the kinds of copy Brownie actually makes are allowed; a new kind
-- widens these checks in its own migration.
ALTER TABLE source_snapshot
    ADD COLUMN origin_connection_id BIGINT,
    ADD COLUMN origin_grant_id BIGINT,
    ADD COLUMN origin_external_id TEXT,
    ADD COLUMN origin_revision TEXT,
    ADD COLUMN origin_modified_at TIMESTAMPTZ,
    ADD COLUMN origin_title TEXT,
    ADD COLUMN origin_link TEXT,
    ADD COLUMN origin_conversion TEXT;

ALTER TABLE source_snapshot
    ADD CONSTRAINT source_snapshot_kind_known CHECK (kind IN ('ARTIFACT', 'GOOGLE_CALENDAR')),
    ADD CONSTRAINT source_snapshot_origin_connection_fk
        FOREIGN KEY (workspace_id, origin_connection_id) REFERENCES connector_connection (workspace_id, id),
    ADD CONSTRAINT source_snapshot_origin_grant_fk
        FOREIGN KEY (workspace_id, origin_grant_id) REFERENCES connector_resource_grant (workspace_id, id),
    -- A copied source says where it came from, completely, and how it was converted; an upload says nothing of the kind.
    ADD CONSTRAINT source_snapshot_origin_shape CHECK (
        CASE WHEN kind = 'GOOGLE_CALENDAR'
            THEN origin_connection_id IS NOT NULL AND origin_grant_id IS NOT NULL AND origin_external_id IS NOT NULL
                 -- IS NOT DISTINCT FROM, not =, so a missing conversion is refused rather than read as unknown and let through.
                 AND origin_revision IS NOT NULL AND origin_conversion IS NOT DISTINCT FROM 'CALENDAR_EVENT_AS_TEXT'
            ELSE origin_connection_id IS NULL AND origin_grant_id IS NULL AND origin_external_id IS NULL
                 AND origin_revision IS NULL AND origin_modified_at IS NULL AND origin_title IS NULL
                 AND origin_link IS NULL AND origin_conversion IS NULL
        END),
    ADD CONSTRAINT source_snapshot_origin_bounded CHECK (
        (origin_external_id IS NULL OR char_length(origin_external_id) BETWEEN 1 AND 1024)
        AND (origin_revision IS NULL OR char_length(origin_revision) BETWEEN 1 AND 255)
        AND (origin_title IS NULL OR char_length(origin_title) BETWEEN 1 AND 500)
        -- Only ever a page at the provider, shown to the person as a link: never another scheme.
        AND (origin_link IS NULL OR (char_length(origin_link) <= 2048 AND origin_link ~ '^https://')));

-- The same version of the same thing, read through the same choice, is one
-- snapshot however many times it is imported: importing it again links the
-- snapshot that already exists instead of making a second copy.
CREATE UNIQUE INDEX source_snapshot_origin_version_key
    ON source_snapshot (workspace_id, origin_grant_id, origin_external_id, origin_revision)
    WHERE origin_grant_id IS NOT NULL;

ALTER TABLE audit_event DROP CONSTRAINT audit_event_action_known;
ALTER TABLE audit_event ADD CONSTRAINT audit_event_action_known CHECK (action IN (
    'DOCUMENT_TRASHED', 'DOCUMENT_RESTORED', 'DOCUMENT_DELETED', 'WORKSPACE_DELETED',
    'DOCUMENT_EXPORTED', 'JOB_RETRIED', 'SUPPORT_GRANT_CREATED', 'SUPPORT_GRANT_REVOKED',
    'CONNECTOR_CONNECTED', 'CONNECTOR_DISCONNECTED', 'SOURCE_IMPORTED'
));

-- Deleting a workspace now takes its connections before anything that names
-- them, the order recording a copy, recording a choice and disconnecting all
-- use: taken the other way round (choices before connections), a deletion
-- and one of those could each wait for the other. The routine is the one
-- before it, unchanged except for that lock.
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
