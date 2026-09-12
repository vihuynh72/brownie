-- Personal workspaces and tenant membership.
--
-- is_personal and the partial unique index below guarantee exactly one
-- personal workspace per owner at the database level, not just by
-- application discipline, while still allowing additional, non-personal
-- (team) workspaces per owner once that feature exists.
CREATE TABLE workspace (
    id BIGSERIAL PRIMARY KEY,
    owner_user_id BIGINT NOT NULL REFERENCES user_identity (id),
    locale TEXT NOT NULL DEFAULT 'en-US',
    time_zone TEXT NOT NULL DEFAULT 'UTC',
    status TEXT NOT NULL DEFAULT 'ACTIVE',
    retention_policy_version INT NOT NULL DEFAULT 1,
    quota_policy TEXT NOT NULL DEFAULT 'standard',
    is_personal BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX workspace_one_personal_per_owner ON workspace (owner_user_id) WHERE is_personal;

-- A composite primary key, not a surrogate ID: a membership IS the
-- (workspace, user) pair, with no independent identity of its own yet.
CREATE TABLE workspace_member (
    workspace_id BIGINT NOT NULL REFERENCES workspace (id),
    user_id BIGINT NOT NULL REFERENCES user_identity (id),
    role TEXT NOT NULL,
    state TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (workspace_id, user_id)
);
