-- export_approval (V31) had no unique constraint on (workspace_id, id), so
-- nothing could foreign-key into it by its own bare id -- added here,
-- before it is needed below, rather than editing V31 itself (already run
-- and tested in an earlier task this same session).
ALTER TABLE export_approval ADD CONSTRAINT export_approval_workspace_id_id_key UNIQUE (workspace_id, id);

-- The final, durable record of one completed export transaction: exactly
-- which artifact bytes were shipped, for which approval, bound to which
-- validation manifest. pdf columns are nullable together, the same
-- honest partial-result shape validation_manifest already uses -- a
-- receipt never claims a PDF succeeded when the bound manifest itself
-- never produced one.
CREATE TABLE export_receipt (
    id BIGSERIAL PRIMARY KEY,
    workspace_id BIGINT NOT NULL,
    document_id BIGINT NOT NULL,
    revision_id BIGINT NOT NULL,
    template_version_id BIGINT NOT NULL,
    export_approval_id BIGINT NOT NULL,
    validation_manifest_id BIGINT NOT NULL,
    docx_artifact_id BIGINT NOT NULL,
    docx_sha256 TEXT NOT NULL,
    pdf_artifact_id BIGINT,
    pdf_sha256 TEXT,
    format TEXT NOT NULL,
    actor_user_id BIGINT NOT NULL,
    exported_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT export_receipt_revision_fk
        FOREIGN KEY (workspace_id, document_id, revision_id)
        REFERENCES document_revision (workspace_id, document_id, id),
    CONSTRAINT export_receipt_template_version_fk
        FOREIGN KEY (workspace_id, template_version_id) REFERENCES template_version (workspace_id, id),
    CONSTRAINT export_receipt_approval_fk
        FOREIGN KEY (workspace_id, export_approval_id) REFERENCES export_approval (workspace_id, id),
    CONSTRAINT export_receipt_validation_manifest_fk
        FOREIGN KEY (workspace_id, validation_manifest_id) REFERENCES validation_manifest (workspace_id, id),
    CONSTRAINT export_receipt_docx_artifact_fk
        FOREIGN KEY (workspace_id, docx_artifact_id) REFERENCES artifact (workspace_id, id),
    CONSTRAINT export_receipt_pdf_artifact_fk
        FOREIGN KEY (workspace_id, pdf_artifact_id) REFERENCES artifact (workspace_id, id),
    CONSTRAINT export_receipt_docx_sha256_format CHECK (docx_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT export_receipt_pdf_sha256_format CHECK (pdf_sha256 IS NULL OR pdf_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT export_receipt_pdf_columns_together
        CHECK ((pdf_artifact_id IS NULL) = (pdf_sha256 IS NULL)),
    CONSTRAINT export_receipt_format_valid CHECK (format IN ('DOCX', 'PDF', 'BOTH'))
);

CREATE INDEX export_receipt_document_idx
    ON export_receipt (workspace_id, document_id, exported_at DESC);

ALTER TABLE export_receipt ENABLE ROW LEVEL SECURITY;

CREATE POLICY export_receipt_member_select ON export_receipt
    FOR SELECT TO brownie_api
    USING (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = export_receipt.workspace_id
              AND wm.user_id = current_workspace_user_id()
        )
    );

CREATE POLICY export_receipt_member_insert ON export_receipt
    FOR INSERT TO brownie_api
    WITH CHECK (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = export_receipt.workspace_id
              AND wm.user_id = current_workspace_user_id()
        )
    );
