-- A document is tenant-owned and pinned to one exact template version. The
-- expanded template-version key makes the selected version's parent template
-- part of the database constraint, rather than trusting a caller to pair
-- unrelated IDs from the same workspace.
ALTER TABLE template_version
    ADD CONSTRAINT template_version_workspace_template_id_id_key UNIQUE (workspace_id, template_id, id);

CREATE TABLE document (
    id BIGSERIAL PRIMARY KEY,
    workspace_id BIGINT NOT NULL,
    title TEXT NOT NULL,
    template_id BIGINT NOT NULL,
    template_version_id BIGINT NOT NULL,
    current_revision_id BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT document_workspace_id_id_key UNIQUE (workspace_id, id),
    CONSTRAINT document_title_not_blank CHECK (char_length(btrim(title)) > 0),
    CONSTRAINT document_template_version_fk
        FOREIGN KEY (workspace_id, template_id, template_version_id)
        REFERENCES template_version (workspace_id, template_id, id)
);

CREATE INDEX document_workspace_id_created_at_idx ON document (workspace_id, created_at, id);

-- A revision is never updated. Its content is the bounded typed field tree
-- validated by the application before persistence; JSONB keeps that exact
-- versioned tree together without accepting a generic patch language.
CREATE TABLE document_revision (
    id BIGSERIAL PRIMARY KEY,
    workspace_id BIGINT NOT NULL,
    document_id BIGINT NOT NULL,
    revision_number INT NOT NULL,
    parent_revision_id BIGINT,
    content JSONB NOT NULL,
    content_hash TEXT NOT NULL,
    actor_user_id BIGINT NOT NULL,
    edit_reason TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT document_revision_workspace_document_id_key UNIQUE (workspace_id, document_id, id),
    CONSTRAINT document_revision_document_fk
        FOREIGN KEY (workspace_id, document_id) REFERENCES document (workspace_id, id),
    CONSTRAINT document_revision_parent_fk
        FOREIGN KEY (workspace_id, document_id, parent_revision_id)
        REFERENCES document_revision (workspace_id, document_id, id),
    CONSTRAINT document_revision_actor_membership_fk
        FOREIGN KEY (workspace_id, actor_user_id) REFERENCES workspace_member (workspace_id, user_id),
    CONSTRAINT document_revision_number_key UNIQUE (document_id, revision_number),
    CONSTRAINT document_revision_number_positive CHECK (revision_number > 0),
    CONSTRAINT document_revision_content_object CHECK (jsonb_typeof(content) = 'object'),
    CONSTRAINT document_revision_content_hash_format CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT document_revision_edit_reason_not_blank CHECK (char_length(btrim(edit_reason)) > 0)
);

CREATE INDEX document_revision_workspace_document_number_idx
    ON document_revision (workspace_id, document_id, revision_number);

-- document and document_revision form a deliberate circular relationship:
-- a newly created document receives its initial revision in the same
-- transaction, then points at that exact row.
ALTER TABLE document
    ADD CONSTRAINT document_current_revision_fk
    FOREIGN KEY (workspace_id, id, current_revision_id)
    REFERENCES document_revision (workspace_id, document_id, id);

-- The durable idempotency identity is shared with other mutation operations,
-- while this receipt remains document-specific and points at the exact
-- revision produced by a successful mutation.
ALTER TABLE idempotency_record
    DROP CONSTRAINT idempotency_record_operation_known,
    ADD CONSTRAINT idempotency_record_operation_known CHECK (operation IN (
        'job.enqueue', 'job.request-cancellation', 'job.request-resume',
        'document.create', 'document.edit-content'
    )),
    ADD CONSTRAINT idempotency_record_workspace_actor_operation_id_key
        UNIQUE (workspace_id, actor_user_id, operation, id);

