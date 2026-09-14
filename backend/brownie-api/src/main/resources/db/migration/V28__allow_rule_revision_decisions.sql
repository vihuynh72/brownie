-- rule_revision (V13) shipped with only SELECT/INSERT policies, correct at
-- the time since nothing produced anything but PROPOSED rows. Accept/reject
-- decisions are real now, so an UPDATE policy is needed -- the same "only a
-- PROPOSED row can ever be the target" shape document_patch_proposal (V25)
-- already establishes for an identical PROPOSED -> decided transition.
CREATE POLICY rule_revision_member_update ON rule_revision
    FOR UPDATE TO brownie_api
    USING (
        status = 'PROPOSED'
        AND EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = rule_revision.workspace_id AND wm.user_id = current_workspace_user_id()
        )
    )
    WITH CHECK (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = rule_revision.workspace_id AND wm.user_id = current_workspace_user_id()
        )
    );
