-- The UserIdentity entity from the master plan's entity inventory (§7.2):
-- keyed by issuer plus subject, never by email, since email is display and
-- contact data that a provider account can change.

CREATE TABLE user_identity (
    id BIGSERIAL PRIMARY KEY,
    issuer TEXT NOT NULL,
    subject TEXT NOT NULL,
    email TEXT,
    display_name TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_login_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    disabled_at TIMESTAMPTZ,
    CONSTRAINT user_identity_issuer_subject_key UNIQUE (issuer, subject)
);
