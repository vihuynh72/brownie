-- The name the person gave us themselves: the provider's display name is a
-- hint, and these two columns are what Brownie actually calls them.
ALTER TABLE user_identity
    ADD COLUMN given_name text,
    ADD COLUMN family_name text,
    ADD COLUMN name_confirmed_at timestamptz;
