-- The plain-text analog of extraction_version (V8) and
-- pdf_extraction_version (V9): one immutable extraction attempt against
-- one plain-text artifact under one parser version. Simpler than either --
-- plain text has no structure to represent, only its own immutable text
-- alongside a line-ending-normalized form, so a status column and two text
-- columns are the whole graph; there is no feature_report or
-- unsupported_reason, since decoding either succeeds or fails outright.
CREATE TABLE plain_text_extraction_version (
    id BIGSERIAL PRIMARY KEY,
    workspace_id BIGINT NOT NULL,
    artifact_id BIGINT NOT NULL,
    parser_version TEXT NOT NULL,
    status TEXT NOT NULL,
    original_text TEXT,
    normalized_text TEXT,
    failure_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT plain_text_extraction_version_artifact_fk
        FOREIGN KEY (workspace_id, artifact_id) REFERENCES artifact (workspace_id, id),
    CONSTRAINT plain_text_extraction_version_artifact_parser_version_key UNIQUE (artifact_id, parser_version)
);

CREATE INDEX plain_text_extraction_version_workspace_id_idx ON plain_text_extraction_version (workspace_id);

ALTER TABLE plain_text_extraction_version ENABLE ROW LEVEL SECURITY;

-- Same member-scoped, insert-and-select-only policy shape as
-- extraction_version (V8) and pdf_extraction_version (V9): every row here
-- is written exactly once and never changes afterward.
CREATE POLICY plain_text_extraction_version_member_select ON plain_text_extraction_version
    FOR SELECT
    USING (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = plain_text_extraction_version.workspace_id
              AND wm.user_id = current_workspace_user_id()
        )
    );

CREATE POLICY plain_text_extraction_version_member_insert ON plain_text_extraction_version
    FOR INSERT
    WITH CHECK (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = plain_text_extraction_version.workspace_id
              AND wm.user_id = current_workspace_user_id()
        )
    );
