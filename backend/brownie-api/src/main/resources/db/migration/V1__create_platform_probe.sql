-- The first real, versioned schema for this application. Its only job is
-- to give PlatformProbeController something real to write to and read
-- from, proving the migration/role/request pipeline already built here
-- works end to end -- it is a platform self-check, not a product table,
-- and is expected to be removed once real domain tables make it
-- unnecessary as evidence.

CREATE TABLE platform_probe (
    id BIGSERIAL PRIMARY KEY,
    message TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
