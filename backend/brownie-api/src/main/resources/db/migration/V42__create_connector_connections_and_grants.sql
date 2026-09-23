-- Connections to a person's Google account, made so that Brownie can read
-- a file or a calendar event they choose, and the record of what they chose.
--
-- A connection is not a way of signing in: it belongs to a person who is
-- already signed in, in one workspace, and it lets Brownie do exactly one
-- kind of thing with their Google account. There is one row per kind of
-- access, because each is agreed to separately and each carries only the
-- permission it needs: reading files the person picks in Google Drive, or
-- reading events on calendars they own.
--
-- The refresh token Google hands back is the only thing here that could be
-- used to reach someone's Google data, so it is stored only as ciphertext,
-- encrypted by the application with a key that is not in this database: a
-- dump or a backup of this database holds nothing Google would accept. It
-- exists exactly while the connection is usable. When Google stops
-- accepting it, or the application can no longer decrypt it, it is wiped
-- and the connection waits for its person to connect again; when the
-- person disconnects, it is revoked at Google and wiped, and the row is
-- kept, without it, so that anything already copied through it can still
-- say where it came from.
CREATE TABLE connector_connection (
    id BIGSERIAL PRIMARY KEY,
    workspace_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    provider TEXT NOT NULL,
    access TEXT NOT NULL,
    -- The provider's own stable identifier for the account, and its address
    -- for showing to the person. The identifier never changes for a row;
    -- connecting a different account is a different connection.
    account_id TEXT NOT NULL,
    account_email TEXT,
    -- Exactly what the provider says it granted, space separated, which is
    -- not necessarily what was asked for: a person can untick a permission.
    granted_scopes TEXT NOT NULL,
    state TEXT NOT NULL,
    reconnect_reason TEXT,
    token_key_id TEXT,
    token_nonce BYTEA,
    token_ciphertext BYTEA,
    -- When the person last agreed. Kept after a token stops working, because
    -- a token issued to an app the provider still treats as being tested
    -- stops working a fixed time after this, and saying so is the most
    -- useful thing the page can tell them.
    token_issued_at TIMESTAMPTZ,
    connected_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    disconnected_at TIMESTAMPTZ,
    provider_revocation TEXT,
    CONSTRAINT connector_connection_workspace_id_id_key UNIQUE (workspace_id, id),
    CONSTRAINT connector_connection_member_fk
        FOREIGN KEY (workspace_id, user_id) REFERENCES workspace_member (workspace_id, user_id),
    CONSTRAINT connector_connection_provider_known CHECK (provider IN ('GOOGLE')),
    CONSTRAINT connector_connection_access_known CHECK (access IN ('DRIVE_FILES', 'CALENDAR_EVENTS')),
    CONSTRAINT connector_connection_state_known CHECK (state IN ('ACTIVE', 'RECONNECT_REQUIRED', 'DISCONNECTED')),
    CONSTRAINT connector_connection_account_bounded CHECK (
        char_length(account_id) BETWEEN 1 AND 255
        AND (account_email IS NULL OR char_length(account_email) BETWEEN 1 AND 320)),
    CONSTRAINT connector_connection_scopes_bounded CHECK (char_length(granted_scopes) BETWEEN 1 AND 2000),
    -- A token exists exactly while the connection is usable.
    CONSTRAINT connector_connection_token_shape CHECK (
        CASE WHEN state = 'ACTIVE'
            THEN token_key_id IS NOT NULL AND token_nonce IS NOT NULL AND token_ciphertext IS NOT NULL
                 AND token_issued_at IS NOT NULL
            ELSE token_key_id IS NULL AND token_nonce IS NULL AND token_ciphertext IS NULL
        END),
    -- A 96-bit nonce, and room for Google's largest documented refresh token
    -- with its authentication tag, with nothing larger accepted.
    CONSTRAINT connector_connection_token_bounded CHECK (
        (token_key_id IS NULL OR token_key_id ~ '^[A-Za-z0-9._-]{1,64}$')
        AND (token_nonce IS NULL OR octet_length(token_nonce) = 12)
        AND (token_ciphertext IS NULL OR octet_length(token_ciphertext) BETWEEN 17 AND 1024)),
    CONSTRAINT connector_connection_reconnect_shape CHECK (
        (state = 'RECONNECT_REQUIRED') = (reconnect_reason IS NOT NULL)
        AND (reconnect_reason IS NULL OR reconnect_reason IN ('TOKEN_REJECTED', 'TOKEN_UNREADABLE', 'PERMISSION_MISSING'))),
    CONSTRAINT connector_connection_disconnect_shape CHECK (
        (state = 'DISCONNECTED') = (disconnected_at IS NOT NULL)
        AND (state = 'DISCONNECTED') = (provider_revocation IS NOT NULL)
        AND (provider_revocation IS NULL OR provider_revocation IN ('REVOKED', 'FAILED', 'NOT_NEEDED')))
);

