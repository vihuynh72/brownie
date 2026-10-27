-- The one-time upload link a chat app hands a person so a form can reach
-- Brownie: the link's presented token is the whole credential, claimed
-- atomically by digest, so unknown, expired, already-used, or
-- revoked-grant links all answer the same 404. Like the other mcp_ rows,
-- the token itself is never stored -- only its SHA-256 -- and every state
-- change runs under the function owner so the anonymous route needs no
-- member context and two raced claims cannot both succeed.
CREATE TABLE mcp_form_upload (
    id BIGINT GENERATED ALWAYS AS IDENTITY,
    workspace_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    grant_id BIGINT NOT NULL,
    token_sha256 TEXT NOT NULL,
    file_name TEXT NOT NULL,
    state TEXT NOT NULL DEFAULT 'PENDING',
    template_id BIGINT,
    failure_code TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL DEFAULT now() + interval '15 minutes',
    used_at TIMESTAMPTZ,
    CONSTRAINT mcp_form_upload_pk PRIMARY KEY (workspace_id, id),
    CONSTRAINT mcp_form_upload_workspace_fk
        FOREIGN KEY (workspace_id) REFERENCES workspace (id),
    CONSTRAINT mcp_form_upload_grant_fk
        FOREIGN KEY (workspace_id, grant_id) REFERENCES mcp_grant (workspace_id, id) ON DELETE CASCADE,
    CONSTRAINT mcp_form_upload_state_known CHECK (state IN ('PENDING', 'PROCESSING', 'READY', 'FAILED')),
    CONSTRAINT mcp_form_upload_token_sha256_key UNIQUE (token_sha256),
    CONSTRAINT mcp_form_upload_token_sha256_hex CHECK (token_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT mcp_form_upload_expires_after_creation CHECK (expires_at > created_at)
);

CREATE INDEX mcp_form_upload_workspace_id_idx ON mcp_form_upload (workspace_id);

ALTER TABLE mcp_form_upload ENABLE ROW LEVEL SECURITY;

CREATE POLICY mcp_form_upload_member_select ON mcp_form_upload
    FOR SELECT TO brownie_api
    USING (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = mcp_form_upload.workspace_id AND wm.user_id = current_workspace_user_id()
        )
    );

CREATE POLICY mcp_form_upload_member_insert ON mcp_form_upload
    FOR INSERT TO brownie_api
    WITH CHECK (
        user_id = current_workspace_user_id()
        AND EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = mcp_form_upload.workspace_id AND wm.user_id = current_workspace_user_id()
        )
    );

REVOKE UPDATE, DELETE, TRUNCATE ON mcp_form_upload FROM brownie_api, brownie_worker;

