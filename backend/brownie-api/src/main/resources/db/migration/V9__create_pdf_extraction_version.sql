-- The PDF analog of extraction_version (V8): one immutable extraction
-- attempt against one PDF artifact under one parser version. Kept as its
-- own table rather than folded into extraction_version -- the two
-- formats' "what went wrong" shapes genuinely differ (DOCX rejects for a
-- list of located structural findings; a PDF is unsupported for one of
-- two whole-document reasons), and a shared table would need nullable
-- columns whose meaning depends on a format the row itself does not state.
CREATE TABLE pdf_extraction_version (
    id BIGSERIAL PRIMARY KEY,
    workspace_id BIGINT NOT NULL,
    artifact_id BIGINT NOT NULL,
    parser_version TEXT NOT NULL,
    status TEXT NOT NULL,
    unsupported_reason TEXT,
    unsupported_detail TEXT,
    graph JSONB,
    failure_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pdf_extraction_version_artifact_fk
        FOREIGN KEY (workspace_id, artifact_id) REFERENCES artifact (workspace_id, id),
    CONSTRAINT pdf_extraction_version_artifact_parser_version_key UNIQUE (artifact_id, parser_version)
);

CREATE INDEX pdf_extraction_version_workspace_id_idx ON pdf_extraction_version (workspace_id);

ALTER TABLE pdf_extraction_version ENABLE ROW LEVEL SECURITY;

-- Same member-scoped, insert-and-select-only policy shape as
-- extraction_version (V8): every row here is written exactly once and
-- never changes afterward, so there is no UPDATE policy either.
CREATE POLICY pdf_extraction_version_member_select ON pdf_extraction_version
    FOR SELECT
    USING (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = pdf_extraction_version.workspace_id
              AND wm.user_id = current_workspace_user_id()
        )
    );

CREATE POLICY pdf_extraction_version_member_insert ON pdf_extraction_version
    FOR INSERT
    WITH CHECK (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = pdf_extraction_version.workspace_id
              AND wm.user_id = current_workspace_user_id()
        )
    );