-- One usable, or waiting-to-be-reconnected, connection per person, kind of
-- access and workspace; any number of disconnected ones behind it.
CREATE UNIQUE INDEX connector_connection_open_key
    ON connector_connection (workspace_id, user_id, provider, access)
    WHERE state <> 'DISCONNECTED';

CREATE INDEX connector_connection_person_idx ON connector_connection (workspace_id, user_id, connected_at DESC, id DESC);

ALTER TABLE connector_connection ENABLE ROW LEVEL SECURITY;

-- A connection is a person's own, even from another member of the same
-- workspace: sharing a workspace is not sharing a Google account.
CREATE POLICY connector_connection_own_select ON connector_connection
    FOR SELECT TO brownie_api
    USING (
        user_id = current_workspace_user_id()
        AND EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = connector_connection.workspace_id
              AND wm.user_id = current_workspace_user_id()
        )
    );

-- Only in the person's own name, only as a usable connection, and only by
-- the workspace's owner, the one role that may connect an outside account.
CREATE POLICY connector_connection_owner_insert ON connector_connection
    FOR INSERT TO brownie_api
    WITH CHECK (
        user_id = current_workspace_user_id()
        AND state = 'ACTIVE'
        AND disconnected_at IS NULL
        AND EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = connector_connection.workspace_id
              AND wm.user_id = current_workspace_user_id()
              AND wm.role = 'OWNER'
        )
    );

-- Only the person's own, and never once disconnected: a disconnected row is
-- a record, not a connection that can be brought back.
CREATE POLICY connector_connection_own_update ON connector_connection
    FOR UPDATE TO brownie_api
    USING (
        user_id = current_workspace_user_id()
        AND state <> 'DISCONNECTED'
        AND EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = connector_connection.workspace_id
              AND wm.user_id = current_workspace_user_id()
        )
    )
    WITH CHECK (user_id = current_workspace_user_id());

-- Which account a row is for, what kind of access it is and when it was
-- made are fixed; only its state, its token and their times change.
REVOKE UPDATE, DELETE, TRUNCATE ON connector_connection FROM brownie_api, brownie_worker;
GRANT UPDATE (account_email, granted_scopes, state, reconnect_reason, token_key_id, token_nonce, token_ciphertext,
              token_issued_at, updated_at, disconnected_at, provider_revocation)
    ON connector_connection TO brownie_api;

-- One thing a person chose for Brownie to read through a connection: a file
-- they picked, or the calendar whose events they asked for. It is Brownie's
-- own record of that choice, distinct from whatever the provider's
-- permission would technically allow, and it is what a read is checked
-- against. It can only ever allow reading.
CREATE TABLE connector_resource_grant (
    id BIGSERIAL PRIMARY KEY,
    workspace_id BIGINT NOT NULL,
    connection_id BIGINT NOT NULL,
    resource_type TEXT NOT NULL,
    external_id TEXT NOT NULL,
    display_name TEXT,
    allowed_operations TEXT[] NOT NULL DEFAULT ARRAY['READ']::TEXT[],
    granted_by_user_id BIGINT NOT NULL,
    granted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at TIMESTAMPTZ,
    revoked_reason TEXT,
    CONSTRAINT connector_resource_grant_workspace_id_id_key UNIQUE (workspace_id, id),
    CONSTRAINT connector_resource_grant_connection_fk
        FOREIGN KEY (workspace_id, connection_id) REFERENCES connector_connection (workspace_id, id),
    CONSTRAINT connector_resource_grant_member_fk
        FOREIGN KEY (workspace_id, granted_by_user_id) REFERENCES workspace_member (workspace_id, user_id),
    CONSTRAINT connector_resource_grant_type_known CHECK (resource_type IN ('DRIVE_FILE', 'CALENDAR')),
    -- Drive file ids are letters, digits, '-' and '_'; a calendar is named
    -- 'primary' or by an address. The length is checked apart from the
    -- characters because a regular expression here counts to 255 at most.
    CONSTRAINT connector_resource_grant_external_id_shape CHECK (
        char_length(external_id) BETWEEN 1 AND 256 AND external_id ~ '^[A-Za-z0-9_@.+-]+$'),
    CONSTRAINT connector_resource_grant_display_name_bounded CHECK (
        display_name IS NULL OR char_length(display_name) BETWEEN 1 AND 255),
    -- Reading, and nothing else, whatever the provider's permission would allow.
    CONSTRAINT connector_resource_grant_read_only CHECK (allowed_operations = ARRAY['READ']::TEXT[]),
    CONSTRAINT connector_resource_grant_revocation_shape CHECK (
        (revoked_at IS NULL) = (revoked_reason IS NULL)
        AND (revoked_reason IS NULL OR revoked_reason IN ('DISCONNECTED', 'REMOVED', 'PROVIDER_ACCESS_LOST')))
);

