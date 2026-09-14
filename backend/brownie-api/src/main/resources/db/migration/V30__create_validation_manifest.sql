-- One complete, independent run of every validation layer this codebase
-- can check today against one exact document revision: the freshly
-- filled and sanitized DOCX this run produced, its rendered PDF once a
-- later task starts rendering one (both artifact columns nullable until
-- then), and every finding every layer produced. Export approval binds to
-- one exact row here by id, never to "the latest manifest for a revision."
CREATE TABLE validation_manifest (
    id BIGSERIAL PRIMARY KEY,
    workspace_id BIGINT NOT NULL,
    document_id BIGINT NOT NULL,
    revision_id BIGINT NOT NULL,
    template_id BIGINT NOT NULL,
    template_version_id BIGINT NOT NULL,
    docx_artifact_id BIGINT NOT NULL,
    docx_sha256 TEXT NOT NULL,
    pdf_artifact_id BIGINT,
    pdf_sha256 TEXT,
    findings JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT validation_manifest_workspace_id_id_key UNIQUE (workspace_id, id),
    CONSTRAINT validation_manifest_revision_fk
        FOREIGN KEY (workspace_id, document_id, revision_id)
        REFERENCES document_revision (workspace_id, document_id, id),
    CONSTRAINT validation_manifest_template_version_fk
        FOREIGN KEY (workspace_id, template_id, template_version_id)
        REFERENCES template_version (workspace_id, template_id, id),
    CONSTRAINT validation_manifest_docx_artifact_fk
        FOREIGN KEY (workspace_id, docx_artifact_id) REFERENCES artifact (workspace_id, id),
    CONSTRAINT validation_manifest_pdf_artifact_fk
        FOREIGN KEY (workspace_id, pdf_artifact_id) REFERENCES artifact (workspace_id, id),
    CONSTRAINT validation_manifest_docx_sha256_format CHECK (docx_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT validation_manifest_pdf_sha256_format CHECK (pdf_sha256 IS NULL OR pdf_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT validation_manifest_pdf_columns_together
        CHECK ((pdf_artifact_id IS NULL) = (pdf_sha256 IS NULL)),
    CONSTRAINT validation_manifest_findings_array CHECK (jsonb_typeof(findings) = 'array')
);

CREATE INDEX validation_manifest_revision_idx
    ON validation_manifest (workspace_id, document_id, revision_id, created_at DESC);

ALTER TABLE validation_manifest ENABLE ROW LEVEL SECURITY;

CREATE POLICY validation_manifest_member_select ON validation_manifest
    FOR SELECT TO brownie_api
    USING (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = validation_manifest.workspace_id
              AND wm.user_id = current_workspace_user_id()
        )
    );

CREATE POLICY validation_manifest_member_insert ON validation_manifest
    FOR INSERT TO brownie_api
    WITH CHECK (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = validation_manifest.workspace_id
              AND wm.user_id = current_workspace_user_id()
        )
    );
