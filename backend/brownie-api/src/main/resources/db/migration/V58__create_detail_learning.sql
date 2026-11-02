-- What one "learn details after approval" run wrote: the run itself, and for
-- every key it set, what was there before, so Undo can restore exactly that.

CREATE TABLE detail_learning (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    owner_user_id BIGINT NOT NULL REFERENCES user_identity (id) ON DELETE CASCADE,
    document_id BIGINT NOT NULL REFERENCES document (id) ON DELETE CASCADE,
    writes JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE detail_learning ENABLE ROW LEVEL SECURITY;

CREATE POLICY detail_learning_own_select ON detail_learning
    FOR SELECT
    TO brownie_api
    USING (owner_user_id = current_workspace_user_id());
CREATE POLICY detail_learning_own_insert ON detail_learning
    FOR INSERT
    TO brownie_api
    WITH CHECK (owner_user_id = current_workspace_user_id());

REVOKE ALL ON detail_learning FROM PUBLIC;
GRANT SELECT, INSERT ON detail_learning TO brownie_api;
