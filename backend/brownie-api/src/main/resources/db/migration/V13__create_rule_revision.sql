-- One proposed rule against one template's own draft version. Every row
-- from this codebase's own code today is PROPOSED and never changes again
-- afterward -- no UPDATE policy exists yet, the same write-once shape
-- extraction_version (V8) already established, until a later accept/reject
-- decision needs one.
CREATE TABLE rule_revision (
    id BIGSERIAL PRIMARY KEY,
    workspace_id BIGINT NOT NULL,
    template_id BIGINT NOT NULL,
    template_version_id BIGINT NOT NULL,
    category TEXT NOT NULL,
    scope JSONB NOT NULL,
    payload JSONB NOT NULL,
    schema_version TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'PROPOSED',
    human_explanation TEXT,
    author_user_id BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT rule_revision_template_fk
        FOREIGN KEY (workspace_id, template_id) REFERENCES template (workspace_id, id),
    CONSTRAINT rule_revision_template_version_fk
        FOREIGN KEY (workspace_id, template_version_id) REFERENCES template_version (workspace_id, id)
);

CREATE INDEX rule_revision_workspace_id_idx ON rule_revision (workspace_id);
CREATE INDEX rule_revision_template_version_id_idx ON rule_revision (template_version_id);

ALTER TABLE rule_revision ENABLE ROW LEVEL SECURITY;

-- Same member-scoped policy shape as template's and template_version's own (V12).
CREATE POLICY rule_revision_member_select ON rule_revision
    FOR SELECT
    USING (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = rule_revision.workspace_id AND wm.user_id = current_workspace_user_id()
        )
    );

CREATE POLICY rule_revision_member_insert ON rule_revision
    FOR INSERT
    WITH CHECK (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = rule_revision.workspace_id AND wm.user_id = current_workspace_user_id()
        )
    );
