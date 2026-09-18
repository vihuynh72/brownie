-- Two records that were missing between a document and the work done for
-- it.
--
-- document_source is the document's own link to a workspace-level source
-- snapshot: which notes or transcripts belong to this document, so the
-- workspace can list them after a reload and a generation can be started
-- from one of them by name rather than by a value held only in a browser
-- tab. Linking the same snapshot twice is a no-op, not a duplicate.
--
-- generation_run is the owned record of one generation started for one
-- document: which revision was current, which source fed it, which durable
-- job carries it, and which model and prompt version it used. Anything
-- the worker stages for the run (detected questions, resolved answers) is
-- read only after this row proves the job belongs to the workspace and
-- document a caller named, and the questions it raises reference this row.

CREATE TABLE document_source (
    workspace_id BIGINT NOT NULL,
    document_id BIGINT NOT NULL,
    source_snapshot_id BIGINT NOT NULL,
    attached_by_user_id BIGINT NOT NULL,
    attached_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT document_source_pk PRIMARY KEY (workspace_id, document_id, source_snapshot_id),
    CONSTRAINT document_source_document_fk
        FOREIGN KEY (workspace_id, document_id) REFERENCES document (workspace_id, id),
    CONSTRAINT document_source_snapshot_fk
        FOREIGN KEY (workspace_id, source_snapshot_id) REFERENCES source_snapshot (workspace_id, id),
    CONSTRAINT document_source_attached_by_fk
        FOREIGN KEY (workspace_id, attached_by_user_id) REFERENCES workspace_member (workspace_id, user_id)
);

CREATE INDEX document_source_document_idx ON document_source (workspace_id, document_id, attached_at DESC);

ALTER TABLE document_source ENABLE ROW LEVEL SECURITY;

CREATE POLICY document_source_member_select ON document_source
    FOR SELECT TO brownie_api
    USING (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = document_source.workspace_id
              AND wm.user_id = current_workspace_user_id()
        )
    );

CREATE POLICY document_source_member_insert ON document_source
    FOR INSERT TO brownie_api
    WITH CHECK (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = document_source.workspace_id
              AND wm.user_id = current_workspace_user_id()
        )
    );

CREATE TABLE generation_run (
    id BIGSERIAL PRIMARY KEY,
    workspace_id BIGINT NOT NULL,
    document_id BIGINT NOT NULL,
    base_revision_id BIGINT NOT NULL,
    template_version_id BIGINT NOT NULL,
    source_snapshot_id BIGINT NOT NULL,
    job_id BIGINT NOT NULL,
    bundle_hash TEXT NOT NULL,
    model_name TEXT NOT NULL,
    prompt_version TEXT NOT NULL,
    requested_by_user_id BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT generation_run_workspace_id_id_key UNIQUE (workspace_id, id),
    CONSTRAINT generation_run_job_key UNIQUE (job_id),
    CONSTRAINT generation_run_revision_fk
        FOREIGN KEY (workspace_id, document_id, base_revision_id)
        REFERENCES document_revision (workspace_id, document_id, id),
    CONSTRAINT generation_run_template_version_fk
        FOREIGN KEY (workspace_id, template_version_id) REFERENCES template_version (workspace_id, id),
    CONSTRAINT generation_run_snapshot_fk
        FOREIGN KEY (workspace_id, source_snapshot_id) REFERENCES source_snapshot (workspace_id, id),
    CONSTRAINT generation_run_job_fk
        FOREIGN KEY (workspace_id, job_id) REFERENCES job (workspace_id, id),
    CONSTRAINT generation_run_requested_by_fk
        FOREIGN KEY (workspace_id, requested_by_user_id) REFERENCES workspace_member (workspace_id, user_id),
    CONSTRAINT generation_run_bundle_hash_format CHECK (bundle_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT generation_run_model_name_not_blank CHECK (char_length(btrim(model_name)) > 0),
    CONSTRAINT generation_run_prompt_version_not_blank CHECK (char_length(btrim(prompt_version)) > 0)
);

CREATE INDEX generation_run_document_idx ON generation_run (workspace_id, document_id, created_at DESC);

ALTER TABLE generation_run ENABLE ROW LEVEL SECURITY;

CREATE POLICY generation_run_member_select ON generation_run
    FOR SELECT TO brownie_api
    USING (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = generation_run.workspace_id
              AND wm.user_id = current_workspace_user_id()
        )
    );

CREATE POLICY generation_run_member_insert ON generation_run
    FOR INSERT TO brownie_api
    WITH CHECK (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = generation_run.workspace_id
              AND wm.user_id = current_workspace_user_id()
        )
    );

-- A question now knows which run raised it, and which attempt of that
-- run: the job's fencing token at the moment the worker staged it. Older
-- rows predate runs and keep nulls; every new row carries both. The run
-- is how one run's questions stop shadowing a later run's on the same
-- document; the attempt token is how a reload after every question was
-- answered does not materialize the same staged questions a second time.
ALTER TABLE question ADD COLUMN generation_run_id BIGINT;
ALTER TABLE question ADD COLUMN attempt_fencing_token BIGINT;
ALTER TABLE question ADD CONSTRAINT question_generation_run_fk
    FOREIGN KEY (workspace_id, generation_run_id) REFERENCES generation_run (workspace_id, id);
ALTER TABLE question ADD CONSTRAINT question_attempt_requires_run
    CHECK (attempt_fencing_token IS NULL OR generation_run_id IS NOT NULL);
CREATE INDEX question_generation_run_idx ON question (workspace_id, generation_run_id, status);
