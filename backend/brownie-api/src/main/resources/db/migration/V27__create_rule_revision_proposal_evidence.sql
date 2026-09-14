-- Needed so rule_revision_proposal_evidence can reference (workspace_id, id)
-- compositely, the same reasoning already applied to source_span (V21) and
-- document_patch_proposal (V25).
ALTER TABLE rule_revision ADD CONSTRAINT rule_revision_workspace_id_id_key UNIQUE (workspace_id, id);

-- Needed so the same table can also reference template_example
-- (workspace_id, id) compositely.
ALTER TABLE template_example ADD CONSTRAINT template_example_workspace_id_id_key UNIQUE (workspace_id, id);

-- One row per (rule, example) pair actually considered while proposing that
-- rule from examples -- normalized rather than a JSONB array of IDs so
-- template_example_fk below is a real, enforced foreign key, the same
-- reasoning document_patch_proposal_evidence (V25) already applies to its
-- own source_span_id. Never written for a manually proposed rule (see
-- RuleRevision's own javadoc); append-only, like every other evidence table
-- in this codebase.
CREATE TABLE rule_revision_proposal_evidence (
    id BIGSERIAL PRIMARY KEY,
    workspace_id BIGINT NOT NULL,
    rule_revision_id BIGINT NOT NULL,
    template_example_id BIGINT NOT NULL,
    supports BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT rule_revision_proposal_evidence_rule_fk
        FOREIGN KEY (workspace_id, rule_revision_id) REFERENCES rule_revision (workspace_id, id),
    CONSTRAINT rule_revision_proposal_evidence_example_fk
        FOREIGN KEY (workspace_id, template_example_id) REFERENCES template_example (workspace_id, id),
    CONSTRAINT rule_revision_proposal_evidence_unique
        UNIQUE (rule_revision_id, template_example_id)
);

CREATE INDEX rule_revision_proposal_evidence_lookup_idx
    ON rule_revision_proposal_evidence (workspace_id, rule_revision_id);

ALTER TABLE rule_revision_proposal_evidence ENABLE ROW LEVEL SECURITY;

CREATE POLICY rule_revision_proposal_evidence_member_select ON rule_revision_proposal_evidence
    FOR SELECT TO brownie_api
    USING (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = rule_revision_proposal_evidence.workspace_id AND wm.user_id = current_workspace_user_id()
        )
    );

CREATE POLICY rule_revision_proposal_evidence_member_insert ON rule_revision_proposal_evidence
    FOR INSERT TO brownie_api
    WITH CHECK (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = rule_revision_proposal_evidence.workspace_id AND wm.user_id = current_workspace_user_id()
        )
    );
