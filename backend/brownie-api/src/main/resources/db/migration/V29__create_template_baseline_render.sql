-- The proven sample-fill-and-render baseline behind one activated template
-- version. Written exactly once, only once activation itself has already
-- succeeded -- a failed baseline attempt leaves no row at all, the same
-- "never persist a failed attempt" choice already made for extraction's
-- own transient parse failures at the application layer. UNIQUE on
-- template_version_id: a version activates at most once, ever, so at most
-- one baseline can ever back it.
CREATE TABLE template_baseline_render (
    id BIGSERIAL PRIMARY KEY,
    workspace_id BIGINT NOT NULL,
    template_version_id BIGINT NOT NULL,
    docx_artifact_id BIGINT NOT NULL,
    pdf_artifact_id BIGINT NOT NULL,
    renderer_version TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT template_baseline_render_version_fk
        FOREIGN KEY (workspace_id, template_version_id) REFERENCES template_version (workspace_id, id),
    CONSTRAINT template_baseline_render_docx_fk
        FOREIGN KEY (workspace_id, docx_artifact_id) REFERENCES artifact (workspace_id, id),
    CONSTRAINT template_baseline_render_pdf_fk
        FOREIGN KEY (workspace_id, pdf_artifact_id) REFERENCES artifact (workspace_id, id),
    CONSTRAINT template_baseline_render_version_unique UNIQUE (template_version_id)
);

CREATE INDEX template_baseline_render_lookup_idx ON template_baseline_render (workspace_id, template_version_id);

ALTER TABLE template_baseline_render ENABLE ROW LEVEL SECURITY;

CREATE POLICY template_baseline_render_member_select ON template_baseline_render
    FOR SELECT TO brownie_api
    USING (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = template_baseline_render.workspace_id AND wm.user_id = current_workspace_user_id()
        )
    );

CREATE POLICY template_baseline_render_member_insert ON template_baseline_render
    FOR INSERT TO brownie_api
    WITH CHECK (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = template_baseline_render.workspace_id AND wm.user_id = current_workspace_user_id()
        )
    );
