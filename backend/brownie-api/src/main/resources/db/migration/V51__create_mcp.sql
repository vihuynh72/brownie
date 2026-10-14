-- The record of who may call Brownie from a chat app over MCP, what each
-- call did, and the single-use download links handed out for exports.
-- The grant table covers both credential kinds with one row set: a
-- personal token a person pastes into a developer tool, and an OAuth
-- client a hosted app registers through the authorization server. Which
-- columns a kind uses is enforced by the CHECKs below rather than by
-- convention.
--
-- A token itself is never stored: what is kept is its SHA-256 and the
-- last four characters as a hint, so a leaked table leaks nothing that
-- authenticates.

CREATE TABLE mcp_grant (
    id BIGSERIAL PRIMARY KEY,
    workspace_id BIGINT NOT NULL REFERENCES workspace (id),
    user_id BIGINT NOT NULL,
    kind TEXT NOT NULL,
    name TEXT NOT NULL,
    registered_client_id TEXT,
    scopes TEXT[] NOT NULL,
    token_sha256 TEXT,
    token_hint TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT mcp_grant_workspace_id_id_key UNIQUE (workspace_id, id),
    CONSTRAINT mcp_grant_member_fk
        FOREIGN KEY (workspace_id, user_id) REFERENCES workspace_member (workspace_id, user_id),
    CONSTRAINT mcp_grant_kind_known CHECK (kind IN ('PERSONAL_TOKEN', 'OAUTH_CLIENT')),
    -- A personal token's label is the short name a person gave it; an OAuth
    -- client's name is the registration's own longer client_name.
    CONSTRAINT mcp_grant_name_bounded CHECK (
        (kind = 'PERSONAL_TOKEN' AND char_length(btrim(name)) BETWEEN 1 AND 80)
        OR (kind = 'OAUTH_CLIENT' AND char_length(btrim(name)) BETWEEN 1 AND 200)),
    CONSTRAINT mcp_grant_scopes_known CHECK (
        array_length(scopes, 1) >= 1
        AND scopes <@ ARRAY['read', 'fill', 'export']::TEXT[]),
    -- Each kind fills exactly its own credential columns: a personal token
    -- is a hash and a hint, an OAuth client is the registration it came
    -- from. The other kind's columns stay null, so neither can carry both.
    CONSTRAINT mcp_grant_personal_token_columns CHECK (
        (kind = 'PERSONAL_TOKEN') =
        (token_sha256 IS NOT NULL AND token_hint IS NOT NULL AND registered_client_id IS NULL)),
    CONSTRAINT mcp_grant_oauth_columns CHECK (
        (kind = 'OAUTH_CLIENT') =
        (registered_client_id IS NOT NULL AND token_sha256 IS NULL AND token_hint IS NULL)),
    CONSTRAINT mcp_grant_token_sha256_hex CHECK (token_sha256 IS NULL OR token_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT mcp_grant_token_hint_shape CHECK (token_hint IS NULL OR char_length(token_hint) = 4),
    CONSTRAINT mcp_grant_token_sha256_key UNIQUE (token_sha256)
);

-- At most one live grant per person and registered OAuth client: a second
-- consent for the same client refreshes the existing grant rather than
-- stacking another.
CREATE UNIQUE INDEX mcp_grant_one_live_oauth_per_client ON mcp_grant (user_id, registered_client_id)
    WHERE kind = 'OAUTH_CLIENT' AND revoked_at IS NULL;

CREATE INDEX mcp_grant_workspace_id_idx ON mcp_grant (workspace_id);

-- One row per tool call: the durable record of what a connected app did,
-- including what it was refused. document_id stays null for calls that
-- name none (listing templates) or for refusals where the named document
-- was never established as the caller's. The code column carries a short
-- machine code, never an argument a caller typed.
CREATE TABLE mcp_tool_call (
    id BIGSERIAL PRIMARY KEY,
    workspace_id BIGINT NOT NULL,
    grant_id BIGINT NOT NULL,
    document_id BIGINT,
    tool TEXT NOT NULL,
    outcome TEXT NOT NULL,
    code TEXT,
    at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT mcp_tool_call_grant_fk
        FOREIGN KEY (workspace_id, grant_id) REFERENCES mcp_grant (workspace_id, id) ON DELETE CASCADE,
    CONSTRAINT mcp_tool_call_document_fk
        FOREIGN KEY (workspace_id, document_id) REFERENCES document (workspace_id, id),
    CONSTRAINT mcp_tool_call_tool_bounded CHECK (char_length(tool) BETWEEN 1 AND 80),
    CONSTRAINT mcp_tool_call_outcome_known CHECK (outcome IN ('OK', 'REFUSED', 'ERROR')),
    CONSTRAINT mcp_tool_call_code_bounded CHECK (code IS NULL OR char_length(code) BETWEEN 1 AND 100)
);

CREATE INDEX mcp_tool_call_workspace_id_idx ON mcp_tool_call (workspace_id);
CREATE INDEX mcp_tool_call_document_idx ON mcp_tool_call (workspace_id, document_id, at);

-- A single-use, short-lived address that hands one exported file to a
-- browser, without a session. The token is never stored, only its SHA-256;
-- claiming it is one atomic read-and-mark through mcp_claim_download_link,
-- so a raced second use finds used_at set and gets nothing.
CREATE TABLE mcp_download_link (
    id BIGSERIAL PRIMARY KEY,
    workspace_id BIGINT NOT NULL,
    grant_id BIGINT NOT NULL,
    export_receipt_id BIGINT NOT NULL,
    file TEXT NOT NULL,
    token_sha256 TEXT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT mcp_download_link_workspace_id_id_key UNIQUE (workspace_id, id),
    CONSTRAINT mcp_download_link_grant_fk
        FOREIGN KEY (workspace_id, grant_id) REFERENCES mcp_grant (workspace_id, id) ON DELETE CASCADE,
    CONSTRAINT mcp_download_link_receipt_fk
        FOREIGN KEY (workspace_id, export_receipt_id) REFERENCES export_receipt (workspace_id, id),
    CONSTRAINT mcp_download_link_file_known CHECK (file IN ('DOCX', 'PDF')),
    CONSTRAINT mcp_download_link_token_sha256_key UNIQUE (token_sha256),
    CONSTRAINT mcp_download_link_token_sha256_hex CHECK (token_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT mcp_download_link_expires_after_creation CHECK (expires_at > created_at)
);

CREATE INDEX mcp_download_link_workspace_id_idx ON mcp_download_link (workspace_id);

-- Which patch proposal a finished generation run was applied as -- one
-- row per run, written the moment apply happens, so a second status call
-- answers with the same proposal instead of applying twice. There is no
-- other record of the pairing: the run knows its job and the proposal its
-- document, but nothing names run to proposal until this does.
CREATE TABLE mcp_applied_run (
    workspace_id BIGINT NOT NULL,
    run_id BIGINT NOT NULL,
    document_id BIGINT NOT NULL,
    proposal_id BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT mcp_applied_run_pk PRIMARY KEY (workspace_id, run_id),
    CONSTRAINT mcp_applied_run_run_fk
        FOREIGN KEY (workspace_id, run_id) REFERENCES generation_run (workspace_id, id),
    CONSTRAINT mcp_applied_run_document_fk
        FOREIGN KEY (workspace_id, document_id) REFERENCES document (workspace_id, id)
);

CREATE INDEX mcp_applied_run_document_idx ON mcp_applied_run (workspace_id, document_id);

ALTER TABLE mcp_grant ENABLE ROW LEVEL SECURITY;
ALTER TABLE mcp_tool_call ENABLE ROW LEVEL SECURITY;
ALTER TABLE mcp_download_link ENABLE ROW LEVEL SECURITY;
ALTER TABLE mcp_applied_run ENABLE ROW LEVEL SECURITY;

CREATE POLICY mcp_grant_member_select ON mcp_grant
    FOR SELECT TO brownie_api
    USING (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = mcp_grant.workspace_id AND wm.user_id = current_workspace_user_id()
        )
    );

CREATE POLICY mcp_grant_member_insert ON mcp_grant
    FOR INSERT TO brownie_api
    WITH CHECK (
        user_id = current_workspace_user_id()
        AND EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = mcp_grant.workspace_id AND wm.user_id = current_workspace_user_id()
        )
    );

