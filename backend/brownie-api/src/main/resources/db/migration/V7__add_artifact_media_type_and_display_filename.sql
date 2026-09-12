-- detected_media_type is observed once, from the actual bytes, the same
-- moment byte_count/sha256 are recorded -- never from a client-declared
-- content type. display_filename is sanitized display metadata only,
-- set once at allocation time; it is never used as a real path.
ALTER TABLE artifact
    ADD COLUMN detected_media_type TEXT,
    ADD COLUMN display_filename TEXT;
