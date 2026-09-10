-- An artifact designated as a source. One artifact ever has at most one
-- snapshot, enforced here rather than only in application code.
CREATE TABLE source_snapshot (
    id BIGSERIAL PRIMARY KEY,
    workspace_id BIGINT NOT NULL,
    artifact_id BIGINT NOT NULL,
    kind TEXT NOT NULL,
    fetched_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT source_snapshot_artifact_fk
        FOREIGN KEY (workspace_id, artifact_id) REFERENCES artifact (workspace_id, id),
    CONSTRAINT source_snapshot_artifact_key UNIQUE (artifact_id)
);

-- Lets source_span reference (workspace_id, id) together as a genuine
-- composite foreign key, the same reasoning already applied to artifact's
-- own (workspace_id, id) key in V8.
ALTER TABLE source_snapshot ADD CONSTRAINT source_snapshot_workspace_id_id_key UNIQUE (workspace_id, id);

CREATE INDEX source_snapshot_workspace_id_idx ON source_snapshot (workspace_id);

ALTER TABLE source_snapshot ENABLE ROW LEVEL SECURITY;

CREATE POLICY source_snapshot_member_select ON source_snapshot
    FOR SELECT
    USING (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = source_snapshot.workspace_id
              AND wm.user_id = current_workspace_user_id()
        )
    );

CREATE POLICY source_snapshot_member_insert ON source_snapshot
    FOR INSERT
    WITH CHECK (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = source_snapshot.workspace_id
              AND wm.user_id = current_workspace_user_id()
        )
    );

-- One immutable citation into a snapshot's extraction graph, pinned to the
-- exact extraction version active when it was created (extraction_parser_
-- version) so a later parser upgrade can never silently change what an
-- existing span resolves to. locator is the per-format addressing
-- (DOCX part+node, PDF page+line, or a plain-text code-point range),
-- stored as jsonb since its shape genuinely differs by format -- the same
-- reasoning the extraction_version/pdf_extraction_version tables already
-- apply to their own graph columns.
CREATE TABLE source_span (
    id BIGSERIAL PRIMARY KEY,
    workspace_id BIGINT NOT NULL,
    source_snapshot_id BIGINT NOT NULL,
    extraction_parser_version TEXT NOT NULL,
    locator JSONB NOT NULL,
    excerpt_hash TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT source_span_snapshot_fk
        FOREIGN KEY (workspace_id, source_snapshot_id) REFERENCES source_snapshot (workspace_id, id)
);

CREATE INDEX source_span_workspace_id_idx ON source_span (workspace_id);
CREATE INDEX source_span_snapshot_id_idx ON source_span (source_snapshot_id);

ALTER TABLE source_span ENABLE ROW LEVEL SECURITY;

CREATE POLICY source_span_member_select ON source_span
    FOR SELECT
    USING (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = source_span.workspace_id
              AND wm.user_id = current_workspace_user_id()
        )
    );

CREATE POLICY source_span_member_insert ON source_span
    FOR INSERT
    WITH CHECK (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = source_span.workspace_id
              AND wm.user_id = current_workspace_user_id()
        )
    );