CREATE POLICY mcp_grant_member_update ON mcp_grant
    FOR UPDATE TO brownie_api
    USING (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = mcp_grant.workspace_id AND wm.user_id = current_workspace_user_id()
        )
    )
    WITH CHECK (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = mcp_grant.workspace_id AND wm.user_id = current_workspace_user_id()
        )
    );

CREATE POLICY mcp_tool_call_member_select ON mcp_tool_call
    FOR SELECT TO brownie_api
    USING (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = mcp_tool_call.workspace_id AND wm.user_id = current_workspace_user_id()
        )
    );

CREATE POLICY mcp_tool_call_member_insert ON mcp_tool_call
    FOR INSERT TO brownie_api
    WITH CHECK (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = mcp_tool_call.workspace_id AND wm.user_id = current_workspace_user_id()
        )
    );

CREATE POLICY mcp_download_link_member_select ON mcp_download_link
    FOR SELECT TO brownie_api
    USING (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = mcp_download_link.workspace_id AND wm.user_id = current_workspace_user_id()
        )
    );

CREATE POLICY mcp_download_link_member_insert ON mcp_download_link
    FOR INSERT TO brownie_api
    WITH CHECK (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = mcp_download_link.workspace_id AND wm.user_id = current_workspace_user_id()
        )
    );