CREATE UNIQUE INDEX connector_resource_grant_open_key
    ON connector_resource_grant (connection_id, resource_type, external_id)
    WHERE revoked_at IS NULL;

CREATE INDEX connector_resource_grant_workspace_idx ON connector_resource_grant (workspace_id, connection_id);

ALTER TABLE connector_resource_grant ENABLE ROW LEVEL SECURITY;

CREATE POLICY connector_resource_grant_own_select ON connector_resource_grant
    FOR SELECT TO brownie_api
    USING (
        EXISTS (
            SELECT 1 FROM connector_connection c
            WHERE c.workspace_id = connector_resource_grant.workspace_id
              AND c.id = connector_resource_grant.connection_id
              AND c.user_id = current_workspace_user_id()
        )
        AND EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = connector_resource_grant.workspace_id
              AND wm.user_id = current_workspace_user_id()
        )
    );

-- Recorded by the person whose connection it is, through a connection that
-- is usable, starting now, and open.
CREATE POLICY connector_resource_grant_own_insert ON connector_resource_grant
    FOR INSERT TO brownie_api
    WITH CHECK (
        granted_by_user_id = current_workspace_user_id()
        AND revoked_at IS NULL
        AND granted_at = now()
        AND EXISTS (
            SELECT 1 FROM connector_connection c
            WHERE c.workspace_id = connector_resource_grant.workspace_id
              AND c.id = connector_resource_grant.connection_id
              AND c.user_id = current_workspace_user_id()
              AND c.state = 'ACTIVE'
        )
    );

-- The only change a grant ever sees: an open one is taken back, once.
CREATE POLICY connector_resource_grant_own_revoke ON connector_resource_grant
    FOR UPDATE TO brownie_api
    USING (
        revoked_at IS NULL
        AND EXISTS (
            SELECT 1 FROM connector_connection c
            WHERE c.workspace_id = connector_resource_grant.workspace_id
              AND c.id = connector_resource_grant.connection_id
              AND c.user_id = current_workspace_user_id()
        )
    )
    WITH CHECK (revoked_at IS NOT NULL);

REVOKE UPDATE, DELETE, TRUNCATE ON connector_resource_grant FROM brownie_api, brownie_worker;
GRANT UPDATE (revoked_at, revoked_reason) ON connector_resource_grant TO brownie_api;

-- Connecting and disconnecting an outside account are on the record, with
-- the kind of access and what the provider said about revocation; never the
-- account's address or anything from the token.
ALTER TABLE audit_event DROP CONSTRAINT audit_event_action_known;
ALTER TABLE audit_event ADD CONSTRAINT audit_event_action_known CHECK (action IN (
    'DOCUMENT_TRASHED', 'DOCUMENT_RESTORED', 'DOCUMENT_DELETED', 'WORKSPACE_DELETED',
    'DOCUMENT_EXPORTED', 'JOB_RETRIED', 'SUPPORT_GRANT_CREATED', 'SUPPORT_GRANT_REVOKED',
    'CONNECTOR_CONNECTED', 'CONNECTOR_DISCONNECTED'
));

-- Deleting a workspace removes its connections too: the grants after the
-- snapshots that may name them, then the connections, whose tokens go with
-- them, all before the memberships they belong to. The two routines below
-- are the ones every workspace deletion and every replay of one passes
-- through, unchanged except for those lines and the matching counts.

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
            + (SELECT count(*) FROM public.connector_resource_grant x WHERE x.workspace_id = v_request.workspace_id)
            + (SELECT count(*) FROM public.connector_connection x WHERE x.workspace_id = v_request.workspace_id)
        INTO remaining_rows;
    END IF;

    SELECT count(*) INTO pending_objects
    FROM public.deletion_blob_task t
    WHERE t.deletion_request_id = p_request_id AND t.state = 'PENDING';

    RETURN NEXT;
END;
$$;


-- CREATE OR REPLACE keeps each routine's owner and permissions; they are
-- restated so that this file alone says who may run them: nobody.
REVOKE ALL ON FUNCTION retention_purge_workspace(BIGINT, BIGINT) FROM PUBLIC, brownie_api, brownie_worker;
REVOKE ALL ON FUNCTION retention_remaining(BIGINT) FROM PUBLIC, brownie_api, brownie_worker;
