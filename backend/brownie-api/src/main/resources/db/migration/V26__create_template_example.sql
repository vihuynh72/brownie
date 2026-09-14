-- One completed document offered as an example of one specific template
-- draft version, immutable once attached: alignment_status is decided once,
-- at attach time, against that exact version's own field definitions, and
-- never updated afterward -- a later draft-binding change does not silently
-- re-grade an already-attached example. Append-only, the same
-- select/insert-only row-level security shape document_revision_field_evidence
-- (V21) already establishes for a similar immutable, per-relationship record.
CREATE TABLE template_example (
    id BIGSERIAL PRIMARY KEY,
    workspace_id BIGINT NOT NULL,
    template_id BIGINT NOT NULL,
    template_version_id BIGINT NOT NULL,
    source_artifact_id BIGINT NOT NULL,
    extraction_version_id BIGINT NOT NULL,
    alignment_status TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT template_example_template_fk
        FOREIGN KEY (workspace_id, template_id) REFERENCES template (workspace_id, id),
    CONSTRAINT template_example_template_version_fk
        FOREIGN KEY (workspace_id, template_version_id) REFERENCES template_version (workspace_id, id),
    CONSTRAINT template_example_artifact_fk
        FOREIGN KEY (workspace_id, source_artifact_id) REFERENCES artifact (workspace_id, id),
    CONSTRAINT template_example_extraction_fk
        FOREIGN KEY (workspace_id, extraction_version_id) REFERENCES extraction_version (workspace_id, id),
    CONSTRAINT template_example_alignment_status_enum
        CHECK (alignment_status IN ('ALIGNED', 'MISMATCHED_FAMILY'))
);

CREATE INDEX template_example_lookup_idx ON template_example (workspace_id, template_version_id);

ALTER TABLE template_example ENABLE ROW LEVEL SECURITY;

CREATE POLICY template_example_member_select ON template_example
    FOR SELECT TO brownie_api
    USING (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = template_example.workspace_id AND wm.user_id = current_workspace_user_id()
        )
    );

CREATE POLICY template_example_member_insert ON template_example
    FOR INSERT TO brownie_api
    WITH CHECK (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = template_example.workspace_id AND wm.user_id = current_workspace_user_id()
        )
    );
