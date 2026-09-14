-- A person's own decision to approve one exact, already-validated
-- revision for export. Bound to the document revision, template version,
-- and validation manifest it was granted against; approving again always
-- appends a new row rather than mutating an old one, the same
-- immutability discipline every other revision-shaped record in this
-- schema already uses.
CREATE TABLE export_approval (
    id BIGSERIAL PRIMARY KEY,
    workspace_id BIGINT NOT NULL,
    document_id BIGINT NOT NULL,
    revision_id BIGINT NOT NULL,
    template_version_id BIGINT NOT NULL,
    validation_manifest_id BIGINT NOT NULL,
    format TEXT NOT NULL,
    actor_user_id BIGINT NOT NULL,
    approved_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT export_approval_revision_fk
        FOREIGN KEY (workspace_id, document_id, revision_id)
        REFERENCES document_revision (workspace_id, document_id, id),
    CONSTRAINT export_approval_template_version_fk
        FOREIGN KEY (workspace_id, template_version_id) REFERENCES template_version (workspace_id, id),
    CONSTRAINT export_approval_validation_manifest_fk
        FOREIGN KEY (workspace_id, validation_manifest_id) REFERENCES validation_manifest (workspace_id, id),
    CONSTRAINT export_approval_format_valid CHECK (format IN ('DOCX', 'PDF', 'BOTH'))
);

CREATE INDEX export_approval_document_idx
    ON export_approval (workspace_id, document_id, approved_at DESC);

ALTER TABLE export_approval ENABLE ROW LEVEL SECURITY;

CREATE POLICY export_approval_member_select ON export_approval
    FOR SELECT TO brownie_api
    USING (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = export_approval.workspace_id
              AND wm.user_id = current_workspace_user_id()
        )
    );

CREATE POLICY export_approval_member_insert ON export_approval
    FOR INSERT TO brownie_api
    WITH CHECK (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = export_approval.workspace_id
              AND wm.user_id = current_workspace_user_id()
        )
    );
