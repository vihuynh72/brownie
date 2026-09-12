-- Row-level security on the two tenant tables, as defense in depth behind
-- the application's own authorization checks -- not a replacement for
-- them. brownie_migration owns these tables and bypasses RLS by default
-- (ordinary Postgres owner behavior, not something granted here), so
-- Flyway itself is unaffected; brownie_api and brownie_worker are
-- ordinary, non-bypassing roles and are fully subject to it.
--
-- Every policy reads this. The application sets app.current_user_id once
-- per transaction and nowhere else; Postgres reverts it automatically
-- when that transaction ends. On a genuinely untouched connection that
-- reverted value reads back as NULL, but once any transaction on a given
-- pooled connection has ever set this parameter, later reverts read back
-- as an empty string instead -- confirmed against a real pooled
-- connection, not assumed from documentation -- so both are normalized
-- to NULL here rather than trusting current_setting's raw result. Every
-- comparison below against NULL is false, so a missing context denies
-- access rather than defaulting to allow, in either case.
CREATE FUNCTION current_workspace_user_id() RETURNS bigint AS $$
    SELECT NULLIF(current_setting('app.current_user_id', true), '')::bigint
$$ LANGUAGE sql STABLE;

ALTER TABLE workspace ENABLE ROW LEVEL SECURITY;
ALTER TABLE workspace_member ENABLE ROW LEVEL SECURITY;

-- Visible either by direct ownership or by membership. Ownership alone
-- has to be enough on its own: right after a brand new workspace is
-- inserted, its membership row does not exist for another moment yet
-- (see JdbcWorkspaceRepository), and RETURNING from that INSERT is itself
-- subject to this same SELECT policy.
CREATE POLICY workspace_owner_or_member ON workspace
    FOR SELECT
    USING (
        owner_user_id = current_workspace_user_id()
        OR EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = workspace.id
              AND wm.user_id = current_workspace_user_id()
        )
    );

CREATE POLICY workspace_insert_own ON workspace
    FOR INSERT
    WITH CHECK (owner_user_id = current_workspace_user_id());

CREATE POLICY workspace_member_own ON workspace_member
    FOR SELECT
    USING (user_id = current_workspace_user_id());

CREATE POLICY workspace_member_insert_own ON workspace_member
    FOR INSERT
    WITH CHECK (user_id = current_workspace_user_id());
