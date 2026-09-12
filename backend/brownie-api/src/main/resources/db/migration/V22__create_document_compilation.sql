-- One exact record of compiling a document revision into a filled DOCX and
-- a rendered PDF: which template version's bindings were used, the
-- resulting artifacts and their hashes, the renderer that produced the
-- PDF, and independent content-integrity findings. This is a compilation
-- record, not the later, fuller export receipt this plan describes (bound
-- to a human review decision and a validation manifest, neither of which
-- exist yet) -- named for what it actually is.
CREATE TABLE document_compilation (
    id BIGSERIAL PRIMARY KEY,
    workspace_id BIGINT NOT NULL,
    document_id BIGINT NOT NULL,
    revision_id BIGINT NOT NULL,
    template_id BIGINT NOT NULL,
    template_version_id BIGINT NOT NULL,
    docx_artifact_id BIGINT NOT NULL,
    docx_sha256 TEXT NOT NULL,
    pdf_artifact_id BIGINT NOT NULL,
    pdf_sha256 TEXT NOT NULL,
    renderer_version TEXT NOT NULL,
    integrity_findings JSONB NOT NULL,
    compiled_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT document_compilation_revision_fk
        FOREIGN KEY (workspace_id, document_id, revision_id)
        REFERENCES document_revision (workspace_id, document_id, id),
    CONSTRAINT document_compilation_template_version_fk
        FOREIGN KEY (workspace_id, template_id, template_version_id)
        REFERENCES template_version (workspace_id, template_id, id),
    CONSTRAINT document_compilation_docx_artifact_fk
        FOREIGN KEY (workspace_id, docx_artifact_id) REFERENCES artifact (workspace_id, id),
    CONSTRAINT document_compilation_pdf_artifact_fk
        FOREIGN KEY (workspace_id, pdf_artifact_id) REFERENCES artifact (workspace_id, id),
    CONSTRAINT document_compilation_docx_sha256_format CHECK (docx_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT document_compilation_pdf_sha256_format CHECK (pdf_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT document_compilation_renderer_version_not_blank CHECK (char_length(btrim(renderer_version)) > 0),
    CONSTRAINT document_compilation_integrity_findings_array CHECK (jsonb_typeof(integrity_findings) = 'array')
);

CREATE INDEX document_compilation_revision_idx
    ON document_compilation (workspace_id, document_id, revision_id, compiled_at DESC);

ALTER TABLE document_compilation ENABLE ROW LEVEL SECURITY;

CREATE POLICY document_compilation_member_select ON document_compilation
    FOR SELECT TO brownie_api
    USING (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = document_compilation.workspace_id
              AND wm.user_id = current_workspace_user_id()
        )
    );

CREATE POLICY document_compilation_member_insert ON document_compilation
    FOR INSERT TO brownie_api
    WITH CHECK (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = document_compilation.workspace_id
              AND wm.user_id = current_workspace_user_id()
        )
    );
