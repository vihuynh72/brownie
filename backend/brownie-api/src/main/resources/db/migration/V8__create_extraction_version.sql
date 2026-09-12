-- One artifact's own id is already globally unique, but a bare id alone on
-- a child table's foreign key would not stop that child row from claiming
-- a workspace_id that disagrees with the artifact's real owning workspace.
-- This composite unique key lets extraction_version's own foreign key
-- reference (workspace_id, id) together, so Postgres itself rejects a
-- mismatched pair rather than relying on application code to keep them in
-- sync.
ALTER TABLE artifact ADD CONSTRAINT artifact_workspace_id_id_key UNIQUE (workspace_id, id);

-- One immutable extraction attempt against one artifact under one parser
-- version. Extraction is a pure function of an artifact's immutable bytes
-- and the extractor's own version, so exactly one row exists per
-- (artifact_id, parser_version) pair: status COMPLETE carries a graph and
-- an empty feature_report; UNSUPPORTED carries a non-empty feature_report
-- and no graph; FAILED carries neither, only failure_reason.
CREATE TABLE extraction_version (
    id BIGSERIAL PRIMARY KEY,
    workspace_id BIGINT NOT NULL,
    artifact_id BIGINT NOT NULL,
    parser_version TEXT NOT NULL,
    status TEXT NOT NULL,
    feature_report JSONB NOT NULL DEFAULT '[]',
    graph JSONB,
    failure_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT extraction_version_artifact_fk
        FOREIGN KEY (workspace_id, artifact_id) REFERENCES artifact (workspace_id, id),
    CONSTRAINT extraction_version_artifact_parser_version_key UNIQUE (artifact_id, parser_version)
);

CREATE INDEX extraction_version_workspace_id_idx ON extraction_version (workspace_id);

ALTER TABLE extraction_version ENABLE ROW LEVEL SECURITY;

-- Same member-scoped policy shape as artifact's own (V6): a row is visible
-- or writable only to a member of the workspace it belongs to, checked
-- against the transaction-local user set by TenantContext.
CREATE POLICY extraction_version_member_select ON extraction_version
    FOR SELECT
    USING (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = extraction_version.workspace_id
              AND wm.user_id = current_workspace_user_id()
        )
    );

CREATE POLICY extraction_version_member_insert ON extraction_version
    FOR INSERT
    WITH CHECK (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = extraction_version.workspace_id
              AND wm.user_id = current_workspace_user_id()
        )
    );
