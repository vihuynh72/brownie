-- An uninvited person's one way to ask for a seat: one row each, first
-- asked-at kept when they ask again, an optional note. There is no list
-- screen for ten people, so the row is only ever their own.

CREATE TABLE invitation_request (
    user_id BIGINT PRIMARY KEY REFERENCES user_identity (id) ON DELETE CASCADE,
    note TEXT NULL CHECK (note IS NULL OR char_length(note) <= 500),
    requested_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE invitation_request ENABLE ROW LEVEL SECURITY;

-- A person may only ever read or write their own request; nobody else's.
CREATE POLICY invitation_request_own_select ON invitation_request
    FOR SELECT
    TO brownie_api
    USING (user_id = current_workspace_user_id());
CREATE POLICY invitation_request_own_insert ON invitation_request
    FOR INSERT
    TO brownie_api
    WITH CHECK (user_id = current_workspace_user_id());
CREATE POLICY invitation_request_own_update ON invitation_request
    FOR UPDATE
    TO brownie_api
    USING (user_id = current_workspace_user_id())
    WITH CHECK (user_id = current_workspace_user_id());

REVOKE ALL ON invitation_request FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE ON invitation_request TO brownie_api;
