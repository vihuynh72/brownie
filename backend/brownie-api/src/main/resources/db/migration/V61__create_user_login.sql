-- One person, every way they can sign in: each provider's (issuer, subject)
-- is a login that names the user it belongs to. user_identity keeps the
-- person; user_login is every alias to them. Existing identities start as
-- their own only login.
CREATE TABLE user_login (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    issuer      TEXT NOT NULL,
    subject     TEXT NOT NULL,
    user_id     BIGINT NOT NULL REFERENCES user_identity (id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT user_login_issuer_subject_key UNIQUE (issuer, subject)
);

INSERT INTO user_login (issuer, subject, user_id)
SELECT issuer, subject, id FROM user_identity;
