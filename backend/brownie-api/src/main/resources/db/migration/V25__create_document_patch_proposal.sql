-- A scoped proposal to change some of a document's fields, frozen against
-- the exact revision it was generated from. Proposed values reuse the
-- same closed typed-field JSON shape document_revision's own content
-- column already establishes; proposed evidence reuses the same
-- field-id -> source-span-id shape document_revision_field_evidence
-- already establishes, just not requiring every proposed field to have
-- one. Status transitions PROPOSED -> ACCEPTED exactly once; there is no
-- REJECTED state here because rejecting a proposal has no effect on the
-- document at all -- simply never accepting it is sufficient.
CREATE TABLE document_patch_proposal (
    id BIGSERIAL PRIMARY KEY,
    workspace_id BIGINT NOT NULL,
    document_id BIGINT NOT NULL,
    base_revision_id BIGINT NOT NULL,
    proposed_values JSONB NOT NULL,
    status TEXT NOT NULL DEFAULT 'PROPOSED',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT document_patch_proposal_base_revision_fk
        FOREIGN KEY (workspace_id, document_id, base_revision_id)
        REFERENCES document_revision (workspace_id, document_id, id),
    CONSTRAINT document_patch_proposal_status_enum
        CHECK (status IN ('PROPOSED', 'ACCEPTED'))
);

CREATE INDEX document_patch_proposal_lookup_idx
    ON document_patch_proposal (workspace_id, document_id);

-- Needed before document_patch_proposal_evidence's own foreign key below
-- can reference (workspace_id, id) together -- a plain PRIMARY KEY on id
-- alone is not enough for a composite foreign key naming workspace_id too.
ALTER TABLE document_patch_proposal ADD CONSTRAINT document_patch_proposal_workspace_id_id_key UNIQUE (workspace_id, id);

-- One row per proposed field's own evidence, mirroring
-- document_revision_field_evidence's exact shape -- a proposal is never
-- required to cite evidence for a field it proposes.
CREATE TABLE document_patch_proposal_evidence (
    id BIGSERIAL PRIMARY KEY,
    workspace_id BIGINT NOT NULL,
    proposal_id BIGINT NOT NULL,
    field_id TEXT NOT NULL,
    source_span_id BIGINT NOT NULL,
    CONSTRAINT document_patch_proposal_evidence_proposal_fk
        FOREIGN KEY (workspace_id, proposal_id) REFERENCES document_patch_proposal (workspace_id, id),
    CONSTRAINT document_patch_proposal_evidence_span_fk
        FOREIGN KEY (workspace_id, source_span_id) REFERENCES source_span (workspace_id, id),
    CONSTRAINT document_patch_proposal_evidence_field_id_not_blank
        CHECK (char_length(btrim(field_id)) > 0),
    CONSTRAINT document_patch_proposal_evidence_unique
        UNIQUE (proposal_id, field_id, source_span_id)
);

CREATE INDEX document_patch_proposal_evidence_lookup_idx
    ON document_patch_proposal_evidence (workspace_id, proposal_id);

ALTER TABLE document_patch_proposal ENABLE ROW LEVEL SECURITY;
ALTER TABLE document_patch_proposal_evidence ENABLE ROW LEVEL SECURITY;

CREATE POLICY document_patch_proposal_member_select ON document_patch_proposal
    FOR SELECT TO brownie_api
    USING (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = document_patch_proposal.workspace_id
              AND wm.user_id = current_workspace_user_id()
        )
    );

CREATE POLICY document_patch_proposal_member_insert ON document_patch_proposal
    FOR INSERT TO brownie_api
    WITH CHECK (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = document_patch_proposal.workspace_id
              AND wm.user_id = current_workspace_user_id()
        )
    );

-- Only a PROPOSED row can ever be updated, and only into ACCEPTED -- the
-- same "the WHERE clause is the only place a race is actually resolved"
-- pattern question's own answer column already establishes.
CREATE POLICY document_patch_proposal_member_update ON document_patch_proposal
    FOR UPDATE TO brownie_api
    USING (
        status = 'PROPOSED'
        AND EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = document_patch_proposal.workspace_id
              AND wm.user_id = current_workspace_user_id()
        )
    )
    WITH CHECK (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = document_patch_proposal.workspace_id
              AND wm.user_id = current_workspace_user_id()
        )
    );

CREATE POLICY document_patch_proposal_evidence_member_select ON document_patch_proposal_evidence
    FOR SELECT TO brownie_api
    USING (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = document_patch_proposal_evidence.workspace_id
              AND wm.user_id = current_workspace_user_id()
        )
    );

CREATE POLICY document_patch_proposal_evidence_member_insert ON document_patch_proposal_evidence
    FOR INSERT TO brownie_api
    WITH CHECK (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = document_patch_proposal_evidence.workspace_id
              AND wm.user_id = current_workspace_user_id()
        )
    );
