-- A typed question raised against one document's field: either a required
-- field extraction could not resolve, or a resolved candidate conflicts
-- with that field's own current value in the document. candidates is a
-- small, bounded JSON array of the alternatives being asked about (empty
-- for a plain missing-information question); it is descriptive context for
-- the person answering, not itself an executable structure. A question is
-- append-only until answered exactly once -- there is no route back from
-- ANSWERED to OPEN, and no route to change an existing answer, matching
-- this codebase's existing preference for an auditable state transition
-- over a freely mutable row.
CREATE TABLE question (
    id BIGSERIAL PRIMARY KEY,
    workspace_id BIGINT NOT NULL,
    document_id BIGINT NOT NULL,
    field_id TEXT NOT NULL,
    reason TEXT NOT NULL,
    candidates JSONB NOT NULL,
    status TEXT NOT NULL DEFAULT 'OPEN',
    answer_value TEXT,
    answered_by_user_id BIGINT,
    answered_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT question_document_fk FOREIGN KEY (workspace_id, document_id) REFERENCES document (workspace_id, id),
    CONSTRAINT question_answered_by_membership_fk
        FOREIGN KEY (workspace_id, answered_by_user_id) REFERENCES workspace_member (workspace_id, user_id),
    CONSTRAINT question_reason_known CHECK (reason IN ('MISSING_REQUIRED', 'CONFLICT')),
    CONSTRAINT question_status_known CHECK (status IN ('OPEN', 'ANSWERED')),
    CONSTRAINT question_field_id_not_blank CHECK (char_length(btrim(field_id)) > 0),
    CONSTRAINT question_candidates_array CHECK (jsonb_typeof(candidates) = 'array'),
    CONSTRAINT question_answer_consistency CHECK (
        (status = 'OPEN' AND answer_value IS NULL AND answered_by_user_id IS NULL AND answered_at IS NULL)
        OR (status = 'ANSWERED' AND answer_value IS NOT NULL AND answered_by_user_id IS NOT NULL AND answered_at IS NOT NULL)
    )
);

CREATE INDEX question_workspace_document_status_idx ON question (workspace_id, document_id, status);

ALTER TABLE question ENABLE ROW LEVEL SECURITY;

CREATE POLICY question_member_select ON question
    FOR SELECT TO brownie_api
    USING (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = question.workspace_id
              AND wm.user_id = current_workspace_user_id()
        )
    );

CREATE POLICY question_member_insert ON question
    FOR INSERT TO brownie_api
    WITH CHECK (
        status = 'OPEN'
        AND EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = question.workspace_id
              AND wm.user_id = current_workspace_user_id()
        )
    );

-- Only an OPEN question owned by this workspace can be updated, and only
-- into a fully-answered state by the actor answering it -- never a partial
-- update, never a change to an already-answered row (USING re-checks the
-- pre-update row, so an already-ANSWERED question is simply not a match).
CREATE POLICY question_member_answer ON question
    FOR UPDATE TO brownie_api
    USING (
        status = 'OPEN'
        AND EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = question.workspace_id
              AND wm.user_id = current_workspace_user_id()
        )
    )
    WITH CHECK (
        status = 'ANSWERED'
        AND answered_by_user_id = current_workspace_user_id()
        AND answer_value IS NOT NULL
        AND answered_at IS NOT NULL
    );
