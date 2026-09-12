-- Uploaded files, tracked separately from their bytes: blob_key names the
-- object in blob storage, byte_count/sha256 stay NULL until content has
-- actually been written and observed once, and never change after that.
CREATE TABLE artifact (
    id BIGSERIAL PRIMARY KEY,
    workspace_id BIGINT NOT NULL REFERENCES workspace (id),
    blob_key TEXT NOT NULL UNIQUE,
    status TEXT NOT NULL DEFAULT 'UPLOADING',
    byte_count BIGINT,
    sha256 TEXT,
    rejection_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    finalized_at TIMESTAMPTZ
);

CREATE INDEX artifact_workspace_id_idx ON artifact (workspace_id);

ALTER TABLE artifact ENABLE ROW LEVEL SECURITY;

CREATE POLICY artifact_member_select ON artifact
    FOR SELECT
    USING (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = artifact.workspace_id
              AND wm.user_id = current_workspace_user_id()
        )
    );

CREATE POLICY artifact_member_insert ON artifact
    FOR INSERT
    WITH CHECK (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = artifact.workspace_id
              AND wm.user_id = current_workspace_user_id()
        )
    );

CREATE POLICY artifact_member_update ON artifact
    FOR UPDATE
    USING (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = artifact.workspace_id
              AND wm.user_id = current_workspace_user_id()
        )
    )
    WITH CHECK (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = artifact.workspace_id
              AND wm.user_id = current_workspace_user_id()
        )
    );

-- Deliberately no separate policy for a system-wide maintenance sweep of
-- abandoned uploads. A real attempt at one was tried and reverted: Postgres
-- requires a row to be visible under the table's SELECT policies (not only
-- the UPDATE policy being evaluated) both before and after the update, so
-- a workable sweep policy would need its own SELECT-visible carve-out --
-- and because SELECT policies apply to every ordinary query against this
-- table, not only the sweep's own, that carve-out would let any member
-- read other workspaces' abandoned artifacts too. Building this safely
-- needs a database identity scoped to that one maintenance job alone,
-- distinct from brownie_api, which does not exist yet. Abandoned uploads
-- are instead expired lazily, under the acting member's own normal
-- context, the next time anyone touches them.
