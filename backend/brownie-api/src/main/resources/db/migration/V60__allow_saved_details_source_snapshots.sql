-- A fill reads the person's saved details as a source, frozen into a snapshot
-- it can cite the way it cites any other source: one more kind, no origin to
-- keep -- the shape check's no-origin branch already says what it may carry.
ALTER TABLE source_snapshot
    DROP CONSTRAINT source_snapshot_kind_known,
    ADD CONSTRAINT source_snapshot_kind_known CHECK (kind IN ('ARTIFACT', 'GOOGLE_CALENDAR', 'GOOGLE_DRIVE', 'SAVED_DETAILS'));
