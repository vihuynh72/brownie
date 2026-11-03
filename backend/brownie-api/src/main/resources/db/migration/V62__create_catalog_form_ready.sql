-- S2 instant library start: the agency's own form bytes kept by catalog id
-- (a fresh copy skips the download; checked_at drives the three-day re-check)
-- and the prepared result of any upload kept by its file hash, so taking the
-- same bytes in again -- a library start, or a re-upload -- skips the read.
CREATE TABLE catalog_form_source (
    catalog_id      TEXT PRIMARY KEY,
    source_bytes    BYTEA NOT NULL,
    source_sha256   TEXT NOT NULL,
    checked_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- A prepared form by its source bytes' hash: the spots, notices and naming a
-- prepare produced once, plus the worked-copy bytes a Word form keeps (null
-- for a PDF, whose source fills as it is). The same file in any workspace
-- replays this instead of finding and naming its places again. A new recipe
-- version simply does not match an older row.
CREATE TABLE prepare_cache (
    source_sha256       TEXT NOT NULL,
    recipe_version      TEXT NOT NULL,
    source_format       TEXT NOT NULL,
    output_bytes        BYTEA,
    spots               JSONB NOT NULL,
    notices             JSONB NOT NULL,
    converter           TEXT,
    spot_naming         TEXT NOT NULL,
    rules_only_reason   TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (source_sha256, recipe_version)
);
