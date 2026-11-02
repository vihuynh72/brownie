-- A person's saved details ("Your details"): answers they approved once, kept
-- for the next form. Sensitive values are never stored in plain text -- only
-- sealed, with a four-character hint for masked display.

CREATE TABLE saved_detail (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    owner_user_id BIGINT NOT NULL REFERENCES user_identity (id) ON DELETE CASCADE,
    workspace_id BIGINT NOT NULL REFERENCES workspace (id) ON DELETE CASCADE,
    key TEXT NOT NULL CHECK (key ~ '^[a-z_]+\.[a-z_0-9]+$'),
    label TEXT NOT NULL,
    value_text TEXT NULL CHECK (value_text IS NULL OR char_length(value_text) <= 2000),
    value_sealed BYTEA NULL,
    value_hint TEXT NULL,
    sensitive BOOLEAN NOT NULL DEFAULT false,
    origin TEXT NOT NULL CHECK (origin IN ('AUTO', 'MANUAL')),
    source_document_id BIGINT NULL REFERENCES document (id) ON DELETE SET NULL,
    source_label TEXT NULL,
    source_title TEXT NULL,
    saved_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    confirmed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (owner_user_id, key),
    CHECK (
        (sensitive AND value_text IS NULL AND value_sealed IS NOT NULL AND value_hint IS NOT NULL)
        OR (NOT sensitive AND value_text IS NOT NULL AND value_sealed IS NULL AND value_hint IS NULL)
    )
);

ALTER TABLE saved_detail ENABLE ROW LEVEL SECURITY;

-- The owner reads and writes only their own rows; nobody else's. brownie_worker
-- gets nothing in this phase.
CREATE POLICY saved_detail_own_select ON saved_detail
    FOR SELECT
    TO brownie_api
    USING (owner_user_id = current_workspace_user_id());
CREATE POLICY saved_detail_own_insert ON saved_detail
    FOR INSERT
    TO brownie_api
    WITH CHECK (owner_user_id = current_workspace_user_id());
CREATE POLICY saved_detail_own_update ON saved_detail
    FOR UPDATE
    TO brownie_api
    USING (owner_user_id = current_workspace_user_id())
    WITH CHECK (owner_user_id = current_workspace_user_id());
CREATE POLICY saved_detail_own_delete ON saved_detail
    FOR DELETE
    TO brownie_api
    USING (owner_user_id = current_workspace_user_id());

REVOKE ALL ON saved_detail FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE, DELETE ON saved_detail TO brownie_api;
