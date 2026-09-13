-- Lets document_revision_field_evidence reference (workspace_id, id)
-- together as a genuine composite foreign key, the same reasoning already
-- applied to source_snapshot's own (workspace_id, id) key in V11.
ALTER TABLE source_span ADD CONSTRAINT source_span_workspace_id_id_key UNIQUE (workspace_id, id);

-- A user-asserted citation: which already-persisted source spans back one
-- field's value in one immutable document revision. This is deliberately
-- separate from document_revision's own content/content_hash -- provenance
-- about a value is not part of the value's own typed identity, and two
-- revisions can carry the identical field values while citing different
-- evidence. It is not the later, model-assisted ClaimEvidence relationship
-- (support kind, validator result); it only records that the actor pointed
-- at this excerpt for this field.
CREATE TABLE document_revision_field_evidence (
    id BIGSERIAL PRIMARY KEY,
    workspace_id BIGINT NOT NULL,
    document_id BIGINT NOT NULL,
    revision_id BIGINT NOT NULL,
    field_id TEXT NOT NULL,
    source_span_id BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT document_revision_field_evidence_revision_fk
        FOREIGN KEY (workspace_id, document_id, revision_id)
        REFERENCES document_revision (workspace_id, document_id, id),
    CONSTRAINT document_revision_field_evidence_span_fk
        FOREIGN KEY (workspace_id, source_span_id) REFERENCES source_span (workspace_id, id),
    CONSTRAINT document_revision_field_evidence_field_id_not_blank
        CHECK (char_length(btrim(field_id)) > 0),
    CONSTRAINT document_revision_field_evidence_unique
        UNIQUE (document_id, revision_id, field_id, source_span_id)
);

CREATE INDEX document_revision_field_evidence_lookup_idx
    ON document_revision_field_evidence (workspace_id, document_id, revision_id);

ALTER TABLE document_revision_field_evidence ENABLE ROW LEVEL SECURITY;

CREATE POLICY document_revision_field_evidence_member_select ON document_revision_field_evidence
    FOR SELECT TO brownie_api
    USING (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = document_revision_field_evidence.workspace_id
              AND wm.user_id = current_workspace_user_id()
        )
    );

CREATE POLICY document_revision_field_evidence_member_insert ON document_revision_field_evidence
    FOR INSERT TO brownie_api
    WITH CHECK (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = document_revision_field_evidence.workspace_id
              AND wm.user_id = current_workspace_user_id()
        )
    );