-- Claiming an upload link: the only write the anonymous route makes
-- before it has an identity. Everything that would make the link
-- worthless -- spent, expired, or the grant that minted it since revoked
-- -- answers as if it never existed, so a guesser learns nothing from the
-- difference. A successful claim moves the row to PROCESSING so the page
-- can keep asking GET what happened to it after the bytes were accepted.
CREATE OR REPLACE FUNCTION mcp_claim_form_upload(p_token_sha256 TEXT)
RETURNS TABLE (
    workspace_id BIGINT,
    upload_id BIGINT,
    grant_id BIGINT,
    user_id BIGINT,
    file_name TEXT,
    grant_name TEXT
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_upload_id BIGINT;
    v_workspace_id BIGINT;
BEGIN
    SELECT u.id, u.workspace_id INTO v_upload_id, v_workspace_id
    FROM public.mcp_form_upload u
    JOIN public.mcp_grant g
        ON g.workspace_id = u.workspace_id AND g.id = u.grant_id
    WHERE u.token_sha256 = p_token_sha256
      AND u.used_at IS NULL
      AND u.expires_at > clock_timestamp()
      AND g.revoked_at IS NULL
      AND u.state = 'PENDING'
    FOR UPDATE OF u;

    IF NOT FOUND THEN
        RETURN;
    END IF;

    UPDATE public.mcp_form_upload u
    SET used_at = clock_timestamp(), state = 'PROCESSING'
    WHERE u.id = v_upload_id AND u.workspace_id = v_workspace_id;

    RETURN QUERY
        SELECT u.workspace_id, u.id, u.grant_id, u.user_id, u.file_name, g.name
        FROM public.mcp_form_upload u
        JOIN public.mcp_grant g
            ON g.workspace_id = u.workspace_id AND g.id = u.grant_id
        WHERE u.id = v_upload_id AND u.workspace_id = v_workspace_id;
END;
$$;

-- Reading a link's state for the page, and finishing it once the intake
-- ran: both as small as they can be, both under the function owner.
CREATE OR REPLACE FUNCTION mcp_peek_form_upload(p_token_sha256 TEXT)
RETURNS TABLE (
    workspace_id BIGINT,
    upload_id BIGINT,
    state TEXT,
    expires_at TIMESTAMPTZ,
    grant_name TEXT
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
BEGIN
    RETURN QUERY
        SELECT u.workspace_id, u.id, u.state, u.expires_at, g.name
        FROM public.mcp_form_upload u
        JOIN public.mcp_grant g
            ON g.workspace_id = u.workspace_id AND g.id = u.grant_id
        WHERE u.token_sha256 = p_token_sha256
          AND g.revoked_at IS NULL;
END;
$$;

CREATE OR REPLACE FUNCTION mcp_finish_form_upload(
    p_workspace_id BIGINT,
    p_upload_id BIGINT,
    p_state TEXT,
    p_template_id BIGINT,
    p_failure_code TEXT
)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
BEGIN
    UPDATE public.mcp_form_upload u
    SET state = p_state, template_id = p_template_id, failure_code = p_failure_code
    WHERE u.id = p_upload_id AND u.workspace_id = p_workspace_id;
END;
$$;

REVOKE ALL ON FUNCTION mcp_claim_form_upload(TEXT) FROM PUBLIC, brownie_api, brownie_worker;
REVOKE ALL ON FUNCTION mcp_peek_form_upload(TEXT) FROM PUBLIC, brownie_api, brownie_worker;
REVOKE ALL ON FUNCTION mcp_finish_form_upload(BIGINT, BIGINT, TEXT, BIGINT, TEXT) FROM PUBLIC, brownie_api, brownie_worker;
GRANT EXECUTE ON FUNCTION mcp_claim_form_upload(TEXT) TO brownie_api;
GRANT EXECUTE ON FUNCTION mcp_peek_form_upload(TEXT) TO brownie_api;
GRANT EXECUTE ON FUNCTION mcp_finish_form_upload(BIGINT, BIGINT, TEXT, BIGINT, TEXT) TO brownie_api;

-- One more audited action: a form uploaded through a chat-app link.
ALTER TABLE audit_event DROP CONSTRAINT audit_event_action_known;
ALTER TABLE audit_event ADD CONSTRAINT audit_event_action_known CHECK (action IN (
    'DOCUMENT_TRASHED', 'DOCUMENT_RESTORED', 'DOCUMENT_DELETED', 'WORKSPACE_DELETED',
    'DOCUMENT_EXPORTED', 'JOB_RETRIED', 'SUPPORT_GRANT_CREATED', 'SUPPORT_GRANT_REVOKED',
    'CONNECTOR_CONNECTED', 'CONNECTOR_DISCONNECTED', 'SOURCE_IMPORTED',
    'EXTERNAL_ACTION_APPROVED', 'EXTERNAL_ACTION_SENT', 'EXTERNAL_ACTION_FINISHED',
    'TEMPLATE_VERSION_DERIVED', 'DOCUMENT_TEMPLATE_VERSION_CHANGED',
    'BATCH_CREATED', 'BATCH_EXPORTED', 'BATCH_DELETED',
    'MCP_GRANT_CREATED', 'MCP_GRANT_REVOKED', 'MCP_FILL_CONFIRMED', 'MCP_EXPORTED',
    'MCP_FORM_UPLOADED'
));

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

    DELETE FROM public.mcp_download_link x WHERE x.workspace_id = p_workspace_id;
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;
    DELETE FROM public.mcp_tool_call x WHERE x.workspace_id = p_workspace_id;
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;
    DELETE FROM public.mcp_applied_run x WHERE x.workspace_id = p_workspace_id;
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;
        DELETE FROM public.mcp_form_upload x WHERE x.workspace_id = p_workspace_id;
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;
DELETE FROM public.mcp_grant x WHERE x.workspace_id = p_workspace_id;
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;

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
        -- The owner's authorizations travel under the one principal name the
        -- authorization server stores, "user:<id>", and go with the identity
        -- itself rather than with any workspace they consented from.
        DELETE FROM public.oauth2_authorization x WHERE x.principal_name = 'user:' || v_owner_user_id::TEXT;
        GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;
        DELETE FROM public.oauth2_authorization_consent x WHERE x.principal_name = 'user:' || v_owner_user_id::TEXT;
        GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;
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
    v_owner_principal TEXT;
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
            + (SELECT count(*) FROM public.mcp_tool_call x WHERE x.workspace_id = v_request.workspace_id AND x.document_id = v_request.target_id)
            + (SELECT count(*) FROM public.mcp_applied_run x WHERE x.workspace_id = v_request.workspace_id AND x.document_id = v_request.target_id)
            + (SELECT count(*) FROM public.mcp_download_link x
                   JOIN public.export_receipt r ON r.workspace_id = x.workspace_id AND r.id = x.export_receipt_id
                   WHERE r.workspace_id = v_request.workspace_id AND r.document_id = v_request.target_id)
            + (SELECT count(*) FROM public.job x WHERE x.workspace_id = v_request.workspace_id
                   AND x.resource_type = 'document' AND x.resource_id = v_request.target_id)
        INTO remaining_rows;
    ELSE
        v_owner_principal := 'user:' || (
            SELECT w.owner_user_id::TEXT FROM public.workspace w WHERE w.id = v_request.workspace_id);
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
            + (SELECT count(*) FROM public.mcp_download_link x WHERE x.workspace_id = v_request.workspace_id)
            + (SELECT count(*) FROM public.mcp_tool_call x WHERE x.workspace_id = v_request.workspace_id)
            + (SELECT count(*) FROM public.mcp_applied_run x WHERE x.workspace_id = v_request.workspace_id)
            + (SELECT count(*) FROM public.mcp_form_upload x WHERE x.workspace_id = v_request.workspace_id)
            + (SELECT count(*) FROM public.mcp_grant x WHERE x.workspace_id = v_request.workspace_id)
            + (SELECT count(*) FROM public.oauth2_authorization x WHERE x.principal_name = v_owner_principal)
            + (SELECT count(*) FROM public.oauth2_authorization_consent x WHERE x.principal_name = v_owner_principal)
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
REVOKE ALL ON FUNCTION retention_purge_workspace(BIGINT, BIGINT) FROM PUBLIC, brownie_api, brownie_worker;
REVOKE ALL ON FUNCTION retention_remaining(BIGINT) FROM PUBLIC, brownie_api, brownie_worker;