CREATE TABLE document_command_receipt (
    id BIGSERIAL PRIMARY KEY,
    command_id UUID NOT NULL UNIQUE,
    workspace_id BIGINT NOT NULL,
    actor_user_id BIGINT NOT NULL,
    idempotency_record_id BIGINT NOT NULL,
    document_id BIGINT NOT NULL,
    revision_id BIGINT NOT NULL,
    operation TEXT NOT NULL,
    request_hash TEXT NOT NULL,
    accepted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT document_command_receipt_workspace_id_id_key UNIQUE (workspace_id, id),
    CONSTRAINT document_command_receipt_idempotency_fk
        FOREIGN KEY (workspace_id, actor_user_id, operation, idempotency_record_id)
        REFERENCES idempotency_record (workspace_id, actor_user_id, operation, id),
    CONSTRAINT document_command_receipt_document_fk
        FOREIGN KEY (workspace_id, document_id) REFERENCES document (workspace_id, id),
    CONSTRAINT document_command_receipt_revision_fk
        FOREIGN KEY (workspace_id, document_id, revision_id)
        REFERENCES document_revision (workspace_id, document_id, id),
    CONSTRAINT document_command_receipt_idempotency_key UNIQUE (workspace_id, idempotency_record_id),
    CONSTRAINT document_command_receipt_operation_known CHECK (
        operation IN ('document.create', 'document.edit-content')
    ),
    CONSTRAINT document_command_receipt_request_hash_format CHECK (request_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX document_command_receipt_workspace_actor_idx
    ON document_command_receipt (workspace_id, actor_user_id, accepted_at);

ALTER TABLE document ENABLE ROW LEVEL SECURITY;
ALTER TABLE document_revision ENABLE ROW LEVEL SECURITY;
ALTER TABLE document_command_receipt ENABLE ROW LEVEL SECURITY;

CREATE POLICY document_member_select ON document
    FOR SELECT TO brownie_api
    USING (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = document.workspace_id
              AND wm.user_id = current_workspace_user_id()
        )
    );

CREATE POLICY document_member_insert ON document
    FOR INSERT TO brownie_api
    WITH CHECK (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = document.workspace_id
              AND wm.user_id = current_workspace_user_id()
        )
    );

CREATE POLICY document_revision_member_select ON document_revision
    FOR SELECT TO brownie_api
    USING (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = document_revision.workspace_id
              AND wm.user_id = current_workspace_user_id()
        )
    );

CREATE POLICY document_revision_member_insert ON document_revision
    FOR INSERT TO brownie_api
    WITH CHECK (
        actor_user_id = current_workspace_user_id()
        AND EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = document_revision.workspace_id
              AND wm.user_id = current_workspace_user_id()
        )
    );

CREATE POLICY document_command_receipt_actor_select ON document_command_receipt
    FOR SELECT TO brownie_api
    USING (
        actor_user_id = current_workspace_user_id()
        AND EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = document_command_receipt.workspace_id
              AND wm.user_id = current_workspace_user_id()
        )
    );

CREATE POLICY document_command_receipt_actor_insert ON document_command_receipt
    FOR INSERT TO brownie_api
    WITH CHECK (
        actor_user_id = current_workspace_user_id()
        AND EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = document_command_receipt.workspace_id
              AND wm.user_id = current_workspace_user_id()
        )
    );

-- The append path needs a row lock to allocate the next revision number, but
-- runtime roles must not receive unrestricted UPDATE permission on document.
-- This tenant-checked function obtains that transaction-scoped lock without
-- exposing a general document-row update operation.
CREATE FUNCTION lock_document_current_revision(
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
    FOR UPDATE;
END;
$$;

REVOKE ALL ON FUNCTION lock_document_current_revision(BIGINT, BIGINT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION lock_document_current_revision(BIGINT, BIGINT) TO brownie_api;

-- Runtime roles cannot update document rows directly. The only pointer
-- transition they can invoke checks actor membership, the previous pointer,
-- and that the selected revision is its direct immutable child.
CREATE FUNCTION advance_document_current_revision(
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

REVOKE ALL ON FUNCTION advance_document_current_revision(BIGINT, BIGINT, BIGINT, BIGINT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION advance_document_current_revision(BIGINT, BIGINT, BIGINT, BIGINT) TO brownie_api;

REVOKE UPDATE ON document FROM brownie_api, brownie_worker;
