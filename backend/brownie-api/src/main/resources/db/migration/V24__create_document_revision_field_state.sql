-- Five independent per-field/per-item dimensions -- authorship, evidence
-- support, validation, review, and lock -- for one immutable document
-- revision. This is deliberately separate from
-- document_revision_field_evidence: that table names WHICH source spans
-- were cited for a field; this one names how well-supported, validated,
-- reviewed, and protected that field (or one item within a repeated
-- field's list) currently is. A field can be AI-composed, directly
-- supported, unreviewed, and locked all at once -- five independent
-- columns, never one shared status enum.
CREATE TABLE document_revision_field_state (
    id BIGSERIAL PRIMARY KEY,
    workspace_id BIGINT NOT NULL,
    document_id BIGINT NOT NULL,
    revision_id BIGINT NOT NULL,
    field_id TEXT NOT NULL,
    item_index INT NULL,
    authorship TEXT NOT NULL,
    evidence_support TEXT NOT NULL,
    validation TEXT NOT NULL,
    review TEXT NOT NULL,
    lock_state TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT document_revision_field_state_revision_fk
        FOREIGN KEY (workspace_id, document_id, revision_id)
        REFERENCES document_revision (workspace_id, document_id, id),
    CONSTRAINT document_revision_field_state_field_id_not_blank
        CHECK (char_length(btrim(field_id)) > 0),
    CONSTRAINT document_revision_field_state_item_index_not_negative
        CHECK (item_index IS NULL OR item_index >= 0),
    CONSTRAINT document_revision_field_state_authorship_enum
        CHECK (authorship IN ('IMPORTED', 'AI_COMPOSED', 'USER_AUTHORED', 'MIXED')),
    CONSTRAINT document_revision_field_state_evidence_support_enum
        CHECK (evidence_support IN ('DIRECT', 'TRANSFORMED', 'AMBIGUOUS', 'UNSUPPORTED', 'MISSING')),
    CONSTRAINT document_revision_field_state_validation_enum
        CHECK (validation IN ('NOT_RUN', 'PASSED', 'WARNING', 'BLOCKING', 'UNAVAILABLE')),
    CONSTRAINT document_revision_field_state_review_enum
        CHECK (review IN ('UNREVIEWED', 'ACCEPTED', 'REJECTED', 'NEEDS_CLARIFICATION')),
    CONSTRAINT document_revision_field_state_lock_enum
        CHECK (lock_state IN ('EDITABLE', 'PRESERVE_ON_REGENERATION', 'EXPLICITLY_LOCKED'))
);

-- A table-level UNIQUE constraint cannot embed an expression -- Postgres
-- only accepts plain column names there -- so uniqueness including a
-- nullable item_index is expressed as a unique index instead. NULL is not
-- distinct from itself under a plain unique index either, so two scalar
-- rows (item_index NULL) for the same field would not collide without
-- normalizing the index; -1 stands in for "no item" only inside this one
-- index expression, never stored or read back as a real value anywhere in
-- application code.
CREATE UNIQUE INDEX document_revision_field_state_unique_idx
    ON document_revision_field_state (document_id, revision_id, field_id, (COALESCE(item_index, -1)));

CREATE INDEX document_revision_field_state_lookup_idx
    ON document_revision_field_state (workspace_id, document_id, revision_id);

ALTER TABLE document_revision_field_state ENABLE ROW LEVEL SECURITY;

CREATE POLICY document_revision_field_state_member_select ON document_revision_field_state
    FOR SELECT TO brownie_api
    USING (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = document_revision_field_state.workspace_id
              AND wm.user_id = current_workspace_user_id()
        )
    );

CREATE POLICY document_revision_field_state_member_insert ON document_revision_field_state
    FOR INSERT TO brownie_api
    WITH CHECK (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = document_revision_field_state.workspace_id
              AND wm.user_id = current_workspace_user_id()
        )
    );