CREATE POLICY mcp_applied_run_member_select ON mcp_applied_run
    FOR SELECT TO brownie_api
    USING (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = mcp_applied_run.workspace_id AND wm.user_id = current_workspace_user_id()
        )
    );

CREATE POLICY mcp_applied_run_member_insert ON mcp_applied_run
    FOR INSERT TO brownie_api
    WITH CHECK (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = mcp_applied_run.workspace_id AND wm.user_id = current_workspace_user_id()
        )
    );

-- A grant's identity columns are written once: the only mutable state is
-- when it was last used and whether it was revoked. Tool calls are
-- append-only -- the record of what happened is never edited. A download
-- link's only change is its one-time claim.
REVOKE UPDATE, DELETE, TRUNCATE ON mcp_grant FROM brownie_api, brownie_worker;
GRANT UPDATE (last_used_at, revoked_at) ON mcp_grant TO brownie_api;

REVOKE UPDATE, DELETE, TRUNCATE ON mcp_tool_call FROM brownie_api, brownie_worker;

REVOKE UPDATE, DELETE, TRUNCATE ON mcp_download_link FROM brownie_api, brownie_worker;
GRANT UPDATE (used_at) ON mcp_download_link TO brownie_api;

REVOKE UPDATE, DELETE, TRUNCATE ON mcp_applied_run FROM brownie_api, brownie_worker;

