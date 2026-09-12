-- Needed so template_version can reference (workspace_id, artifact_id) and
-- (workspace_id, extraction_version_id) compositely, the same
-- cross-tenant-reference protection V8 already gave artifact itself.
ALTER TABLE extraction_version ADD CONSTRAINT extraction_version_workspace_id_id_key UNIQUE (workspace_id, id);

-- A template's identity and lifecycle pointer. current_active_version_id
-- has no foreign key yet -- template_version (below) does not exist until
-- after this statement runs, and it in turn references template. The
-- constraint closing that circular reference is added at the bottom of
-- this file, once both tables exist.
CREATE TABLE template (
    id BIGSERIAL PRIMARY KEY,
    workspace_id BIGINT NOT NULL,
    display_name TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'DRAFT',
    current_active_version_id BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT template_workspace_id_id_key UNIQUE (workspace_id, id)
);

CREATE INDEX template_workspace_id_idx ON template (workspace_id);

-- One field-definition/binding set at one sequential version_number for one
-- template. DRAFT rows are mutable in place (field_definitions and
-- version_number both advance together on each replace -- see
-- JdbcTemplateRepository#replaceDraftBindings); ACTIVATED rows never
-- change again. At most one DRAFT row exists per template at a time.
CREATE TABLE template_version (
    id BIGSERIAL PRIMARY KEY,
    workspace_id BIGINT NOT NULL,
    template_id BIGINT NOT NULL,
    version_number INT NOT NULL,
    source_artifact_id BIGINT NOT NULL,
    extraction_version_id BIGINT NOT NULL,
    status TEXT NOT NULL DEFAULT 'DRAFT',
    field_definitions JSONB NOT NULL DEFAULT '[]',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    activated_at TIMESTAMPTZ,
    CONSTRAINT template_version_workspace_id_id_key UNIQUE (workspace_id, id),
    CONSTRAINT template_version_template_fk
        FOREIGN KEY (workspace_id, template_id) REFERENCES template (workspace_id, id),
    CONSTRAINT template_version_artifact_fk
        FOREIGN KEY (workspace_id, source_artifact_id) REFERENCES artifact (workspace_id, id),
    CONSTRAINT template_version_extraction_fk
        FOREIGN KEY (workspace_id, extraction_version_id) REFERENCES extraction_version (workspace_id, id),
    CONSTRAINT template_version_number_key UNIQUE (template_id, version_number)
);

CREATE INDEX template_version_workspace_id_idx ON template_version (workspace_id);

-- At most one open draft per template -- a partial unique index, the same
-- pattern the personal-workspace constraint already established.
CREATE UNIQUE INDEX template_version_one_draft_idx ON template_version (template_id) WHERE status = 'DRAFT';

ALTER TABLE template ADD CONSTRAINT template_active_version_fk
    FOREIGN KEY (workspace_id, current_active_version_id) REFERENCES template_version (workspace_id, id);

ALTER TABLE template ENABLE ROW LEVEL SECURITY;
ALTER TABLE template_version ENABLE ROW LEVEL SECURITY;

-- Same member-scoped policy shape as artifact's and extraction_version's
-- own (V6, V8). template additionally needs an UPDATE policy -- activation
-- changes its status and current_active_version_id -- scoped to members the
-- same way its SELECT/INSERT policies are.
CREATE POLICY template_member_select ON template
    FOR SELECT
    USING (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = template.workspace_id AND wm.user_id = current_workspace_user_id()
        )
    );

CREATE POLICY template_member_insert ON template
    FOR INSERT
    WITH CHECK (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = template.workspace_id AND wm.user_id = current_workspace_user_id()
        )
    );

CREATE POLICY template_member_update ON template
    FOR UPDATE
    USING (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = template.workspace_id AND wm.user_id = current_workspace_user_id()
        )
    );

CREATE POLICY template_version_member_select ON template_version
    FOR SELECT
    USING (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = template_version.workspace_id AND wm.user_id = current_workspace_user_id()
        )
    );

CREATE POLICY template_version_member_insert ON template_version
    FOR INSERT
    WITH CHECK (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = template_version.workspace_id AND wm.user_id = current_workspace_user_id()
        )
    );

-- Only a DRAFT row can ever be the *target* of an UPDATE (USING) -- an
-- already-ACTIVATED row is rejected by row-level security itself, defense
-- in depth beyond whatever the application layer already checks. The
-- resulting row (WITH CHECK) is allowed to land on either status, since
-- one caller (replaceDraftBindings) writes a new DRAFT row back and
-- another (activate) deliberately flips it to ACTIVATED; without an
-- explicit WITH CHECK here, Postgres reuses USING for both purposes and an
-- activation's own status change would violate its own read-side check.
CREATE POLICY template_version_member_update ON template_version
    FOR UPDATE
    USING (
        status = 'DRAFT'
        AND EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = template_version.workspace_id AND wm.user_id = current_workspace_user_id()
        )
    )
    WITH CHECK (
        status IN ('DRAFT', 'ACTIVATED')
        AND EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = template_version.workspace_id AND wm.user_id = current_workspace_user_id()
        )
    );