-- A bearer token presented at the MCP endpoint resolves to its grant
-- without a workspace member in context -- the whole point of the lookup
-- is to learn who is calling, so it cannot run under that member's own
-- row-level view. Doing it inside the database rather than through a
-- policy hole keeps "revoked or unknown" a single branch, and lets the
-- last-used stamp ride on the same lookup: written here at most once a
-- minute per grant.
CREATE FUNCTION mcp_resolve_personal_token(
    p_token_sha256 TEXT
) RETURNS TABLE (
    workspace_id BIGINT,
    grant_id BIGINT,
    user_id BIGINT,
    kind TEXT,
    name TEXT,
    scopes TEXT[]
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
BEGIN
    RETURN QUERY
        SELECT g.workspace_id, g.id, g.user_id, g.kind, g.name, g.scopes
        FROM public.mcp_grant g
        WHERE g.token_sha256 = p_token_sha256
          AND g.kind = 'PERSONAL_TOKEN'
          AND g.revoked_at IS NULL;

    -- Best-effort stamp, throttled so a busy token cannot turn each call
    -- into a write: once a minute is as fresh as "last used" needs to be.
    IF FOUND THEN
        UPDATE public.mcp_grant g
        SET last_used_at = clock_timestamp()
        WHERE g.token_sha256 = p_token_sha256
          AND g.revoked_at IS NULL
          AND (g.last_used_at IS NULL OR g.last_used_at < clock_timestamp() - interval '1 minute');
    END IF;
END;
$$;

-- Claiming a download link: the only write the anonymous route makes.
-- Everything that would make the link worthless -- spent, expired, or the
-- grant that issued it since revoked -- answers as if it never existed, so
-- a guesser learns nothing from the difference. The read and the used-mark
-- are one statement's worth of work done under the function owner, so the
-- caller needs no member context and two raced claims cannot both succeed.
-- The row also names the grant's owner: the route that serves the file is
-- anonymous, but the receipt and artifact reads after the claim run as
-- that member, the same row-level policies as the grant's own calls.
CREATE FUNCTION mcp_claim_download_link(
    p_token_sha256 TEXT
) RETURNS TABLE (
    workspace_id BIGINT,
    link_id BIGINT,
    export_receipt_id BIGINT,
    file TEXT,
    user_id BIGINT
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_link_id BIGINT;
BEGIN
    SELECT l.id INTO v_link_id
    FROM public.mcp_download_link l
    JOIN public.mcp_grant g
        ON g.workspace_id = l.workspace_id AND g.id = l.grant_id
    WHERE l.token_sha256 = p_token_sha256
      AND l.used_at IS NULL
      AND l.expires_at > clock_timestamp()
      AND g.revoked_at IS NULL
    FOR UPDATE OF l;

    IF NOT FOUND THEN
        RETURN;
    END IF;

    UPDATE public.mcp_download_link l
    SET used_at = clock_timestamp()
    WHERE l.id = v_link_id;

    RETURN QUERY
        SELECT l.workspace_id, l.id, l.export_receipt_id, l.file, g.user_id
        FROM public.mcp_download_link l
        JOIN public.mcp_grant g
            ON g.workspace_id = l.workspace_id AND g.id = l.grant_id
        WHERE l.id = v_link_id;
END;
$$;

REVOKE ALL ON FUNCTION mcp_resolve_personal_token(TEXT) FROM PUBLIC, brownie_api, brownie_worker;
REVOKE ALL ON FUNCTION mcp_claim_download_link(TEXT) FROM PUBLIC, brownie_api, brownie_worker;
GRANT EXECUTE ON FUNCTION mcp_resolve_personal_token(TEXT) TO brownie_api;
GRANT EXECUTE ON FUNCTION mcp_claim_download_link(TEXT) TO brownie_api;

-- The OAuth authorization server's JDBC storage, in the shape Spring
-- Security's own schema files ship (the 7.1.1 artifact's
-- oauth2-*-schema.sql), adapted to Postgres as their own header directs:
-- timestamp to timestamptz, blob to text. These tables are not
-- workspace-scoped -- a client registration and a person's authorization
-- of it exist apart from any one workspace -- so they carry no row-level
-- policies; brownie_api's ordinary table grant is all they need.
CREATE TABLE oauth2_registered_client (
    id varchar(100) NOT NULL,
    client_id varchar(100) NOT NULL,
    client_id_issued_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    client_secret varchar(200) DEFAULT NULL,
    client_secret_expires_at timestamptz DEFAULT NULL,
    client_name varchar(200) NOT NULL,
    client_authentication_methods varchar(1000) NOT NULL,
    authorization_grant_types varchar(1000) NOT NULL,
    redirect_uris varchar(1000) DEFAULT NULL,
    post_logout_redirect_uris varchar(1000) DEFAULT NULL,
    scopes varchar(1000) NOT NULL,
    client_settings varchar(2000) NOT NULL,
    token_settings varchar(2000) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE oauth2_authorization (
    id varchar(100) NOT NULL,
    registered_client_id varchar(100) NOT NULL,
    principal_name varchar(200) NOT NULL,
    authorization_grant_type varchar(100) NOT NULL,
    authorized_scopes varchar(1000) DEFAULT NULL,
    attributes text DEFAULT NULL,
    state varchar(500) DEFAULT NULL,
    authorization_code_value text DEFAULT NULL,
    authorization_code_issued_at timestamptz DEFAULT NULL,
    authorization_code_expires_at timestamptz DEFAULT NULL,
    authorization_code_metadata text DEFAULT NULL,
    access_token_value text DEFAULT NULL,
    access_token_issued_at timestamptz DEFAULT NULL,
    access_token_expires_at timestamptz DEFAULT NULL,
    access_token_metadata text DEFAULT NULL,
    access_token_type varchar(100) DEFAULT NULL,
    access_token_scopes varchar(1000) DEFAULT NULL,
    oidc_id_token_value text DEFAULT NULL,
    oidc_id_token_issued_at timestamptz DEFAULT NULL,
    oidc_id_token_expires_at timestamptz DEFAULT NULL,
    oidc_id_token_metadata text DEFAULT NULL,
    refresh_token_value text DEFAULT NULL,
    refresh_token_issued_at timestamptz DEFAULT NULL,
    refresh_token_expires_at timestamptz DEFAULT NULL,
    refresh_token_metadata text DEFAULT NULL,
    user_code_value text DEFAULT NULL,
    user_code_issued_at timestamptz DEFAULT NULL,
    user_code_expires_at timestamptz DEFAULT NULL,
    user_code_metadata text DEFAULT NULL,
    device_code_value text DEFAULT NULL,
    device_code_issued_at timestamptz DEFAULT NULL,
    device_code_expires_at timestamptz DEFAULT NULL,
    device_code_metadata text DEFAULT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE oauth2_authorization_consent (
    registered_client_id varchar(100) NOT NULL,
    principal_name varchar(200) NOT NULL,
    authorities varchar(1000) NOT NULL,
    PRIMARY KEY (registered_client_id, principal_name)
);

-- Four new audited actions: a grant made, a grant revoked, a fill
-- confirmed, an export handed out -- each through a chat app rather than
-- the web app. Their details carry ids and counts only, never a name a
-- person or a client typed.
ALTER TABLE audit_event DROP CONSTRAINT audit_event_action_known;
ALTER TABLE audit_event ADD CONSTRAINT audit_event_action_known CHECK (action IN (
    'DOCUMENT_TRASHED', 'DOCUMENT_RESTORED', 'DOCUMENT_DELETED', 'WORKSPACE_DELETED',
    'DOCUMENT_EXPORTED', 'JOB_RETRIED', 'SUPPORT_GRANT_CREATED', 'SUPPORT_GRANT_REVOKED',
    'CONNECTOR_CONNECTED', 'CONNECTOR_DISCONNECTED', 'SOURCE_IMPORTED',
    'EXTERNAL_ACTION_APPROVED', 'EXTERNAL_ACTION_SENT', 'EXTERNAL_ACTION_FINISHED',
    'TEMPLATE_VERSION_DERIVED', 'DOCUMENT_TEMPLATE_VERSION_CHANGED',
    'BATCH_CREATED', 'BATCH_EXPORTED', 'BATCH_DELETED',
    'MCP_GRANT_CREATED', 'MCP_GRANT_REVOKED', 'MCP_FILL_CONFIRMED', 'MCP_EXPORTED'
));

-- Deleting a document for good: the routine from before (V50), unchanged
-- except that the download links naming its export receipts and the tool
-- calls naming it go with it.
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

    -- A download link names this document's receipts and a tool call names
    -- the document itself, so both go before either target below; the
    -- grant that issued them stays, since a grant is the person's own.
    DELETE FROM public.mcp_download_link x
    USING public.export_receipt r
    WHERE r.workspace_id = x.workspace_id AND r.id = x.export_receipt_id
      AND x.workspace_id = p_workspace_id AND r.document_id = p_document_id;
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;
    DELETE FROM public.mcp_tool_call x
    WHERE x.workspace_id = p_workspace_id AND x.document_id = p_document_id;
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;
    DELETE FROM public.mcp_applied_run x
    WHERE x.workspace_id = p_workspace_id AND x.document_id = p_document_id;
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;

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

-- Deleting a workspace: the routine from before (V50), unchanged except
-- that the workspace's MCP download links, tool calls and grants go first
-- -- a tool call or link can still name one of its documents or receipts
-- -- and the owner's OAuth authorization and consent rows go with the
-- owner, keyed by the principal name that is only ever "user:<id>".
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

-- Counting what a deletion still has to remove: the routine from before
-- (V50), unchanged except that a workspace's count covers the three MCP
-- tables and the owner's OAuth authorization rows, and a document's count
-- covers the tool calls and download links still naming it.
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

-- CREATE OR REPLACE keeps the routines' owner and permissions; they are
-- restated so that this file alone says who may run them.
REVOKE ALL ON FUNCTION retention_purge_document(BIGINT, BIGINT, BIGINT) FROM PUBLIC, brownie_api, brownie_worker;
REVOKE ALL ON FUNCTION retention_purge_workspace(BIGINT, BIGINT) FROM PUBLIC, brownie_api, brownie_worker;
REVOKE ALL ON FUNCTION retention_remaining(BIGINT) FROM PUBLIC, brownie_api, brownie_worker;
